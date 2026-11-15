// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives the cluster: registers a FisLink, then keeps the two rows current. Evaluates the renderer five times a second,
 * sends when the rows changed (so text scrolls at about 5 Hz and everything else only on change) and every few seconds
 * as a keepalive, which also notices a cluster that lost its session (ignition cycle). Re-registers after any error or
 * adapter loss. The heartbeat runs for as long as the controller runs: stopping the controller stops it, so the cluster blanks.
 * An unexpected exception in the loop is reported through the state callback and the loop carries on; it never kills the app.
 */
public final class DisplayController {
    private static final Logger LOG = Logger.getLogger("wfabia.display");
    private static final long TICK_MS = 200;
    private static final double KEEPALIVE_S = 3.0;
    /** how long the heartbeat stays off before a re-registration (the cluster drops its radio after about a second) */
    static final double OFF_SECONDS = 3.0;
    /** shown for a few seconds after every successful link (the font has capitals only) */
    static final String WELCOME_1 = "MAXINAV", WELCOME_2 = "AKTIV";

    public interface SpeedSource {
        /** km/h, or null when unknown. Must be fast and never block. */
        Double kmh();
    }

    private final CanBroker broker;
    private final NavModel nav;
    private final Supplier<MaxidotRenderer.Options> options;
    private final SpeedSource speed;
    private final Consumer<String[]> preview;
    private final Consumer<String> state;
    private final double warmup;
    private volatile boolean stopped = true;
    private volatile double welcomeSeconds = 4.0;
    private Thread thread;

    // loop state
    private FisLink link;
    private long registeredGen = -1;
    private boolean failedBefore;
    private String[] shown;
    private int fails;
    private long lastSend;
    private long welcomeUntil;
    private long seenEpoch;
    private long registeredAt;
    private long flagZeroSince;
    private boolean powerCycleNext;     // switch the heartbeat off for a while before the next registration: the cluster must see the radio disappear
    private int registerFailures;

    public DisplayController(CanBroker broker, NavModel nav, Supplier<MaxidotRenderer.Options> options, SpeedSource speed,
                             Consumer<String[]> preview, Consumer<String> state, double warmup) {
        this.broker = broker;
        this.nav = nav;
        this.options = options;
        this.speed = speed;
        this.preview = preview;
        this.state = state;
        this.warmup = warmup;
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive() && !stopped) return;
        stopped = false;
        thread = new Thread(this::run, "display");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        thread = null;
        state.accept("off");
    }

    public void setWelcomeSeconds(double s) { welcomeSeconds = s; }

    String[] compute() { return render(nav, options.get(), speed); }

    /** the two rows the cluster shows for the current state (without the welcome); also the preview while no cluster is linked */
    public static String[] render(NavModel nav, MaxidotRenderer.Options o, SpeedSource speed) {
        Feed.View v = nav.view(o.nav, o.media);
        Double sp = o.speed ? speed.kmh() : null;
        return MaxidotRenderer.screen(v, v.stepElapsed, sp, o);
    }

    private void run() {
        // one link for the whole time the display is wanted: the heartbeat never stops between attempts or adapter hiccups
        link = new FisLink(broker.fisAdapter(), warmup, 0.003);
        link.startHeartbeat();
        registeredGen = -1;
        failedBefore = false;
        shown = null;
        fails = 0;
        lastSend = 0;
        while (!stopped) {
            try {
                if (!iteration()) break;
            } catch (RuntimeException | LinkageError e) {
                // a bug (or a library method missing on this Android version): show it, keep the app alive, try again
                LOG.log(Level.SEVERE, "display loop error", e);
                StackTraceElement[] st = e.getStackTrace();
                state.accept("display error: " + e + " at " + (st.length > 0 ? st[0] : "?"));
                registeredGen = -1;
                if (!sleep(3000)) break;
            }
        }
        link.close();
        link = null;
    }

    /** one pass of the loop; false = stop */
    private boolean iteration() {
        FisLink l = link;
        if (!broker.isUp()) {
            state.accept("waiting for CAN link");
            registeredGen = -1;
            return sleep(500);
        }
        if (!broker.busAlive()) {
            // no frames from the bus: the cluster is off or the adapter is deaf. Do not register or send into the silence;
            // the heartbeat is off too, so the cluster sees the radio gone when the bus comes back
            state.accept("waiting for CAN frames: the bus is silent (cluster off?)");
            registeredGen = -1;
            powerCycleNext = true;
            return sleep(500);
        }
        long gen = broker.generation();
        if (registeredGen != gen) {
            if (powerCycleNext) {
                // like a real radio that switches off with the ignition: without that the cluster may keep treating us as the old, registered radio
                state.accept("cluster restart: switching the radio heartbeat off for " + OFF_SECONDS + " s");
                powerCycleNext = false;
                l.stopHeartbeat();
                if (!sleep((long) (OFF_SECONDS * 1000))) return false;
                l.startHeartbeat();
            }
            state.accept("registering with the cluster");
            if (!sleep((long) (warmup * 1000))) return false;    // let the cluster see the heartbeat first
            if (!broker.isUp() || broker.generation() != gen) return true;
            try {
                l.register(failedBefore);
                registeredGen = gen;
                failedBefore = false;
                shown = null;
                fails = 0;
                welcomeUntil = System.nanoTime() + (long) (welcomeSeconds * 1e9);
                registerFailures = 0;
                seenEpoch = broker.busEpoch();
                registeredAt = System.nanoTime();
                flagZeroSince = 0;
                state.accept("linked, session 0x" + Integer.toHexString(l.session()));
            } catch (IOException e) {
                failedBefore = true;
                if (++registerFailures >= 2) powerCycleNext = true;   // refused twice: let the cluster see the radio disappear
                LOG.info("registration failed: " + e.getMessage());
                long ago = broker.millisSinceRx();
                state.accept("cluster: " + e.getMessage() + " (retrying). bus: rx " + broker.rxCount() + ", tx " + broker.txCount()
                    + ", last frame from the bus " + (ago < 0 ? "never" : ago + " ms ago") + ", dropped " + broker.droppedCount());
                return sleep(3000);
            }
            return true;
        }
        // the cluster restarted (ignition off and on) or stopped seeing us: link again from a clean slate
        long epoch = broker.busEpoch();
        if (epoch != seenEpoch) {
            seenEpoch = epoch;
            state.accept("the bus went quiet and came back (ignition?): linking again");
            registeredGen = -1;
            failedBefore = true;
            powerCycleNext = true;
            return true;
        }
        int flag = broker.radioFlag();
        long nowNs = System.nanoTime();
        if (flag == 0 && broker.radioFlagAgeMs() < 1500 && (nowNs - registeredAt) / 1e9 > 5.0) {
            if (flagZeroSince == 0) flagZeroSince = nowNs;
            if ((nowNs - flagZeroSince) / 1e9 > 2.0) {
                state.accept("the cluster says it sees no radio: linking again");
                registeredGen = -1;
                failedBefore = true;
                powerCycleNext = true;
                return true;
            }
        } else {
            flagZeroSince = 0;
        }
        String[] rows = System.nanoTime() < welcomeUntil
            ? new String[]{MaxidotRenderer.center(WELCOME_1, MaxidotRenderer.ROW), MaxidotRenderer.center(WELCOME_2, MaxidotRenderer.ROW)}
            : compute();
        preview.accept(rows);
        boolean due = shown == null || !Arrays.equals(rows, shown) || (System.nanoTime() - lastSend) / 1e9 > KEEPALIVE_S;
        if (due) {
            if (l.showRows(rows[0], rows[1])) {
                shown = rows;
                fails = 0;
                lastSend = System.nanoTime();
            } else if (++fails >= 2) {
                state.accept("cluster stopped accepting rows, re-registering");
                registeredGen = -1;
                failedBefore = true;
                powerCycleNext = true;
                return true;
            }
        }
        return sleep(TICK_MS);
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return !stopped;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
