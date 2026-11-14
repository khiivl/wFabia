// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Generic protobuf wire reader (port of fox_sniff.wire). Varints are Long, everything else is byte[]. */
public final class Proto {
    private Proto() {}

    public static final class ProtoException extends RuntimeException {
        public ProtoException(String m) { super(m); }
    }

    /** -> {field: [values]}; wire types 1 and 5 are returned as raw byte[] like the Python code. */
    public static Map<Integer, List<Object>> fields(byte[] b) {
        Map<Integer, List<Object>> out = new HashMap<>();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long key = varint(b, pos);
            int f = (int) (key >>> 3), w = (int) (key & 7);
            Object v;
            switch (w) {
                case 0:
                    v = varint(b, pos);
                    break;
                case 1:
                    v = slice(b, pos, 8);
                    break;
                case 2: {
                    long n = varint(b, pos);
                    if (n < 0 || pos[0] + n > b.length) throw new ProtoException("truncated field");
                    v = slice(b, pos, (int) n);
                    break;
                }
                case 5:
                    v = slice(b, pos, 4);
                    break;
                default:
                    throw new ProtoException("bad wire type " + w);
            }
            out.computeIfAbsent(f, k -> new ArrayList<>()).add(v);
        }
        return out;
    }

    private static byte[] slice(byte[] b, int[] pos, int n) {
        int end = Math.min(b.length, pos[0] + n);
        byte[] r = new byte[Math.max(0, end - pos[0])];
        System.arraycopy(b, pos[0], r, 0, r.length);
        pos[0] = pos[0] + n;
        return r;
    }

    private static long varint(byte[] b, int[] pos) {
        long r = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= b.length) throw new ProtoException("truncated varint");
            int c = b[pos[0]++] & 0xFF;
            if (shift < 64) r |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return r;
            shift += 7;
        }
    }

    // helpers mirroring navdecode._first / _text / _s
    public static Object first(Map<Integer, List<Object>> d, int f) {
        List<Object> l = d.get(f);
        return l == null ? null : l.get(0);
    }

    public static long firstLong(Map<Integer, List<Object>> d, int f, long dflt) {
        Object o = first(d, f);
        return o == null ? dflt : (Long) o;
    }

    public static String str(Object o) {
        return new String((byte[]) o, StandardCharsets.UTF_8);
    }

    /** message { 1: string } ; null in -> null out */
    public static String text(Object msg) {
        if (msg == null) return null;
        Object s = first(fields((byte[]) msg), 1);
        return s == null ? "" : str(s);
    }
}
