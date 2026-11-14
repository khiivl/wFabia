// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import de.wfabia.core.BridgeServer;
import de.wfabia.core.NavModel;
import de.wfabia.core.OpenHuContract;
import java.util.Locale;

/**
 * The Open Headunit source: receives the raw Android Auto navigation and media messages that Open Headunit (a build with the
 * frame forwarder) broadcasts to its whitelisted apps, and feeds them to the same model the Zlink tap feeds. See docs/CONTRACT.md.
 */
final class OpenHuSource {
    private final Context ctx;
    private final NavModel nav;
    private final BridgeServer bridge;
    private boolean running;
    private volatile String sessionState = "unknown", transport = "";
    private volatile long frames, ignored, gaps, lastSeq = -1;
    private volatile long lastNanos;

    OpenHuSource(Context ctx, NavModel nav, BridgeServer bridge) {
        this.ctx = ctx.getApplicationContext();
        this.nav = nav;
        this.bridge = bridge;
    }

    private final BroadcastReceiver frameReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getIntExtra(OpenHuContract.EXTRA_VERSION, 1) > OpenHuContract.VERSION) {
                ignored++;   // a newer contract: do not guess what it means
                return;
            }
            long seq = i.getLongExtra(OpenHuContract.EXTRA_SEQ, -1);
            if (seq >= 0) {
                if (lastSeq >= 0 && seq > lastSeq + 1) gaps += seq - lastSeq - 1;
                lastSeq = seq;
            }
            frames++;
            lastNanos = System.nanoTime();
            OpenHuContract.Frame f = OpenHuContract.translate(i.getStringExtra(OpenHuContract.EXTRA_CHANNEL),
                i.getIntExtra(OpenHuContract.EXTRA_MSG, 0), i.getByteArrayExtra(OpenHuContract.EXTRA_PAYLOAD));
            if (f == null) return;
            nav.feed(f.type, f.payload);
            bridge.publishZlink(f.type, f.payload);   // navlive.py and the recorder keep working
        }
    };

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String st = i.getStringExtra(OpenHuContract.EXTRA_SESSION_STATE);
            sessionState = st == null ? "unknown" : st;
            String tr = i.getStringExtra(OpenHuContract.EXTRA_SESSION_TRANSPORT);
            transport = tr == null ? "" : tr;
            if ("disconnected".equals(st) || "failed".equals(st)) nav.reset();   // the phone is gone: no stale steps on the cluster
        }
    };

    synchronized void start() {
        if (running) return;
        ctx.registerReceiver(frameReceiver, new IntentFilter(OpenHuContract.ACTION_FRAME));
        for (String a : OpenHuContract.SESSION_ACTIONS) ctx.registerReceiver(sessionReceiver, new IntentFilter(a));
        running = true;
    }

    synchronized void stop() {
        if (!running) return;
        try { ctx.unregisterReceiver(frameReceiver); } catch (IllegalArgumentException ignoredEx) { /* not registered */ }
        try { ctx.unregisterReceiver(sessionReceiver); } catch (IllegalArgumentException ignoredEx) { /* not registered */ }
        running = false;
        sessionState = "unknown";
        lastSeq = -1;
    }

    long frames() { return frames; }

    long lastMs() { return lastNanos == 0 ? -1 : (System.nanoTime() - lastNanos) / 1_000_000; }

    /** package and version of the installed Open Headunit, or null */
    String installed() {
        PackageManager pm = ctx.getPackageManager();
        for (String p : OpenHuContract.PACKAGES) {
            try {
                PackageInfo pi = pm.getPackageInfo(p, 0);
                return p + " " + (pi.versionName == null ? "" : pi.versionName);
            } catch (PackageManager.NameNotFoundException ignoredEx) {
                // try the next name
            }
        }
        return null;
    }

    String info() {
        String inst = installed();
        if (inst == null) return "Open Headunit is not installed";
        StringBuilder sb = new StringBuilder(inst).append("; session ").append(sessionState);
        if (!transport.isEmpty()) sb.append(" (").append(transport).append(")");
        sb.append("; frames ").append(frames);
        long ms = lastMs();
        if (frames == 0) {
            sb.append(" (none yet: is wFabia on Open Headunit's forwarding list, and is it a build with the frame forwarder?)");
        } else {
            sb.append(String.format(Locale.US, ", last %.1f s ago, gaps %d", ms / 1000.0, gaps));
        }
        if (ignored > 0) sb.append("; ignored ").append(ignored).append(" frames of a newer contract version");
        return sb.toString();
    }
}
