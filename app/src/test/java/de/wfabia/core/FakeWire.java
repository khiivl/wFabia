// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** A fake SLCAN serial port. Lines written to it are recorded and passed to onWrite; inject() feeds the reader. */
class FakeWire implements Slcan.Wire {
    private final ArrayDeque<byte[]> inbox = new ArrayDeque<>();
    final List<String> written = Collections.synchronizedList(new ArrayList<>());
    volatile Consumer<String> onWrite;
    volatile boolean closed, dead, mute;
    private final StringBuilder partial = new StringBuilder();

    void inject(String line) {
        synchronized (inbox) {
            inbox.add((line + "\r").getBytes(StandardCharsets.US_ASCII));
            inbox.notifyAll();
        }
    }

    void injectFrame(int id, byte[] data) { inject(new String(Slcan.format(id, false, data), StandardCharsets.US_ASCII).replace("\r", "")); }

    /** the next read fails like an unplugged adapter */
    void kill() {
        dead = true;
        synchronized (inbox) { inbox.notifyAll(); }
    }

    @Override public int read(byte[] dst, int timeoutMs) throws IOException {
        synchronized (inbox) {
            if (dead || closed) throw new IOException("unplugged");
            if (inbox.isEmpty()) {
                try { inbox.wait(timeoutMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return 0; }
            }
            if (dead || closed) throw new IOException("unplugged");
            if (inbox.isEmpty()) return 0;
            byte[] b = inbox.poll();
            System.arraycopy(b, 0, dst, 0, b.length);
            return b.length;
        }
    }

    @Override public void write(byte[] src) throws IOException {
        if (dead || closed) throw new IOException("unplugged");
        partial.append(new String(src, StandardCharsets.US_ASCII));
        int i;
        while ((i = partial.indexOf("\r")) >= 0) {
            String line = partial.substring(0, i);
            partial.delete(0, i + 1);
            written.add(line);
            // a real adapter answers every command with at least a CR; mute simulates a dead serial link
            if (!mute && !line.startsWith("t") && !line.startsWith("T")) inject("");
            Consumer<String> w = onWrite;
            if (w != null) w.accept(line);
        }
    }

    @Override public void close() { closed = true; synchronized (inbox) { inbox.notifyAll(); } }

    /** connect the wire to a fake cluster: written t-frames go to the cluster, its replies come back as t-frames */
    static FakeWire withCluster(FakeCluster fc) {
        FakeWire w = new FakeWire();
        fc.replySink = (id, data) -> w.injectFrame(id, data);
        w.onWrite = line -> {
            if (line.startsWith("t") && line.length() >= 5) {
                int id = Integer.parseInt(line.substring(1, 4), 16);
                int dlc = Character.digit(line.charAt(4), 16);
                byte[] d = new byte[dlc];
                for (int i = 0; i < dlc; i++) d[i] = (byte) Integer.parseInt(line.substring(5 + 2 * i, 7 + 2 * i), 16);
                fc.tx(id, d);
            }
        };
        return w;
    }
}
