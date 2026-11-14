// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.content.Context;
import de.wfabia.core.Modules;

/** Process-wide state: the module switches (one instance) and what the service reports to the UI. */
public final class StateHub {
    private static final StateHub INSTANCE = new StateHub();

    public static StateHub get() { return INSTANCE; }

    private Modules modules;

    public synchronized Modules modules(Context c) {
        if (modules == null) modules = new Modules(new PrefsStore(c.getApplicationContext()));
        return modules;
    }

    // written by the service threads, read by the UI
    public volatile boolean serviceRunning;
    public volatile String zlinkState = "off", canState = "off", displayState = "off", bridgeState = "off", navSummary = "";
    public volatile boolean zlinkUp, canUp, displayLinked, bridgeRunning;
    public volatile long zlinkFrames, canRx, canTx, canDropped, canOpens;
    public volatile String adapterInfo = "", speedInfo = "", usbInfo = "", txInfo = "", usbEvents = "", ohuInfo = "", msessInfo = "";
    public volatile long ohuFrames, ohuLastMs = -1;
    public volatile int bridgeClients;
    public volatile String lastCan = "";
    public volatile String[] rows = {"          ", "          "};
}
