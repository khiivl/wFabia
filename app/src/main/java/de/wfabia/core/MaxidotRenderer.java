// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** State -> two 10-cell rows (port of maxidot_sim.screen and its helpers; the Python code is the authority). */
public final class MaxidotRenderer {
    private MaxidotRenderer() {}

    public static final int ROW = 10;
    public static final int EXIT_NAME_M = 2000;
    /** GPS speeds up to this many km/h are noise at a standstill and shown as 0 */
    public static final int STANDSTILL_KMH = 3;
    public static final double MEDIA_RATE = 2.5, MEDIA_PAUSE = 1.5, MEDIA_HOLD = 1.5, MEDIA_MIN_S = 4.0;

    private static final String ARROW_UP = "↑", ARROW_DOWN = "↓", ARROW_RIGHT = "→", ARROW_LEFT = "←";
    private static final String DEPART = "▶", CHECK = "✓";
    private static final String FONT_EXTRA = "ÄÖÜ’_°✓▶↑↓→←▲▼";

    /** Display sub-options (the module switches). All true reproduces the Python formatter exactly. */
    public static final class Options {
        public boolean nav = true, lanes = true, exit = true, media = true, speed = true;
    }

    // ---- font -------------------------------------------------------------------------------------------
    private static boolean fontOk(char ch) {
        return FONT_EXTRA.indexOf(ch) >= 0 || (ch >= 0x20 && ch <= 0x5E);
    }

    /** str -> str that the display can show (upper case, Cyrillic transliterated, sharp s -> SS, accents stripped, missing -> '?') */
    public static String toFont(String text) {
        text = Cyrillic.toLatin(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == 'ß') {
                out.append("SS");
            } else if (ch == 'ä' || ch == 'ö' || ch == 'ü') {
                out.append(Character.toUpperCase(ch));
            } else if (fontOk(ch)) {
                out.append(ch);
            } else {
                String base = MaxidotFont.stripAccents(String.valueOf(ch)).toUpperCase(Locale.ROOT);
                StringBuilder b = new StringBuilder();
                for (int k = 0; k < base.length(); k++) b.append(fontOk(base.charAt(k)) ? base.charAt(k) : '?');
                out.append(b.length() == 0 ? "?" : b);
            }
        }
        return out.toString();
    }

    // ---- string helpers with Python semantics -----------------------------------------------------------
    /** Python str.center */
    static String center(String s, int width) {
        int marg = width - s.length();
        if (marg <= 0) return s;
        int left = marg / 2 + (marg & width & 1);
        return spaces(left) + s + spaces(marg - left);
    }

    static String spaces(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(' ');
        return b.toString();
    }

    static String cutPad(String s, int w) {
        if (s.length() > w) s = s.substring(0, w);
        return s + spaces(w - s.length());
    }

    private static String strip(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    // ---- maneuver glyphs --------------------------------------------------------------------------------
    static String maneuverGlyph(ZlinkDecoder.Step step) {
        int code = step.code;
        String n = ZlinkDecoder.MANEUVER.containsKey(code) ? ZlinkDecoder.MANEUVER.get(code) : "";
        String side = n.endsWith("LEFT") ? "L" : n.endsWith("RIGHT") ? "R" : "";
        ZlinkDecoder.Roundabout rb = step.roundabout;
        if (n.contains("ROUNDABOUT")) {
            StringBuilder g = new StringBuilder("O");
            if (rb != null && rb.exit != null && rb.exit != 0) g.append(rb.exit);
            if (rb != null && rb.angle != null && rb.angle != 0 && (code == 33 || code == 35)) {
                long a = rb.angle;
                g.append(a <= 135 ? ARROW_RIGHT : a <= 225 ? ARROW_UP : a <= 315 ? ARROW_LEFT : ARROW_DOWN);
            }
            return g.toString();
        }
        if (n.startsWith("DESTINATION")) return CHECK + (side.equals("L") ? ARROW_LEFT : side.equals("R") ? ARROW_RIGHT : "");
        if (n.equals("DEPART")) return DEPART;
        if (n.equals("NAME_CHANGE") || n.equals("STRAIGHT") || n.equals("MERGE_SIDE_UNSPECIFIED")) return ARROW_UP;
        if (n.startsWith("FERRY")) return "F" + (side.equals("L") ? ARROW_LEFT : side.equals("R") ? ARROW_RIGHT : "");
        if (side.isEmpty()) return "?";
        boolean left = side.equals("L");
        if (n.contains("U_TURN")) return left ? "U" + ARROW_LEFT : ARROW_RIGHT + "U";
        if (n.contains("SHARP")) return left ? ARROW_DOWN + ARROW_LEFT : ARROW_RIGHT + ARROW_DOWN;
        if (n.contains("SLIGHT") || n.contains("KEEP") || n.contains("FORK") || n.contains("MERGE")) return left ? ARROW_LEFT + "\\" : "/" + ARROW_RIGHT;
        if (n.contains("RAMP")) return left ? ARROW_LEFT + "\\" : "/" + ARROW_RIGHT;   // normal ramps leave a road at a shallow angle
        return left ? ARROW_LEFT : ARROW_RIGHT;
    }

    /** <100 m in 10 m steps, <1000 m in 50 m steps, <9950 m one-decimal km, else whole km. null -> "" */
    static String fmtDist(Long m) {
        if (m == null) return "";
        if (m < 100) return (long) Math.rint(m / 10.0) * 10 + "M";
        if (m < 1000) return (long) Math.rint(m / 50.0) * 50 + "M";
        if (m < 9950) return new BigDecimal(m / 1000.0).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "K";
        return (long) Math.rint(m / 1000.0) + "K";
    }

    // ---- lanes ------------------------------------------------------------------------------------------
    private static String laneCh(int shape) {
        switch (shape) {
            case 1: return ARROW_UP;
            case 2: return "\\";
            case 3: return "/";
            case 4: case 6: return ARROW_LEFT;
            case 5: case 7: return ARROW_RIGHT;
            case 8: case 9: return "U";
            default: return "?";
        }
    }

    /** arrows inside one lane are ordered by direction, left to right: u-turn, sharp, left, slight left, straight, ... */
    private static int laneOrder(int shape) {
        switch (shape) {
            case 8: return 0;
            case 6: return 1;
            case 4: return 2;
            case 2: return 3;
            case 1: return 4;
            case 3: return 5;
            case 5: return 6;
            case 7: return 7;
            case 9: return 8;
            default: return 9;     // 0 and anything unknown
        }
    }

    private static boolean anyHl(List<ZlinkDecoder.LaneDir> l) {
        for (ZlinkDecoder.LaneDir d : l) if (d.highlighted) return true;
        return false;
    }

    private static String arrows(List<ZlinkDecoder.LaneDir> l, boolean hl, boolean firstOnly) {
        List<ZlinkDecoder.LaneDir> ds = new ArrayList<>(l);
        ds.sort((a, b) -> Integer.compare(laneOrder(a.shape), laneOrder(b.shape)));   // stable, like sorted()
        if (!(hl || !firstOnly)) ds = ds.subList(0, Math.min(1, ds.size()));
        StringBuilder sb = new StringBuilder();
        for (ZlinkDecoder.LaneDir d : ds) sb.append(laneCh(d.shape));
        return sb.toString();
    }

    private static String buildLanes(List<List<ZlinkDecoder.LaneDir>> lns, boolean firstOnly) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lns.size()) {
            if (anyHl(lns.get(i))) {
                int j = i;
                StringBuilder s = new StringBuilder();
                while (j < lns.size() && anyHl(lns.get(j))) {
                    s.append(arrows(lns.get(j), true, firstOnly));
                    j++;
                }
                out.append('(').append(s).append(')');
                i = j;
            } else {
                out.append(arrows(lns.get(i), false, firstOnly));
                i++;
            }
        }
        return out.toString();
    }

    /** [[{shape, highlighted}]] -> string of at most ROW cells. Recommended lanes in parentheses (adjacent ones share). */
    static String lanesText(List<List<ZlinkDecoder.LaneDir>> lanes) {
        List<List<ZlinkDecoder.LaneDir>> lns = new ArrayList<>();
        for (List<ZlinkDecoder.LaneDir> l : lanes) if (!l.isEmpty()) lns.add(l);
        String t = buildLanes(lns, false);
        if (t.length() <= ROW) return t;
        t = buildLanes(lns, true);
        if (t.length() <= ROW) return t;
        // still too wide: keep the recommended lanes, then widen to neighbours while it still fits
        List<Integer> hlIdx = new ArrayList<>();
        for (int i = 0; i < lns.size(); i++) if (anyHl(lns.get(i))) hlIdx.add(i);
        if (hlIdx.isEmpty()) hlIdx.add(0);
        int lo = hlIdx.get(0), hi = hlIdx.get(hlIdx.size() - 1);
        String best = buildLanes(lns.subList(lo, hi + 1), true);
        while (true) {
            boolean grown = false;
            int[][] tries = {{lo - 1, hi}, {lo, hi + 1}};
            for (int[] r : tries) {
                int nlo = r[0], nhi = r[1];
                if (nlo >= 0 && nhi < lns.size()) {
                    String c = buildLanes(lns.subList(nlo, nhi + 1), true);
                    if (c.length() <= ROW) {
                        lo = nlo;
                        hi = nhi;
                        best = c;
                        grown = true;
                        break;
                    }
                }
            }
            if (!grown) break;
        }
        return best.length() > ROW ? best.substring(0, ROW) : best;
    }

    // ---- exit names -------------------------------------------------------------------------------------
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern WS = Rx.compile("\\s+", 0);
    private static final Pattern REF_ANY = Rx.compile("\\b[ABLKE]\\s?\\d{1,4}[a-z]?\\b", 0);
    private static final Pattern EXIT_WORDS = Pattern.compile("ausfahrt|kreuz|dreieck|anschlussstelle|abzweig", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LEAD_AUSFAHRT = Rx.compile("^ausfahrt\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** '[A 9] [E 49] > Berlin; Leipzig' -> 'Berlin; Leipzig' ; '[L 163]' -> '' (road numbers go to row 1) */
    static String cleanName(String text) {
        String t = BRACKETS.matcher(text == null ? "" : text).replaceAll(" ");
        return strip(WS.matcher(t).replaceAll(" "), " >");
    }

    /** Name of a motorway exit / junction for row 2, else "". Street names are deliberately not shown. */
    static String exitText(ZlinkDecoder.Step st) {
        String n = st.maneuver;
        String[] srcs = {st.road, st.cue};
        for (String src : srcs) {
            if (src == null || src.isEmpty()) continue;
            String ref = ZlinkDecoder.roadRef(src);
            boolean isExit = EXIT_WORDS.matcher(src).find()
                || ((n.startsWith("OFF_RAMP") || n.startsWith("FORK")) && ref != null && ref.startsWith("A"));
            if (!isExit) continue;
            String t = cleanName(src);
            t = LEAD_AUSFAHRT.matcher(t).replaceFirst("");
            t = strip(WS.matcher(REF_ANY.matcher(t).replaceAll(" ")).replaceAll(" "), " ;>/");
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    static String marquee(String text, double t) {
        if (text.length() <= ROW) return center(text, ROW);
        String pad = text + "   ";
        int n = pad.length();
        double k = Math.max(0.0, t - MEDIA_PAUSE);
        int off = k > 0 ? (int) (k * MEDIA_RATE) % n : 0;
        String twice = pad + pad;
        return twice.substring(off, off + ROW);
    }

    // ---- screen -----------------------------------------------------------------------------------------
    /** shown in place of the speed while there is no GPS fix */
    static final String NO_GPS = "KEIN GPS";

    static String speedText(Double speed) {
        String s = String.valueOf((long) Math.rint(speed));
        while (s.length() < 3) s = " " + s;
        return center(s + " KM/H", ROW);
    }

    /** shown on the free top row at a standstill when no navigation is running: BATT 12.4V */
    static String voltText(double volts) {
        return center(String.format(java.util.Locale.ROOT, "BATT %.1fV", volts), ROW);
    }

    /**
     * Feed.View + time -> {row1, row2}, each exactly ROW cells of font characters.
     * @param t   seconds for the exit-name marquee (0 = not scrolled yet)
     * @param speed km/h or null (unknown -> KEIN GPS)
     */
    public static String[] screen(Feed.View state, double t, Double speed, Options o) {
        return screen(state, t, speed, null, o);
    }

    /** @param volts the battery voltage or null: shown on the free top row at a standstill (no navigation, no media) */
    public static String[] screen(Feed.View state, double t, Double speed, Double volts, Options o) {
        if (o == null) o = new Options();
        // where the speed goes: GPS jitter up to STANDSTILL_KMH counts as standing still and shows 0; no speed (no GPS fix, or
        // wRight not delivering) shows KEIN GPS instead of a false 0; blank only if the speed option is switched off
        String blank0 = spaces(ROW);
        String speedRow = !o.speed ? blank0
            : speed == null ? center(NO_GPS, ROW)
            : speedText(Math.rint(speed) <= STANDSTILL_KMH ? 0.0 : speed);
        // standing still with nothing on the top row (no navigation): the battery voltage goes there
        String topFree = o.speed && speed != null && Math.rint(speed) <= STANDSTILL_KMH && volts != null ? voltText(volts) : blank0;
        if (state.media != null) return mediaRows(state.media);
        ZlinkDecoder.Step st = state.step;
        String blank = spaces(ROW);
        if (!o.nav || !state.active || st == null) {
            return new String[]{topFree, speedRow};
        }
        ZlinkDecoder.Dist nxt = state.next != null ? state.next.dist : null;
        String glyph = maneuverGlyph(st);
        String dist = nxt != null ? fmtDist(nxt.metres) : "";
        if (st.maneuver.equals("DEPART") || !state.fresh) dist = "";
        String left = (glyph + " " + dist).strip();
        String r = ZlinkDecoder.roadRef(st.road == null ? "" : st.road);
        String ref = r == null ? "" : r;
        String row1 = left;
        if (!ref.isEmpty()) {   // "glyph dist" and the road number as one block, two spaces apart if that fits, else one
            for (int gap = 2; gap >= 1; gap--) {
                if (left.length() + gap + ref.length() <= ROW) {
                    row1 = left + spaces(gap) + ref;
                    break;
                }
            }
        }
        row1 = center(row1, ROW);
        row1 = cutPad(toFont(row1), ROW);
        String row2 = "";
        if (o.lanes && !st.lanes.isEmpty()) {
            row2 = center(lanesText(st.lanes), ROW);
        } else {
            Long m = nxt != null ? Long.valueOf(nxt.metres) : null;
            if (o.exit && state.fresh && m != null && m <= EXIT_NAME_M) {
                String name = exitText(st);
                if (!name.isEmpty()) row2 = marquee(toFont(name), t);
            }
        }
        if (row2.strip().isEmpty()) row2 = speedRow;   // nothing else to say: GPS speed, or GEPARKT
        return new String[]{row1, cutPad(toFont(row2), ROW)};
    }

    // ---- media (artist / track) -------------------------------------------------------------------------
    /** seconds until `text` has scrolled all the way through (and rested at its end) */
    public static double mediaScrollS(String text) {
        int n = toFont(text).length();
        return n <= ROW ? MEDIA_MIN_S : Math.max(MEDIA_MIN_S, MEDIA_PAUSE + (n - ROW) / MEDIA_RATE + MEDIA_HOLD);
    }

    /** one row of a track display: centered when it fits, else scrolled once from the start to the end */
    static String mediaCells(String text, double elapsed) {
        String t = toFont(text);
        if (t.length() <= ROW) return center(t, ROW);
        int off = Math.min(Math.max(0, (int) ((elapsed - MEDIA_PAUSE) * MEDIA_RATE)), t.length() - ROW);
        return t.substring(off, off + ROW);
    }

    static String[] mediaRows(Feed.MediaView m) {
        String artist = m.artist == null ? "" : m.artist, song = m.song == null ? "" : m.song;
        if (artist.isEmpty()) {
            artist = song;
            song = "";
        }
        return new String[]{mediaCells(artist, m.elapsed), mediaCells(song, m.elapsed)};
    }

    /** NFKD helper kept for tests */
    static String nfkd(String s) { return Normalizer.normalize(s, Normalizer.Form.NFKD); }
}
