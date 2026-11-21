// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class VehiclePipeTest {
    private final AtomicLong clock = new AtomicLong(1000);
    private final VehiclePipe pipe = new VehiclePipe(clock::get);

    private static CanFrame illum(int level) {
        return new CanFrame(0, true, false, VehiclePipe.ID_ILLUM, new byte[]{(byte) 0x80, 0, (byte) level, (byte) 0xFF, 0});
    }

    private static CanFrame alt(int level) {
        return new CanFrame(0, true, false, VehiclePipe.ID_ILLUM_ALT, new byte[]{(byte) level, (byte) 0xFF, 0});
    }

    /** n frames, 50 ms apart, like the real 0x470 */
    private void feed(int level, int n) {
        for (int i = 0; i < n; i++) {
            clock.addAndGet(50);
            pipe.onFrame(illum(level));
        }
    }

    @Test public void invalidBeforeAnyFrameAndWhileCanIsDown() {
        assertFalse(pipe.current().valid);
        pipe.setCanUp(true);
        assertFalse(pipe.current().valid);          // up, but nothing has arrived yet
        feed(100, 3);
        assertTrue(pipe.current().valid);
        pipe.setCanUp(false);
        assertFalse(pipe.current().valid);
        assertFalse(pipe.current().lightsOn);
    }

    @Test public void lightsOffThenOnFollowsTheSwitch() {
        pipe.setCanUp(true);
        feed(0, 40);
        assertTrue(pipe.current().valid);
        assertFalse(pipe.current().lightsOn);
        assertEquals(0, pipe.current().level);
        feed(100, 40);
        assertTrue(pipe.current().lightsOn);
        assertEquals(100, pipe.current().level);
        feed(0, 40);
        assertFalse(pipe.current().lightsOn);
        assertEquals(0, pipe.current().level);
    }

    @Test public void rippleBelowTheTrueLevelDoesNotMoveTheLevel() {
        pipe.setCanUp(true);
        feed(100, 40);
        int[] ripple = {91, 93, 95, 96, 98, 99, 100, 88, 93, 95, 96, 97, 99, 100};
        for (int round = 0; round < 6; round++) {
            for (int v : ripple) {
                clock.addAndGet(50);
                pipe.onFrame(illum(v));
                assertEquals(100, pipe.current().level);
                assertTrue(pipe.current().lightsOn);
            }
        }
    }

    @Test public void wheelDownIsFollowedWithinTheWindow() {
        pipe.setCanUp(true);
        feed(100, 40);
        feed(50, 60);                                 // 3 s at 50 %
        assertEquals(50, pipe.current().level);
        feed(34, 60);
        assertEquals(34, pipe.current().level);
    }

    @Test public void aSingleZeroFrameDoesNotSwitchTheLightsOff() {
        pipe.setCanUp(true);
        feed(80, 40);
        clock.addAndGet(50);
        pipe.onFrame(illum(0));
        feed(80, 20);
        assertTrue(pipe.current().lightsOn);
        assertEquals(80, pipe.current().level);
    }

    @Test public void staleSignalBecomesInvalidAndOff() {
        pipe.setCanUp(true);
        feed(70, 40);
        assertTrue(pipe.current().valid);
        clock.addAndGet(DimmerFilter.STALE_MS + 100);
        pipe.tick();
        assertFalse(pipe.current().valid);
        assertFalse(pipe.current().lightsOn);
        feed(70, 5);                                  // and it recovers on its own
        assertTrue(pipe.current().valid);
        assertTrue(pipe.current().lightsOn);
    }

    @Test public void invalidateDropsTheHeldStateEvenWhenTheClockDidNotAdvance() {
        List<VehiclePipe.State> got = Collections.synchronizedList(new ArrayList<>());
        pipe.addSink(got::add);
        pipe.setCanUp(true);
        feed(70, 40);
        assertTrue(pipe.current().valid);
        assertTrue(pipe.current().lightsOn);
        // a suspend: the monotonic clock stands still, so tick() never finds the signal stale
        pipe.tick();
        assertTrue(pipe.current().valid);
        pipe.invalidate();
        assertFalse(pipe.current().valid);
        assertFalse(pipe.current().lightsOn);
        assertEquals(0, pipe.current().level);
        feed(70, 5);                                  // and the real frames bring it back
        assertTrue(pipe.current().valid);
        assertTrue(pipe.current().lightsOn);
        pipe.stop();
    }

    @Test public void invalidateWithTheAdapterGoneStaysInvalid() {
        pipe.setCanUp(true);
        feed(70, 40);
        pipe.invalidate();                            // ACC_ON, the CANable is not there: no frame follows
        clock.addAndGet(60_000);
        pipe.tick();
        assertFalse(pipe.current().valid);
        assertFalse(pipe.current().lightsOn);
    }

    @Test public void fallbackIdIsUsedOnlyUntilTheMainIdAppears() {
        pipe.setCanUp(true);
        for (int i = 0; i < 40; i++) {
            clock.addAndGet(100);
            pipe.onFrame(alt(60));
        }
        assertEquals(60, pipe.current().level);
        feed(90, 40);                                 // 0x470 shows up: from now on only it counts
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(50);
            pipe.onFrame(alt(10));
            pipe.onFrame(illum(90));
        }
        assertEquals(90, pipe.current().level);
    }

    @Test public void publishesAtMostOncePerMinGap() {
        List<VehiclePipe.State> got = Collections.synchronizedList(new ArrayList<>());
        pipe.addSink(got::add);
        pipe.setCanUp(true);
        long start = clock.get();
        for (int i = 0; i < 400; i++) {               // 4 s with a level change every 30 ms
            clock.addAndGet(10);
            pipe.onFrame(illum(i % 2 == 0 ? 100 : 40));
            clock.addAndGet(10);
            pipe.onFrame(illum(100));
        }
        long elapsed = clock.get() - start;
        pipe.stop();
        assertTrue("seq " + pipe.current().seq + " over " + elapsed + " ms",
            pipe.current().seq <= elapsed / VehiclePipe.MIN_GAP_MS + elapsed / VehiclePipe.KEEPALIVE_MS + 3);
    }

    @Test public void aBlockedClientNeverBlocksTheCanThreadAndGetsTheLatestState() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<VehiclePipe.State> got = Collections.synchronizedList(new ArrayList<>());
        pipe.addSink(s -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            got.add(s);
        });
        pipe.setCanUp(true);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            clock.addAndGet(50);
            pipe.onFrame(illum(i % 400 < 200 ? 100 : 0));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("20000 frames took " + ms + " ms with a blocked client", ms < 3000);
        release.countDown();
        long end = System.currentTimeMillis() + 2000;
        while (got.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertFalse(got.isEmpty());
        Thread.sleep(100);
        assertTrue("delivered states stay bounded: " + got.size(), got.size() <= 3);
        pipe.stop();
    }

    @Test public void aFailingSinkDoesNotStopTheOthers() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        pipe.addSink(s -> { throw new IllegalStateException("boom"); });
        pipe.addSink(s -> seen.countDown());
        pipe.setCanUp(true);
        feed(100, 5);
        assertTrue(seen.await(2, TimeUnit.SECONDS));
        pipe.stop();
    }
}
