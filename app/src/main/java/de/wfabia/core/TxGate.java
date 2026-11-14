// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.Set;
import java.util.logging.Logger;

/**
 * The only way a user (the PC bridge, the adb broadcast) puts a frame on the bus. Needs the send switch plus an id on
 * the allowlist (empty = nothing allowed). The FIS link's own frames do not come through here. Every queued frame is logged.
 */
public final class TxGate {
    private static final Logger LOG = Logger.getLogger("wfabia.tx");

    public interface Policy {
        boolean sendEnabled();

        Set<Integer> allowlist();
    }

    private final CanBroker broker;
    private final Policy policy;

    public TxGate(CanBroker broker, Policy policy) {
        this.broker = broker;
        this.policy = policy;
    }

    /** @return null if the frame was queued, else the reason it was refused */
    public String submit(String source, int id, byte[] data, long ttlMs) {
        if (!policy.sendEnabled()) return "send_disabled";
        if (id < 0 || id > 0x7FF || data.length > 8) return "bad_frame";
        if (!policy.allowlist().contains(id)) return "id_not_allowed";
        if (!broker.isUp()) return "can_down";
        if (!broker.tx(CanBroker.PRIO_OTHER, id, false, data, ttlMs)) return "queue_full";
        LOG.info("TX (" + source + ") 0x" + String.format("%03X", id) + " " + Hex.upper(data, data.length));
        return null;
    }
}
