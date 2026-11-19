// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.os.SystemClock;
import de.wfabia.core.DisplayController;
import java.util.Locale;

/**
 * Speed for the cluster, received from wRight over the vehicle pipe (docs/VEHICLE_PIPE.md, T_LOCATION). wRight is the only app that
 * asks the head unit's location service for fixes, so wFabia no longer holds any location registration or permission.
 * null = unknown: wRight is not connected, has no fix, or the last speed is older than 10 s.
 */
public final class PipedSpeedSource implements DisplayController.SpeedSource {
    private static final long MAX_AGE_MS = 10_000;

    private volatile float speedMs;
    private volatile boolean valid;
    private volatile long speedAt;                 // elapsedRealtime when the last speed arrived
    private volatile long anyAt;
    private volatile int fixes;
    private volatile String from = "-";
    private volatile String sender = "";
    private volatile float accuracy = -1;

    private volatile Runnable onSpeed;

    /** called on every new speed (binder thread): the display wakes up instead of waiting for its next tick */
    void setOnSpeed(Runnable r) { onSpeed = r; }

    /** a location message from an allowed caller (called on a binder thread) */
    void onLocation(String senderPkg, boolean hasSpeed, float speedMs, float accuracy, String provider) {
        long now = SystemClock.elapsedRealtime();
        sender = senderPkg;
        anyAt = now;
        fixes++;
        this.accuracy = accuracy;
        if (hasSpeed) {
            this.speedMs = speedMs;
            valid = true;
            speedAt = now;
            from = provider;
            Runnable r = onSpeed;
            if (r != null) r.run();
        }
    }

    /** wRight left the pipe: the speed is no longer known */
    void clear() {
        valid = false;
        sender = "";
    }

    // the lifecycle calls FabiaService makes are no-ops now: nothing here registers with the location service
    void start() {}

    void stop() { valid = false; }

    boolean isRunning() { return true; }

    @Override public Double kmh() {
        if (!valid || SystemClock.elapsedRealtime() - speedAt > MAX_AGE_MS) return null;
        return speedMs * 3.6;
    }

    /** one line for the Display card: why the speed row shows 0, or where the speed comes from */
    String info() {
        if (sender.isEmpty() && fixes == 0) return "speed: waiting for wRight (allow it in the vehicle pipe card, it supplies the GPS)";
        long ageAny = (SystemClock.elapsedRealtime() - anyAt) / 1000;
        Double v = kmh();
        if (v == null) return "speed: none (wRight sent " + fixes + " fixes, last " + ageAny + " s ago, no recent speed)";
        return String.format(Locale.US, "speed: %.0f km/h from wRight (%s), last fix %d s ago, %d fixes", v, from, ageAny, fixes);
    }
}
