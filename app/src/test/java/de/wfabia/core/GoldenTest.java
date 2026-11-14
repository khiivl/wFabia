// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Replays the real captures through Feed + MaxidotRenderer (87 km/h, no clock) and diffs against maxidot_sim.py's output. */
public class GoldenTest {
    private static final class Rec {
        final double t;
        final int type;
        final byte[] payload;
        Rec(double t, int type, byte[] payload) { this.t = t; this.type = type; this.payload = payload; }
    }

    static File dir() { return new File(System.getProperty("golden.dir")); }

    private static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** maxidot_sim.media_payload: a type-5 payload (song, artist) */
    private static byte[] mediaPayload(String song, String artist) {
        byte[] a = field(1, song == null ? "" : song), b = field(2, artist == null ? "" : artist);
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] field(int n, String v) {
        byte[] b = v.getBytes(StandardCharsets.UTF_8);
        byte[] hdr = b.length < 128 ? new byte[]{(byte) (n << 3 | 2), (byte) b.length}
            : new byte[]{(byte) (n << 3 | 2), (byte) (0x80 | (b.length & 0x7F)), (byte) (b.length >> 7)};
        byte[] r = Arrays.copyOf(hdr, hdr.length + b.length);
        System.arraycopy(b, 0, r, hdr.length, b.length);
        return r;
    }

    private static List<Rec> load(File f) throws Exception {
        List<Rec> recs = new ArrayList<>();
        if (f.getName().endsWith(".jsonl")) {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                JSONObject d = new JSONObject(line);
                if (d.has("raw_hex")) {
                    recs.add(new Rec(d.getDouble("t"), d.getInt("type"), hex(d.getString("raw_hex"))));
                } else if (d.optInt("type", -1) == 32) {
                    String st = d.getJSONObject("data").optString("status", "");
                    int v = st.equals("ACTIVE") ? 1 : st.equals("INACTIVE") ? 2 : 0;
                    recs.add(new Rec(d.getDouble("t"), 32, new byte[]{8, (byte) v}));
                } else if (d.optInt("type", -1) == 5 && d.has("data")) {
                    JSONObject data = d.getJSONObject("data");
                    recs.add(new Rec(d.getDouble("t"), 5,
                        mediaPayload(data.isNull("song") ? null : data.optString("song"), data.isNull("artist") ? null : data.optString("artist"))));
                }
            }
        } else {   // raw framed stream, one frame per second
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
            int i = 0;
            while (b.remaining() >= 8) {
                int typ = b.getInt();
                long ln = b.getInt() & 0xFFFFFFFFL;
                if (ln > (1 << 22)) break;
                int n = (int) Math.min(ln, b.remaining());
                byte[] pl = new byte[n];
                b.get(pl);
                recs.add(new Rec((double) i++, typ, pl));
            }
        }
        return recs;
    }

    /** maxidot_sim.replay(--plain --gps 87): one entry per changed screen */
    static List<String[]> render(File capture) throws Exception {
        Feed feed = new Feed();
        List<String[]> out = new ArrayList<>();
        String[] last = null;
        for (Rec r : load(capture)) {
            if (!feed.feed(r.type, r.payload, r.t)) continue;
            String[] rows = MaxidotRenderer.screen(feed.view(r.t, true, true), 0.0, 87.0, null);
            if (last == null || !Arrays.equals(rows, last)) out.add(rows);
            last = rows;
        }
        return out;
    }

    /** parses a Python repr() string literal starting at s[pos[0]] */
    private static String pyRepr(String s, int[] pos) {
        char q = s.charAt(pos[0]++);
        StringBuilder sb = new StringBuilder();
        while (s.charAt(pos[0]) != q) {
            char c = s.charAt(pos[0]++);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(pos[0]++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'x': sb.append((char) Integer.parseInt(s.substring(pos[0], pos[0] + 2), 16)); pos[0] += 2; break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(pos[0], pos[0] + 4), 16)); pos[0] += 4; break;
                default: sb.append(e);   // \\ \' \"
            }
        }
        pos[0]++;
        return sb.toString();
    }

    static List<String[]> expected(File f) throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            int[] pos = {0};
            while (line.charAt(pos[0]) == ' ') pos[0]++;
            String r1 = pyRepr(line, pos);
            while (line.charAt(pos[0]) == ' ') pos[0]++;
            String r2 = pyRepr(line, pos);
            out.add(new String[]{r1, r2});
        }
        return out;
    }

    private void check(File root, String name) throws Exception {
        List<String[]> got = render(new File(root, "captures/" + name));
        List<String[]> want = expected(new File(root, "expected/" + name + ".rows.txt"));
        int n = Math.min(got.size(), want.size());
        for (int i = 0; i < n; i++) {
            assertEquals(name + " screen " + i + " row 1", want.get(i)[0], got.get(i)[0]);
            assertEquals(name + " screen " + i + " row 2", want.get(i)[1], got.get(i)[1]);
        }
        assertEquals(name + ": number of screens", want.size(), got.size());
        assertTrue(want.size() > 0);
    }

    private int checkAll(File root) throws Exception {
        File[] caps = new File(root, "captures").listFiles((d, name) -> name.endsWith(".jsonl") || name.endsWith(".bin"));
        assertTrue("no captures in " + root, caps != null && caps.length > 0);
        java.util.Arrays.sort(caps);
        for (File c : caps) check(root, c.getName());
        return caps.length;
    }

    /** made-up routes (tools/gen_synthetic.py), committed to git */
    @Test public void syntheticCaptures() throws Exception {
        assertTrue(checkAll(dir()) >= 5);
    }

    /** real recordings: kept out of git (golden-private/), run when present */
    @Test public void privateRealCaptures() throws Exception {
        String p = System.getProperty("golden.private.dir");
        File root = p == null ? null : new File(p);
        org.junit.Assume.assumeTrue("golden-private/ not present", root != null && root.isDirectory());
        checkAll(root);
    }
}
