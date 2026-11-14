// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.List;
import java.util.Set;

/**
 * Which media session's track goes on the cluster. Pure logic; the Android glue (MediaSessionSource) collects the sessions.
 *
 * Rule: only sessions that are playing (or buffering) count, in the order Android gives them (the first is the one that
 * would get the media keys). A session without a title is skipped, so is a package on the ignore list.
 */
public final class MediaSessionPolicy {
    private MediaSessionPolicy() {}

    // android.media.session.PlaybackState values
    public static final int STATE_PAUSED = 2, STATE_PLAYING = 3, STATE_BUFFERING = 6;

    public static final class Info {
        public final String pkg, title, artist;
        public final int state;

        public Info(String pkg, int state, String title, String artist) {
            this.pkg = pkg;
            this.state = state;
            this.title = title;
            this.artist = artist;
        }
    }

    /** @return the session to show, or null if nothing is playing */
    public static Info choose(List<Info> sessions, Set<String> ignorePackages) {
        for (Info s : sessions) {
            if (ignorePackages != null && ignorePackages.contains(s.pkg)) continue;
            if (s.state != STATE_PLAYING && s.state != STATE_BUFFERING) continue;
            if (s.title == null || s.title.trim().isEmpty()) continue;
            return s;
        }
        return null;
    }
}
