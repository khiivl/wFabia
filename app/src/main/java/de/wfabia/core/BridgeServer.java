// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The adb CAN bridge: loopback TCP, one JSON object per line. A PC reaches it with `adb forward tcp:7788 tcp:7788`.
 *
 *   client -> app  {"op":"sub","ch":["can","zlink"],"can_ids":["0x2E8"]}   {"op":"unsub","ch":["can"]}
 *                  {"op":"tx","id":"0x5A0","d":"01FF","ttl_ms":500}        {"op":"module","name":"display","enabled":false}
 *                  {"op":"status"}
 *   app -> client  {"ch":"can","t":..,"dir":"rx|tx","id":"0x40C","d":"19000000"}
 *                  {"ch":"zlink","t":..,"type":"0x8006","raw":"<hex>"}   {"ch":"ctl","ev":"tx_refused","why":"send_disabled"}
 */
public final class BridgeServer {
    private static final Logger LOG = Logger.getLogger("wfabia.bridge");
    private static final int CLIENT_QUEUE = 2000;

    /** what the bridge needs from the service */
    public interface Host {
        boolean sniffEnabled();

        /** @return false if the change is refused (the send switch can only be flipped in the app) */
        boolean setModule(String name, boolean enabled);

        /** a JSON object text with module states and link status */
        String statusJson();
    }

    private final CanBroker broker;
    private final TxGate gate;
    private final Host host;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final CanBroker.Listener canListener = this::onCan;
    private ServerSocket server;
    private Thread acceptThread;

    public BridgeServer(CanBroker broker, TxGate gate, Host host) {
        this.broker = broker;
        this.gate = gate;
        this.host = host;
    }

    public synchronized void start(int port) throws IOException {
        if (server != null) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port));   // loopback only
        server = s;
        broker.subscribe(canListener, null, true, true);
        acceptThread = new Thread(this::acceptLoop, "bridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized int port() { return server == null ? -1 : server.getLocalPort(); }

    public synchronized void stop() {
        broker.unsubscribe(canListener);
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { /* closing */ }
        }
        for (Client c : clients) c.close();
        clients.clear();
        Thread t = acceptThread;
        if (t != null) {
            try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        acceptThread = null;
    }

    public int clientCount() { return clients.size(); }

    private void acceptLoop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket sock = s.accept();
                sock.setTcpNoDelay(true);
                Client c = new Client(sock);
                clients.add(c);
                c.start();
            } catch (IOException e) {
                if (s.isClosed()) return;
                LOG.warning("accept: " + e);
            }
        }
    }

    // ---- publishing -------------------------------------------------------------------------------------
    private void onCan(CanFrame f) {
        if (clients.isEmpty() || !host.sniffEnabled()) return;
        String line = null;
        for (Client c : clients) {
            if (!c.wantCan) continue;
            Set<Integer> ids = c.canIds;
            if (ids != null && !(ids.contains(f.id) && !f.ext)) continue;
            if (line == null) {
                line = String.format(Locale.US, "{\"ch\":\"can\",\"t\":%.3f,\"dir\":\"%s\",\"id\":\"%s\",\"d\":\"%s\"}",
                    f.t, f.rx ? "rx" : "tx", f.idText(), Hex.upper(f.data, f.data.length));
            }
            c.send(line);
        }
    }

    /** relay one raw Zlink frame to the clients that subscribed to the zlink channel */
    public void publishZlink(int type, byte[] payload) {
        if (clients.isEmpty()) return;
        String line = null;
        for (Client c : clients) {
            if (!c.wantZlink) continue;
            if (line == null) {
                line = String.format(Locale.US, "{\"ch\":\"zlink\",\"t\":%.3f,\"type\":\"0x%04X\",\"raw\":\"%s\"}",
                    System.currentTimeMillis() / 1000.0, type, Hex.lower(payload));
            }
            c.send(line);
        }
    }

    /** broadcast a control event to every client */
    public void publishCtl(String ev, String why) {
        String line = ctl(ev, why, null);
        for (Client c : clients) c.send(line);
    }

    static String ctl(String ev, String why, String extra) {
        try {
            JSONObject o = new JSONObject().put("ch", "ctl").put("ev", ev);
            if (why != null) o.put("why", why);
            if (extra != null) o.put("info", extra);
            return o.toString();
        } catch (JSONException e) {
            return "{\"ch\":\"ctl\",\"ev\":\"error\"}";
        }
    }

    // ---- one client -------------------------------------------------------------------------------------
    private final class Client {
        final Socket sock;
        final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>(CLIENT_QUEUE);
        volatile boolean wantCan, wantZlink;
        volatile Set<Integer> canIds;      // null = all
        volatile boolean closed;
        long dropped;

        Client(Socket sock) { this.sock = sock; }

        void start() {
            Thread r = new Thread(this::readLoop, "bridge-reader");
            Thread w = new Thread(this::writeLoop, "bridge-writer");
            r.setDaemon(true);
            w.setDaemon(true);
            r.start();
            w.start();
        }

        void send(String line) {
            if (closed) return;
            if (!out.offer(line)) {
                if (++dropped % 500 == 1) LOG.warning("bridge client too slow, dropping lines (" + dropped + ")");
            }
        }

        void close() {
            closed = true;
            try { sock.close(); } catch (IOException ignored) { /* closing */ }
            out.offer("");   // wakes the writer
        }

        private void writeLoop() {
            try {
                OutputStream os = sock.getOutputStream();
                while (!closed) {
                    String line = out.take();
                    if (closed) break;
                    if (line.isEmpty()) continue;
                    os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    if (out.isEmpty()) os.flush();
                }
            } catch (IOException | InterruptedException e) {
                // client gone
            } finally {
                close();
                clients.remove(this);
            }
        }

        private void readLoop() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        handle(new JSONObject(line));
                    } catch (JSONException | RuntimeException e) {
                        send(ctl("error", "bad_request", null));
                    }
                }
            } catch (IOException e) {
                // client gone
            } finally {
                close();
                clients.remove(this);
            }
        }

        private Set<String> channels(JSONObject o, String dflt) throws JSONException {
            Set<String> chs = new HashSet<>();
            JSONArray a = o.optJSONArray("ch");
            if (a == null) {
                chs.add(dflt);
            } else {
                for (int i = 0; i < a.length(); i++) chs.add(a.getString(i));
            }
            return chs;
        }

        private void handle(JSONObject o) throws JSONException {
            String op = o.optString("op", "");
            switch (op) {
                case "sub": {
                    Set<String> chs = channels(o, "can");
                    JSONArray ids = o.optJSONArray("can_ids");
                    if (ids != null && ids.length() > 0) {
                        Set<Integer> set = new HashSet<>();
                        for (int i = 0; i < ids.length(); i++) set.add(Hex.parseId(ids.get(i).toString()));
                        canIds = set;
                    } else {
                        canIds = null;
                    }
                    if (chs.contains("can")) {
                        if (host.sniffEnabled()) {
                            wantCan = true;
                        } else {
                            wantCan = false;
                            send(ctl("sub_refused", "sniff_disabled", "can"));
                        }
                    }
                    if (chs.contains("zlink")) wantZlink = true;
                    send(ctl("subscribed", null, (wantCan ? "can " : "") + (wantZlink ? "zlink" : "")));
                    break;
                }
                case "unsub": {
                    Set<String> chs = channels(o, "can");
                    if (chs.contains("can")) wantCan = false;
                    if (chs.contains("zlink")) wantZlink = false;
                    send(ctl("unsubscribed", null, null));
                    break;
                }
                case "tx": {
                    int id;
                    byte[] data;
                    try {
                        id = Hex.parseId(o.get("id").toString());
                        data = Hex.parse(o.optString("d", ""));
                    } catch (IllegalArgumentException e) {
                        send(ctl("tx_refused", "bad_frame", null));
                        break;
                    }
                    String why = gate.submit("bridge", id, data, o.optLong("ttl_ms", 0));
                    send(why == null ? ctl("tx_queued", null, String.format("0x%03X", id)) : ctl("tx_refused", why, null));
                    break;
                }
                case "module": {
                    String name = o.getString("name");
                    boolean en = o.getBoolean("enabled");
                    send(host.setModule(name, en) ? ctl("module_ok", null, name) : ctl("module_refused", "not_allowed", name));
                    break;
                }
                case "status":
                    send("{\"ch\":\"ctl\",\"ev\":\"status\",\"status\":" + host.statusJson() + "}");
                    break;
                default:
                    send(ctl("error", "unknown_op", null));
            }
        }
    }
}
