// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * NavTracker + 'fresh' flag + media overlay (port of maxidot_sim.Feed). The distance of the previous step is stale
 * until the first progress message after a step change, so it is not shown in between (otherwise every new step
 * flashes '0M'). Not thread safe: callers synchronise.
 */
public final class Feed {
    public static final double MEDIA_MIN_M = 2000;

    private final NavTracker tr = new NavTracker();
    private String key;
    private boolean fresh = true, live = false;
    private double stepStart;
    private Media media;
    private String mediaId;

    private static final class Media {
        String song, artist;
        double start, dur;
        boolean dismissed;
    }

    /** What the renderer needs: the tracker state plus _fresh / _media (like Feed.view in Python). */
    public static final class View {
        public boolean active, fresh;
        public ZlinkDecoder.Step step;
        public ZlinkDecoder.Next next;
        public MediaView media;            // non-null while a track is being shown
        public double stepElapsed;         // seconds since the current step key changed (marquee time)
    }

    public static final class MediaView {
        public final String song, artist;
        public final double elapsed;
        public MediaView(String song, String artist, double elapsed) { this.song = song; this.artist = artist; this.elapsed = elapsed; }
    }

    /** @param now seconds on any consistent clock (null counts as 0) */
    public boolean feed(int typ, byte[] pl, Double now) {
        double t = now == null ? 0.0 : now;
        if (typ == ZlinkDecoder.T_MEDIA_META) {
            mediaMeta(pl, t);
            return true;
        }
        if (typ == ZlinkDecoder.T_MEDIA_STATUS) return false;
        if (typ == ZlinkDecoder.T_NAVI_STATUS) {   // 1 active, 2 inactive, 3 = seen during reroutes (ignore, do not blank the display)
            int status = pl.length > 1 ? pl[1] & 0xFF : 0;
            if (status == 3) return false;
            live = status == 1;
        } else if (typ == ZlinkDecoder.T_STATE) {
            live = true;                           // a step only arrives while navigating
        }
        boolean ok = tr.feed(typ, pl);
        if (ok && typ == ZlinkDecoder.T_STATE && tr.step != null) {
            ZlinkDecoder.Step st = tr.step;
            String k = st.code + "\u0000" + (st.road == null ? "\u0001" : st.road) + "\u0000"
                + (st.roundabout == null ? "null" : st.roundabout.toString());
            if (!Objects.equals(k, key)) {
                key = k;
                fresh = false;
                stepStart = t;
                if (media != null) media.dismissed = true;   // the navigation always wins when another step arrives
            }
        } else if (ok && typ == ZlinkDecoder.T_PROGRESS) {
            fresh = true;
        }
        return ok;
    }

    private void mediaMeta(byte[] pl, double now) {
        String song = "", artist = "";
        try {
            Map<Integer, List<Object>> f = Proto.fields(pl);
            Object s = Proto.first(f, 1), a = Proto.first(f, 2);
            if (s instanceof byte[]) song = new String((byte[]) s, StandardCharsets.UTF_8);
            if (a instanceof byte[]) artist = new String((byte[]) a, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return;
        }
        String id = song + "\u0000" + artist;
        if (id.equals(mediaId) || (song.isEmpty() && artist.isEmpty())) return;   // the phone repeats the same metadata
        mediaId = id;
        Media m = new Media();
        m.song = song;
        m.artist = artist;
        m.start = now;
        m.dur = Math.max(MaxidotRenderer.mediaScrollS(artist), MaxidotRenderer.mediaScrollS(song));
        media = m;
    }

    /**
     * @param navEnabled   false: navigation is switched off, so it cannot dismiss the media screen
     * @param mediaEnabled false: no media screen at all
     */
    public View view(double now, boolean navEnabled, boolean mediaEnabled) {
        View v = new View();
        v.active = live;
        v.fresh = fresh;
        v.step = tr.step;
        v.next = tr.next;
        v.stepElapsed = Math.max(0.0, now - stepStart);
        Media m = media;
        if (m != null && !m.dismissed && mediaEnabled) {
            Long d = v.next != null && v.next.dist != null ? Long.valueOf(v.next.dist.metres) : null;
            if (navEnabled && v.active && v.step != null && d != null && d <= MEDIA_MIN_M) {
                m.dismissed = true;                // too close to the next turn: navigation keeps the display
            } else if (now - m.start >= m.dur) {
                m.dismissed = true;                // scrolled through once
            } else {
                v.media = new MediaView(m.song, m.artist, now - m.start);
            }
        }
        return v;
    }

    public NavTracker tracker() { return tr; }
}
