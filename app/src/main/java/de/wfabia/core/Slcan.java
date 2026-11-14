// SPDX-License-Identifier: GPL-3.0-or-later
// Port of maxidot/slcan.py (Copyright (C) 2026 the maxidot-fis contributors)
package de.wfabia.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Raw SLCAN protocol for CANable-style adapters. Talks SLCAN directly: close, set bitrate, open. (python-can's slcan
 * backend sends a blank command and a second "open" which left the adapter receiving but never transmitting.)
 */
public final class Slcan {
    private Slcan() {}

    /** the serial port of the adapter */
    public interface Wire {
        /** @return bytes read, 0 on timeout, -1 if the port is gone */
        int read(byte[] dst, int timeoutMs) throws IOException;

        void write(byte[] src) throws IOException;

        void close();
    }

    public static final int CANABLE_VID = 0x16D0, CANABLE_PID = 0x117E;

    /** close, set the bitrate (S3 = 100 kbit/s), open in normal mode. */
    public static int open(Wire w, String bitrateCode, long settleMs) throws IOException {
        return open(w, bitrateCode, settleMs, false);
    }

    /** @param listenOnly L instead of O: the adapter never transmits and never acknowledges, it only listens
     *  @return how many bytes the adapter sent back while it was being set up (every command is answered with at least a CR;
     *          0 means the serial link is dead even though the port opened) */
    public static int open(Wire w, String bitrateCode, long settleMs, boolean listenOnly) throws IOException {
        sleep(settleMs);   // some adapters reset when the port opens
        byte[] buf = new byte[4096];
        int heard = 0;
        for (String cmd : new String[]{"C", bitrateCode, listenOnly ? "L" : "O"}) {
            w.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
            sleep(50);
            for (int i = 0; i < 8; i++) {
                int n = w.read(buf, 20);
                if (n <= 0) break;
                heard += n;
            }
        }
        return heard;
    }

    public static byte[] format(int id, boolean ext, byte[] data) {
        StringBuilder sb = new StringBuilder();
        sb.append(ext ? String.format("T%08X", id) : String.format("t%03X", id));
        sb.append(String.format("%X", data.length));
        sb.append(CanFrame.hex(data, data.length));
        sb.append('\r');
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Accumulates serial bytes and yields complete frame lines. Lines that are not t/T frames are ignored. */
    public static final class LineParser {
        public interface Sink { void frame(boolean ext, int id, byte[] data); }

        private final StringBuilder line = new StringBuilder();

        /** lines that are not frames: the adapter's answers to V, F and the other commands (a BEL means rejected) */
        public java.util.function.Consumer<String> other;

        public void feed(byte[] buf, int n, Sink sink) {
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '\r') {
                    String l = line.toString();
                    if (!parse(l, sink) && other != null && !l.isEmpty()) other.accept(l);
                    line.setLength(0);
                } else if (line.length() < 64) {
                    line.append(c);
                }
            }
        }

        /** @return true if the line was a frame */
        static boolean parse(String s, Sink sink) {
            try {
                boolean ext = s.startsWith("T");
                int idLen = ext ? 8 : 3;
                if (!(s.startsWith("t") || ext) || s.length() < idLen + 2) return false;
                int id = (int) Long.parseLong(s.substring(1, 1 + idLen), 16);
                int dlc = Character.digit(s.charAt(1 + idLen), 16);
                if (dlc < 0 || dlc > 8) return false;
                int from = 2 + idLen;
                int avail = Math.min(dlc * 2, s.length() - from) / 2;   // lenient like bytes.fromhex on a short tail
                byte[] data = new byte[avail];
                for (int i = 0; i < avail; i++) data[i] = (byte) Integer.parseInt(s.substring(from + 2 * i, from + 2 * i + 2), 16);
                sink.frame(ext, id, data);
                return true;
            } catch (RuntimeException ignored) {
                return false;   // malformed line
            }
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }
}
