// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Opt-in search for other display modes (debug.wfabia.sweep=1, docs/CLUSTER_BEHAVIOUR.md). With the display linked and the ignition on it
 * sends messages that differ from the real 37-byte content message in one respect, each followed by the real message again to see that
 * the session still draws:
 * <ul>
 * <li>lines: the same record layout with other line numbers, and with one, three or four line records instead of two;</li>
 * <li>types: the real message with every other value of its first byte (not the init and registration types);</li>
 * <li>bytes: the real message with one of its other fixed bytes changed (every single-bit flip, 00, FF and the neighbours of the real value).</li>
 * </ul>
 * The content message is 09 SS, then line records of 17 bytes (57 0F LL 00 00 XX 00 and 10 cells), then 08. The real one has the lines
 * 02 and 03 with XX = 00 and 0A, which looks like the offset of the first cell. A refusal (2B SS 03) draws nothing and costs nothing.
 * Any other reply is logged and the probe stays on the display for a moment: the cluster understands it. It stops at the first message
 * that gets no answer (except a message of another length: the cluster does not ack those, which counts as a refusal) and at the first
 * one after which the session no longer draws. Never sent: another session byte, the init and the
 * registration messages (each registration would use up one of the three the cluster allows).
 */
final class ContentSweep {
    private ContentSweep() {}

    private static final Logger LOG = Logger.getLogger("wfabia.display");

    static final int TYPE = 0x09, REGISTER_TYPE = 0x02, INIT_TYPE = 0x15;
    /** the fixed bytes of the content message besides its type and session: everything but the cells (9..18, 26..35) */
    static final int[] FIXED = {2, 3, 4, 5, 6, 7, 8, 19, 20, 21, 22, 23, 24, 25, 36};
    static final long HOLD_MS = 1200;                  // a probe that was not refused stays on the display this long, to be read off the cluster
    private static final int BOTTOM_ROW = 26, RECORD = 17;

    /** one message to try: the group it belongs to and what is special about it (both for the log), and its bytes */
    static final class Probe {
        final String group, what;
        final byte[] msg;

        Probe(String group, String what, byte[] msg) {
            this.group = group;
            this.what = what;
            this.msg = msg;
        }
    }

    /** the values tried for a byte with this real value: its single-bit flips, 00, FF and its two neighbours (never the real one) */
    static int[] values(int real) {
        TreeSet<Integer> v = new TreeSet<>();
        for (int bit = 0; bit < 8; bit++) v.add(real ^ (1 << bit));
        v.add(0x00);
        v.add(0xFF);
        v.add((real + 1) & 0xFF);
        v.add((real - 1) & 0xFF);
        v.remove(real);
        int[] out = new int[v.size()];
        int i = 0;
        for (int x : v) out[i++] = x;
        return out;
    }

    /** a line record: 57 0F, the line number, 00 00, the offset of its first cell, 00, and 10 cells */
    static byte[] record(int line, int offset, String cells) {
        byte[] r = new byte[RECORD];
        r[0] = 0x57;
        r[1] = 0x0F;
        r[2] = (byte) line;
        r[5] = (byte) offset;
        System.arraycopy(text(cells), 0, r, 7, FisLink.ROW);
        return r;
    }

    /** type, session byte, the records, the closing 08 */
    static byte[] message(int type, int session, byte[]... records) {
        int n = 3;
        for (byte[] r : records) n += r.length;
        byte[] m = new byte[n];
        m[0] = (byte) type;
        m[1] = (byte) session;
        int i = 2;
        for (byte[] r : records) {
            System.arraycopy(r, 0, m, i, r.length);
            i += r.length;
        }
        m[i] = 0x08;
        return m;
    }

    /** the real message, with SWEEP and BASE as its rows */
    static byte[] base(int session) {
        return FisLink.contentPayload(session, text("SWEEP"), text("BASE"));
    }

    /** records for these lines, the first cell offsets counting up by 10 from 0; each line says its own number */
    private static byte[] lines(int session, int... lines) {
        byte[][] recs = new byte[lines.length][];
        for (int i = 0; i < lines.length; i++) recs[i] = record(lines[i], 10 * i, "LINE " + lines[i]);
        return message(TYPE, session, recs);
    }

    private static void cells(byte[] msg, String text) {
        System.arraycopy(text(text), 0, msg, BOTTOM_ROW, FisLink.ROW);
    }

    /** every message of a full run, in the order they are sent: lines, then types, then bytes */
    static List<Probe> plan(int s) {
        byte[] base = base(s);
        List<Probe> p = new ArrayList<>();
        for (int line = 0; line < 8; line++) {
            for (int off : new int[]{0x00, 0x0A}) {
                p.add(new Probe("lines", "one record, line " + line + ", offset " + hex(off), message(TYPE, s, record(line, off, "LINE " + line))));
            }
        }
        int[][] shapes = {{0, 1}, {1, 2}, {3, 4}, {4, 5}, {2, 4}, {5, 6}, {2, 3, 4}, {1, 2, 3}, {0, 1, 2}, {3, 4, 5}, {2, 3, 4, 5}, {0, 1, 2, 3}};
        for (int[] shape : shapes) p.add(new Probe("lines", shape.length + " records, lines " + java.util.Arrays.toString(shape), lines(s, shape)));
        for (int t = 0; t < 256; t++) {
            if (t == TYPE || t == REGISTER_TYPE || t == INIT_TYPE) continue;
            byte[] m = base.clone();
            m[0] = (byte) t;
            cells(m, "T=" + hex(t));
            p.add(new Probe("types", "type " + hex(t), m));
        }
        for (int pos : FIXED) {
            int real = base[pos] & 0xFF;
            for (int val : values(real)) {
                byte[] m = base.clone();
                m[pos] = (byte) val;
                cells(m, String.format("%02X=%02X", pos, val));
                p.add(new Probe("byte " + pos + " (" + hex(real) + ")", "byte " + pos + " " + hex(real) + " -> " + hex(val), m));
            }
        }
        return p;
    }

    /**
     * Runs the sweep on the calling thread (about 3.5 minutes when everything is refused).
     * @param go  asked before every message: false stops the sweep
     * @param say the display state line (start and end); the per-probe lines go to the log
     * @return null when it ran to the end, else why it stopped
     */
    static String run(FisLink l, BooleanSupplier go, Consumer<String> say) {
        Integer session = l.session();
        if (session == null) return "no session";
        int s = session;
        byte[] base = base(s);
        List<Probe> plan = plan(s);
        int total = plan.size(), sent = 0, notRefused = 0, refused = 0, inGroup = 0;
        String group = null;
        StringBuilder found = new StringBuilder();
        say.accept("sweep: " + total + " messages, session 0x" + Integer.toHexString(s));
        for (Probe p : plan) {
            if (!p.group.equals(group)) {
                if (group != null) LOG.info("sweep: " + group + ": " + refused + " of " + inGroup + " refused");
                group = p.group;
                refused = 0;
                inGroup = 0;
            }
            if (!go.getAsBoolean()) return "stopped after " + sent + " of " + total;
            FisLink.Show r = l.streamSendResult(p.msg, 1);   // one try: a message that got no answer is not repeated
            byte[] reply = l.lastReplyBytes();
            sent++;
            inGroup++;
            boolean silent = r == FisLink.Show.FAILED || reply == null;
            if (silent && p.msg.length == base.length) return "no answer to " + p.what + " (" + sent + " of " + total + ")";
            if (silent) {
                // a message of another length: the cluster does not even ack its packets (measured on the car with 20 bytes), which is
                // its way of refusing it; the real message below shows whether the session is still fine
                refused++;
            } else if (usualRefusal(reply, s)) {
                refused++;
            } else {
                notRefused++;
                String got = Hex.upper(reply, reply.length);
                LOG.info("sweep: " + p.what + " (" + p.msg.length + " bytes): reply " + got);
                if (found.length() < 300) found.append(found.length() > 0 ? "; " : "").append(p.what).append(" = ").append(got);
                sleep(HOLD_MS);
            }
            FisLink.Show back = l.streamSendResult(base);                       // the real message again: does the session still draw?
            if (back != FisLink.Show.OK) return "after " + p.what + " the session no longer draws (reply " + l.lastReply() + ")";
        }
        if (group != null) LOG.info("sweep: " + group + ": " + refused + " of " + inGroup + " refused");
        say.accept("sweep done: " + sent + " messages, " + notRefused + " not refused" + (found.length() > 0 ? ": " + found : ""));
        return null;
    }

    /** the answer to a message the cluster does not take: 2B, our session byte, 03 */
    private static boolean usualRefusal(byte[] reply, int session) {
        return reply.length == 3 && (reply[0] & 0xFF) == 0x2B && (reply[1] & 0xFF) == session && (reply[2] & 0xFF) == 0x03;
    }

    private static byte[] text(String s) { return MaxidotFont.padLeft(MaxidotFont.encode(s), FisLink.ROW); }

    private static String hex(int v) { return String.format("%02X", v & 0xFF); }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
