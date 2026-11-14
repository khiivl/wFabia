// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RendererTest {
    private static String row2(Double speed, MaxidotRenderer.Options o) {
        Feed f = new Feed();
        return MaxidotRenderer.screen(f.view(0, true, true), 0, speed, o)[1];
    }

    @Test public void standstillAndNoSpeedShowZero() {
        MaxidotRenderer.Options o = new MaxidotRenderer.Options();
        String zero = "   0 KM/H ";
        assertEquals(zero, row2(null, o));
        assertEquals(zero, row2(0.0, o));
        assertEquals(zero, row2(1.0, o));      // GPS jitter at a standstill
        assertEquals(zero, row2(3.4, o));      // rounds to 3
        assertEquals("   4 KM/H ", row2(3.6, o));
        assertEquals("  87 KM/H ", row2(87.0, o));
    }

    @Test public void blankWhenTheSpeedOptionIsOff() {
        MaxidotRenderer.Options o = new MaxidotRenderer.Options();
        o.speed = false;
        assertEquals("          ", row2(87.0, o));
        assertEquals("          ", row2(null, o));
    }
}
