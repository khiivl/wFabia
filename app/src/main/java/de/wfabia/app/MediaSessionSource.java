// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import de.wfabia.core.MediaSessionPolicy;
import de.wfabia.core.NavModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the track of the media session that is playing on the head unit right now: any app's, including Open Headunit's own
 * (it publishes the phone's track as a MediaSession). Needs notification access for wFabia (MediaListenerService); with root:
 *   cmd notification allow_listener de.wfabia/de.wfabia.app.MediaListenerService
 */
final class MediaSessionSource {
    private final Context ctx;
    private final NavModel nav;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private MediaSessionManager msm;
    private ComponentName listener;
    private boolean running;
    private volatile String info = "off";
    private String lastTrack = "";

    MediaSessionSource(Context ctx, NavModel nav) {
        this.ctx = ctx.getApplicationContext();
        this.nav = nav;
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::onSessions;

    synchronized void start() {
        if (running) return;
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(ctx, MediaListenerService.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
            running = true;
            onSessions(msm.getActiveSessions(listener));
        } catch (SecurityException e) {
            info = "needs notification access for wFabia: cmd notification allow_listener de.wfabia/de.wfabia.app.MediaListenerService";
        }
    }

    synchronized void stop() {
        if (running && msm != null) msm.removeOnActiveSessionsChangedListener(sessionsChanged);
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) e.getKey().unregisterCallback(e.getValue());
        callbacks.clear();
        running = false;
        info = "off";
        lastTrack = "";
    }

    String info() { return info; }

    private synchronized void onSessions(List<MediaController> controllers) {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) e.getKey().unregisterCallback(e.getValue());
        callbacks.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata m) { evaluate(); }

                    @Override public void onPlaybackStateChanged(PlaybackState s) { evaluate(); }
                };
                c.registerCallback(cb, handler);
                callbacks.put(c, cb);
            }
        }
        evaluate();
    }

    private synchronized void evaluate() {
        List<MediaSessionPolicy.Info> list = new ArrayList<>();
        for (MediaController c : callbacks.keySet()) {
            MediaMetadata m = c.getMetadata();
            PlaybackState s = c.getPlaybackState();
            String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            list.add(new MediaSessionPolicy.Info(c.getPackageName(), s == null ? 0 : s.getState(), title, artist));
        }
        MediaSessionPolicy.Info pick = MediaSessionPolicy.choose(list, null);
        if (pick == null) {
            info = "listening, " + callbacks.size() + " session(s), none playing";
            return;
        }
        info = "playing: " + pick.pkg + " (" + pick.title + ")";
        String track = pick.title + "\u0000" + pick.artist;
        if (!track.equals(lastTrack)) {
            lastTrack = track;
            nav.feedMediaText(pick.title, pick.artist);
        }
    }
}
