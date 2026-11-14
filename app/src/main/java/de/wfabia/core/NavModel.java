// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

/** Thread-safe wrapper around Feed: the Zlink thread feeds it, the display thread reads it. */
public final class NavModel {
    private Feed feed = new Feed();

    /** seconds on a monotonic clock, shared by feed and view */
    public static double now() { return System.nanoTime() / 1e9; }

    public synchronized boolean feed(int type, byte[] payload) { return feed.feed(type, payload, now()); }

    public synchronized Feed.View view(boolean navEnabled, boolean mediaEnabled) { return feed.view(now(), navEnabled, mediaEnabled); }

    /** a track as text (from a MediaSession): handled exactly like the Android Auto media metadata message */
    public synchronized boolean feedMediaText(String song, String artist) {
        byte[] s = (song == null ? "" : song).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] a = (artist == null ? "" : artist).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeField(out, 1, s);
        writeField(out, 2, a);
        return feed.feed(ZlinkDecoder.T_MEDIA_META, out.toByteArray(), now());
    }

    private static void writeField(java.io.ByteArrayOutputStream out, int field, byte[] v) {
        out.write(field << 3 | 2);
        int n = v.length;
        while (n >= 0x80) {
            out.write((n & 0x7F) | 0x80);
            n >>>= 7;
        }
        out.write(n);
        out.write(v, 0, v.length);
    }

    /** forget everything (Zlink tap switched off or reconnected) */
    public synchronized void reset() { feed = new Feed(); }

    /** short text for the status chips */
    public synchronized String summary() {
        NavTracker t = feed.tracker();
        if (!Boolean.TRUE.equals(t.active) && t.step == null) return "no navigation";
        String road = t.step == null || t.step.road == null ? "" : t.step.road;
        return (t.step == null ? "?" : t.step.maneuver) + (road.isEmpty() ? "" : " " + road);
    }
}
