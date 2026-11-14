// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** The Open Headunit contract, the MediaSession policy, and the module rules of the two sources. */
public class SourcesTest {
    private static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** the inverse of OpenHuContract.translate for the frame types the captures hold */
    private static Object[] toOpenHu(int type) {
        switch (type) {
            case 0x8006: return new Object[]{OpenHuContract.CH_NAV, OpenHuContract.NAV_STATE};
            case 0x8007: return new Object[]{OpenHuContract.CH_NAV, OpenHuContract.NAV_POSITION};
            case 0x20: return new Object[]{OpenHuContract.CH_NAV, OpenHuContract.NAV_STATUS};
            case 5: return new Object[]{OpenHuContract.CH_MEDIA, OpenHuContract.MEDIA_METADATA};
            case 4: return new Object[]{OpenHuContract.CH_MEDIA, OpenHuContract.MEDIA_STATUS};
            default: return null;
        }
    }

    private static List<String[]> rows(File capture, boolean viaOpenHu) throws Exception {
        Feed feed = new Feed();
        List<String[]> out = new ArrayList<>();
        String[] last = null;
        for (String line : Files.readAllLines(capture.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            JSONObject d = new JSONObject(line);
            int type = d.getInt("type");
            byte[] payload = hex(d.getString("raw_hex"));
            double t = d.getDouble("t");
            if (viaOpenHu) {
                Object[] ch = toOpenHu(type);
                if (ch == null) continue;
                OpenHuContract.Frame f = OpenHuContract.translate((String) ch[0], (Integer) ch[1], payload);
                assertNotNull("frame type 0x" + Integer.toHexString(type) + " must translate", f);
                type = f.type;
                payload = f.payload;
            }
            if (!feed.feed(type, payload, t)) continue;
            String[] r = MaxidotRenderer.screen(feed.view(t, true, true), 0.0, 87.0, null);
            if (last == null || !Arrays.equals(r, last)) out.add(r);
            last = r;
        }
        return out;
    }

    /** whatever the phone sends through Open Headunit must give the same cluster rows as the same data through Zlink */
    @Test public void openHeadunitPathGivesTheSameRowsAsZlink() throws Exception {
        File[] caps = new File(System.getProperty("golden.dir"), "captures").listFiles((d, n) -> n.endsWith(".jsonl"));
        assertTrue(caps != null && caps.length >= 5);
        Arrays.sort(caps);
        for (File c : caps) {
            List<String[]> direct = rows(c, false), viaOh = rows(c, true);
            assertEquals(c.getName() + ": number of screens", direct.size(), viaOh.size());
            for (int i = 0; i < direct.size(); i++) assertArrayEquals(c.getName() + " screen " + i, direct.get(i), viaOh.get(i));
            assertTrue(direct.size() > 5);
        }
    }

    @Test public void aStepWithoutAnExplicitStatusMeansNavigationIsActive() {
        NavTracker t = new NavTracker();
        assertTrue(t.feed(0x8006, new byte[]{0x0a, 0x04, 0x0a, 0x02, 0x08, 0x08}));   // a step: turn normal right
        assertEquals(Boolean.TRUE, t.active);
        assertTrue(t.feed(0x20, new byte[]{0x08, 0x02}));                              // an explicit "inactive" still wins
        assertEquals(Boolean.FALSE, t.active);
        assertTrue(t.feed(0x8006, new byte[]{0x0a, 0x04, 0x0a, 0x02, 0x08, 0x08}));
        assertEquals(Boolean.FALSE, t.active);                                         // once a status was seen, only a status changes it
    }

    @Test public void translateMapsTheMessagesTheDisplayUses() {
        byte[] p = {1, 2, 3};
        assertEquals(0x8006, OpenHuContract.translate("nav", 32774, p).type);
        assertEquals(0x8007, OpenHuContract.translate("nav", 32775, p).type);
        assertEquals(0x20, OpenHuContract.translate("nav", 32771, p).type);
        assertArrayEquals(new byte[]{8, 2}, OpenHuContract.translate("nav", 32770, p).payload);   // navigation stopped = inactive
        assertEquals(5, OpenHuContract.translate("media", 32771, p).type);
        assertEquals(4, OpenHuContract.translate("media", 32769, p).type);
        assertNull(OpenHuContract.translate("nav", 32769, p));      // start: the status message follows
        assertNull(OpenHuContract.translate("nav", 32772, p));      // legacy next-turn messages
        assertNull(OpenHuContract.translate("media", 32770, p));
        assertNull(OpenHuContract.translate("nav", 99999, p));
        assertNull(OpenHuContract.translate("audio", 32774, p));    // the same message id means something else on another channel
        assertNull(OpenHuContract.translate("nav", 32774, null));
    }

    @Test public void mediaTextGoesThroughTheSameMediaRules() {
        NavModel nav = new NavModel();
        assertTrue(nav.feedMediaText("Song", "Artist"));
        Feed.View v = nav.view(true, true);
        assertNotNull(v.media);
        String[] r = MaxidotRenderer.screen(v, 0.0, 50.0, null);
        assertEquals("  ARTIST  ", r[0]);
        assertEquals("   SONG   ", r[1]);
        // a title without an artist: the title takes the first row, like the Android Auto metadata
        NavModel nav2 = new NavModel();
        nav2.feedMediaText("Only Title", "");
        String[] r2 = MaxidotRenderer.screen(nav2.view(true, true), 0.0, 50.0, null);
        assertEquals("ONLY TITLE", r2[0]);
        assertEquals("          ", r2[1]);
    }

    private static MediaSessionPolicy.Info s(String pkg, int state, String title) {
        return new MediaSessionPolicy.Info(pkg, state, title, "artist");
    }

    @Test public void mediaSessionPolicyPicksThePlayingSession() {
        int play = MediaSessionPolicy.STATE_PLAYING, pause = MediaSessionPolicy.STATE_PAUSED, buf = MediaSessionPolicy.STATE_BUFFERING;
        assertNull(MediaSessionPolicy.choose(Collections.emptyList(), null));
        assertNull(MediaSessionPolicy.choose(Arrays.asList(s("a", pause, "x"), s("b", 0, "y")), null));          // nothing playing
        assertEquals("b", MediaSessionPolicy.choose(Arrays.asList(s("a", pause, "x"), s("b", play, "y")), null).pkg);
        assertEquals("a", MediaSessionPolicy.choose(Arrays.asList(s("a", play, "x"), s("b", play, "y")), null).pkg);   // system order wins
        assertEquals("a", MediaSessionPolicy.choose(Arrays.asList(s("a", buf, "x")), null).pkg);                  // buffering counts
        assertEquals("b", MediaSessionPolicy.choose(Arrays.asList(s("a", play, "  "), s("b", play, "y")), null).pkg);  // blank title skipped
        assertEquals("b", MediaSessionPolicy.choose(Arrays.asList(s("a", play, null), s("b", play, "y")), null).pkg);
        assertEquals("b", MediaSessionPolicy.choose(Arrays.asList(s("a", play, "x"), s("b", play, "y")), new HashSet<>(Arrays.asList("a"))).pkg);
    }

    private static final class MemStore implements Modules.Store {
        final Map<String, Object> m = new HashMap<>();
        @Override public boolean getBool(String k, boolean d) { return m.containsKey(k) ? (Boolean) m.get(k) : d; }
        @Override public void putBool(String k, boolean v) { m.put(k, v); }
        @Override public String getString(String k, String d) { return m.containsKey(k) ? (String) m.get(k) : d; }
        @Override public void putString(String k, String v) { m.put(k, v); }
    }

    @Test public void zlinkAndOpenHeadunitExcludeEachOther() {
        MemStore store = new MemStore();
        Modules m = new Modules(store);
        assertTrue(m.wanted(Modules.ZLINK) && !m.wanted(Modules.OHU));                     // Zlink is the default
        m.set(Modules.OHU, true);
        assertTrue(m.wanted(Modules.OHU));
        assertFalse(m.wanted(Modules.ZLINK));
        assertTrue(m.effective(Modules.NAV) && m.effective(Modules.LANES) && m.effective(Modules.MEDIA));   // the display options work from either source
        m.set(Modules.ZLINK, true);
        assertTrue(m.wanted(Modules.ZLINK));
        assertFalse(m.wanted(Modules.OHU));
        assertFalse((Boolean) store.m.get(Modules.OHU));                                    // and it is remembered
        m.set(Modules.ZLINK, false);
        assertFalse(m.effective(Modules.NAV));                                              // no source: no navigation
        assertFalse(m.effective(Modules.MEDIA));
        m.set(Modules.MSESS, true);
        assertFalse(m.effective(Modules.NAV));
        assertTrue("media works from a MediaSession alone", m.effective(Modules.MEDIA));
        assertEquals(Modules.SOURCE, m.blockedBy(Modules.NAV));
    }
}
