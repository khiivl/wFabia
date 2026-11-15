// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** Ukrainian and Russian on the MaxiDot. reference/python/maxidot_sim.py (cyr_latin, to_font) gives the same strings. */
public class CyrillicTest {
    private static void font(String expected, String text) { assertEquals(text, expected, MaxidotRenderer.toFont(text)); }

    @Test public void ukrainianFollowsTheNationalStandard() {
        font("KYIV", "Київ");
        font("KHARKIV", "Харків");
        font("ZAPORIZHZHIA", "Запоріжжя");
        font("ZGHURIVKA", "Згурівка");          // зг -> ZGH
        font("ROZGHON", "Розгон");
        font("UKRAINA", "Україна");              // ї inside a word -> I
        font("YIZHAKEVYCH", "Їжакевич");         // at the start -> YI
        font("YENAKIIEVE", "Єнакієве");
        font("YURII", "Юрій");
        font("ZNAMIANKA", "Знам'янка");          // the apostrophe is not written, я stays inside the word
        font("PIAT", "п’ять");
        font("OKEAN ELZY", "Океан Ельзи");       // ь is not written
        font("SHCHASTIA", "Щастя");
    }

    @Test public void russianUsesTheCommonScheme() {
        font("KOROLYOV", "Королёв");
        font("GORBACHOV", "Горбачёв");           // ё after ч -> O
        font("MYSH", "Мышь");
        font("PODEZD", "Подъезд");
        font("SPLIN - VYKHODA NET", "Сплин - Выхода нет");
        font("ELEKTRICHKA", "Электричка");
    }

    @Test public void theLettersDecideTheLanguage() {
        assertEquals(Cyrillic.Lang.UK, Cyrillic.detect("Київ"));
        assertEquals(Cyrillic.Lang.RU, Cyrillic.detect("Мышь"));
        assertEquals(Cyrillic.Lang.UK, Cyrillic.detect("Кино"));         // neither kind: Ukrainian
        assertEquals(Cyrillic.Lang.UK, Cyrillic.detect("Київ і Москва")); // more Ukrainian-only letters
        font("KYNO", "Кино");
        font("MINSK", "Мінськ");
    }

    @Test public void everythingElseIsLeftToTheFontRules() {
        String latin = "Bahnhofstraße";
        assertSame(latin, Cyrillic.toLatin(latin));
        font("BAHNHOFSTRASSE", latin);
        font("DJ KYIV 2", "DJ Київ 2");
        font("ROKNROL", "Рок'н'рол");            // apostrophes inside a Cyrillic word are not written
        font("ROK'", "Рок'");                     // elsewhere they stay
        font("UL", "Ўл");                         // another alphabet's letter with a base letter: ў -> у
        font("?", "Ђ");                           // no base letter: the font shows '?'
        font("→ 300M", "→ 300M");
    }

    @Test public void mediaRowsShowTheTransliteration() {
        NavModel nav = new NavModel();
        nav.feedMediaText("Вахтерам", "Бумбокс");
        String[] r = MaxidotRenderer.screen(nav.view(true, true), 0.0, 50.0, null);
        assertArrayEquals(new String[]{" BUMBOKS  ", "VAKHTERAM "}, r);
    }
}
