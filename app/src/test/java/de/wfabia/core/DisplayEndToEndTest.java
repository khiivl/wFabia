// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** The whole display path on its real threads: fake cluster, SLCAN wire, broker, FIS link, display loop, renderer, live nav frames. */
public class DisplayEndToEndTest {
    private static void await(java.util.function.BooleanSupplier c, String what, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timeout waiting for " + what);
            Thread.sleep(10);
        }
    }

    private static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static DisplayController controller(FakeCluster fc, FakeWire wire, java.util.concurrent.atomic.AtomicReference<String> state, CanBroker broker) {
        DisplayController dc = new DisplayController(broker, new NavModel(), MaxidotRenderer.Options::new, () -> 50.0, r -> {}, state::set, 0.1);
        dc.setWelcomeSeconds(0.1);
        return dc;
    }

    /**
     * The cluster drops our session while the bus runs on (a warning such as low fuel takes the display) and refuses the
     * old session: link again at once, without the heartbeat pause and without the welcome.
     */
    @Test public void droppedSessionIsRelinkedQuicklyWithoutTheWelcome() throws Exception {
        FakeCluster fc = new FakeCluster(0x2A);
        FakeWire wire = FakeWire.withCluster(fc);
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        AtomicReference<String> state = new AtomicReference<>("");
        List<String> history = Collections.synchronizedList(new ArrayList<>());
        List<String> rowsSeen = Collections.synchronizedList(new ArrayList<>());
        DisplayController dc = new DisplayController(broker, new NavModel(), MaxidotRenderer.Options::new, () -> 50.0,
            r -> rowsSeen.add(r[0] + "|" + r[1]),
            st -> { state.set(st); if (history.isEmpty() || !history.get(history.size() - 1).equals(st)) history.add(st); }, 0.1);
        dc.setWelcomeSeconds(0.4);
        broker.start();
        dc.start();
        try {
            await(() -> state.get().startsWith("linked, session 0x2a"), "first link, last state: " + state.get(), 8000);
            await(() -> rowsSeen.contains("          |  50 KM/H "), "the screen after the welcome", 3000);
            rowsSeen.clear();
            history.clear();
            long dropped = System.currentTimeMillis();
            fc.restart(0x31);
            try {
                await(() -> state.get().startsWith("linked, session 0x31"), "quick relink", 2000);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "; state history: " + history, e);
            }
            long took = System.currentTimeMillis() - dropped;
            Thread.sleep(500);
            assertEquals(0x27, (int) fc.replies.get(fc.replies.size() - 1));   // rows are accepted again
            assertTrue("relinked in " + took + " ms", took < 2000);
            assertTrue("no heartbeat pause: " + history, history.stream().noneMatch(h -> h.contains("heartbeat off")));
            assertTrue("no welcome after a quick relink: " + rowsSeen, rowsSeen.stream().noneMatch(r -> r.startsWith(" M")));
        } finally {
            dc.stop();
            broker.stop();
        }
    }

    /** the cluster keeps refusing the registration while the bus runs on: after the quick budget the heartbeat pause follows */
    @Test public void quickRelinkFallsBackToTheHeartbeatPause() throws Exception {
        FakeCluster fc = new FakeCluster(0x2A);
        FakeWire wire = FakeWire.withCluster(fc);
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        AtomicReference<String> state = new AtomicReference<>("");
        List<String> history = Collections.synchronizedList(new ArrayList<>());
        DisplayController dc = new DisplayController(broker, new NavModel(), MaxidotRenderer.Options::new, () -> 50.0, r -> {},
            st -> { state.set(st); if (history.isEmpty() || !history.get(history.size() - 1).equals(st)) history.add(st); }, 0.1);
        dc.setWelcomeSeconds(0.1);
        dc.setQuickRelinkSeconds(1.0);
        broker.start();
        dc.start();
        try {
            await(() -> state.get().startsWith("linked, session 0x2a"), "first link", 8000);
            fc.restartStrict(0x44);   // refuses every registration until it has seen the heartbeat go away
            try {
                await(() -> state.get().startsWith("linked, session 0x44"), "link after the heartbeat pause", 15000);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "; state history: " + history, e);
            }
            assertTrue("tried the quick way first: " + history, history.stream().anyMatch(h -> h.startsWith("linking again")));
            assertTrue("then paused the heartbeat: " + history, history.stream().anyMatch(h -> h.contains("heartbeat off")));
        } finally {
            dc.stop();
            broker.stop();
        }
    }

    @Test public void theWelcomeAppearsLetterByLetter() {
        double total = 4.0;
        assertEquals(" M        |          ", String.join("|", DisplayController.welcomeRows(0.0, total)));
        assertEquals(" MA       |          ", String.join("|", DisplayController.welcomeRows(0.2, total)));
        assertEquals(" MAXINAV  |          ", String.join("|", DisplayController.welcomeRows(1.2, total)));
        assertEquals(" MAXINAV  |  A       ", String.join("|", DisplayController.welcomeRows(1.4, total)));
        assertEquals(" MAXINAV  |  AKTIV   ", String.join("|", DisplayController.welcomeRows(2.2, total)));
        assertEquals(" MAXINAV  |  AKTIV   ", String.join("|", DisplayController.welcomeRows(3.9, total)));
        // a short welcome still ends on the whole text
        assertEquals(" MAXINAV  |  AKTIV   ", String.join("|", DisplayController.welcomeRows(0.5, 0.8)));
        // every frame adds exactly one letter, in reading order
        String last = "";
        int letters = 0;
        for (double t = 0; t < 2.5; t += 0.2) {
            String now = String.join("", DisplayController.welcomeRows(t + 1e-9, total)).replace(" ", "");
            if (!now.equals(last)) {
                assertEquals(++letters, now.length());
                assertTrue(now, ("MAXINAV" + "AKTIV").startsWith(now));
                last = now;
            }
        }
        assertEquals(12, letters);
    }

    /** the bus goes silent and speaks again (ignition cycle): relink right away, without waiting for rejected rows */
    /** after the ignition cycle the cluster refuses the registration until it has seen the radio heartbeat go away and come back */
    @Test public void strictClusterIsLinkedAfterTheHeartbeatPause() throws Exception {
        FakeCluster fc = new FakeCluster(0x2A);
        FakeWire wire = FakeWire.withCluster(fc);
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        AtomicReference<String> state = new AtomicReference<>("");
        java.util.List<String> history = java.util.Collections.synchronizedList(new ArrayList<>());
        DisplayController dc = new DisplayController(broker, new NavModel(), MaxidotRenderer.Options::new, () -> 50.0, r -> {},
            st -> { state.set(st); if (history.isEmpty() || !history.get(history.size() - 1).equals(st)) history.add(st); }, 0.1);
        dc.setWelcomeSeconds(0.1);
        broker.start();
        dc.start();
        try {
            await(() -> state.get().startsWith("linked, session 0x2a"), "first link", 8000);
            wire.injectFrame(0x40C, new byte[]{0x19, 0, 0, 0});
            fc.powerOff();
            Thread.sleep(2200);                                   // ignition off
            fc.restartStrict(0x55);                               // ignition on: the cluster still believes in the old radio
            wire.injectFrame(0x40C, new byte[]{0x00, 0, 0, 0});
            try {
                await(() -> state.get().startsWith("linked, session 0x55"), "link after the heartbeat pause", 25000);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "; state history: " + history + "; cluster replies: " + fc.replies, e);
            }
        } finally {
            dc.stop();
            broker.stop();
        }
    }

    @Test public void busGapTriggersRelink() throws Exception {
        FakeCluster fc = new FakeCluster(0x2A);
        FakeWire wire = FakeWire.withCluster(fc);
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        AtomicReference<String> state = new AtomicReference<>("");
        DisplayController dc = controller(fc, wire, state, broker);
        broker.start();
        dc.start();
        try {
            await(() -> state.get().startsWith("linked, session 0x2a"), "first link", 8000);
            wire.injectFrame(0x40C, new byte[]{0x19, 0, 0, 0});
            fc.powerOff();
            Thread.sleep(2200);                                   // ignition off: nothing on the bus
            fc.restart(0x40);                                     // ignition on: the cluster boots with a fresh session
            wire.injectFrame(0x40C, new byte[]{0x00, 0, 0, 0});   // and speaks again
            await(() -> state.get().startsWith("linked, session 0x40"), "relink after the bus came back, last state: " + state.get(), 8000);
        } finally {
            dc.stop();
            broker.stop();
        }
    }

    @Test public void displayLoopRunsWithRealFramesAndEveryOptionCombination() throws Exception {
        List<Throwable> crashes = Collections.synchronizedList(new ArrayList<>());
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            crashes.add(e);
            e.printStackTrace();
        });
        FakeCluster fc = new FakeCluster(0x2A);
        FakeWire wire = FakeWire.withCluster(fc);
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        NavModel nav = new NavModel();
        MaxidotRenderer.Options opts = new MaxidotRenderer.Options();
        AtomicReference<String> state = new AtomicReference<>("");
        AtomicReference<String[]> rows = new AtomicReference<>();
        List<String> firstRows = Collections.synchronizedList(new ArrayList<>());
        Double[] speed = {87.0};
        DisplayController dc = new DisplayController(broker, nav, () -> opts, () -> speed[0],
            r -> { rows.set(r); if (firstRows.isEmpty()) firstRows.add(r[0] + "|" + r[1]); }, state::set, 0.1);
        dc.setWelcomeSeconds(0.8);
        broker.start();
        dc.start();
        try {
            await(() -> state.get().startsWith("linked"), "linked, last state: " + state.get(), 8000);
            await(() -> rows.get() != null, "first rows", 2000);
            assertEquals(" M        |          ", firstRows.get(0));   // the welcome starts with its first letter, in its final cell
            await(() -> fc.messages.stream().anyMatch(m -> m.length == 37 && fc.rows(m).equals(" MAXINAV  |  AKTIV   ")), "welcome on the cluster", 3000);
            await(() -> "          |  87 KM/H ".equals(rows.get()[0] + "|" + rows.get()[1]), "idle screen after the welcome", 4000);   // speed on row 2

            File dir = new File(System.getProperty("golden.dir"), "captures");
            int shown = 0;
            for (String name : new String[]{"synthetic_highway.jsonl", "synthetic_city.jsonl", "synthetic_lanes.jsonl", "synthetic_roundabouts.jsonl"}) {
                int n = 0;
                for (String line : Files.readAllLines(new File(dir, name).toPath(), StandardCharsets.UTF_8)) {
                    JSONObject d = new JSONObject(line);
                    if (!d.has("raw_hex")) continue;
                    nav.feed(d.getInt("type"), hex(d.getString("raw_hex")));
                    if (++n % 4 == 0) {
                        Thread.sleep(30);
                        // flip the display options while it runs
                        opts.lanes = (n / 4) % 2 == 0;
                        opts.exit = (n / 4) % 3 != 0;
                        opts.nav = (n / 4) % 5 != 0;
                        speed[0] = (n / 4) % 4 == 0 ? null : 60.0 + n;
                        shown++;
                    }
                }
            }
            Thread.sleep(600);
            assertTrue(shown > 10);
            assertTrue("the cluster accepted rows: " + fc.replies.size(), fc.replies.size() > 5);
            assertTrue("no rejected content messages", !fc.replies.contains(0x2B));
            assertEquals("uncaught exceptions: " + crashes, 0, crashes.size());
        } finally {
            dc.stop();
            broker.stop();
            Thread.setDefaultUncaughtExceptionHandler(old);
        }
    }
}
