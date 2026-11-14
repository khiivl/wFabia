// SPDX-License-Identifier: GPL-3.0-or-later
// Port of maxidot/slcan.py FrameBuffer (Copyright (C) 2026 the maxidot-fis contributors)
package de.wfabia.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Thread-safe rolling buffer of received (can_id, data) frames, addressed by absolute index. */
public class FrameBuffer {
    private static final class Entry {
        final int id;
        final byte[] data;
        Entry(int id, byte[] data) { this.id = id; this.data = data; }
    }

    private final Object lock = new Object();
    private final List<Entry> frames = new ArrayList<>();
    private long base = 0;
    private final int keep, drop;

    public FrameBuffer() { this(50000, 25000); }

    public FrameBuffer(int keep, int drop) { this.keep = keep; this.drop = drop; }

    public void append(int id, byte[] data) {
        synchronized (lock) {
            frames.add(new Entry(id, data.clone()));
            if (frames.size() > keep) {
                frames.subList(0, drop).clear();
                base += drop;
            }
            lock.notifyAll();
        }
    }

    public long mark() {
        synchronized (lock) { return base + frames.size(); }
    }

    public byte[] waitFor(long mark, int id, Predicate<byte[]> pred, double timeoutSeconds) {
        long end = System.nanoTime() + (long) (timeoutSeconds * 1e9);
        long pos = mark;
        while (true) {
            synchronized (lock) {
                int start = (int) Math.max(0, pos - base);
                for (int i = start; i < frames.size(); i++) {
                    Entry e = frames.get(i);
                    if (e.id == id && pred.test(e.data)) return e.data;
                }
                pos = base + frames.size();
                long left = end - System.nanoTime();
                if (left <= 0) return null;
                try {
                    lock.wait(Math.max(1, Math.min(5, left / 1_000_000)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }
}
