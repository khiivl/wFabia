// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

/**
 * Turns the raw instrument-illumination samples of the comfort CAN (0x470 byte 2, fallback 0x635 byte 0) into a clean
 * state: lights on/off and the dimmer wheel level. No window, so no added latency beyond one frame (50 ms).
 *
 * Measured in the car: 0 = light switch off or ignition off, 34..100 = light switch on, following the dimmer wheel
 * (34 = darkest). A ripple rides on the signal: an abrupt drop of up to 12 points that recovers upward within about
 * 450 ms, and it never reaches 0. A real move of the wheel keeps going down or stays down. So:
 *  - a rise is taken at once;
 *  - a drop below the held level is taken one frame later, and only if the signal has not started rising again
 *    (a ripple dip rises again at once and is ignored, together with its recovery ramp);
 *  - lights ON / OFF are taken on the first frame: the real data has no glitch frames (16.5k samples), and the cluster
 *    already lags the light switch by about 270 ms, so nothing more is added on top.
 *
 * Pure Java. Receive only: feed it from the RX path and nothing else. It never transmits and never blocks.
 */
public final class DimmerFilter {
    public static final int WHEEL_MIN = 34;
    public static final int WHEEL_MAX = 100;
    /** no sample for this long: the signal is gone (CAN down, ignition off) */
    public static final long STALE_MS = 5000;

    private boolean first = true;
    private boolean lightsOn;
    private int level;                       // 0 when the lights are off
    private int prev;                        // the previous raw sample
    private int pending;                     // a drop waiting for its confirming next frame, 0 = none
    private int zeroRun, nonZeroRun;
    private long lastSampleMs = Long.MIN_VALUE;

    /** @return true when lightsOn or level changed and a new state should be published */
    public synchronized boolean feed(long tMs, int raw) {
        if (raw < 0 || raw > 100) return false;
        lastSampleMs = tMs;
        boolean wasOn = lightsOn;
        int wasLevel = level;
        if (raw == 0) {
            zeroRun++;
            nonZeroRun = 0;
            pending = 0;
            if (first || lightsOn) {
                lightsOn = false;
                level = 0;
            }
        } else {
            nonZeroRun++;
            zeroRun = 0;
            if (!lightsOn) {
                {
                    lightsOn = true;
                    level = raw;
                    pending = 0;
                }
            } else {
                if (pending != 0) {
                    if (raw <= pending) level = pending;      // not rising after the drop: it was the wheel
                    pending = 0;                              // (raw > pending: a ripple dip, the held level stays)
                }
                if (raw >= level) level = raw;
                else if (raw < prev) pending = raw;           // a new drop below the held level: confirm next frame
                // raw < level and raw >= prev: the recovery ramp of a dip, ignored
            }
        }
        first = false;
        prev = raw;
        return wasOn != lightsOn || wasLevel != level;
    }

    /** call from a timer: true if the state just went stale and was reset to "off" */
    public synchronized boolean checkStale(long nowMs) {
        if (lastSampleMs == Long.MIN_VALUE || nowMs - lastSampleMs <= STALE_MS) return false;
        reset();
        return true;
    }

    public synchronized void reset() {
        first = true;
        lightsOn = false;
        level = 0;
        prev = 0;
        pending = 0;
        zeroRun = nonZeroRun = 0;
        lastSampleMs = Long.MIN_VALUE;
    }

    public synchronized boolean lightsOn() { return lightsOn; }

    /** 0 when the lights are off, else 34..100 (levels below 34 are clamped up by the receiver's mapping) */
    public synchronized int level() { return level; }
}
