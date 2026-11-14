// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class FisAndBrokerTest {
    private static java.util.List<String> copy(java.util.List<String> l) {
        synchronized (l) { return new java.util.ArrayList<>(l); }
    }

    private static void await(java.util.function.BooleanSupplier c, String what) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timeout waiting for " + what);
            Thread.sleep(10);
        }
    }

    // ---- FisLink against the fake cluster directly -------------------------------------------------------
    @Test public void registersAndShowsRows() throws Exception {
        FakeCluster fc = new FakeCluster(0x2A);
        try (FisLink link = new FisLink(fc, 0.05, 0.003)) {
            link.open();
            assertEquals(Integer.valueOf(0x2A), link.session());
            assertTrue(link.showRows("HELLO", "WORLD"));
            byte[] last = fc.messages.get(fc.messages.size() - 1);
            assertEquals(37, last.length);
            assertEquals("HELLO     |WORLD     ", fc.rows(last));
            assertTrue(link.showRows("AßÄ", "→ 300M  B91"));      // umlaut cell, SS, arrow cell
            assertEquals(0x27, (int) fc.replies.get(fc.replies.size() - 1));
            Thread.sleep(300);                                                 // heartbeat keeps running
            long hb = fc.sentIds.stream().filter(x -> x[0] == 0x439).count();
            assertTrue("heartbeats: " + hb, hb >= 2);
        }
    }

    @Test public void contentPayloadLayout() {
        byte[] top = "0123456789".getBytes(), bottom = "ABCDEFGHIJ".getBytes();
        byte[] p = FisLink.contentPayload(0x0E, top, bottom);
        assertEquals(37, p.length);
        assertArrayEquals(new byte[]{0x09, 0x0E, 0x57, 0x0F, 0x02, 0x00, 0x00, 0x00, 0x00}, java.util.Arrays.copyOfRange(p, 0, 9));
        assertArrayEquals(top, java.util.Arrays.copyOfRange(p, 9, 19));
        assertArrayEquals(new byte[]{0x57, 0x0F, 0x03, 0x00, 0x00, 0x0A, 0x00}, java.util.Arrays.copyOfRange(p, 19, 26));
        assertArrayEquals(bottom, java.util.Arrays.copyOfRange(p, 26, 36));
        assertEquals(0x08, p[36]);
        // a 37-byte message is sent as 20 21 22 03 24 15 (packet 3 has opcode 0, the last has 1)
        StringBuilder ops = new StringBuilder();
        for (FisTransport.Packet pk : FisTransport.chunkMessage(p)) ops.append(String.format("%02X ", pk.frame[0] & 0xFF));
        assertEquals("20 21 22 03 24 15 ", ops.toString());
    }

    @Test public void clusterRejectsStaleSession() throws Exception {
        FakeCluster fc = new FakeCluster(0x2A);
        try (FisLink link = new FisLink(fc, 0.0, 0.003)) {
            link.open();
            byte[] bad = FisLink.contentPayload(0x11, MaxidotFont.padLeft(new byte[0], 10), MaxidotFont.padLeft(new byte[0], 10));
            assertFalse(link.streamSend(bad));   // 0x2B reply
            assertEquals(0x2B, (int) fc.replies.get(fc.replies.size() - 1));
        }
    }

    // ---- through the broker and a fake SLCAN port --------------------------------------------------------
    @Test public void fisLinkThroughBrokerAndSlcan() throws Exception {
        FakeCluster fc = new FakeCluster(0x31);
        FakeWire wire = FakeWire.withCluster(fc);
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        broker.start();
        try {
            await(broker::isUp, "link up");
            assertTrue(wire.written.contains("C") && wire.written.contains("S3") && wire.written.contains("O"));   // SLCAN init
            try (FisLink link = new FisLink(broker.fisAdapter(), 0.1, 0.003)) {
                link.open();
                assertEquals(Integer.valueOf(0x31), link.session());
                assertTrue(link.showRows("DRESDEN", "87 KM/H"));
                assertEquals("DRESDEN   |87 KM/H   ", fc.rows(fc.messages.get(fc.messages.size() - 1)));
            }
            assertTrue(broker.rxCount() > 0 && broker.txCount() > 0);
            assertNotNull(broker.lastRx());
        } finally {
            broker.stop();
        }
    }

    @Test public void otherFramesWaitForFisMessageToEnd() throws Exception {
        FakeWire wire = new FakeWire();
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        broker.start();
        try {
            await(broker::isUp, "link up");
            broker.beginFisMessage();
            assertTrue(broker.tx(CanBroker.PRIO_OTHER, 0x5A0, false, new byte[]{1, (byte) 0xFF}, 0));
            broker.tx(CanBroker.PRIO_FIS, 0x6B9, false, new byte[]{0x21}, 0);
            broker.tx(CanBroker.PRIO_HEARTBEAT, 0x439, false, new byte[]{0, 1, 0, 0, 0, 0}, 0);
            await(() -> wire.written.contains("t6B9121"), "FIS frame");
            Thread.sleep(150);
            assertTrue("heartbeat goes first", wire.written.contains("t4396000100000000"));
            assertFalse("user frame must wait", wire.written.contains("t5A0201FF"));
            broker.endFisMessage();
            await(() -> wire.written.contains("t5A0201FF"), "user frame after the message");
        } finally {
            broker.stop();
        }
    }

    @Test public void fullQueueDropsUserFramesNotHeartbeat() throws Exception {
        FakeWire wire = new FakeWire();
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        broker.start();
        try {
            await(broker::isUp, "link up");
            broker.beginFisMessage();
            int refused = 0;
            for (int i = 0; i < 300; i++) if (!broker.tx(CanBroker.PRIO_OTHER, 0x5A0, false, new byte[]{(byte) i}, 0)) refused++;
            assertTrue("dropped " + refused, refused >= 172);
            assertEquals(refused, broker.droppedCount());
            for (int i = 0; i < 20; i++) assertTrue(broker.tx(CanBroker.PRIO_HEARTBEAT, 0x439, false, new byte[]{0, 1, 0, 0, 0, 0}, 0));
            broker.endFisMessage();
        } finally {
            broker.stop();
        }
    }

    @Test public void expiredUserFramesAreNotSent() throws Exception {
        FakeWire wire = new FakeWire();
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50);
        broker.start();
        try {
            await(broker::isUp, "link up");
            broker.beginFisMessage();
            broker.tx(CanBroker.PRIO_OTHER, 0x5A0, false, new byte[]{9}, 30);
            Thread.sleep(100);
            broker.endFisMessage();
            Thread.sleep(150);
            assertFalse(wire.written.contains("t5A019"));
        } finally {
            broker.stop();
        }
    }

    @Test public void reopensAfterAdapterLoss() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        FakeWire[] wires = new FakeWire[3];
        CanBroker.WireFactory f = () -> {
            int n = opens.getAndIncrement();
            if (n == 0) throw new IOException("no adapter yet");
            wires[n] = new FakeWire();
            return wires[n];
        };
        CanBroker broker = new CanBroker(f, null, 0, 50);
        broker.start();
        try {
            await(broker::isUp, "link up after the first failed open");
            long gen = broker.generation();
            wires[1].kill();
            await(() -> broker.generation() > gen && broker.isUp(), "reopen");
            assertTrue(opens.get() >= 3);
            wires[2].injectFrame(0x40C, new byte[]{0x19, 0, 0, 0});
            await(() -> broker.rxCount() >= 1, "rx on the new wire");
            assertEquals(0x40C, broker.lastRx().id);
        } finally {
            broker.stop();
        }
    }

    @Test public void silentBusReinitialisesTheAdapter() throws Exception {
        FakeWire wire = new FakeWire();
        CanBroker broker = new CanBroker(() -> wire, null, 0, 50, 200);
        broker.setRecover(true);
        broker.start();
        try {
            await(broker::isUp, "link up");
            await(() -> broker.reinitCount() >= 2, "two re-inits on a silent bus");
            await(() -> new java.util.ArrayList<>(copy(wire.written)).stream().filter("O"::equals).count() >= 3, "third O command");
            long cs = new java.util.ArrayList<>(copy(wire.written)).stream().filter("C"::equals).count(), os = new java.util.ArrayList<>(copy(wire.written)).stream().filter("O"::equals).count();
            assertTrue("C commands " + cs, cs >= 3 && os >= 3);                 // the initial open plus two resets
            long before = broker.reinitCount();
            wire.injectFrame(0x40C, new byte[]{0x19, 0, 0, 0});                  // the bus speaks: no more resets
            Thread.sleep(100);
            long after = broker.reinitCount();
            wire.injectFrame(0x40C, new byte[]{0x19, 0, 0, 0});
            Thread.sleep(100);
            assertTrue(broker.reinitCount() - after <= 1);
            assertTrue(after >= before);
        } finally {
            broker.stop();
        }
    }

    @Test public void muteSerialLinkIsReopenedAndReported() throws Exception {
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> details = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        FakeWire[] wires = new FakeWire[10];
        CanBroker.WireFactory f = () -> {
            int n = opens.getAndIncrement();
            wires[n] = new FakeWire();
            wires[n].mute = n < 2;                  // the first two opens: the port opens but the adapter says nothing
            return wires[n];
        };
        CanBroker broker = new CanBroker(f, new CanBroker.Status() {
            @Override public void onLink(boolean up, String detail) { details.add(up + ":" + detail); }
            @Override public void onDropped(long total) {}
        }, 0, 50);
        broker.setRecover(true);
        broker.start();
        try {
            await(broker::isUp, "link up on the third open");
            assertTrue("opens " + opens.get(), opens.get() >= 3);
            synchronized (details) {
                assertTrue(details.toString(), details.stream().anyMatch(d -> d.contains("does not answer on the serial port")));
            }
        } finally {
            broker.stop();
        }
    }

    @Test public void slcanParsing() {
        java.util.List<String> got = new java.util.ArrayList<>();
        Slcan.LineParser p = new Slcan.LineParser();
        byte[] b = "t40C41900\rt6B9321\rgarbage\rT123456781AB\rt4C00\r".getBytes();
        p.feed(b, b.length, (ext, id, d) -> got.add((ext ? "E" : "S") + Integer.toHexString(id) + ":" + Hex.upper(d, d.length)));
        assertEquals("[S40c:1900, S6b9:21, E12345678:AB, S4c0:]", got.toString());
        assertEquals("t5A0201FF\r", new String(Slcan.format(0x5A0, false, new byte[]{1, (byte) 0xFF})));
    }
}
