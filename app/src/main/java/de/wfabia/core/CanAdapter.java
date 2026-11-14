// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.util.function.Predicate;

/**
 * What the FIS link needs from the CAN side: transmit, and wait for frames the cluster sent after a mark.
 * Implemented by CanBroker (real) and by the fake cluster in the tests.
 */
public interface CanAdapter {
    /** transmit one frame with FIS priority */
    void tx(int canId, byte[] data);

    /** transmit the presence heartbeat (highest priority; never dropped) */
    void txHeartbeat(int canId, byte[] data);

    /** index of the next received frame; pass to waitFor */
    long mark();

    /** first received frame with this id that satisfies pred, after mark; null on timeout */
    byte[] waitFor(long mark, int canId, Predicate<byte[]> pred, double timeoutSeconds);

    /** a FIS message (several frames) begins / ends: other traffic must not be sent in between */
    void beginMessage();

    void endMessage();
}
