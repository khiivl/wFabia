// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

/** One CAN frame as seen on (rx) or sent to (tx) the bus. Immutable. */
public final class CanFrame {
    public final double t;        // unix seconds
    public final boolean rx, ext;
    public final int id;
    public final byte[] data;

    public CanFrame(double t, boolean rx, boolean ext, int id, byte[] data) {
        this.t = t;
        this.rx = rx;
        this.ext = ext;
        this.id = id;
        this.data = data;
    }

    public static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format("%02X", b[i] & 0xFF));
        return sb.toString();
    }

    public String idText() { return ext ? String.format("0x%08X", id) : String.format("0x%03X", id); }

    @Override public String toString() { return (rx ? "RX " : "TX ") + idText() + " " + hex(data, data.length); }
}
