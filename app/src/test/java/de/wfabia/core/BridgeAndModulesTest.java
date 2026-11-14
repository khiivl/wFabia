// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class BridgeAndModulesTest {
    static final class MemStore implements Modules.Store {
        final Map<String, Object> m = new HashMap<>();
        @Override public boolean getBool(String k, boolean d) { return m.containsKey(k) ? (Boolean) m.get(k) : d; }
        @Override public void putBool(String k, boolean v) { m.put(k, v); }
        @Override public String getString(String k, String d) { return m.containsKey(k) ? (String) m.get(k) : d; }
        @Override public void putString(String k, String v) { m.put(k, v); }
    }

    @Test public void moduleDependencies() {
        Modules m = new Modules(new MemStore());
        assertTrue(m.effective(Modules.DISPLAY) && m.effective(Modules.LANES) && m.effective(Modules.SNIFF));
        assertFalse("send starts off", m.wanted(Modules.SEND));
        m.set(Modules.CAN, false);
        assertFalse(m.effective(Modules.DISPLAY));
        assertFalse(m.effective(Modules.BRIDGE));
        assertFalse(m.effective(Modules.SPEED));                 // needs display
        assertEquals(Modules.CAN, m.blockedBy(Modules.DISPLAY));
        assertTrue(m.effective(Modules.ZLINK));
        m.set(Modules.CAN, true);
        m.set(Modules.ZLINK, false);
        assertFalse(m.effective(Modules.NAV));
        assertFalse(m.effective(Modules.LANES));                 // needs nav
        assertTrue(m.effective(Modules.SPEED));
        m.set(Modules.ZLINK, true);
        m.set(Modules.NAV, false);
        assertFalse(m.options().lanes);
        assertFalse(m.options().exit);
        assertTrue(m.options().speed);
    }

    @Test public void sendNeverRestoredAndAllowlistParsed() {
        MemStore s = new MemStore();
        Modules m = new Modules(s);
        m.set(Modules.SEND, true);
        m.setAllowlist("5a0, 0x2E8 zzz 9999");
        assertEquals("5A0,2E8", m.allowlistText());
        Modules again = new Modules(s);
        assertFalse(again.wanted(Modules.SEND));
        assertEquals(2, again.allowlist().size());
    }

    @Test public void bridgeSniffTxAndZlink() throws Exception {
        FakeWire wire = new FakeWire();
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        broker.start();
        Modules mods = new Modules(new MemStore());
        TxGate gate = new TxGate(broker, new TxGate.Policy() {
            @Override public boolean sendEnabled() { return mods.effective(Modules.SEND); }
            @Override public java.util.Set<Integer> allowlist() { return mods.allowlist(); }
        });
        BridgeServer server = new BridgeServer(broker, gate, new BridgeServer.Host() {
            @Override public boolean sniffEnabled() { return mods.effective(Modules.SNIFF); }
            @Override public boolean setModule(String name, boolean en) {
                if (name.equals(Modules.SEND)) return false;
                mods.set(name, en);
                return true;
            }
            @Override public String statusJson() { return "{\"can_up\":" + broker.isUp() + "}"; }
        });
        server.start(0);
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), server.port())) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            java.io.OutputStream out = s.getOutputStream();
            long end = System.currentTimeMillis() + 5000;
            while (!broker.isUp() && System.currentTimeMillis() < end) Thread.sleep(10);

            send(out, "{\"op\":\"sub\",\"ch\":[\"can\",\"zlink\"],\"can_ids\":[\"0x40C\"]}");
            assertEquals("subscribed", JSON(in.readLine()).getString("ev"));

            wire.injectFrame(0x2E8, new byte[]{1});                 // filtered out
            wire.injectFrame(0x40C, new byte[]{0x19, 0, 0, 0});
            JSONObject can = JSON(in.readLine());
            assertEquals("can", can.getString("ch"));
            assertEquals("rx", can.getString("dir"));
            assertEquals("0x40C", can.getString("id"));
            assertEquals("19000000", can.getString("d"));

            server.publishZlink(0x8006, new byte[]{0x0a, 0x1f});
            JSONObject z = JSON(in.readLine());
            assertEquals("zlink", z.getString("ch"));
            assertEquals("0x8006", z.getString("type"));
            assertEquals("0a1f", z.getString("raw"));

            send(out, "{\"op\":\"tx\",\"id\":\"0x5A0\",\"d\":\"01FF\",\"ttl_ms\":500}");
            JSONObject r = JSON(in.readLine());
            assertEquals("tx_refused", r.getString("ev"));
            assertEquals("send_disabled", r.getString("why"));
            assertFalse(wire.written.contains("t5A0201FF"));

            mods.set(Modules.SEND, true);                           // armed in the app, but the allowlist is empty
            send(out, "{\"op\":\"tx\",\"id\":\"0x5A0\",\"d\":\"01FF\"}");
            assertEquals("id_not_allowed", JSON(in.readLine()).getString("why"));

            mods.setAllowlist("5A0");
            send(out, "{\"op\":\"tx\",\"id\":\"0x5A0\",\"d\":\"01FF\"}");
            assertEquals("tx_queued", JSON(in.readLine()).getString("ev"));
            end = System.currentTimeMillis() + 3000;
            while (!wire.written.contains("t5A0201FF") && System.currentTimeMillis() < end) Thread.sleep(10);
            assertTrue(wire.written.contains("t5A0201FF"));
            // our own tx echo is filtered by the id filter (0x5A0 is not 0x40C), so the next line is the status reply
            send(out, "{\"op\":\"status\"}");
            String next = in.readLine();
            assertTrue(next, next.contains("\"ev\":\"status\"") && next.contains("can_up"));

            send(out, "{\"op\":\"module\",\"name\":\"send\",\"enabled\":true}");
            String refused = drainUntil(in, "module_refused");
            assertTrue(refused, refused.contains("module_refused"));

            send(out, "{\"op\":\"module\",\"name\":\"sniff\",\"enabled\":false}");
            drainUntil(in, "module_ok");
            send(out, "{\"op\":\"sub\",\"ch\":[\"can\"]}");
            String sub = drainUntil(in, "sub_refused");
            assertTrue(sub, sub.contains("sniff_disabled"));
        } finally {
            server.stop();
            broker.stop();
        }
        assertNull(null);
    }

    private static void send(java.io.OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static JSONObject JSON(String s) throws Exception { return new JSONObject(s); }

    private static String drainUntil(BufferedReader in, String needle) throws Exception {
        for (int i = 0; i < 20; i++) {
            String l = in.readLine();
            if (l == null) break;
            if (l.contains(needle)) return l;
        }
        return "not found: " + needle;
    }
}
