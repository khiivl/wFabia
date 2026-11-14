// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decoder for the Zlink 0x8006 / 0x8007 messages (port of navdecode.py). The code in navdecode.py is the authority. */
public final class ZlinkDecoder {
    private ZlinkDecoder() {}

    public static final int T_NAVI_STATUS = 0x20, T_STATE = 0x8006, T_PROGRESS = 0x8007, T_MEDIA_STATUS = 4, T_MEDIA_META = 5;

    public static final Map<Integer, String> MANEUVER = new HashMap<>();
    static {
        String[] names = {"UNKNOWN", "DEPART", "NAME_CHANGE", "KEEP_LEFT", "KEEP_RIGHT", "TURN_SLIGHT_LEFT",
            "TURN_SLIGHT_RIGHT", "TURN_NORMAL_LEFT", "TURN_NORMAL_RIGHT", "TURN_SHARP_LEFT", "TURN_SHARP_RIGHT",
            "U_TURN_LEFT", "U_TURN_RIGHT", "ON_RAMP_SLIGHT_LEFT", "ON_RAMP_SLIGHT_RIGHT", "ON_RAMP_NORMAL_LEFT",
            "ON_RAMP_NORMAL_RIGHT", "ON_RAMP_SHARP_LEFT", "ON_RAMP_SHARP_RIGHT", "ON_RAMP_U_TURN_LEFT",
            "ON_RAMP_U_TURN_RIGHT", "OFF_RAMP_SLIGHT_LEFT", "OFF_RAMP_SLIGHT_RIGHT", "OFF_RAMP_NORMAL_LEFT",
            "OFF_RAMP_NORMAL_RIGHT", "FORK_LEFT", "FORK_RIGHT", "MERGE_LEFT", "MERGE_RIGHT", "MERGE_SIDE_UNSPECIFIED"};
        for (int i = 0; i < names.length; i++) MANEUVER.put(i, names[i]);
        // 30 and 31 do not exist
        String[] hi = {"ROUNDABOUT_ENTER_AND_EXIT_CW", "ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE",
            "ROUNDABOUT_ENTER_AND_EXIT_CCW", "ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE", "STRAIGHT", "FERRY_BOAT",
            "FERRY_TRAIN", "DESTINATION", "DESTINATION_STRAIGHT", "DESTINATION_LEFT", "DESTINATION_RIGHT",
            "ROUNDABOUT_ENTER_CW", "ROUNDABOUT_EXIT_CW", "ROUNDABOUT_ENTER_CCW", "ROUNDABOUT_EXIT_CCW",
            "FERRY_BOAT_LEFT", "FERRY_BOAT_RIGHT", "FERRY_TRAIN_LEFT", "FERRY_TRAIN_RIGHT"};
        for (int i = 0; i < hi.length; i++) MANEUVER.put(32 + i, hi[i]);
    }

    private static final String[] UNIT = {"UNKNOWN", "METERS", "KILOMETERS", "KILOMETERS_P1", "MILES", "MILES_P1", "FEET", "YARDS"};

    // ---- value classes ----------------------------------------------------------------------------------
    public static final class LaneDir {
        public final int shape;
        public final boolean highlighted;
        public LaneDir(int shape, boolean highlighted) { this.shape = shape; this.highlighted = highlighted; }
    }

    public static final class Roundabout {
        public final Long exit, angle;   // as on the wire; null if absent
        public Roundabout(Long exit, Long angle) { this.exit = exit; this.angle = angle; }
        @Override public String toString() { return "{exit=" + exit + ",angle=" + angle + "}"; }
    }

    public static final class Step {
        public int code;
        public String maneuver;
        public Roundabout roundabout;      // null unless a roundabout code with an exit field
        public String road, cue;           // null if the field is absent
        public List<List<LaneDir>> lanes = new ArrayList<>();
    }

    public static final class Dist {
        public long metres;
        public String text, unit;
    }

    public static final class Next {
        public Dist dist;
        public long seconds;
    }

    public static final class Trip {
        public Dist dist;
        public String eta;
        public long seconds;
    }

    public static final class StateMsg {
        public Step step;
        public String destination;
    }

    public static final class ProgressMsg {
        public Next next;
        public Trip trip;
    }

    // ---- decode -----------------------------------------------------------------------------------------
    public static StateMsg decodeState(byte[] payload) {
        Map<Integer, List<Object>> top = Proto.fields(payload);
        StateMsg out = new StateMsg();
        if (top.containsKey(1)) {
            Map<Integer, List<Object>> s = Proto.fields((byte[]) top.get(1).get(0));
            Map<Integer, List<Object>> man = s.containsKey(1) ? Proto.fields((byte[]) s.get(1).get(0)) : new HashMap<>();
            int t = (int) Proto.firstLong(man, 1, 0);
            List<List<LaneDir>> lanes = new ArrayList<>();
            List<Object> laneBlobs = s.get(3);
            if (laneBlobs != null) {
                for (Object lb : laneBlobs) {
                    List<LaneDir> lane = new ArrayList<>();
                    List<Object> dirs = Proto.fields((byte[]) lb).get(1);
                    if (dirs != null) {
                        for (Object x : dirs) {
                            Map<Integer, List<Object>> xf = Proto.fields((byte[]) x);
                            lane.add(new LaneDir((int) Proto.firstLong(xf, 1, 0), Proto.firstLong(xf, 2, 0) != 0));
                        }
                    }
                    lanes.add(lane);
                }
            }
            Step st = new Step();
            st.code = t;
            st.maneuver = MANEUVER.containsKey(t) ? MANEUVER.get(t) : "?" + t;
            boolean rbCode = t == 33 || t == 35 || t == 32 || t == 34 || t == 43 || t == 44 || t == 45 || t == 46;
            if (rbCode && man.containsKey(2)) {
                Object e = man.get(2).get(0), a = man.containsKey(3) ? man.get(3).get(0) : null;
                st.roundabout = new Roundabout(e instanceof Long ? (Long) e : null, a instanceof Long ? (Long) a : null);
            }
            st.road = Proto.text(Proto.first(s, 2));
            st.cue = Proto.text(Proto.first(s, 4));
            st.lanes = lanes;
            out.step = st;
        }
        if (top.containsKey(2)) out.destination = Proto.text(top.get(2).get(0));
        return out;
    }

    private static Dist dist(byte[] b) {
        Map<Integer, List<Object>> d = Proto.fields(b);
        Dist r = new Dist();
        r.metres = Proto.firstLong(d, 1, 0);
        Object tx = Proto.first(d, 2);
        r.text = tx == null ? "" : Proto.str(tx);
        int u = (int) Proto.firstLong(d, 3, 0);
        r.unit = u >= 0 && u < UNIT.length ? UNIT[u] : "?" + u;
        return r;
    }

    public static ProgressMsg decodeProgress(byte[] payload) {
        Map<Integer, List<Object>> top = Proto.fields(payload);
        ProgressMsg out = new ProgressMsg();
        if (top.containsKey(1)) {
            Map<Integer, List<Object>> n = Proto.fields((byte[]) top.get(1).get(0));
            out.next = new Next();
            Object d = Proto.first(n, 1);
            out.next.dist = dist(d == null ? new byte[0] : (byte[]) d);
            out.next.seconds = Proto.firstLong(n, 2, 0);
        }
        if (top.containsKey(2)) {
            Map<Integer, List<Object>> t = Proto.fields((byte[]) top.get(2).get(0));
            out.trip = new Trip();
            Object d = Proto.first(t, 1);
            out.trip.dist = dist(d == null ? new byte[0] : (byte[]) d);
            Object eta = Proto.first(t, 2);
            out.trip.eta = eta == null ? "" : Proto.str(eta);
            out.trip.seconds = Proto.firstLong(t, 3, 0);
        }
        return out;
    }

    // ---- road numbers -----------------------------------------------------------------------------------
    private static final Pattern REF = Rx.compile("\\b([ABLKE])\\s?(\\d{1,4}[a-z]?)\\b", 0);

    /** 'A 9' / 'B180 Kalkstrasse' -> 'A9' (first German road number, A > B > L/K > E), else null. */
    public static String roadRef(String text) {
        if (text == null || text.isEmpty()) return null;
        int bestRank = -1;
        String best = null;
        Matcher m = REF.matcher(text);
        while (m.find()) {
            int rank = "ABLKE".indexOf(m.group(1));
            if (best == null || rank < bestRank) {
                bestRank = rank;
                best = m.group(1) + m.group(2);
            }
        }
        return best;
    }
}
