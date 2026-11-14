// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.List;
import java.util.Map;

/** Folds the Zlink message stream into one current state (port of navdecode.NavTracker). Safe to feed garbage. */
public final class NavTracker {
    public Boolean active;                       // null = unknown
    public ZlinkDecoder.Step step;
    public String destination;
    public ZlinkDecoder.Next next;
    public ZlinkDecoder.Trip trip;
    public String lastError;

    /** @return true if the message was understood */
    public boolean feed(int typ, byte[] payload) {
        try {
            if (typ == ZlinkDecoder.T_NAVI_STATUS) {
                Map<Integer, List<Object>> f = Proto.fields(payload);
                long st = Proto.firstLong(f, 1, 0);
                active = st == 1;
                if (st != 1) {
                    step = null;
                    destination = null;
                    next = null;
                    trip = null;
                }
            } else if (typ == ZlinkDecoder.T_STATE) {
                ZlinkDecoder.StateMsg d = ZlinkDecoder.decodeState(payload);
                step = d.step;
                destination = d.destination;
                // a source that never sends the explicit status (Open Headunit forwards what the phone sends, and the phone may
                // skip it): a step with no status seen yet means navigation is running
                if (active == null && d.step != null) active = true;
            } else if (typ == ZlinkDecoder.T_PROGRESS) {
                ZlinkDecoder.ProgressMsg d = ZlinkDecoder.decodeProgress(payload);
                next = d.next;
                trip = d.trip;
            } else {
                return false;
            }
        } catch (RuntimeException e) {   // truncated / unexpected: keep the last good state
            lastError = "type 0x" + Integer.toHexString(typ) + ": " + e;
            return false;
        }
        return true;
    }
}
