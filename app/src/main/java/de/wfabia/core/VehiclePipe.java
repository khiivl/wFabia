// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The vehicle state wFabia publishes to companion apps (wRight): whether the car's lights are on and the dimmer wheel level,
 * read from frames the CAN broker already receives. See docs/VEHICLE_PIPE.md.
 *
 * Rules that keep the USB path safe:
 *  - onFrame() runs on the CAN reader thread. It only feeds a small filter and sets an atomic reference: no I/O, no locks a
 *    client can hold.
 *  - Delivery to the sinks happens on a separate single thread, with at most one delivery queued (the latest state wins),
 *    so a slow or dead client can never slow the USB read loop.
 *  - Nothing in here transmits.
 */
public final class VehiclePipe {
    /** instrument illumination: 0x470 byte 2 (fallback 0x635 byte 0 until a 0x470 frame has been seen) */
    public static final int ID_ILLUM = 0x470, ID_ILLUM_ALT = 0x635;
    /** at most one state change per MIN_GAP_MS, and a state at least every KEEPALIVE_MS */
    public static final long MIN_GAP_MS = 40, KEEPALIVE_MS = 2000;

    /** immutable; valid = the CAN link is up and illumination frames are arriving */
    public static final class State {
        public final boolean valid, lightsOn;
        public final int level;          // 0 = lights off, else 34..100
        public final long seq;

        State(boolean valid, boolean lightsOn, int level, long seq) {
            this.valid = valid;
            this.lightsOn = lightsOn;
            this.level = level;
            this.seq = seq;
        }

        @Override public String toString() { return "valid=" + valid + " lights=" + lightsOn + " level=" + level + " seq=" + seq; }
    }

    public interface Sink { void onState(State s); }

    private final LongSupplier clock;
    private final DimmerFilter filter = new DimmerFilter();
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();
    private final ExecutorService pub = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vehicle-pipe");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<State> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    private volatile State current = new State(false, false, 0, 0);
    private volatile boolean canUp;
    private volatile boolean seenIllum;
    private volatile boolean dirty;
    private volatile long lastSampleMs = Long.MIN_VALUE;
    private long lastPublishMs = Long.MIN_VALUE;       // guarded by this
    private long seq;                                   // guarded by this

    public VehiclePipe() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    /** test seam: a monotonic clock in milliseconds */
    public VehiclePipe(LongSupplier clock) {
        this.clock = clock;
    }

    public void addSink(Sink s) { sinks.add(s); }

    public void removeSink(Sink s) { sinks.remove(s); }

    public State current() { return current; }

    /** CAN reader thread. Never blocks. */
    public void onFrame(CanFrame f) {
        if (!f.rx || f.ext) return;
        int raw;
        if (f.id == ID_ILLUM && f.data.length > 2) {
            seenIllum = true;
            raw = f.data[2] & 0xFF;
        } else if (f.id == ID_ILLUM_ALT && f.data.length > 0 && !seenIllum) {
            raw = f.data[0] & 0xFF;
        } else {
            return;
        }
        long now = clock.getAsLong();
        boolean wasValid = lastSampleMs != Long.MIN_VALUE;
        lastSampleMs = now;
        if (filter.feed(now, raw) || !wasValid) dirty = true;
        maybePublish(now, !wasValid);          // the first valid sample is published at once, not after the rate limit
    }

    /** link state from the CAN broker (any thread) */
    public void setCanUp(boolean up) {
        if (canUp == up) return;
        canUp = up;
        if (!up) {
            filter.reset();
            lastSampleMs = Long.MIN_VALUE;
        }
        dirty = true;
        maybePublish(clock.getAsLong(), true);
    }

    /**
     * Drops the held state: until a frame arrives again the pipe says "invalid", and the companion app (wRight) takes that for
     * lights off. Called at ACC_OFF (the lights state is dropped with the ignition) and again at ACC_ON. Nothing else would
     * take the state back: the ticker is idle while the ignition is off, and the stale test counts in System.nanoTime(), which
     * does not advance while the unit is suspended - a night's sleep can look like a few milliseconds. With the CANable
     * disconnected no frame ever comes to replace it, so the last state of the previous drive would be reported as the car's.
     */
    public void invalidate() {
        filter.reset();
        lastSampleMs = Long.MIN_VALUE;
        dirty = true;
        maybePublish(clock.getAsLong(), true);
    }

    /** about once a second from the service ticker: stale detection and the keepalive */
    public void tick() {
        long now = clock.getAsLong();
        long last = lastSampleMs;
        if (last != Long.MIN_VALUE && now - last > DimmerFilter.STALE_MS) {
            filter.reset();
            lastSampleMs = Long.MIN_VALUE;
            dirty = true;
        }
        maybePublish(now, false);
    }

    private synchronized void maybePublish(long now, boolean force) {
        boolean keepalive = lastPublishMs == Long.MIN_VALUE || now - lastPublishMs >= KEEPALIVE_MS;
        boolean ready = dirty && (force || lastPublishMs == Long.MIN_VALUE || now - lastPublishMs >= MIN_GAP_MS);
        if (!ready && !keepalive) return;
        State s = new State(canUp && lastSampleMs != Long.MIN_VALUE, filter.lightsOn(), filter.level(), ++seq);
        dirty = false;
        lastPublishMs = now;
        current = s;
        pending.set(s);
        if (scheduled.compareAndSet(false, true)) pub.execute(this::drain);
    }

    private void drain() {
        scheduled.set(false);
        State s = pending.getAndSet(null);
        if (s == null) return;
        for (Sink k : sinks) {
            try {
                k.onState(s);
            } catch (RuntimeException ignored) {
                // a failing client must not stop the others
            }
        }
    }

    public void stop() { pub.shutdownNow(); }
}
