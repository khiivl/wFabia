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
 * sends when the rows changed (so text scrolls at about 5 Hz and everything else only on change) and every second
 * as a keepalive, which also notices a cluster that lost its session (ignition cycle). Re-registers after any error or
 * adapter loss. The heartbeat runs for as long as the controller runs: stopping the controller stops it, so the cluster blanks.
 * An unexpected exception in the loop is reported through the state callback and the loop carries on; it never kills the app.
 *
 * Two ways back after a lost session:
 *  - quick: the cluster stops accepting rows while the bus runs on (a warning such as low fuel takes the display and drops our
 *    session). The heartbeat never stopped, so the cluster still sees the radio: register again at once, no warm-up, no
 *    welcome, and retry every few hundred milliseconds. Only when that has not held for {@link #QUICK_RELINK_S} does it fall
 *    back to the slow way;
 *  - slow: the bus went quiet and came back (ignition), or the quick way did not hold: switch the heartbeat off for
 *    {@link #OFF_SECONDS} so the cluster sees the radio disappear, warm up, register.
 * The welcome shows once per ignition (bus epoch), as a loading bar, not after a quick re-link or an adapter reconnect.
 */
public final class DisplayController {
    private static final Logger LOG = Logger.getLogger("wfabia.display");
    private static final long TICK_MS = 200;
    /** rows are re-sent at least this often even when unchanged; a dropped session shows up within it */
    private static final double KEEPALIVE_S = 1.0;
    /** how long the heartbeat stays off before a re-registration (the cluster drops its radio after about a second) */
    static final double OFF_SECONDS = 3.0;
    /** a quick re-link episode may take this long before the slow way takes over */
    static final double QUICK_RELINK_S = 10.0;
    /** pause between quick registration attempts */
    static final long QUICK_RETRY_MS = 300;
    /** shown after the first link of an ignition (the font has capitals only) */
    static final String WELCOME_1 = "MAXINAV", WELCOME_2 = "AKTIV";
    /** the welcome's letters appear one per step, then the whole text holds for the rest of the welcome */
    static final double WELCOME_STEP_S = 0.2;
    /** at most this share of the welcome is spent on the letters appearing */
    static final double WELCOME_REVEAL_SHARE = 0.6;

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
    private volatile double quickRelinkSeconds = QUICK_RELINK_S;
    private Thread thread;

    // loop state
    private FisLink link;
    private long registeredGen = -1;
    private long linkedGen = -1;        // the adapter generation of the last successful link, kept while a re-link is under way
    private boolean failedBefore;
    private String[] shown;
    private long lastSend;
    private long welcomeUntil;
    private long welcomeStart;
    private long welcomedEpoch = -1;   // the bus epoch (ignition) the welcome was last shown in
    private long quickUntil;           // a quick re-link episode runs until then; 0 = none
    private boolean flagQuickTried;    // a quick re-link for "the cluster sees no radio" was tried since the flag was last seen set
    private long seenEpoch;
    private long registeredAt;
    private long flagZeroSince;
    private long heartbeatOffAt;       // when the controller switched the heartbeat off for a lost adapter or a silent bus, 0 = it runs
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

    /** how long the quick way may try before the heartbeat pause (tests shorten it) */
    void setQuickRelinkSeconds(double s) { quickRelinkSeconds = s; }

    String[] compute() { return render(nav, options.get(), speed); }

    /**
     * The welcome as a little loading bar: the letters of both rows appear one by one in the cells they end up in (MAXINAV,
     * then AKTIV), then the whole text holds for the rest of the welcome.
     * @param elapsed seconds since the link
     * @param total   seconds the welcome lasts
     */
    static String[] welcomeRows(double elapsed, double total) {
        String top = MaxidotRenderer.center(WELCOME_1, MaxidotRenderer.ROW), bottom = MaxidotRenderer.center(WELCOME_2, MaxidotRenderer.ROW);
        int letters = WELCOME_1.length() + WELCOME_2.length();
        double reveal = Math.min(WELCOME_STEP_S * letters, WELCOME_REVEAL_SHARE * total);
        int shown = reveal <= 0 ? letters : Math.min(letters, 1 + (int) Math.floor(Math.max(0, elapsed) * letters / reveal + 1e-9));
        return new String[]{firstLetters(top, shown), firstLetters(bottom, shown - WELCOME_1.length())};
    }

    /** the row with only its first n letters, the others blanked in place */
    private static String firstLetters(String row, int n) {
        StringBuilder b = new StringBuilder(row.length());
        int seen = 0;
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch != ' ' && seen++ < n) b.append(ch);
            else b.append(' ');
        }
        return b.toString();
    }

    /** the cluster dropped our session while the bus runs on: link again without the heartbeat pause */
    private void relinkQuickly(String why) {
        state.accept(why);
        registeredGen = -1;
        failedBefore = true;
        if (quickUntil == 0) quickUntil = System.nanoTime() + (long) (quickRelinkSeconds * 1e9);
    }

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
        linkedGen = -1;
        failedBefore = false;
        shown = null;
        lastSend = 0;
        welcomedEpoch = -1;   // switching the display on is a start: welcome again
        quickUntil = 0;
        flagQuickTried = false;
        heartbeatOffAt = 0;
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

    /** switches the heartbeat off (once) and remembers since when */
    private void heartbeatOff(FisLink l) {
        if (!l.heartbeatRunning()) return;
        l.stopHeartbeat();
        heartbeatOffAt = System.nanoTime();
    }

    /** one pass of the loop; false = stop */
    private boolean iteration() {
        FisLink l = link;
        if (!broker.isUp()) {
            // The adapter is gone (unplugged, USB reset, head unit asleep). A real radio is off in that case, so switch the
            // heartbeat off too: left running, the cluster sees the radio come back at once with no session, keeps its display
            // on (the "radio" screen) and shows nothing. The registration after the adapter returns follows the heartbeat pause.
            state.accept("waiting for CAN link");
            heartbeatOff(l);
            registeredGen = -1;
            failedBefore = true;
            powerCycleNext = true;
            shown = null;
            return sleep(500);
        }
        if (!broker.ignitionOn()) {
            // Key at position 0 or 1 with the key still in the lock: the bus runs on and the head unit gets no ACC_OFF, but the cluster
            // is dark and loses its radio. Behave like a radio that dies with the ignition: the heartbeat off now, so that the cluster
            // sees it gone, and a clean registration the moment the ignition comes back (the bus epoch changes with it).
            state.accept("waiting for the ignition (key position 2)");
            heartbeatOff(l);
            registeredGen = -1;
            powerCycleNext = true;
            shown = null;
            return sleep(200);
        }
        if (!broker.busAlive()) {
            // no frames from the bus: the cluster is off or the adapter is deaf. Do not register or send into the silence;
            // the heartbeat is off too, so the cluster sees the radio gone when the bus comes back
            state.accept("waiting for CAN frames: the bus is silent (cluster off?)");
            heartbeatOff(l);
            registeredGen = -1;
            powerCycleNext = true;
            return sleep(500);
        }
        long gen = broker.generation();
        if (registeredGen != gen) {
            if (linkedGen != -1 && linkedGen != gen) {
                // the adapter was reopened since the last link, possibly too fast to be seen as "down" here: the heartbeat gap was
                // short, the cluster never saw the radio go and would refuse a new registration. Let it see the radio disappear.
                state.accept("the adapter was reopened: linking again after a heartbeat pause");
                linkedGen = -1;
                quickUntil = 0;
                failedBefore = true;
                powerCycleNext = true;
            }
            boolean quick = quickUntil != 0 && !powerCycleNext;
            if (quick && (System.nanoTime() > quickUntil || broker.busEpoch() != seenEpoch)) {
                // the quick way did not hold, or the bus went quiet and came back meanwhile (ignition): let the cluster see the
                // radio disappear, as after an ignition cycle
                quick = false;
                quickUntil = 0;
                powerCycleNext = true;
            }
            if (powerCycleNext) {
                // like a real radio that switches off with the ignition: without that the cluster may keep treating us as the old, registered radio
                state.accept("cluster restart: switching the radio heartbeat off for " + OFF_SECONDS + " s");
                powerCycleNext = false;
                quickUntil = 0;
                l.stopHeartbeat();
                // the cluster has already seen the radio gone for as long as the heartbeat has been off
                long offFor = heartbeatOffAt == 0 ? 0 : (System.nanoTime() - heartbeatOffAt) / 1_000_000L;
                long rest = (long) (OFF_SECONDS * 1000) - offFor;
                if (rest > 0 && !sleep(rest)) return false;
                heartbeatOffAt = 0;
                l.startHeartbeat();
            }
            if (!l.heartbeatRunning()) {   // switched off while the adapter was away and no pause was due: the radio is back now
                heartbeatOffAt = 0;
                l.startHeartbeat();
            }
            if (quick) {
                state.accept("linking again (the radio heartbeat kept running)");
            } else {
                state.accept("registering with the cluster");
                if (!sleep((long) (warmup * 1000))) return false;    // let the cluster see the heartbeat first
            }
            if (!broker.isUp() || broker.generation() != gen) return true;
            try {
                l.register(failedBefore);
                long now = System.nanoTime();
                long epoch = broker.busEpoch();
                registeredGen = gen;
                linkedGen = gen;
                failedBefore = false;
                shown = null;
                if (epoch != welcomedEpoch) {   // the first link of this ignition (or since the display was switched on)
                    welcomedEpoch = epoch;
                    welcomeStart = now;
                    welcomeUntil = now + (long) (welcomeSeconds * 1e9);
                } else {
                    welcomeUntil = 0;
                }
                registerFailures = 0;
                seenEpoch = epoch;
                registeredAt = now;
                flagZeroSince = 0;
                state.accept("linked, session 0x" + Integer.toHexString(l.session()) + (quick ? " (quick re-link)" : ""));
            } catch (IOException e) {
                failedBefore = true;
                if (quick) {
                    state.accept("linking again: " + e.getMessage() + " (retrying)");
                    return sleep(QUICK_RETRY_MS);
                }
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
        if (flag > 0) flagQuickTried = false;
        if (flag == 0 && broker.radioFlagAgeMs() < 1500 && (nowNs - registeredAt) / 1e9 > 5.0) {
            if (flagZeroSince == 0) flagZeroSince = nowNs;
            if ((nowNs - flagZeroSince) / 1e9 > 2.0) {
                if (!flagQuickTried) {
                    // try the quick way once; if the cluster still sees no radio after it, the heartbeat pause follows
                    flagQuickTried = true;
                    relinkQuickly("the cluster says it sees no radio: linking again");
                } else {
                    state.accept("the cluster still sees no radio: linking again");
                    registeredGen = -1;
                    failedBefore = true;
                    powerCycleNext = true;
                }
                return true;
            }
        } else {
            flagZeroSince = 0;
        }
        String[] rows = nowNs < welcomeUntil ? welcomeRows((nowNs - welcomeStart) / 1e9, welcomeSeconds) : compute();
        preview.accept(rows);
        // No extra rate cap here: l.showRows() is synchronous and blocks on the cluster's ack, and this loop is single-threaded,
        // so a new row can never go out before the cluster replied to the last one. poke() changes how soon a change is *noticed*,
        // not how fast sends can leave; capping it at TICK_MS would just throttle below whatever the hardware can actually do.
        boolean due = shown == null || !Arrays.equals(rows, shown) || (System.nanoTime() - lastSend) / 1e9 > KEEPALIVE_S;
        if (due) {
            if (l.showRows(rows[0], rows[1])) {
                shown = rows;
                lastSend = System.nanoTime();
                quickUntil = 0;   // the cluster takes rows again: any quick re-link episode is over
            } else {
                // showRows already tried twice, reconnecting in between: the cluster dropped our session (a warning took the
                // display, for example). The heartbeat still runs, so register again right away.
                relinkQuickly("the cluster stopped accepting rows: linking again");
                return true;
            }
        }
        return sleep(TICK_MS);
    }

    private final Object wake = new Object();
    private boolean poked;

    /** something the rows depend on changed (a new speed): evaluate them now instead of at the next 200 ms tick. Any thread. */
    public void poke() {
        synchronized (wake) {
            poked = true;
            wake.notifyAll();
        }
    }

    private boolean sleep(long ms) {
        try {
            synchronized (wake) {
                if (!poked) wake.wait(ms);
                poked = false;
            }
            return !stopped;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
