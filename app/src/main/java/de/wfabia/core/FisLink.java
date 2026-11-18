// SPDX-License-Identifier: GPL-3.0-or-later
// Port of maxidot/link.py (Copyright (C) 2026 the maxidot-fis contributors)
package de.wfabia.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FisLink: play the radio toward the MaxiDot cluster and put text on its two rows.
 * open() runs the heartbeat, waits, registers. Closing the link stops the heartbeat and the cluster blanks.
 * The real radio must be off the bus.
 */
public final class FisLink implements AutoCloseable {
    public static final int ROW = 10;
    static final byte[] HEARTBEAT = {0x00, 0x01, 0x00, 0x00, 0x00, 0x00};
    static final double HEARTBEAT_PERIOD = 0.23;
    static final double IDLE_CLOSE = 0.6;                       // drop an idle connection after this many seconds
    static final byte[] INIT_MESSAGE = {0x15, 0x39, 0x00, 0x01, 0x01};
    static final byte[] REGISTER_MESSAGE = concat(new byte[]{0x02, (byte) 0x80, 0x39, 0x20}, "Radiodaten".getBytes(StandardCharsets.US_ASCII));

    private final CanAdapter c;
    private final double warmup, gap;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean hbStop;
    private Thread hbThread;
    private boolean streamOpen;
    private long streamLastNanos;
    private volatile Integer session;
    private volatile String lastError;

    /** @param warmup seconds of heartbeat before registering
     *  @param gap    seconds between data packets (a real radio uses 0.009; 0.003 works and is as fast as the cluster allows) */
    public FisLink(CanAdapter c, double warmup, double gap) {
        this.c = c;
        this.warmup = warmup;
        this.gap = gap;
    }

    public FisLink(CanAdapter c) { this(c, 3.0, 0.003); }

    public Integer session() { return session; }

    public String lastError() { return lastError; }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // -- lifecycle -----------------------------------------------------------------------------------------
    /** Start the heartbeat (idempotent). It must run for as long as the display is wanted: stop it and the cluster blanks. */
    public synchronized void startHeartbeat() {
        if (hbThread != null && hbThread.isAlive() && !hbStop) return;
        hbStop = false;
        hbThread = new Thread(this::heartbeat, "fis-heartbeat");
        hbThread.setDaemon(true);
        hbThread.start();
    }

    public synchronized boolean heartbeatRunning() { return hbThread != null && hbThread.isAlive() && !hbStop; }

    /** Stop the heartbeat (and close any open transport connection) without closing the link: the cluster sees its radio switch off. */
    public void stopHeartbeat() {
        try {
            streamClose();
        } catch (RuntimeException ignored) {
            // adapter may be gone
        }
        hbStop = true;
        Thread t;
        synchronized (this) {
            t = hbThread;
            hbThread = null;
        }
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Start the heartbeat, wait for the cluster to notice the "radio", register. Blocks about warmup + 1 s. */
    public void open() throws IOException {
        startHeartbeat();
        try {
            FisTransport.sleep(warmup);   // its 0x40C byte 0 turns 0x19
            if (Thread.currentThread().isInterrupted()) throw new IOException("interrupted");
            register(false);
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage();
            close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    @Override
    public void close() {
        try {
            streamClose();
        } catch (RuntimeException ignored) {
            // adapter may already be gone
        }
        hbStop = true;
        Thread t = hbThread;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        hbThread = null;
    }

    private void heartbeat() {
        while (!hbStop) {
            try {
                c.txHeartbeat(FisTransport.ID_HEARTBEAT, HEARTBEAT);
                if (lock.tryLock()) {
                    try {
                        streamIdleCheck();
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (RuntimeException e) {
                lastError = "heartbeat: " + e;   // the heartbeat must keep running whatever happens
            }
            try {
                Thread.sleep((long) (HEARTBEAT_PERIOD * 1000));
            } catch (InterruptedException e) {
                if (hbStop) return;
            }
        }
    }

    /** Boot sequence of the radio: init message, then the "Radiodaten" message which hands us a session id. */
    public void register(boolean cleanSlate) throws IOException {
        lock.lock();
        c.beginMessage();
        try {
            streamOpen = false;             // a fresh adapter or a failed attempt: no transport connection is open from our side
            if (cleanSlate) FisTransport.disconnect(c);   // after a failure the cluster may still hold the old connection: close it first
            if (!FisTransport.connect(c)) throw new IOException("Cluster did not answer the connection setup (is it powered, is the real radio off?)");
            byte[] initReply = FisTransport.sendMessage(c, INIT_MESSAGE, FisTransport.DEFAULT_GAP);
            if (initReply == null) throw new IOException("Init message was not answered");
            FisTransport.disconnect(c);
            if (!FisTransport.connect(c)) throw new IOException("Second connection setup failed");
            byte[] reply = FisTransport.sendMessage(c, REGISTER_MESSAGE, FisTransport.DEFAULT_GAP);
            if (reply == null || reply.length < 2 || (reply[0] & 0xFF) != 0x23) {
                throw new IOException("Registration refused (init reply " + Hex.upper(initReply, initReply.length) + ", registration reply "
                    + (reply == null ? "none" : Hex.upper(reply, reply.length)) + ")");
            }
            session = reply[1] & 0xFF;
            // like the real radio: a blank content message in the same connection, then disconnect
            FisTransport.sendMessage(c, contentPayload(session, blank(), blank()), FisTransport.DEFAULT_GAP);
            FisTransport.disconnect(c);
        } finally {
            c.endMessage();
            lock.unlock();
        }
    }

    private static byte[] blank() {
        byte[] b = new byte[ROW];
        java.util.Arrays.fill(b, (byte) ' ');
        return b;
    }

    /** The 37-byte message that sets the two display rows. top / bottom: exactly 10 bytes each. */
    public static byte[] contentPayload(int session, byte[] top, byte[] bottom) {
        if (top.length != ROW || bottom.length != ROW) throw new IllegalArgumentException("rows must be 10 bytes");
        byte[] out = new byte[37];
        int i = 0;
        byte[] head = {0x09, (byte) session, 0x57, 0x0F, 0x02, 0x00, 0x00, 0x00, 0x00};
        System.arraycopy(head, 0, out, i, head.length);
        i += head.length;
        System.arraycopy(top, 0, out, i, ROW);
        i += ROW;
        byte[] mid = {0x57, 0x0F, 0x03, 0x00, 0x00, 0x0A, 0x00};
        System.arraycopy(mid, 0, out, i, mid.length);
        i += mid.length;
        System.arraycopy(bottom, 0, out, i, ROW);
        i += ROW;
        out[i] = 0x08;
        return out;
    }

    // -- display -------------------------------------------------------------------------------------------
    /** Show two rows of up to 10 cells each (run through the font encoder). Keeps one connection open between updates
     *  (about 4 updates/s). -> true if the cluster accepted it. */
    public boolean showRows(String top, String bottom) {
        Integer s = session;
        if (s == null) return false;
        byte[] payload = contentPayload(s, MaxidotFont.padLeft(MaxidotFont.encode(top), ROW), MaxidotFont.padLeft(MaxidotFont.encode(bottom), ROW));
        for (int attempt = 0; attempt < 2; attempt++) {
            if (streamSend(payload)) return true;
        }
        return false;
    }

    /** Send a content payload over a persistent connection. True if the cluster answered 'accepted' (0x27). */
    boolean streamSend(byte[] payload) {
        lock.lock();
        c.beginMessage();
        try {
            streamIdleCheck();
            for (int i = 0; i < 2; i++) {   // one reconnect on any hiccup
                if (!streamOpen) {
                    if (!FisTransport.connect(c)) continue;
                    streamOpen = true;
                }
                Long mLast = FisTransport.sendData(c, payload, gap);
                if (mLast != null) {
                    byte[] reply = FisTransport.waitReply(c, mLast, gap);
                    if (reply != null) {
                        streamLastNanos = System.nanoTime();
                        return reply.length > 0 && (reply[0] & 0xFF) == 0x27;
                    }
                }
                streamClose();
            }
            return false;
        } finally {
            c.endMessage();
            lock.unlock();
        }
    }

    void streamClose() {
        lock.lock();
        try {
            if (streamOpen) {
                FisTransport.disconnect(c);
                streamOpen = false;
            }
        } finally {
            lock.unlock();
        }
    }

    private void streamIdleCheck() {
        lock.lock();
        try {
            if (streamOpen && (System.nanoTime() - streamLastNanos) / 1e9 > IDLE_CLOSE) streamClose();
        } finally {
            lock.unlock();
        }
    }
}
