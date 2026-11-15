// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

/**
 * Cyrillic to Latin capitals, because the MaxiDot has no Cyrillic cells.
 * <ul>
 *   <li>Ukrainian follows the national standard (Cabinet of Ministers resolution 55 of 2010): Київ KYIV, Харків KHARKIV,
 *       Запоріжжя ZAPORIZHZHIA, Згурівка ZGHURIVKA, Ялта YALTA but Україна UKRAINA (є ї й ю я are YE YI Y YU YA at the start
 *       of a word, IE I I IU IA inside it), the apostrophe and the soft sign are not written (Знам'янка ZNAMIANKA).</li>
 *   <li>Russian uses the common readable scheme: Щукин SHCHUKIN, Королёв KOROLYOV, Горбачёв GORBACHOV, Толстой TOLSTOY,
 *       Мышь MYSH, Подъезд PODEZD.</li>
 * </ul>
 * Which one a text gets follows its letters: і ї є ґ are only Ukrainian, ы э ё ъ only Russian, the more frequent kind wins.
 * A text with neither (Кино, Океан Ельзи) is read as Ukrainian. Text without Cyrillic is returned unchanged; the font rules
 * (accents stripped, unknown cells '?') apply to whatever comes out.
 */
public final class Cyrillic {
    private Cyrillic() {}

    public enum Lang { UK, RU }

    private static final String UK_ONLY = "іїєґ", RU_ONLY = "ыэёъ";
    private static final String APOSTROPHES = "'’ʼ`";

    static boolean isCyrillic(char ch) { return ch >= 0x0400 && ch <= 0x04FF; }

    public static boolean has(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (isCyrillic(s.charAt(i))) return true;
        return false;
    }

    /** Ukrainian unless the Russian-only letters outnumber the Ukrainian-only ones */
    public static Lang detect(String s) {
        int uk = 0, ru = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (UK_ONLY.indexOf(c) >= 0) uk++;
            else if (RU_ONLY.indexOf(c) >= 0) ru++;
        }
        return ru > uk ? Lang.RU : Lang.UK;
    }

    public static String toLatin(String s) { return has(s) ? toLatin(s, detect(s)) : s; }

    public static String toLatin(String s, Lang lang) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        boolean inWord = false;      // the previous character was a letter (or an apostrophe inside a Cyrillic word)
        char prev = 0;               // the previous Cyrillic letter, lower case, 0 if the previous character was not one
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (APOSTROPHES.indexOf(ch) >= 0 && prev != 0 && i + 1 < s.length() && isCyrillic(s.charAt(i + 1))) {
                continue;            // м'ясо, п’ять: the apostrophe inside a word is not written, and the word goes on
            }
            if (!isCyrillic(ch)) {
                out.append(ch);
                inWord = Character.isLetter(ch);
                prev = 0;
                continue;
            }
            char c = Character.toLowerCase(ch);
            String l = lang == Lang.RU ? russian(c, prev) : ukrainian(c, prev, !inWord);
            if (l == null) {
                // a letter of another Cyrillic alphabet: its base letter if it has one (ў -> у), else as it is (the font shows '?')
                String base = MaxidotFont.stripAccents(String.valueOf(c));
                char b = base.length() == 1 ? base.charAt(0) : 0;
                l = b != c && isCyrillic(b) ? (lang == Lang.RU ? russian(b, prev) : ukrainian(b, prev, !inWord)) : null;
                if (l == null) l = String.valueOf(ch);
            }
            out.append(l);
            inWord = true;
            prev = c;
        }
        return out.toString();
    }

    /** one Ukrainian letter; null if it is not one (letters of the Russian alphabet are taken too, for mixed text) */
    private static String ukrainian(char c, char prev, boolean initial) {
        switch (c) {
            case 'а': return "A";
            case 'б': return "B";
            case 'в': return "V";
            case 'г': return prev == 'з' ? "GH" : "H";     // зг -> ZGH, so it cannot be read as ж
            case 'ґ': return "G";
            case 'д': return "D";
            case 'е': return "E";
            case 'є': return initial ? "YE" : "IE";
            case 'ж': return "ZH";
            case 'з': return "Z";
            case 'и': return "Y";
            case 'і': return "I";
            case 'ї': return initial ? "YI" : "I";
            case 'й': return initial ? "Y" : "I";
            case 'к': return "K";
            case 'л': return "L";
            case 'м': return "M";
            case 'н': return "N";
            case 'о': return "O";
            case 'п': return "P";
            case 'р': return "R";
            case 'с': return "S";
            case 'т': return "T";
            case 'у': return "U";
            case 'ф': return "F";
            case 'х': return "KH";
            case 'ц': return "TS";
            case 'ч': return "CH";
            case 'ш': return "SH";
            case 'щ': return "SHCH";
            case 'ь': return "";
            case 'ю': return initial ? "YU" : "IU";
            case 'я': return initial ? "YA" : "IA";
            case 'ы': return "Y";
            case 'э': return "E";
            case 'ё': return "YO";
            case 'ъ': return "";
            default: return null;
        }
    }

    /** one Russian letter; null if it is not one (the Ukrainian-only letters are taken too, for mixed text) */
    private static String russian(char c, char prev) {
        switch (c) {
            case 'а': return "A";
            case 'б': return "B";
            case 'в': return "V";
            case 'г': return "G";
            case 'д': return "D";
            case 'е': return "E";
            case 'ё': return "жчшщц".indexOf(prev) >= 0 ? "O" : "YO";
            case 'ж': return "ZH";
            case 'з': return "Z";
            case 'и': return "I";
            case 'й': return "Y";
            case 'к': return "K";
            case 'л': return "L";
            case 'м': return "M";
            case 'н': return "N";
            case 'о': return "O";
            case 'п': return "P";
            case 'р': return "R";
            case 'с': return "S";
            case 'т': return "T";
            case 'у': return "U";
            case 'ф': return "F";
            case 'х': return "KH";
            case 'ц': return "TS";
            case 'ч': return "CH";
            case 'ш': return "SH";
            case 'щ': return "SHCH";
            case 'ъ': return "";
            case 'ы': return "Y";
            case 'ь': return "";
            case 'э': return "E";
            case 'ю': return "YU";
            case 'я': return "YA";
            case 'і': return "I";
            case 'ї': return "YI";
            case 'є': return "YE";
            case 'ґ': return "G";
            default: return null;
        }
    }
}
