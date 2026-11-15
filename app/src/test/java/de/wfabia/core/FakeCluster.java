// SPDX-License-Identifier: GPL-3.0-or-later
// Port of maxidot_can/fake_cluster.py: answers the transport protocol like the real cluster so tests run offline.
package de.wfabia.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

public class FakeCluster extends FrameBuffer implements CanAdapter {
    static final int ID_SETUP_REQUEST = 0x4D9, ID_SETUP_RESPONSE = 0x2E8, ID_RADIO = 0x6B9, ID_CLUSTER = 0x699;

    volatile int session;
    final List<byte[]> messages = new ArrayList<>();      // every completed transport message received
    final List<int[]> sentIds = new ArrayList<>();         // (can id) of every frame the program transmitted
    volatile int setups;
    final List<Integer> replies = new ArrayList<>();       // first byte of every reply sent
    BiConsumer<Integer, byte[]> replySink;                 // optional: also hand replies to a SLCAN wire
    private ByteArrayOutputStream buf = new ByteArrayOutputStream();

    FakeCluster(int session) { this.session = session; }

    FakeCluster() { this(0x2A); }

    /** the cluster lost power and came back: it forgot the registration and hands out a new session byte */
    synchronized void restart(int newSession) {
        session = newSession;
        buf = new ByteArrayOutputStream();
        poweredOff = false;
    }

    private volatile boolean poweredOff;

    /** ignition off: the cluster answers nothing (and sends nothing) until restart() */
    void powerOff() { poweredOff = true; }

    private volatile boolean strict, gapSeen;
    private volatile long lastHeartbeat;

    /** like restart(), but the cluster only accepts a registration after it has seen the radio heartbeat disappear for 1.5 s */
    synchronized void restartStrict(int newSession) {
        restart(newSession);
        strict = true;
        gapSeen = false;
        lastHeartbeat = System.nanoTime();
    }

    @Override public void append(int id, byte[] data) {
        super.append(id, data);
        if (replySink != null) replySink.accept(id, data);
    }

    @Override public synchronized void tx(int canId, byte[] data) {
        sentIds.add(new int[]{canId});
        if (canId == 0x439) {   // the radio heartbeat, however it got here (directly or through the SLCAN wire)
            long now = System.nanoTime();
            if (strict && now - lastHeartbeat > 1_500_000_000L) gapSeen = true;
            lastHeartbeat = now;
        }
        if (poweredOff) return;
        if (canId == ID_SETUP_REQUEST && data.length >= 2 && (data[1] & 0xFF) == 0xC0) {
            setups++;
            buf = new ByteArrayOutputStream();
            append(ID_SETUP_RESPONSE, new byte[]{0x39, (byte) 0xD0, (byte) 0x99});
        } else if (canId == ID_RADIO && data.length > 0) {
            radioFrame(data);
        }
    }

    @Override public void txHeartbeat(int canId, byte[] data) { tx(canId, data); }

    @Override public void beginMessage() {}

    @Override public void endMessage() {}

    private void radioFrame(byte[] d) {
        int op = (d[0] & 0xFF) >> 4, seq = d[0] & 0xF;
        if ((d[0] & 0xFF) == 0xA0) {
            append(ID_CLUSTER, new byte[]{(byte) 0xA1, 0x04, (byte) 0x8A, (byte) 0x85, 0x43, (byte) 0x94});
        } else if (op <= 3) {
            buf.write(d, 1, d.length - 1);
            if (op == 0 || op == 1) append(ID_CLUSTER, new byte[]{(byte) (0xB0 | ((seq + 1) & 0xF))});
            if (op == 1) {
                byte[] msg = buf.toByteArray();
                buf = new ByteArrayOutputStream();
                messages.add(msg);
                byte[] r = reply(msg);
                byte[] out = new byte[r.length + 1];
                out[0] = 0x10;
                System.arraycopy(r, 0, out, 1, r.length);
                replies.add(r[0] & 0xFF);
                append(ID_CLUSTER, out);
            }
        }
    }

    private byte[] reply(byte[] msg) {
        if (Arrays.equals(msg, new byte[]{0x15, 0x39, 0x00, 0x01, 0x01})) return new byte[]{0x35};
        if (msg.length >= 4 && msg[0] == 0x02 && (msg[1] & 0xFF) == 0x80 && msg[2] == 0x39 && msg[3] == 0x20) {
            if (strict && !gapSeen) return new byte[]{0x2B, (byte) session, 0x03};   // still thinks the old radio is there
            return new byte[]{0x23, (byte) session, 0x01};
        }
        // Content message: rejected unless length 37, session byte matches and every constant byte is what a real radio sends.
        boolean ok = msg.length == 37 && msg[0] == 0x09 && (msg[1] & 0xFF) == session
            && Arrays.equals(Arrays.copyOfRange(msg, 2, 7), new byte[]{0x57, 0x0F, 0x02, 0x00, 0x00})
            && msg[7] == 0 && msg[8] == 0
            && Arrays.equals(Arrays.copyOfRange(msg, 19, 26), new byte[]{0x57, 0x0F, 0x03, 0x00, 0x00, 0x0A, 0x00})
            && msg[36] == 0x08;
        return ok ? new byte[]{0x27, (byte) session, 0x01} : new byte[]{0x2B, (byte) session, 0x03};
    }

    String rows(byte[] msg) {
        return new String(msg, 9, 10, java.nio.charset.StandardCharsets.ISO_8859_1) + "|"
            + new String(msg, 26, 10, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
