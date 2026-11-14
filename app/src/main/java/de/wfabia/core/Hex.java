// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

/** Small hex helpers used by the bridge. */
public final class Hex {
    private Hex() {}

    private static final char[] UP = "0123456789ABCDEF".toCharArray(), LOW = "0123456789abcdef".toCharArray();

    public static String upper(byte[] b, int len) {
        char[] c = new char[len * 2];
        for (int i = 0; i < len; i++) {
            c[2 * i] = UP[(b[i] >> 4) & 0xF];
            c[2 * i + 1] = UP[b[i] & 0xF];
        }
        return new String(c);
    }

    public static String lower(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[2 * i] = LOW[(b[i] >> 4) & 0xF];
            c[2 * i + 1] = LOW[b[i] & 0xF];
        }
        return new String(c);
    }

    /** "01 FF", "01ff" -> bytes; throws IllegalArgumentException on odd length or non-hex */
    public static byte[] parse(String s) {
        String h = s.replace(" ", "");
        if (h.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16), lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("not hex");
            b[i] = (byte) (hi << 4 | lo);
        }
        return b;
    }

    /** "0x5A0", "5A0" (hex), "1440" is read as hex too: CAN ids are always hex here */
    public static int parseId(String s) {
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        return Integer.parseInt(t, 16);
    }
}
