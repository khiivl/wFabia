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
 * as a keepalive. Re-registers after an error or adapter loss. The heartbeat runs for as long as the controller runs:
 * stopping the controller stops it, so the cluster blanks. An unexpected exception in the loop is reported through the state
 * callback and the loop carries on; it never kills the app.
 *
 * What the cluster does (measured in the car, see docs/CLUSTER_BEHAVIOUR.md):
 *  - The radio follows the ACC bit of the key frame like a real radio: key at position 0 (ACC off) the radio is off, the heartbeat
 *    stops at once and the channel is closed; when ACC returns the radio "boots": the heartbeat starts again after the cluster has
 *    had {@link #OFF_SECONDS} without it and {@link #BOOT_SECONDS} to power up, then it registers. A cluster that powers up while
 *    the radio is present polls it with its own setup requests, gets no answer and keeps every session of that radio at display
 *    flag 00 ("display off") until the radio has been gone for about 3 s.
 *  - With ACC on and the ignition off (key position 1) it answers a content message with "23 SS 00": accepted, display off, the
 *    session byte still valid. That is not an error. The radio stays registered and the heartbeat keeps running, as a real radio
 *    does in ACC; the rows are offered once a second, and at once when the ignition comes back ("27 SS 01": drawn).
 *  - It accepts three registrations per presence of the radio and refuses every further one ("2B FF 09") until the radio has
 *    been gone for about three seconds. So a registration is never repeated blindly: after three, or after a refusal, the
 *    heartbeat is switched off for {@link #OFF_SECONDS} first.
 *  - "2B SS 03" to a content message: the session is gone (a cluster reset, a warning that took the display): register again.
 *  - No answer to the connection setup: the cluster is not up yet. Ask again every half second, the heartbeat keeps running.
 *
 * Two ways back after a lost session:
 *  - quick: the heartbeat never stopped, so the cluster still sees the radio: register again at once, no warm-up, no welcome,
 *    and retry every few hundred milliseconds. Only when that has not held for {@link #QUICK_RELINK_S} does it fall back to the slow way;
 *  - slow: the bus went quiet and came back, a registration was refused, or the quick way did not hold: switch the heartbeat off
 *    for {@link #OFF_SECONDS} so the cluster sees the radio disappear, warm up, register.
 * The welcome shows once per bus epoch, as a loading bar, not after a quick re-link or an adapter reconnect.
 */
public final class DisplayController {
    private static final Logger LOG = Logger.getLogger("wfabia.display");
    private static final long TICK_MS = 200;
    /** rows are re-sent at least this often even when unchanged; a lost session shows up within it */
    private static final double KEEPALIVE_S = 1.0;
    /** how long the heartbeat stays off before a re-registration: the cluster forgets a radio that has been gone for about 3 s */
    public static final double OFF_SECONDS = 3.0;
    /** the cluster accepts this many registrations while it sees the radio, then refuses until the radio has been gone */
    static final int MAX_REGISTRATIONS_PER_PRESENCE = 3;
    /** a quick re-link episode may take this long before the slow way takes over */
    static final double QUICK_RELINK_S = 10.0;
    /** pause between quick registration attempts */
    static final long QUICK_RETRY_MS = 300;
    /** how long the handshake is tried again every 0.5 s while the cluster does not answer, before the full pause is taken */
    static final double NOT_READY_MAX_S = 8.0;
    /** after ACC returned the cluster needs this long to power up before the radio appears again (tune: debug.wfabia.boot) */
    public static final double BOOT_SECONDS = 2.5;
    /** a radio that came back and got a stuck session (flag 00 with the ignition on) makes the next boot wait this much longer, up to the maximum */
    static final double BOOT_LEARN_STEP_S = 1.0, BOOT_MAX_S = 6.0;
    /** the cluster does not answer the rows (it is starting, or busy with its own requests): ask again with the same session for this long, then register again */
    static final double ROWS_SILENT_MAX_S = 6.0;
    /** the display stays off although the ignition is on for this long: the session is stuck, the radio has to disappear and appear again */
    static final double STUCK_AFTER_S = 3.0;
    /** after the ignition comes back the rows are offered this often for a moment, the display needs a little to wake up */
    static final double WAKE_PROBE_EVERY_S = 0.25, WAKE_PROBE_FOR_S = 2.0;
    /** shown after the first link of an ignition (the font has capitals only) */
    static final String WELCOME_1 = "MAXINAV", WELCOME_2 = "AKTIV";
    /** the welcome's letters appear one per step, then the whole text holds for the rest of the welcome */
    static final double WELCOME_STEP_S = 0.2;
    /** at most this share of the welcome is spent on the letters appearing */
    static final double WELCOME_REVEAL_SHARE = 0.6;
    /** the loop shows one more letter with every answer of the cluster; the whole welcome may take this many times welcomeSeconds */
    static final int WELCOME_LETTERS = WELCOME_1.length() + WELCOME_2.length();
    static final double WELCOME_CAP_FACTOR = 5.0;

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
    /** tuning on a real car without a rebuild (FabiaService sets them from debug.wfabia.* properties): heartbeat pause, warm-up (<0 = the constructor value), and whether the warm-up may end on the radio flag */
    public volatile double offSeconds = OFF_SECONDS, warmupSeconds = -1;
    public volatile boolean flagWait = true;
    public volatile double bootSeconds = BOOT_SECONDS;
    /** debug.wfabia.sweep: run the content-message probe (ContentSweep) once while the display is linked; back to 0 arms it again */
    public volatile boolean sweepRequested;
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
    private int welcomeLetters;         // letters of the welcome the cluster has drawn so far
    private long welcomeFullAt;         // when the last one was drawn, 0 = not yet
    private long welcomedEpoch = -1;   // the bus epoch the welcome was last shown in
    private long quickUntil;           // a quick re-link episode runs until then; 0 = none
    private boolean flagQuickTried;    // a quick re-link for "the cluster sees no radio" was tried since the flag was last seen set
    private long seenEpoch;
    private long registeredAt;
    private long flagZeroSince;
    private long heartbeatOffAt;       // when the controller switched the heartbeat off for a lost adapter or a silent bus, 0 = it runs
    private boolean powerCycleNext;     // switch the heartbeat off for a while before the next registration: the cluster must see the radio disappear
    private int regsSinceAbsence;       // registrations since the radio was last gone for the full pause
    private long notReadySince;         // since when the cluster has not answered the connection setup, 0 = it did
    private boolean displayOff;         // the cluster answers "accepted, display off" (ignition not on)
    private boolean sweepDone;          // the probe ran since sweepRequested was last off
    private boolean lastIgnitionOn = true;
    private long probeUntil;            // after the ignition came back: offer the rows often until then
    private boolean radioOn = true;     // the key has ACC on: the radio runs (key position 0 = the radio is off)
    private long failedRowsSince;       // since when the cluster has not answered the rows, 0 = it did
    private long inactiveOnSince;       // since when the display has been off with the ignition on, 0 = not
    private boolean reappearTried;      // the radio already disappeared and appeared for such a stuck session
    private long radioBootedAt;         // when the radio last started after ACC returned
    private double learnedBootS;        // the boot delay the cluster has turned out to need (0 = the default is enough)

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

    String[] compute() { return render(nav, options.get(), speed, broker.voltage()); }

    /**
     * The welcome as a little loading bar: the letters of both rows appear one by one in the cells they end up in (MAXINAV,
     * then AKTIV), then the whole text holds for the rest of the welcome.
     * @param elapsed seconds since the link
     * @param total   seconds the welcome lasts
     */
    static String[] welcomeRows(double elapsed, double total) {
        int letters = WELCOME_LETTERS;
        double reveal = Math.min(WELCOME_STEP_S * letters, WELCOME_REVEAL_SHARE * total);
        int shown = reveal <= 0 ? letters : Math.min(letters, 1 + (int) Math.floor(Math.max(0, elapsed) * letters / reveal + 1e-9));
        return welcomeRowsFor(shown);
    }

    /** the welcome with its first n letters on the display (the loop shows one more with every answer of the cluster) */
    static String[] welcomeRowsFor(int shown) {
        String top = MaxidotRenderer.center(WELCOME_1, MaxidotRenderer.ROW), bottom = MaxidotRenderer.center(WELCOME_2, MaxidotRenderer.ROW);
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
        return render(nav, o, speed, null);
    }

    /** the same with the battery voltage (or null), which takes the place of 0 KM/H at a standstill */
    public static String[] render(NavModel nav, MaxidotRenderer.Options o, SpeedSource speed, Double volts) {
        Feed.View v = nav.view(o.nav, o.media);
        Double sp = o.speed ? speed.kmh() : null;
        return MaxidotRenderer.screen(v, v.stepElapsed, sp, volts, o);
    }

    private void run() {
        // one link for the whole time the display is wanted: the heartbeat never stops between attempts or adapter hiccups
        link = new FisLink(broker.fisAdapter(), warmup, 0.003);
        final FisLink mine = link;
        broker.setRadioPresent(mine::heartbeatRunning);      // the broker answers the cluster's polling only while this is the radio
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
        regsSinceAbsence = 0;
        notReadySince = 0;
        displayOff = false;
        lastIgnitionOn = broker.ignitionOn();
        probeUntil = 0;
        radioOn = broker.accOn();
        if (radioOn) broker.holdHeartbeat(false);     // key at position 0 at the start: the heartbeat stays held until ACC comes
        failedRowsSince = 0;
        inactiveOnSince = 0;
        reappearTried = false;
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
        broker.setRadioPresent(null);
        link.close();
        link = null;
    }

    /** switches the heartbeat off (once) and remembers since when */
    private void heartbeatOff(FisLink l) {
        if (!l.heartbeatRunning()) return;
        l.stopHeartbeat();
        heartbeatOffAt = System.nanoTime();
    }

    /** the rows for the content message that ends a registration: the welcome if one is due, else the current rows */
    private String[] firstRows() {
        return broker.busEpoch() != welcomedEpoch ? welcomeRows(0, welcomeSeconds) : compute();
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
            displayOff = false;
            return sleep(500);
        }
        if (!broker.answerC0 && !broker.accOn()) {
            // Key at position 0: ACC is off, a real radio is off too. The broker has already stopped the heartbeat frames; the open
            // channel is closed cleanly and nothing is sent until ACC is back.
            if (radioOn) {
                state.accept("key off (ACC off): the radio is off, heartbeat stopped");
                heartbeatOff(l);
                radioOn = false;
                registeredGen = -1;
                shown = null;
                displayOff = false;
                quickUntil = 0;
                notReadySince = 0;
                failedRowsSince = 0;
                inactiveOnSince = 0;
            }
            return sleep(100);
        }
        if (!radioOn && !broker.answerC0) {
            // ACC is back: the radio boots. The registration branch below waits until the cluster has been without the radio for
            // OFF_SECONDS and has had BOOT_SECONDS to power up, then starts the heartbeat and registers.
            state.accept("key on (ACC): the radio boots");
            radioOn = true;
            radioBootedAt = System.nanoTime();
            welcomedEpoch = -1;        // a radio that boots shows its welcome
            registeredGen = -1;
            powerCycleNext = true;
            displayOff = false;
        }
        if (!broker.busAlive()) {
            // no frames from the bus: the cluster is off or the adapter is deaf. Do not register or send into the silence;
            // the heartbeat is off too, so the cluster sees the radio gone when the bus comes back
            state.accept("waiting for CAN frames: the bus is silent (cluster off?)");
            heartbeatOff(l);
            registeredGen = -1;
            powerCycleNext = true;
            displayOff = false;
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
            if (regsSinceAbsence >= MAX_REGISTRATIONS_PER_PRESENCE && !powerCycleNext) {
                // the cluster would refuse the next one anyway ("2B FF 09") and go on refusing until the radio has been gone
                state.accept("registered " + regsSinceAbsence + " times while the radio was present: pausing the heartbeat before the next one");
                quickUntil = 0;
                powerCycleNext = true;
            }
            boolean quick = quickUntil != 0 && !powerCycleNext;
            if (quick && (System.nanoTime() > quickUntil || broker.busEpoch() != seenEpoch)) {
                // the quick way did not hold, or the bus went quiet and came back meanwhile: let the cluster see the radio disappear
                quick = false;
                quickUntil = 0;
                powerCycleNext = true;
            }
            if (powerCycleNext) {
                // like a real radio that switches off: without that the cluster keeps treating us as the old, registered radio
                state.accept("cluster restart: switching the radio heartbeat off for " + offSeconds + " s");
                powerCycleNext = false;
                quickUntil = 0;
                l.stopHeartbeat();
                // the cluster has already seen the radio gone for as long as the heartbeat has been off
                long offFor = heartbeatOffAt == 0 ? 0 : (System.nanoTime() - heartbeatOffAt) / 1_000_000L;
                offFor = Math.max(offFor, broker.heartbeatHeldForMs());   // the broker stopped the heartbeat at the ACC fall already
                long rest = (long) (offSeconds * 1000) - offFor;
                rest = Math.max(rest, (long) (Math.max(bootSeconds, learnedBootS) * 1000) - broker.accOnForMs());   // and the cluster needs time to power up
                if (rest > 0 && !sleep(rest)) return false;
                if (!broker.accOn()) return true;      // the key went off again while we waited: the radio stays off
                heartbeatOffAt = 0;
                notReadySince = 0;
                regsSinceAbsence = 0;          // the cluster forgot the radio, it counts registrations from zero again
                broker.holdHeartbeat(false);
                l.startHeartbeat();
            }
            if (!l.heartbeatRunning()) {   // switched off while the adapter was away and no pause was due: the radio is back now
                heartbeatOffAt = 0;
                broker.holdHeartbeat(false);
                l.startHeartbeat();
            }
            if (quick) {
                state.accept("linking again (the radio heartbeat kept running)");
            } else {
                state.accept("registering with the cluster");
                if (notReadySince == 0) {   // a retry while the cluster is still starting needs no new warm-up: the heartbeat has been running
                    long warm = (long) ((warmupSeconds >= 0 ? warmupSeconds : warmup) * 1000);
                    if (!(flagWait ? waitForRadio(warm) : sleep(warm))) return false;    // let the cluster see the heartbeat first
                }
            }
            if (!broker.isUp() || broker.generation() != gen) return true;
            try {
                String[] first = firstRows();
                l.register(failedBefore, first[0], first[1]);
                regsSinceAbsence++;
                long now = System.nanoTime();
                long epoch = broker.busEpoch();
                registeredGen = gen;
                linkedGen = gen;
                failedBefore = false;
                notReadySince = 0;
                if (epoch != welcomedEpoch) {   // the first link of this ignition (or since the display was switched on)
                    welcomedEpoch = epoch;
                    welcomeLetters = 0;
                    welcomeFullAt = 0;
                    // the letters go out one per answer of the cluster, however long that takes; the whole text then holds for a moment
                    welcomeUntil = now + (long) (welcomeSeconds * WELCOME_CAP_FACTOR * 1e9);
                } else {
                    welcomeUntil = 0;
                }
                seenEpoch = epoch;
                registeredAt = now;
                flagZeroSince = 0;
                FisLink.Show first1 = l.registerShow();
                displayOff = first1 == FisLink.Show.INACTIVE;
                if (first1 == FisLink.Show.OK) {
                    shown = first;           // the rows went out with the registration
                    lastSend = now;
                    if (welcomeUntil != 0) welcomeLetters = 1;   // and with them the welcome's first letter
                } else {
                    shown = null;
                    lastSend = displayOff ? now : 0;
                }
                state.accept("linked, session 0x" + Integer.toHexString(l.session()) + (quick ? " (quick re-link)" : "")
                    + (displayOff ? ", display off (no ignition)" : ""));
            } catch (IOException e) {
                failedBefore = true;
                LOG.info("registration failed: " + e.getMessage());
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean notReady = msg.startsWith("Cluster did not answer") || msg.startsWith("Init message was not answered")
                    || msg.startsWith("Second connection setup failed");
                if (notReady) {
                    // The cluster is not up yet (it is restarting). It holds no stale state, so the heartbeat stays on and the
                    // handshake is tried again shortly; only if it stays silent for a long time the full pause follows.
                    if (notReadySince == 0) notReadySince = System.nanoTime();
                    if ((System.nanoTime() - notReadySince) / 1e9 < NOT_READY_MAX_S) {
                        state.accept("the cluster is not up yet: trying again");
                        return sleep(500);
                    }
                }
                // Refused (or silent for too long): the cluster counts three registrations per presence of the radio and refuses the
                // rest until the radio has been gone for about three seconds. The pause starts now.
                notReadySince = 0;
                quickUntil = 0;
                powerCycleNext = true;
                long ago = broker.millisSinceRx();
                state.accept("cluster: " + e.getMessage() + " (retrying). bus: rx " + broker.rxCount() + ", tx " + broker.txCount()
                    + ", last frame from the bus " + (ago < 0 ? "never" : ago + " ms ago") + ", dropped " + broker.droppedCount());
                return true;
            }
            return true;
        }
        // the cluster restarted (the bus went silent and came back) or stopped seeing us: link again from a clean slate
        long epoch = broker.busEpoch();
        if (epoch != seenEpoch) {
            seenEpoch = epoch;
            state.accept("the bus went quiet and came back: linking again");
            registeredGen = -1;
            failedBefore = true;
            powerCycleNext = true;
            displayOff = false;
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
        boolean welcoming = nowNs < welcomeUntil;
        int welcomeNext = Math.min(WELCOME_LETTERS, welcomeLetters + 1);   // one more letter each time the cluster has answered
        boolean paced = false;                                               // the next letter follows at once
        String[] rows = welcoming ? welcomeRowsFor(welcomeNext) : compute();
        preview.accept(rows);

        boolean ignitionOn = broker.ignitionOn();
        if (ignitionOn && !lastIgnitionOn) {
            // the key is back at position 2: the cluster's display wakes up. The session is still valid (the radio kept running as in
            // ACC), so the rows go out at once, and are offered often for a moment because the display needs a little to come on.
            shown = null;
            lastSend = 0;
            probeUntil = nowNs + (long) (WAKE_PROBE_FOR_S * 1e9);
        }
        lastIgnitionOn = ignitionOn;

        if (!sweepRequested) sweepDone = false;
        if (sweepRequested && !sweepDone && !displayOff && shown != null && ignitionOn && broker.accOn() && nowNs >= probeUntil
            && nowNs >= welcomeUntil && quickUntil == 0 && l.session() != null) {
            // opt-in experiment: the display is linked and drawing, the key is steady at position 2
            sweepDone = true;
            String why = ContentSweep.run(l, () -> sweepRequested && !stopped && broker.ignitionOn() && broker.accOn() && broker.radioFlag() > 0, state);
            if (why != null) state.accept("sweep ended: " + why);
            shown = null;
            lastSend = 0;
            return true;
        }

        double sinceSend = (nowNs - lastSend) / 1e9;
        boolean due;
        if (displayOff) {
            // the display is off: ask whether it is on again, once a second (often just after the ignition came back)
            due = sinceSend >= (nowNs < probeUntil ? WAKE_PROBE_EVERY_S : KEEPALIVE_S);
        } else {
            // No extra rate cap here: l.show() is synchronous and blocks on the cluster's ack, and this loop is single-threaded,
            // so a new row can never go out before the cluster replied to the last one. poke() changes how soon a change is *noticed*,
            // not how fast sends can leave; capping it at TICK_MS would just throttle below whatever the hardware can actually do.
            due = shown == null || !Arrays.equals(rows, shown) || sinceSend > KEEPALIVE_S;
        }
        if (due) {
            FisLink.Show r = l.show(rows[0], rows[1]);
            if (r == FisLink.Show.OK || r == FisLink.Show.INACTIVE) failedRowsSince = 0;
            switch (r) {
                case OK:
                    shown = rows;
                    lastSend = System.nanoTime();
                    quickUntil = 0;   // the cluster takes rows again: any quick re-link episode is over
                    inactiveOnSince = 0;
                    reappearTried = false;
                    if (welcoming) {
                        welcomeLetters = welcomeNext;
                        if (welcomeLetters < WELCOME_LETTERS) {
                            paced = true;
                        } else if (welcomeFullAt == 0) {   // the whole text is on the display: it holds for a moment, then the screen goes on
                            welcomeFullAt = System.nanoTime();
                            welcomeUntil = Math.min(welcomeUntil, welcomeFullAt + (long) (welcomeSeconds * (1 - WELCOME_REVEAL_SHARE) * 1e9));
                        }
                    }
                    if (displayOff) {
                        displayOff = false;
                        state.accept("linked, session 0x" + Integer.toHexString(l.session()) + ", display on");
                    }
                    break;
                case INACTIVE:
                    // accepted, but the display is off (ignition not on): nothing is wrong, the session stays, the heartbeat runs on
                    shown = null;
                    lastSend = System.nanoTime();
                    quickUntil = 0;
                    if (!displayOff) {
                        displayOff = true;
                        state.accept("linked, session 0x" + Integer.toHexString(l.session()) + ", display off (no ignition)");
                    }
                    break;
                case REJECTED:
                    displayOff = false;
                    relinkQuickly("the cluster refused the rows (reply " + l.lastReply() + "): session lost, linking again");
                    return true;
                default:
                    // No answer at all: the cluster is starting or busy with its own requests. The session is probably fine, so ask again
                    // with the same one (registering again would only use up the three registrations); after a while register again.
                    if (failedRowsSince == 0) failedRowsSince = System.nanoTime();
                    if ((System.nanoTime() - failedRowsSince) / 1e9 < ROWS_SILENT_MAX_S) {
                        state.accept("the cluster is silent: asking again");
                        return sleep(250);
                    }
                    failedRowsSince = 0;
                    displayOff = false;
                    relinkQuickly("the cluster did not answer the rows: linking again");
                    return true;
            }
        }
        if (displayOff && ignitionOn && broker.accOn()) {
            // The display stays off although the key is at position 2: the session was registered while the cluster was powering up
            // and is stuck at flag 00. Only a radio that disappears for 3 s and appears again gets an active display.
            if (inactiveOnSince == 0) inactiveOnSince = nowNs;
            if (!reappearTried && (nowNs - inactiveOnSince) / 1e9 > STUCK_AFTER_S) {
                reappearTried = true;
                if ((nowNs - radioBootedAt) / 1e9 < 20.0) {
                    // the radio appeared while the cluster was still powering up: next time it waits longer
                    learnedBootS = Math.min(BOOT_MAX_S, Math.max(bootSeconds, learnedBootS) + BOOT_LEARN_STEP_S);
                    state.accept("the radio appeared too early after the key came on: the next boot waits " + learnedBootS + " s");
                }
                state.accept("the display stays off with the ignition on: the radio disappears and appears again");
                registeredGen = -1;
                powerCycleNext = true;
                return true;
            }
        } else if (!displayOff || !ignitionOn) {
            inactiveOnSince = 0;
            if (!ignitionOn) reappearTried = false;
        }
        return paced ? !stopped : tickWait();   // while the welcome spells out, the next letter goes as soon as the cluster has answered
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

    /** A plain sleep. Every protocol wait goes through this one (the heartbeat pause, the warm-up, the retry delays): a new speed must
     *  never cut them short, the cluster has to see the radio gone for the whole pause. */
    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return !stopped;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * The warm-up before a registration: ends the moment the cluster says it sees a radio (a 0x40C frame with byte 0 above 0 that
     * arrived after the heartbeat started, so a flag from before the pause cannot count). While the heartbeat runs and no session
     * exists the cluster shows its radio screen empty, so the handshake follows at once. maxMs is the old fixed warm-up, kept as the
     * ceiling when the flag never comes.
     */
    private boolean waitForRadio(long maxMs) {
        long t0 = System.nanoTime();
        while (!stopped) {
            long waited = (System.nanoTime() - t0) / 1_000_000L;
            if (waited >= maxMs) return true;
            // a flag frame that came in after the heartbeat started (its age is less than the time waited): the cluster sees the radio
            long age = broker.radioFlagAgeMs();
            if (broker.radioFlag() > 0 && age >= 0 && age <= waited) return true;
            if (!sleep(20)) return false;
        }
        return false;
    }

    /** The idle wait between two evaluations of the rows: the only wait that poke() may end early. */
    private boolean tickWait() {
        try {
            synchronized (wake) {
                if (!poked) wake.wait(TICK_MS);
                poked = false;
            }
            return !stopped;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
