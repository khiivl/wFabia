// SPDX-License-Identifier: GPL-3.0-or-later
// Port of maxidot/font.py (Copyright (C) 2026 the maxidot-fis contributors)
package de.wfabia.core;

import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The MaxiDot font: text -> display bytes. */
public final class MaxidotFont {
    private MaxidotFont() {}

    private static final Map<Character, Integer> GLYPHS = new HashMap<>();
    static {
        Object[][] g = {
            {'Ä', 0x5F}, {'Ö', 0x60}, {'Ü', 0x61}, {'ä', 0x5F}, {'ö', 0x60}, {'ü', 0x61},
            {'’', 0x62}, {'_', 0x66}, {'✓', 0x67}, {'°', 0x6B}, {'▶', 0x70}, {'ℹ', 0x71},
            {'↑', 0x18}, {'↓', 0x19}, {'→', 0x1A}, {'←', 0x1B}, {'↕', 0x1D}, {'▲', 0x1E},
            {'▼', 0x1F}};
        for (Object[] e : g) GLYPHS.put((Character) e[0], (Integer) e[1]);
    }
    private static final char LONG_ARROW = '➔';

    /** str -> bytes for the display. Capitals only; umlauts have cells, sharp s becomes SS, accents are stripped, unknown -> '?'. */
    public static byte[] encode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Integer g = GLYPHS.get(ch);
            if (g != null) {
                out.write(g);
            } else if (ch == 'ß') {
                out.write('S');
                out.write('S');
            } else if (ch == LONG_ARROW) {
                out.write(0x68);
                out.write(0x69);
            } else {
                String base = stripAccents(String.valueOf(ch)).toUpperCase(Locale.ROOT);
                for (int k = 0; k < base.length(); k++) {
                    char c = base.charAt(k);
                    out.write(c >= 0x20 && c <= 0x5E ? c : 0x3F);
                }
            }
        }
        return out.toByteArray();
    }

    static String stripAccents(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            int type = Character.getType(d.charAt(i));
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK) {
                sb.append(d.charAt(i));
            }
        }
        return sb.toString();
    }

    /** cut to width and pad with spaces on the right */
    public static byte[] padLeft(byte[] data, int width) {
        byte[] r = new byte[width];
        Arrays.fill(r, (byte) ' ');
        System.arraycopy(data, 0, r, 0, Math.min(width, data.length));
        return r;
    }
}
