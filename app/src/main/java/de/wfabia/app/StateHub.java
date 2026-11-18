// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.content.Context;
import de.wfabia.core.Modules;
import de.wfabia.core.VehiclePipe;

/** Process-wide state: the module switches (one instance) and what the service reports to the UI. */
public final class StateHub {
    private static final StateHub INSTANCE = new StateHub();

    public static StateHub get() { return INSTANCE; }

    private Modules modules;

    public synchronized Modules modules(Context c) {
        if (modules == null) modules = new Modules(new PrefsStore(c.getApplicationContext()));
        return modules;
    }

    /** lights and dimmer level from the CAN bus, fed by FabiaService, served to companion apps by VehiclePipeService */
    public final VehiclePipe vehicle = new VehiclePipe();
    /** how many companion apps are connected to the vehicle pipe, for the UI */
    public volatile int vehicleClients;
    public volatile String vehicleInfo = "";
    /** the head unit's ignition line: false between ACC_OFF and ACC_ON, when the CAN adapter and the display are switched off */
    public volatile boolean accOn = true;

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
