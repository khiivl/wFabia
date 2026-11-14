// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Logger;

/**
 * The one client of the Zlink metadata socket (127.0.0.1:1555). Frames are {u32 LE type, u32 LE length} + protobuf.
 * Reconnects every few seconds. Do not open this socket from anywhere else: Zlink accepts one client.
 */
public final class ZlinkClient {
    private static final Logger LOG = Logger.getLogger("wfabia.zlink");
    private static final int MAX_FRAME = 1 << 22;   // album art can be large; anything beyond this is garbage

    public interface Listener {
        void onFrame(int type, byte[] payload);

        void onState(boolean connected, String detail);
    }

    private final String host;
    private final int port;
    private final long retryMs;
    private final Listener listener;
    private volatile boolean stopped = true;
    private volatile Socket socket;
    private Thread thread;
    private volatile long frames;
    private volatile long lastFrameNanos;

    public ZlinkClient(String host, int port, long retryMs, Listener listener) {
        this.host = host;
        this.port = port;
        this.retryMs = retryMs;
        this.listener = listener;
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive() && !stopped) return;
        stopped = false;
        thread = new Thread(this::run, "zlink-client");
        thread.setDaemon(true);
        thread.start();
    }

    /** closes the socket so Zlink sees a free slot again */
    public synchronized void stop() {
        stopped = true;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { /* closing */ }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        thread = null;
        listener.onState(false, "off");
    }

    public long frames() { return frames; }

    public long millisSinceFrame() { return lastFrameNanos == 0 ? -1 : (System.nanoTime() - lastFrameNanos) / 1_000_000; }

    private void run() {
        String last = null;
        while (!stopped) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 3000);
                socket = s;
                s.setTcpNoDelay(true);
                last = null;
                listener.onState(true, "connected :" + port);
                readFrames(new DataInputStream(s.getInputStream()));
                if (!stopped) listener.onState(false, "closed by Zlink");
            } catch (IOException e) {
                String d = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!stopped && !d.equals(last)) {
                    LOG.info("zlink: " + d);
                    listener.onState(false, d);
                    last = d;
                }
            } finally {
                socket = null;
                try { s.close(); } catch (IOException ignored) { /* closing */ }
            }
            if (stopped) break;
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                if (stopped) break;
            }
        }
    }

    private void readFrames(DataInputStream in) throws IOException {
        byte[] hdr = new byte[8];
        while (!stopped) {
            try {
                in.readFully(hdr);
            } catch (EOFException e) {
                return;
            }
            ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            int type = b.getInt();
            long len = b.getInt() & 0xFFFFFFFFL;
            if (len > MAX_FRAME) throw new IOException("bad frame length " + len);
            byte[] payload = new byte[(int) len];
            in.readFully(payload);
            frames++;
            lastFrameNanos = System.nanoTime();
            try {
                listener.onFrame(type, payload);
            } catch (RuntimeException e) {
                LOG.warning("frame handler failed: " + e);
            }
        }
    }
}
