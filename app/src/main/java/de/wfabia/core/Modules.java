// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The module switches and their dependencies. A switch can be on while its module is blocked because something it
 * needs is off (the UI shows "needs ..."). "send" is never restored as on: it resets to off on every service start.
 */
public final class Modules {
    public static final String ZLINK = "zlink", CAN = "can", DISPLAY = "display", BRIDGE = "bridge";
    public static final String NAV = "nav", LANES = "lanes", EXIT = "exit", MEDIA = "media", SPEED = "speed";
    public static final String SNIFF = "sniff", SEND = "send", LISTEN = "listen", NOTX = "notx", DIAG = "diag", RECOVER = "recover";
    public static final String OHU = "ohu", MSESS = "msess";
    /** pseudo modules: some data source for navigation / for media */
    public static final String SOURCE = "source", MEDIASRC = "mediasrc";

    /** persistence (SharedPreferences in the app) */
    public interface Store {
        boolean getBool(String key, boolean dflt);
        void putBool(String key, boolean value);
        String getString(String key, String dflt);
        void putString(String key, String value);
    }

    public interface Listener { void onModulesChanged(); }

    private static final String[][] NEEDS = {
        {DISPLAY, CAN}, {BRIDGE, CAN},
        {NAV, SOURCE}, {NAV, DISPLAY}, {MEDIA, MEDIASRC}, {MEDIA, DISPLAY}, {LANES, NAV}, {EXIT, NAV},
        {SPEED, DISPLAY}, {SNIFF, BRIDGE}, {SEND, BRIDGE},
    };

    /** pairs that exclude each other: listen-only never transmits, so nothing that needs to transmit can run */
    private static final String[][] CONFLICTS = {{DISPLAY, LISTEN}, {SEND, LISTEN}, {DISPLAY, NOTX}, {SEND, NOTX}};

    private static final String[] ALL = {ZLINK, CAN, DISPLAY, BRIDGE, NAV, LANES, EXIT, MEDIA, SPEED, SNIFF, SEND, LISTEN, NOTX, DIAG, RECOVER, OHU, MSESS};

    private final Store store;
    private final Map<String, Boolean> want = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private Set<Integer> allowlist = new LinkedHashSet<>();

    public Modules(Store store) {
        this.store = store;
        for (String k : ALL) want.put(k, store.getBool(k, !k.equals(SEND) && !k.equals(LISTEN) && !k.equals(NOTX) && !k.equals(DIAG) && !k.equals(RECOVER) && !k.equals(OHU) && !k.equals(MSESS)));
        want.put(SEND, false);              // sending always starts off
        store.putBool(SEND, false);
        allowlist = parseAllowlist(store.getString("allowlist", ""));
    }

    public synchronized boolean wanted(String k) { return Boolean.TRUE.equals(want.get(k)); }

    public synchronized void set(String k, boolean on) {
        if (!want.containsKey(k) || wanted(k) == on) return;
        want.put(k, on);
        // one Android Auto receiver at a time: Zlink and Open Headunit exclude each other
        if (on && k.equals(OHU) && wanted(ZLINK)) { want.put(ZLINK, false); store.putBool(ZLINK, false); }
        if (on && k.equals(ZLINK) && wanted(OHU)) { want.put(OHU, false); store.putBool(OHU, false); }
        if (!k.equals(SEND)) store.putBool(k, on);
        fire();
    }

    /** the switch is on and everything it needs is effectively on */
    public synchronized boolean effective(String k) {
        if (k.equals(SOURCE)) return effective(ZLINK) || effective(OHU);
        if (k.equals(MEDIASRC)) return effective(SOURCE) || effective(MSESS);
        if (!wanted(k)) return false;
        for (String[] n : NEEDS) if (n[0].equals(k) && !effective(n[1])) return false;
        for (String[] c : CONFLICTS) if (c[0].equals(k) && wanted(c[1])) return false;
        return true;
    }

    /** name of the first missing requirement, or null */
    public synchronized String blockedBy(String k) {
        for (String[] n : NEEDS) if (n[0].equals(k) && !effective(n[1])) return n[1];
        for (String[] c : CONFLICTS) if (c[0].equals(k) && wanted(c[1])) return "listen-only / receive-only off";
        return null;
    }

    public synchronized MaxidotRenderer.Options options() {
        MaxidotRenderer.Options o = new MaxidotRenderer.Options();
        o.nav = effective(NAV);
        o.lanes = effective(LANES);
        o.exit = effective(EXIT);
        o.media = effective(MEDIA);
        o.speed = effective(SPEED);
        return o;
    }

    /**
     * What the cluster would show if it were linked, for the preview in the app: the display's own switches, without what the
     * display itself needs (the CAN link, a cluster) and without the display switch. So the preview works on a phone with no
     * CANable attached.
     */
    public synchronized MaxidotRenderer.Options previewOptions() {
        MaxidotRenderer.Options o = new MaxidotRenderer.Options();
        o.nav = wanted(NAV) && effective(SOURCE);
        o.lanes = o.nav && wanted(LANES);
        o.exit = o.nav && wanted(EXIT);
        o.media = wanted(MEDIA) && effective(MEDIASRC);
        o.speed = wanted(SPEED);
        return o;
    }

    public synchronized Set<Integer> allowlist() { return new LinkedHashSet<>(allowlist); }

    public synchronized void setAllowlist(String text) {
        allowlist = parseAllowlist(text);
        store.putString("allowlist", allowlistText());
        fire();
    }

    public synchronized String allowlistText() {
        StringBuilder sb = new StringBuilder();
        for (int id : allowlist) sb.append(sb.length() == 0 ? "" : ",").append(String.format("%03X", id));
        return sb.toString();
    }

    /** "5A0, 0x2E8" -> {0x5A0, 0x2E8}; invalid entries are ignored; only 11-bit ids */
    public static Set<Integer> parseAllowlist(String text) {
        Set<Integer> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String part : text.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                int id = Hex.parseId(part);
                if (id >= 0 && id <= 0x7FF) out.add(id);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    public synchronized void addListener(Listener l) { listeners.add(l); }

    private void fire() {
        for (Listener l : new ArrayList<>(listeners)) l.onModulesChanged();
    }
}
