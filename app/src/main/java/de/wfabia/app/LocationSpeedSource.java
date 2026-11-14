// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.content.ContextCompat;
import de.wfabia.core.DisplayController;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speed from the head unit's location service. It listens to every provider Android offers (gps, passive, vendor or mock
 * providers) because head units differ in which one carries the GPS, and uses the freshest fix that has a speed.
 * null = unknown: no permission, no provider, no fix, or the last fix with a speed is older than 10 s. A provider that
 * sends positions without a speed gets one from the distance between its own consecutive fixes (never the network provider).
 */
final class LocationSpeedSource implements DisplayController.SpeedSource {
    private static final String TAG = "wfabia.speed";
    private static final long MAX_AGE_MS = 10_000;

    private final Context ctx;
    private volatile float speedMs;
    private volatile boolean valid;
    private volatile long fixAt;                 // elapsedRealtime of the last fix that gave a speed
    private volatile String speedFrom = "-";
    private volatile long anyFixAt;
    private volatile int fixes, fixesWithSpeed;
    private volatile String lastProvider = "-";
    private volatile float accuracy = -1;
    private final java.util.Map<String, Location> previous = new java.util.HashMap<>();
    private final List<String> providers = new ArrayList<>();
    private LocationManager lm;
    private boolean running;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            String prov = l.getProvider() == null ? "?" : l.getProvider();
            long now = SystemClock.elapsedRealtime();
            fixes++;
            lastProvider = prov;
            anyFixAt = now;
            accuracy = l.hasAccuracy() ? l.getAccuracy() : -1;
            if (l.hasSpeed()) {
                fixesWithSpeed++;
                speedMs = l.getSpeed();
                valid = true;
                fixAt = now;
                speedFrom = prov;
            } else if (!"network".equals(prov)) {
                Location prev;
                synchronized (previous) { prev = previous.get(prov); }
                if (prev != null) {
                    double dt = (l.getElapsedRealtimeNanos() - prev.getElapsedRealtimeNanos()) / 1e9;
                    if (dt > 0.3 && dt < 10) {
                        speedMs = (float) (prev.distanceTo(l) / dt);
                        valid = true;
                        fixAt = now;
                        speedFrom = prov + " (from positions)";
                    }
                }
            }
            synchronized (previous) { previous.put(prov, l); }
        }

        @Override public void onProviderEnabled(String p) {}

        @Override public void onProviderDisabled(String p) {}
    };

    LocationSpeedSource(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    synchronized void start() {
        if (running) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            providers.clear();
            for (String name : lm.getAllProviders()) {
                try {
                    lm.requestLocationUpdates(name, 1000L, 0f, listener, Looper.getMainLooper());
                    providers.add(name);
                } catch (SecurityException | IllegalArgumentException e) {
                    Log.w(TAG, "provider " + name + " not available: " + e);
                }
            }
            running = !providers.isEmpty();
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "location updates not available: " + e);
        }
    }

    synchronized void stop() {
        if (running && lm != null) lm.removeUpdates(listener);
        running = false;
        valid = false;
    }

    synchronized boolean isRunning() { return running; }

    @Override public Double kmh() {
        if (!valid || SystemClock.elapsedRealtime() - fixAt > MAX_AGE_MS) return null;
        return speedMs * 3.6;
    }

    /** one line for the Display card: why the speed row shows 0, or where the speed comes from */
    String info() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "speed: no location permission";
        }
        if (!running) return "speed: no location provider could be started";
        String provs = "providers " + providers;
        if (fixes == 0) return "speed: no fix from any provider yet (" + provs + ")";
        Double v = kmh();
        String ageAny = String.format(Locale.US, "%.1f s", (SystemClock.elapsedRealtime() - anyFixAt) / 1000.0);
        if (v == null) {
            return "speed: none (" + provs + "; " + fixes + " fixes, " + fixesWithSpeed + " with a speed, last fix " + ageAny + " ago from " + lastProvider + ")";
        }
        return String.format(Locale.US, "speed: %.0f km/h from %s, last fix %s ago, %d fixes (%s)", v, speedFrom, ageAny, fixes, provs);
    }
}
