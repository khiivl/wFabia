// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one owner of the CAN adapter. A single reader thread parses SLCAN and fans frames out to subscribers; a single
 * writer thread sends with three priorities (heartbeat > FIS > everything else). Frames of the third class are only
 * sent between FIS messages, never inside one. A full queue drops non-FIS frames (and counts it), never the heartbeat.
 * After the adapter is lost it is reopened every few seconds.
 */
public final class CanBroker {
    private static final Logger LOG = Logger.getLogger("wfabia.broker");

    public static final int PRIO_HEARTBEAT = 0, PRIO_FIS = 1, PRIO_OTHER = 2;
    private static final int MAX_HB = 4, MAX_FIS = 64, MAX_OTHER = 128, RING = 4096;
    private static final long MAX_HOLD_NANOS = 5_000_000_000L;   // safety: a forgotten FIS hold never blocks the bus for long

    public interface WireFactory { Slcan.Wire open() throws IOException; }

    public interface Listener { void onFrame(CanFrame f); }

    public interface Status {
        void onLink(boolean up, String detail);
        void onDropped(long totalDropped);
    }

    private static final class Pending {
        final int id;
        final boolean ext;
        final byte[] data;
        final long deadline;   // nanoTime, 0 = none
        Pending(int id, boolean ext, byte[] data, long deadline) { this.id = id; this.ext = ext; this.data = data; this.deadline = deadline; }
    }

    private static final class Sub {
        final Listener l;
        final Set<Integer> ids;     // null = all
        final boolean rx, tx;
        Sub(Listener l, Set<Integer> ids, boolean rx, boolean tx) { this.l = l; this.ids = ids; this.rx = rx; this.tx = tx; }
    }

    private final WireFactory factory;
    private final Status status;
    private final long settleMs, retryMs, silenceMs;
    private final Object wireLock = new Object();     // serialises writes: frames from the writer thread and SLCAN commands from the reader
    private final AtomicLong reinits = new AtomicLong();

    private final Object lock = new Object();
    private final Deque<Pending> hb = new ArrayDeque<>(), fis = new ArrayDeque<>(), other = new ArrayDeque<>();
    private int fisHold;
    private long holdSince;
    private boolean up;
    private long generation;

    private final CopyOnWriteArrayList<Sub> subs = new CopyOnWriteArrayList<>();
    private final Object ringLock = new Object();
    private final ArrayDeque<CanFrame> ring = new ArrayDeque<>();
    private final FrameBuffer fisBuf = new FrameBuffer(8192, 4096);

    private final AtomicLong rxCount = new AtomicLong(), txCount = new AtomicLong(), dropped = new AtomicLong();
    private volatile long lastRxNanos;
    private volatile CanFrame lastRx;

    private volatile boolean stopped = true;
    private volatile Slcan.Wire wire;
    private Thread supervisor;
    private String lastDetail = null;
    private boolean lastUp = false;

    public CanBroker(WireFactory factory, Status status) { this(factory, status, 2000, 3000); }

    public CanBroker(WireFactory factory, Status status, long settleMs, long retryMs) { this(factory, status, settleMs, retryMs, 5000); }

    /** @param silenceMs no frame from the bus for this long: re-initialise the adapter (it may have gone bus-off because nobody acknowledged) */
    public CanBroker(WireFactory factory, Status status, long settleMs, long retryMs, long silenceMs) {
        this.silenceMs = silenceMs;
        this.factory = factory;
        this.status = status;
        this.settleMs = settleMs;
        this.retryMs = retryMs;
    }

    // ---- lifecycle --------------------------------------------------------------------------------------
    public synchronized void start() {
        if (supervisor != null && supervisor.isAlive() && !stopped) return;
        stopped = false;
        supervisor = new Thread(this::run, "can-broker");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    public synchronized void stop() {
        stopped = true;
        Slcan.Wire w = wire;
        if (w != null) w.close();
        Thread t = supervisor;
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        supervisor = null;
        synchronized (lock) {
            up = false;
            hb.clear(); fis.clear(); other.clear();
            fisHold = 0;
            lock.notifyAll();
        }
        link(false, "stopped");
    }

    public boolean isUp() { synchronized (lock) { return up; } }

    public long generation() { synchronized (lock) { return generation; } }

    private void link(boolean upNow, String detail) {
        if (upNow == lastUp && detail != null && detail.equals(lastDetail)) return;
        lastUp = upNow;
        lastDetail = detail;
        if (status != null) status.onLink(upNow, detail);
    }

    private void run() {
        while (!stopped) {
            Slcan.Wire w = null;
            Thread writer = null;
            String detail;
            try {
                w = factory.open();
                wire = w;
                int heard = Slcan.open(w, "S3", settleMs, listenOnly);
                if (diag) probe(w);
                if (recover && heard == 0) {
                    // every SLCAN command is answered with at least a CR: silence means the serial link is dead although the port opened
                    byte[] probeBuf = new byte[256];
                    int more = w.read(probeBuf, 400);
                    if (more <= 0) throw new IOException("the adapter does not answer on the serial port (0 bytes after C/S3/O/V); reopening the USB connection");
                    rawBytes.addAndGet(more);
                }
                synchronized (lock) {
                    hb.clear(); fis.clear(); other.clear();
                    fisHold = 0;
                    up = true;
                    generation++;
                }
                openedAtNanos = System.nanoTime();
                link(true, "open");
                final Slcan.Wire fw = w;
                writer = new Thread(() -> writeLoop(fw), "can-writer");
                writer.setDaemon(true);
                writer.start();
                readLoop(w);
                detail = "adapter closed";
            } catch (IOException | RuntimeException e) {
                detail = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            synchronized (lock) {
                up = false;
                lock.notifyAll();
            }
            if (writer != null) {
                writer.interrupt();
                try { writer.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (w != null) w.close();
            wire = null;
            if (stopped) break;
            link(false, detail);
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                if (stopped) break;
            }
        }
    }

    private void readLoop(Slcan.Wire w) throws IOException {
        byte[] buf = new byte[4096];
        Slcan.LineParser parser = new Slcan.LineParser();
        parser.other = this::note;
        long quietSince = System.nanoTime();
        long lastFlags = System.nanoTime();
        int silentResets = 0;
        long rxSeen = rxCount.get();
        while (!stopped) {
            if (rxCount.get() != rxSeen) {   // the bus spoke: the adapter is fine
                rxSeen = rxCount.get();
                silentResets = 0;
            }
            if (diag && (System.nanoTime() - lastFlags) / 1_000_000 >= 2000) {   // ask the adapter for its error flags every 2 s
                lastFlags = System.nanoTime();
                synchronized (wireLock) { w.write(new byte[]{'F', '\r'}); }
            }
            int n = w.read(buf, 20);
            if (n < 0) throw new IOException("port closed");
            if (n > 0) {
                rawBytes.addAndGet(n);
                lastRaw = printable(buf, n);
                parser.feed(buf, n, (ext, id, data) -> {
                    CanFrame f = new CanFrame(System.currentTimeMillis() / 1000.0, true, ext, id, data);
                    rxCount.incrementAndGet();
                    long nowNs = System.nanoTime();
                    if (lastRxNanos != 0 && nowNs - lastRxNanos > BUS_GAP_NANOS) busEpoch.incrementAndGet();   // the bus was silent and speaks again
                    if (!ext && id == ID_TERMINALS && data.length > 0) noteTerminals(data[0] & 0xFF);
                    if (!ext && id == 0x40C && data.length > 0) {
                        int flagNow = data[0] & 0xFF;
                        if (flagNow != radioFlag) {
                            // what the cluster believes about the radio, for the timeline of an ignition restart
                            LOG.info("cluster radio flag " + (radioFlag < 0 ? "?" : String.format("%02X", radioFlag)) + " -> " + String.format("%02X", flagNow)
                                + " (heartbeat " + (hbHoldSince == 0 ? "sent" : "held") + ")");
                        }
                        radioFlag = data[0] & 0xFF;
                        radioFlagAt = nowNs;
                    }
                    lastRxNanos = nowNs;
                    lastRx = f;
                    if (!ext && (id == 0x2E8 || id == 0x699 || id == 0x40C)) fisBuf.append(id, data);
                    deliver(f);
                });
            }
            long now = System.nanoTime();
            long lastActivity = Math.max(quietSince, lastRxNanos);
            if (recover && silenceMs > 0 && (now - lastActivity) / 1_000_000 >= silenceMs) {
                // silence on a bus that should never be silent: the adapter may be bus-off (nobody acknowledged our frames). Reset it.
                // If two resets bring nothing back, do what a replug does: close the USB connection and open it again.
                if (silentResets >= 2) throw new IOException("no frames after two adapter resets: reopening the USB connection");
                silentResets++;
                long n2 = reinits.incrementAndGet();
                LOG.info("no frame from the bus for " + silenceMs / 1000.0 + " s, re-initialising the adapter (#" + n2 + ")");
                link(true, "no frames from the bus, re-initialising the adapter (#" + n2 + ")");
                synchronized (wireLock) {
                    for (String cmd : new String[]{"C", "S3", listenOnly ? "L" : "O", "V", "F"}) {
                        w.write((cmd + "\r").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                    }
                }
                quietSince = System.nanoTime();
            }
        }
    }

    private void writeLoop(Slcan.Wire w) {
        try {
            while (!stopped) {
                Pending p;
                synchronized (lock) {
                    while ((p = pollNext()) == null) {
                        if (!up || stopped) return;
                        lock.wait(50);
                    }
                }
                try {
                    writeStartNanos = System.nanoTime();
                    synchronized (wireLock) {
                        w.write(Slcan.format(p.id, p.ext, p.data));
                    }
                    writeStartNanos = 0;
                    lastWriteNanos = System.nanoTime();
                } catch (IOException e) {
                    writeStartNanos = 0;
                    writeErrors.incrementAndGet();
                    lastWriteError = e.toString();
                    LOG.log(Level.WARNING, "write failed: " + e);
                    w.close();   // makes the reader fail, the supervisor reopens
                    return;
                }
                txCount.incrementAndGet();
                deliver(new CanFrame(System.currentTimeMillis() / 1000.0, false, p.ext, p.id, p.data));
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "writer failed", e);
            w.close();   // the supervisor reopens the adapter
        }
    }

    /** next frame to send, or null. Call with lock held. */
    private Pending pollNext() {
        if (!hb.isEmpty()) return hb.poll();
        if (!fis.isEmpty()) return fis.poll();
        boolean hold = fisHold > 0 && System.nanoTime() - holdSince < MAX_HOLD_NANOS;
        if (!hold) {
            while (!other.isEmpty()) {
                Pending p = other.poll();
                if (p.deadline != 0 && System.nanoTime() > p.deadline) {
                    LOG.info("user frame expired before it could be sent: 0x" + Integer.toHexString(p.id));
                    continue;
                }
                return p;
            }
        }
        return null;
    }

    // ---- fan-out ----------------------------------------------------------------------------------------
    private void deliver(CanFrame f) {
        synchronized (ringLock) {
            ring.addLast(f);
            while (ring.size() > RING) ring.removeFirst();
        }
        for (Sub s : subs) {
            if (f.rx ? !s.rx : !s.tx) continue;
            if (s.ids != null && !(s.ids.contains(f.id) && !f.ext)) continue;
            try {
                s.l.onFrame(f);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "listener failed", e);
            }
        }
    }

    public void subscribe(Listener l, Set<Integer> ids, boolean rx, boolean tx) { subs.add(new Sub(l, ids, rx, tx)); }

    public void unsubscribe(Listener l) {
        for (Sub s : subs) if (s.l == l) subs.remove(s);
    }

    /** the last RING frames, oldest first */
    public List<CanFrame> snapshot() {
        synchronized (ringLock) { return new ArrayList<>(ring); }
    }

    // ---- transmit ---------------------------------------------------------------------------------------
    /** @param ttlMs 0 = no expiry; a frame still queued after this long is discarded unsent
     *  @return false if the frame was not queued (adapter down or queue full) */
    public boolean tx(int prio, int id, boolean ext, byte[] data, long ttlMs) {
        Pending p = new Pending(id, ext, data.clone(), ttlMs > 0 ? System.nanoTime() + ttlMs * 1_000_000L : 0);
        boolean droppedNow = false;
        if (listenOnly || noTx) return false;   // listen-only and receive-only never transmit, not even the heartbeat
        synchronized (lock) {
            if (!up) { refusedDown.incrementAndGet(); return false; }
            Deque<Pending> q = prio == PRIO_HEARTBEAT ? hb : prio == PRIO_FIS ? fis : other;
            int max = prio == PRIO_HEARTBEAT ? MAX_HB : prio == PRIO_FIS ? MAX_FIS : MAX_OTHER;
            if (q.size() >= max) {
                if (prio == PRIO_HEARTBEAT) {
                    q.poll();                 // never drop the newest heartbeat
                } else {
                    droppedNow = true;
                }
            }
            if (!droppedNow) {
                accepted.incrementAndGet();
                q.add(p);
                lock.notifyAll();
            }
        }
        if (droppedNow) {
            long d = dropped.incrementAndGet();
            LOG.warning("tx queue full, frame dropped (" + d + " total)");
            if (status != null) status.onDropped(d);
            return false;
        }
        return true;
    }

    public void beginFisMessage() {
        synchronized (lock) {
            if (fisHold++ == 0) holdSince = System.nanoTime();
        }
    }

    public void endFisMessage() {
        synchronized (lock) {
            if (fisHold > 0) fisHold--;
            lock.notifyAll();
        }
    }

    // ---- counters ---------------------------------------------------------------------------------------
    public long rxCount() { return rxCount.get(); }
    public long reinitCount() { return reinits.get(); }

    // ---- where do transmitted frames get stuck? ---------------------------------------------------------
    private final AtomicLong accepted = new AtomicLong(), refusedDown = new AtomicLong(), writeErrors = new AtomicLong();
    private volatile long writeStartNanos, lastWriteNanos;
    private volatile String lastWriteError = "";

    /** one line for the CAN card: frames queued / refused, write errors, whether the writer is blocked on the USB port, queue depths */
    public String txInfo() {
        long w0 = writeStartNanos, lw = lastWriteNanos;
        int h, f, o;
        synchronized (lock) { h = hb.size(); f = fis.size(); o = other.size(); }
        String writer = w0 != 0 ? String.format("BLOCKED in a USB write for %.1f s", (System.nanoTime() - w0) / 1e9)
            : lw == 0 ? "no write yet" : String.format("last write %.1f s ago", (System.nanoTime() - lw) / 1e9);
        return "tx accepted " + accepted.get() + ", refused (adapter down) " + refusedDown.get() + ", dropped (queue full) " + dropped.get()
            + ", write errors " + writeErrors.get() + (lastWriteError.isEmpty() ? "" : " [" + lastWriteError + "]")
            + "; writer: " + writer + "; queues hb/fis/other " + h + "/" + f + "/" + o;
    }

    // ---- listen-only and the adapter's own answers ------------------------------------------------------
    private volatile boolean listenOnly;
    private volatile String adapterVersion = "", adapterFlags = "", adapterOther = "";

    /** Listen-only: the adapter is opened with L, nothing is ever transmitted (not even the heartbeat) and nothing is acknowledged. Reopens the adapter. */
    public void setListenOnly(boolean on) {
        if (listenOnly == on) return;
        listenOnly = on;
        synchronized (lock) {
            hb.clear(); fis.clear(); other.clear();
        }
        Slcan.Wire w = wire;
        if (w != null) w.close();   // the supervisor reopens it in the new mode
    }

    public boolean isListenOnly() { return listenOnly; }

    private volatile boolean diag, recover;

    /** Diagnostics: query the adapter (V at open, F every 2 s) and show its answers. Off by default: the reference sender never does this. */
    public void setDiag(boolean on) { diag = on; }

    /** Auto-recovery: reset the adapter when the bus is silent, reopen USB after two failed resets, reopen when it does not answer at open. Off by default. */
    public void setRecover(boolean on) { recover = on; }

    private volatile boolean noTx;

    /** Receive-only but acknowledging: the adapter is in normal mode (it acknowledges frames, so the cluster has a live partner) but the app sends nothing. */
    public void setNoTx(boolean on) {
        if (noTx == on) return;
        noTx = on;
        synchronized (lock) { hb.clear(); fis.clear(); other.clear(); }
    }

    public boolean isNoTx() { return noTx; }

    /** V and F go out after every open: the answers tell whether the serial link and the bus are alive */
    private void probe(Slcan.Wire w) throws IOException {
        synchronized (wireLock) {
            w.write(new byte[]{'V', '\r'});
            w.write(new byte[]{'F', '\r'});
        }
    }

    /** terminal status of the cluster: byte 0 bit0 = key in, bit2 = ACC, bit1 = ignition (15): 01 key in, 05 ACC, 07 ignition on */
    private static final int ID_TERMINALS = 0x271;
    private volatile int ignition = -1;         // 1 = ignition on, 0 = off or ACC only, -1 = not seen yet

    /**
     * The comfort bus keeps running with the key in the lock, so switching the ignition off and on again without taking the key out
     * leaves no silent gap, and the head unit sees no ACC_OFF either (that comes only with the key out). The cluster still restarts
     * and forgets its radio. The ignition bit is the signal for that: a rise from off to on counts as the bus speaking again.
     */
    private void noteTerminals(int b0) {
        int now = (b0 & 0x02) != 0 ? 1 : 0;
        int was = ignition;
        if (was == now) return;
        ignition = now;
        long t = System.nanoTime();
        ignitionEdgeAt = t;
        LOG.info("ignition " + (now == 1 ? "on" : "off") + " (0x271 b0=" + String.format("%02X", b0) + ")");
        if (now == 0) {
            ignitionOffAt = t;
            // The radio heartbeat stops here, at the CAN level, whatever the display loop is busy with (a registration, a pause, a send):
            // a heartbeat that is still running when the cluster boots makes it show its radio screen, empty, and refuse a registration.
            // It stays stopped until the display loop starts it again after its pause (holdHeartbeat(false)), not when the ignition returns.
            holdHeartbeat(true);
            synchronized (lock) { hb.clear(); }
        } else if (was == 0) {
            busEpoch.incrementAndGet();   // the ignition is back: the cluster has restarted (a flick of 0.9 s was enough for that)
        }
    }

    private volatile long ignitionOffAt, ignitionEdgeAt;
    private volatile long hbHoldSince;          // nanoTime since which no heartbeat frame is sent, 0 = sent

    /** hold = true: no radio heartbeat frame is transmitted (the frames the display link asks for are dropped); false: they go out again */
    public void holdHeartbeat(boolean hold) {
        if (hold) { if (hbHoldSince == 0) hbHoldSince = System.nanoTime(); }
        else hbHoldSince = 0;
    }

    /** how long the heartbeat has been held back, ms; -1 = not held */
    public long heartbeatHeldForMs() { long h = hbHoldSince; return h == 0 ? -1 : (System.nanoTime() - h) / 1_000_000L; }

    /** ms since the ignition bit last changed in either direction, even a dip too short to count as a restart; -1 = never */
    public long ignitionEdgeAgoMs() { return ignitionEdgeAt == 0 ? -1 : (System.nanoTime() - ignitionEdgeAt) / 1_000_000L; }

    /** false from the first frame that shows the ignition off (key position 0 or 1); true when on or not known yet */
    public boolean ignitionOn() { return ignition != 0; }

    private static final long BUS_GAP_NANOS = 1_500_000_000L;
    private final AtomicLong busEpoch = new AtomicLong();
    private volatile int radioFlag = -1;
    private volatile long radioFlagAt;

    /** counts every time the bus was silent for more than 1.5 s and then spoke again (an ignition off and on, a module waking up) */
    public long busEpoch() { return busEpoch.get(); }

    /** first byte of the cluster's 0x40C frame: 0x19 = it sees a radio, 0x00 = it does not; -1 = not seen yet */
    public int radioFlag() { return radioFlag; }

    /** how long ago the 0x40C frame was last seen, ms; -1 = never */
    public long radioFlagAgeMs() { return radioFlagAt == 0 ? -1 : (System.nanoTime() - radioFlagAt) / 1_000_000; }

    private volatile long openedAtNanos;

    /**
     * True while it makes sense to transmit: the adapter is open and frames arrived within the last 5 s, or it was opened less
     * than 5 s ago. With a silent bus (cluster off, adapter deaf) nobody acknowledges anything, so the heartbeat and the
     * cluster registration only pile up unacknowledged frames.
     */
    public boolean busAlive() {
        if (!isUp()) return false;
        long now = System.nanoTime(), rx = lastRxNanos;
        if (rx != 0 && now - rx < 5_000_000_000L) return true;
        return now - openedAtNanos < 5_000_000_000L;
    }

    private volatile long adapterAt;
    private final AtomicLong rawBytes = new AtomicLong();
    private volatile String lastRaw = "";

    /** the last bytes from the serial port as text: CR, BEL and other control bytes are spelled out */
    private static String printable(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, n - 40); i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 13) sb.append("<CR>");
            else if (c == 7) sb.append("<BEL>");
            else if (c < 32 || c > 126) sb.append(String.format("<%02X>", c));
            else sb.append((char) c);
        }
        return sb.toString();
    }

    private void note(String line) {
        adapterAt = System.nanoTime();
        String l = line.replace("\u0007", "<BEL>").replaceAll("[^\\x20-\\x7E<>]", "");
        if (l.startsWith("V")) adapterVersion = l;
        else if (l.startsWith("F")) adapterFlags = l;
        else adapterOther = l;
    }

    /** the adapter's answers, decoded, for the status card; "no answer yet" means the serial link itself is silent */
    public String adapterInfo() {
        if (!diag) return "diagnostics off (switch them on to query the adapter)";
        StringBuilder sb = new StringBuilder();
        sb.append(adapterVersion.isEmpty() ? "version: no answer" : "version " + adapterVersion);
        if (adapterFlags.isEmpty()) {
            sb.append(", flags: no answer");
        } else {
            sb.append(", flags ").append(adapterFlags);
            try {
                int f = Integer.parseInt(adapterFlags.substring(1), 16);
                String[] names = {"rx fifo full", "tx fifo full", "error warning", "data overrun", "", "error passive", "arbitration lost", "bus error"};
                StringBuilder d = new StringBuilder();
                for (int i = 0; i < 8; i++) if ((f & (1 << i)) != 0 && !names[i].isEmpty()) d.append(d.length() == 0 ? "" : ", ").append(names[i]);
                sb.append(d.length() == 0 ? " (no errors)" : " (" + d + ")");
            } catch (RuntimeException ignored) {
                // not a hex flag byte
            }
        }
        if (!adapterOther.isEmpty()) sb.append(", other: ").append(adapterOther);
        sb.append(", serial bytes in ").append(rawBytes.get());
        if (!lastRaw.isEmpty()) sb.append(", last: ").append(lastRaw);
        long at = adapterAt;
        sb.append(at == 0 ? ", it has never answered" : String.format(", last answer %.1f s ago", (System.nanoTime() - at) / 1e9));
        return sb.toString();
    }
    public long txCount() { return txCount.get(); }
    public long droppedCount() { return dropped.get(); }
    public CanFrame lastRx() { return lastRx; }
    public long millisSinceRx() { return lastRxNanos == 0 ? -1 : (System.nanoTime() - lastRxNanos) / 1_000_000; }

    // ---- the FIS side -----------------------------------------------------------------------------------
    /** the adapter view the FIS link uses: FIS-priority transmit and a buffer of the cluster's frames */
    public CanAdapter fisAdapter() {
        return new CanAdapter() {
            @Override public void tx(int canId, byte[] data) { CanBroker.this.tx(PRIO_FIS, canId, false, data, 0); }
            @Override public void txHeartbeat(int canId, byte[] data) {
                if (busAlive() && hbHoldSince == 0) CanBroker.this.tx(PRIO_HEARTBEAT, canId, false, data, 0);
            }
            @Override public long mark() { return fisBuf.mark(); }
            @Override public byte[] waitFor(long mark, int canId, Predicate<byte[]> pred, double timeoutSeconds) {
                return fisBuf.waitFor(mark, canId, pred, timeoutSeconds);
            }
            @Override public void beginMessage() { beginFisMessage(); }
            @Override public void endMessage() { endFisMessage(); }
        };
    }
}
