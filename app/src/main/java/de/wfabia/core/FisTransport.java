// SPDX-License-Identifier: GPL-3.0-or-later
// Port of maxidot/transport.py (Copyright (C) 2026 the maxidot-fis contributors)
package de.wfabia.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The transport layer between "radio" and cluster (a VW TP 2.0-style session; see PROTOCOL.md). */
public final class FisTransport {
    private FisTransport() {}

    public static final int ID_SETUP_REQUEST = 0x4D9;    // radio -> cluster
    public static final int ID_SETUP_RESPONSE = 0x2E8;   // cluster -> radio
    public static final int ID_RADIO = 0x6B9;            // radio's data channel
    public static final int ID_CLUSTER = 0x699;          // cluster's data channel
    public static final int ID_HEARTBEAT = 0x439;        // radio presence heartbeat

    static final byte[] SETUP_REQUEST = {0x08, (byte) 0xC0, (byte) 0xB9};
    static final byte[] PARAMS_REQUEST = {(byte) 0xA0, 0x04, (byte) 0x82, (byte) 0x84, (byte) 0x82, (byte) 0xB2};   // block size 4
    public static final double DEFAULT_GAP = 0.009;                                                             // what a real radio uses

    public static final class Packet {
        public final int op, seq;
        public final byte[] frame;
        Packet(int op, int seq, byte[] frame) { this.op = op; this.seq = seq; this.frame = frame; }
    }

    /** Split a message into transport packets: 7 payload bytes each; opcode 2 = more follow, 0 = more follow and an ack
     *  is needed (every 4th packet), 1 = last packet, ack needed. */
    public static List<Packet> chunkMessage(byte[] payload) {
        List<Packet> out = new ArrayList<>();
        int n = (payload.length + 6) / 7;
        for (int i = 0; i < n; i++) {
            byte[] chunk = Arrays.copyOfRange(payload, i * 7, Math.min(payload.length, i * 7 + 7));
            boolean last = i == n - 1;
            int op = last ? 1 : ((i + 1) % 4 == 0 ? 0 : 2);
            byte[] frame = new byte[chunk.length + 1];
            frame[0] = (byte) ((op << 4) | (i & 0xF));
            System.arraycopy(chunk, 0, frame, 1, chunk.length);
            out.add(new Packet(op, i & 0xF, frame));
        }
        return out;
    }

    /** Open a session: connection setup, then channel parameters. -> true if the cluster answered both. */
    public static boolean connect(CanAdapter c) {
        long m = c.mark();
        c.tx(ID_SETUP_REQUEST, SETUP_REQUEST);
        if (c.waitFor(m, ID_SETUP_RESPONSE, d -> d.length >= 2 && (d[1] & 0xFF) == 0xD0, 0.5) == null) return false;
        sleep(0.005);
        m = c.mark();
        c.tx(ID_RADIO, PARAMS_REQUEST);
        return c.waitFor(m, ID_CLUSTER, d -> d.length > 0 && (d[0] & 0xFF) == 0xA1, 0.5) != null;
    }

    public static void disconnect(CanAdapter c) { disconnect(c, 0.03); }

    public static void disconnect(CanAdapter c, double pause) {
        c.tx(ID_RADIO, new byte[]{(byte) 0xA8});
        sleep(pause);
    }

    /** Send a message's packets, honouring the cluster's acks. -> mark taken before the last packet, or null on failure. */
    public static Long sendData(CanAdapter c, byte[] payload, double gap) {
        Long mLast = null;
        for (Packet p : chunkMessage(payload)) {
            sleep(gap);
            if (p.op == 0 || p.op == 1) mLast = c.mark();
            c.tx(ID_RADIO, p.frame);
            if (p.op == 0 || p.op == 1) {
                final int want = 0xB0 | ((p.seq + 1) & 0xF);
                if (c.waitFor(mLast, ID_CLUSTER, d -> d.length > 0 && (d[0] & 0xFF) == want, 0.5) == null) return null;
            }
        }
        return mLast;
    }

    /** Wait for the cluster's reply message and ack it. -> reply payload (after its header byte) or null. */
    public static byte[] waitReply(CanAdapter c, long mLast, double gap) {
        byte[] reply = c.waitFor(mLast, ID_CLUSTER, d -> d.length > 0 && ((d[0] & 0xFF) >> 4) <= 3, 1.5);
        if (reply == null) return null;
        sleep(gap);
        c.tx(ID_RADIO, new byte[]{(byte) (0xB0 | (((reply[0] & 0xF) + 1) & 0xF))});
        return Arrays.copyOfRange(reply, 1, reply.length);
    }

    /** Send one message inside an open session and return the cluster's reply payload (or null). */
    public static byte[] sendMessage(CanAdapter c, byte[] payload, double gap) {
        Long mLast = sendData(c, payload, gap);
        if (mLast == null) return null;
        return waitReply(c, mLast, gap);
    }

    static void sleep(double seconds) {
        if (seconds <= 0) return;
        try {
            long ns = (long) (seconds * 1e9);
            Thread.sleep(ns / 1_000_000L, (int) (ns % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
