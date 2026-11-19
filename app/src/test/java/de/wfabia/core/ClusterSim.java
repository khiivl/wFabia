// SPDX-License-Identifier: GPL-3.0-or-later
// Not a JUnit test: a runnable model of the cluster's power-up behaviour (docs/CLUSTER_BEHAVIOUR.md) driving the real display loop.
// javac -d out app/src/main/java/de/wfabia/core/{<files without org.json/android>}.java FakeCluster.java FakeWire.java ClusterSim.java
// java -cp out de.wfabia.core.ClusterSim 1.5 1500
package de.wfabia.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulated cluster v2, following the two captures (2026-11-16 and 2026-11-20):
 *  - frame 0x271 b0: 01 key at 0 (ACC off), 05 ACC, 07 ignition; ACC fall powers the cluster down, ACC rise powers it up;
 *  - after a power-up the cluster is silent for INIT ms; if the radio is present then (or appears during it), the cluster polls it
 *    with its own setup requests (0x2E8 39 C0 99, BURST ms), gets no answer, and keeps every session of that radio at flag 00;
 *  - the radio is forgotten after 2.8 s without heartbeat (registration count and the disabled state reset);
 *  - three registrations per presence, then 2B FF 09;
 *  - content: 27 SS 01 with ignition on and radio not disabled, else 23 SS 00; 2B SS 03 for a wrong session.
 */
public class ClusterSim {
    static final long T0 = System.nanoTime();
    static long ms() { return (System.nanoTime() - T0) / 1_000_000L; }
    static void ev(String s) { System.out.println(String.format("%7.2f  %s", ms() / 1000.0, s)); }

    static int INIT = 1500, BURST = 3300;

    static class SimCluster extends FakeCluster {
        volatile int key = 0x07;
        volatile long lastHb;
        volatile long silentUntil;
        volatile long burstUntil;
        volatile boolean radioDisabled;
        int regCount, registered = -1, nextSession = 0xC0;
        volatile int shown, inactive, refusedReg, acceptedReg;
        volatile long lastShownAt;
        FakeWire wire;
        private ByteArrayOutputStream sbuf = new ByteArrayOutputStream();

        SimCluster() { super(0); }

        boolean ignition() { return (key & 2) != 0; }
        boolean acc() { return (key & 4) != 0; }
        boolean radioPresent() { return lastHb != 0 && System.nanoTime() - lastHb < 350_000_000L; }

        synchronized void setKey(int b) {
            int was = key;
            key = b;
            wire.injectFrame(0x271, new byte[]{(byte) b, 0});
            boolean accWas = (was & 4) != 0, accNow = (b & 4) != 0;
            if (accWas && !accNow) {
                registered = -1; regCount = 0; radioDisabled = false;
                ev("cluster: power down (ACC off), sessions lost");
            } else if (!accWas && accNow) {
                registered = -1; regCount = 0; radioDisabled = false;
                long now = ms();
                silentUntil = now + INIT;
                ev("cluster: power up, silent for " + INIT + " ms" + (radioPresent() ? ", radio PRESENT: it will poll it" : ", radio absent"));
                if (radioPresent()) startBurst(now + 300);
            }
        }

        void startBurst(long from) {
            burstUntil = from + BURST;
            silentUntil = Math.max(silentUntil, burstUntil);
            new Thread(() -> {
                try { Thread.sleep(Math.max(0, from - ms())); } catch (InterruptedException e) { return; }
                ev("cluster: polls the radio (C0 requests) for " + BURST + " ms, nobody answers");
                while (ms() < burstUntil) {
                    wire.injectFrame(0x2E8, new byte[]{0x39, (byte) 0xC0, (byte) 0x99});
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
                radioDisabled = true;
                ev("cluster: gave up polling: this radio is disabled (flag 00) until it has been gone for 3 s");
            }).start();
        }

        @Override public synchronized void tx(int canId, byte[] data) {
            long now = System.nanoTime();
            if (canId == 0x439) {
                if (lastHb != 0 && now - lastHb > 2_800_000_000L) {
                    if (registered != -1 || radioDisabled || regCount != 0) ev("cluster: radio absent " + (now - lastHb) / 1_000_000 + " ms: forgotten (count, disabled state reset)");
                    registered = -1; regCount = 0; radioDisabled = false;
                }
                boolean wasAbsent = lastHb == 0 || now - lastHb > 350_000_000L;
                lastHb = now;
                if (wasAbsent && ms() < silentUntil && burstUntil < ms() && acc()) {
                    // the radio appears while the cluster is still powering up: it counts as present at power-up
                    ev("cluster: the radio appeared during the power-up: it will poll it");
                    startBurst(ms());
                }
                return;
            }
            if (ms() < silentUntil || !acc()) return;
            if (canId == 0x4D9 && data.length >= 2 && (data[1] & 0xFF) == 0xC0) {
                sbuf = new ByteArrayOutputStream();
                append(0x2E8, new byte[]{0x39, (byte) 0xD0, (byte) 0x99});
            } else if (canId == 0x6B9 && data.length > 0) {
                radio(data);
            }
        }

        @Override public void txHeartbeat(int canId, byte[] data) { tx(canId, data); }

        private void radio(byte[] d) {
            int op = (d[0] & 0xFF) >> 4, seq = d[0] & 0xF;
            if ((d[0] & 0xFF) == 0xA0) {
                append(0x699, new byte[]{(byte) 0xA1, 0x04, (byte) 0x8A, (byte) 0x85, 0x43, (byte) 0x94});
            } else if (op <= 3) {
                sbuf.write(d, 1, d.length - 1);
                if (op == 0 || op == 1) append(0x699, new byte[]{(byte) (0xB0 | ((seq + 1) & 0xF))});
                if (op == 1) {
                    byte[] msg = sbuf.toByteArray();
                    sbuf = new ByteArrayOutputStream();
                    byte[] r = reply(msg);
                    byte[] out = new byte[r.length + 1];
                    out[0] = 0x10;
                    System.arraycopy(r, 0, out, 1, r.length);
                    append(0x699, out);
                }
            }
        }

        private byte[] reply(byte[] msg) {
            boolean display = ignition() && !radioDisabled;
            if (Arrays.equals(msg, new byte[]{0x15, 0x39, 0x00, 0x01, 0x01})) return new byte[]{0x35};
            if (msg.length >= 4 && msg[0] == 0x02 && (msg[1] & 0xFF) == 0x80 && msg[2] == 0x39 && msg[3] == 0x20) {
                if (regCount >= 3) { refusedReg++; ev("cluster: registration REFUSED (2B FF 09)"); return new byte[]{0x2B, (byte) 0xFF, 0x09}; }
                regCount++;
                registered = nextSession++ & 0xFF;
                acceptedReg++;
                ev("cluster: registration accepted, session " + Integer.toHexString(registered) + " (" + regCount + " of 3), display " + (display ? "ON" : "off"));
                return new byte[]{0x23, (byte) registered, (byte) (display ? 1 : 0)};
            }
            boolean ok = msg.length == 37 && msg[0] == 0x09 && registered >= 0 && (msg[1] & 0xFF) == registered
                && Arrays.equals(Arrays.copyOfRange(msg, 2, 7), new byte[]{0x57, 0x0F, 0x02, 0x00, 0x00})
                && msg[7] == 0 && msg[8] == 0
                && Arrays.equals(Arrays.copyOfRange(msg, 19, 26), new byte[]{0x57, 0x0F, 0x03, 0x00, 0x00, 0x0A, 0x00})
                && msg[36] == 0x08;
            if (!ok) return new byte[]{0x2B, (byte) (registered & 0xFF), 0x03};
            if (display) { shown++; lastShownAt = ms(); return new byte[]{0x27, (byte) registered, 0x01}; }
            inactive++;
            return new byte[]{0x23, (byte) registered, 0x00};
        }
    }

    static SimCluster sim;
    static long lastKeyAt;

    static void waitText(long since, long maxMs) throws Exception {
        long end = System.currentTimeMillis() + maxMs;
        while (sim.lastShownAt <= since && System.currentTimeMillis() < end) Thread.sleep(10);
    }

    static void keyOff() { sim.setKey(0x05); sim.setKey(0x01); }
    static void keyOn() { sim.setKey(0x03); sim.setKey(0x07); }

    /** key to position 0 for offMs, back to 2: the time until the text is back, counted from the key coming back */
    static void flick(String name, int offMs) throws Exception {
        ev("=== " + name + ": key to 0 for " + offMs + " ms ===");
        int regs = sim.acceptedReg, ref = sim.refusedReg;
        keyOff();
        Thread.sleep(offMs);
        long on = ms();
        keyOn();
        waitText(on, 25000);
        long took = sim.lastShownAt > on ? sim.lastShownAt - on : -1;
        ev(String.format("RESULT %-22s text back %s after the key returned, registrations %d, refused %d", name, took < 0 ? "NEVER" : took + " ms", sim.acceptedReg - regs, sim.refusedReg - ref));
        Thread.sleep(3000);
    }

    static void accDip(int ms) throws Exception {
        ev("=== ACC dip: ignition off " + ms + " ms, ACC stays on ===");
        int regs = sim.acceptedReg;
        sim.setKey(0x05);
        Thread.sleep(ms);
        long on = ms();
        sim.setKey(0x07);
        waitText(on, 25000);
        long took = sim.lastShownAt > on ? sim.lastShownAt - on : -1;
        ev(String.format("RESULT %-22s text back %s after the ignition returned, registrations %d", "ACC dip " + ms + " ms", took < 0 ? "NEVER" : took + " ms", sim.acceptedReg - regs));
        Thread.sleep(3000);
    }

    public static void main(String[] args) throws Exception {
        double warm = args.length > 0 ? Double.parseDouble(args[0]) : 1.5;
        if (args.length > 1) INIT = Integer.parseInt(args[1]);
        sim = new SimCluster();
        FakeWire wire = FakeWire.withCluster(sim);
        sim.wire = wire;
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        AtomicReference<String> state = new AtomicReference<>("");
        DisplayController dc = new DisplayController(broker, new NavModel(), MaxidotRenderer.Options::new, () -> 50.0, r -> {},
            st -> { if (!st.equals(state.get())) { state.set(st); ev("app: " + st); } }, warm);
        dc.setWelcomeSeconds(0.5);
        Thread bus = new Thread(() -> {
            long n = 0;
            while (true) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                n++;
                if (n % 2 == 0) wire.injectFrame(0x271, new byte[]{(byte) sim.key, 0});
                if (n % 5 == 0) {
                    wire.injectFrame(0x40C, new byte[]{(byte) (sim.radioPresent() ? 0x19 : 0x00), (byte) (sim.ignition() ? 0x01 : 0x11), 0, 0, 0, 0});
                    wire.injectFrame(0x408, new byte[]{0x0C, (byte) (sim.ignition() ? 0x01 : 0x11), 0, 0, 0, 0});
                }
            }
        });
        bus.setDaemon(true);
        bus.start();
        broker.start();
        dc.start();
        try {
            long t = ms();
            waitText(t, 30000);
            ev("=== cold start (key at 2): text after " + (sim.lastShownAt - t) + " ms ===");
            Thread.sleep(2000);
            accDip(500);
            accDip(500);
            flick("flick 0.5 s", 500);
            flick("flick 1.5 s", 1500);
            flick("flick 4 s", 4000);
            flick("flick 0.5 s again", 500);
            ev("=== ON pulses from key position 0 (as in the capture) ===");
            keyOff(); Thread.sleep(6000);
            for (int onMs : new int[]{600, 1200}) {
                keyOn(); Thread.sleep(onMs); keyOff();
                ev("pulse ON " + onMs + " ms done, key back at 0");
                Thread.sleep(8000);
            }
            ev("=== key to 2 and stay ===");
            long on = ms();
            keyOn();
            waitText(on, 25000);
            ev(String.format("RESULT %-22s text back %s after the key came on", "ON and stay", sim.lastShownAt > on ? (sim.lastShownAt - on) + " ms" : "NEVER"));
            Thread.sleep(3000);
            ev("=== stuck session: the cluster disables the radio while the key is at 2 ===");
            long s0 = ms();
            sim.radioDisabled = true;
            long gone = ms();
            Thread.sleep(500);
            waitText(gone + 1000, 30000);
            ev(String.format("RESULT %-22s text back %s after the radio was disabled", "stuck recovery", sim.lastShownAt > gone + 1000 ? (sim.lastShownAt - gone) + " ms" : "NEVER"));
        } finally {
            dc.stop();
            broker.stop();
        }
        ev("TOTAL registrations accepted " + sim.acceptedReg + ", refused " + sim.refusedReg + ", content shown " + sim.shown + ", inactive " + sim.inactive);
        System.exit(0);
    }
}
