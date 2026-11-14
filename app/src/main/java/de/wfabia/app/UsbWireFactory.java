// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import de.wfabia.core.CanBroker;
import de.wfabia.core.Slcan;
import java.io.IOException;

/** Opens the CANable (USB 16D0:117E, CDC ACM) as an SLCAN serial port. Throws IOException with a reason if that is not possible yet. */
final class UsbWireFactory implements CanBroker.WireFactory {
    private static final String PERMISSION_ACTION = "de.wfabia.USB_PERMISSION";

    private final Context ctx;
    private long lastPermissionRequest;
    /** why the adapter is not open (or "open"), for the CAN card */
    volatile String info = "not tried yet";
    private volatile boolean forceRequest;
    private volatile String dtrInfo = "";
    private long lastAutoLeave;

    /** ask for USB permission again right now (the button on the main screen) */
    void requestPermissionNow() { forceRequest = true; }

    /** the result of the permission dialog */
    void onPermissionResult(boolean granted) { info = granted ? "USB permission granted" : "USB permission DENIED in the dialog"; }

    UsbWireFactory(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    static final int DFU_VID = 0x0483, DFU_PID = 0xDF11;

    /**
     * The adapter sits in the STM32 ROM bootloader (DFU) instead of running its firmware. This sends what `dfu-util -s 0x08000000:leave`
     * sends: set the address pointer to the start of the application flash, then a zero-length download, which makes the bootloader
     * start the application. Nothing is erased or written. Returns a message for the user.
     */
    String leaveDfu() {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbDevice dfu = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() == DFU_VID && d.getProductId() == DFU_PID) dfu = d;
        }
        if (dfu == null) return "no DFU device (0483:df11) on the USB bus: the adapter is not in bootloader mode right now";
        if (!um.hasPermission(dfu)) {
            um.requestPermission(dfu, PendingIntent.getBroadcast(ctx, 0, new Intent(PERMISSION_ACTION), 0));
            return "USB permission for the DFU device was requested: accept the dialog on the head unit, then press the button again";
        }
        UsbDeviceConnection c = um.openDevice(dfu);
        if (c == null) return "could not open the DFU device";
        try {
            if (dfu.getInterfaceCount() < 1 || !c.claimInterface(dfu.getInterface(0), true)) return "could not claim the DFU interface";
            byte[] st = new byte[6];
            c.controlTransfer(0x21, 4, 0, 0, null, 0, 1000);                     // DFU_CLRSTATUS
            dfuStatus(c, st);
            byte[] setAddress = {0x21, 0x00, 0x00, 0x00, 0x08};                   // command 0x21 + address 0x08000000 (little endian)
            if (c.controlTransfer(0x21, 1, 0, 0, setAddress, setAddress.length, 1000) < 0) return "DFU set-address request failed";
            dfuStatus(c, st);                                                      // dfuDNBUSY
            sleepMs(Math.max(50, (st[1] & 0xFF) | (st[2] & 0xFF) << 8 | (st[3] & 0xFF) << 16));
            dfuStatus(c, st);                                                      // dfuDNLOAD-IDLE
            c.controlTransfer(0x21, 1, 0, 0, null, 0, 1000);                      // zero-length download = leave DFU
            dfuStatus(c, st);                                                      // manifestation: the device starts the application
            return "leave request sent: the adapter should restart as a CANable (16d0:117e) within a second or two";
        } finally {
            try { c.close(); } catch (RuntimeException ignored) { /* the device is resetting */ }
        }
    }

    private static void dfuStatus(UsbDeviceConnection c, byte[] st) {
        c.controlTransfer(0xA1, 3, 0, 0, st, 6, 1000);                            // DFU_GETSTATUS
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** how the USB port is handled; the Serial test screen flips these to find what this head unit tolerates */
    static final class Opts {
        boolean clearHalt;        // ClearFeature(ENDPOINT_HALT) on the bulk endpoints at open
        boolean dtrRts = true;    // set DTR and RTS after opening
        // A reader thread with timeout-0 reads (the library's async UsbRequest path, no cancelled transfers). Do NOT turn this off:
        // reads with a short USB timeout cancel the pending IN transfer every few ms on this head unit's MUSB host and wedge it.
        boolean blockingReader = true;
    }

    static final Opts DEFAULT = new Opts();

    @Override
    public Slcan.Wire open() throws IOException { return open(DEFAULT); }

    Slcan.Wire open(Opts o) throws IOException {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbDevice dev = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() == Slcan.CANABLE_VID && d.getProductId() == Slcan.CANABLE_PID) dev = d;
        }
        if (dev == null) {
            for (UsbDevice d : um.getDeviceList().values()) {
                if (d.getVendorId() == DFU_VID && d.getProductId() == DFU_PID) {
                    // leave the bootloader by ourselves, at most every 15 s; the adapter then re-enumerates as a CANable
                    long now = System.currentTimeMillis();
                    if (now - lastAutoLeave > 15_000) {
                        lastAutoLeave = now;
                        info = "adapter was in its DFU bootloader: " + leaveDfu();
                    } else {
                        info = "the adapter is in its STM32 DFU bootloader (0483:df11); waiting for it to restart (or press 'Exit DFU mode')";
                    }
                    throw new IOException("the CANable is in its DFU bootloader (0483:df11) and is not running its firmware");
                }
            }
            info = "no CANable (16D0:117E) on the USB bus";
            throw new IOException("CANable 16D0:117E not found (is the USB port a host port? adb shell dumpsys usb)");
        }
        if (!um.hasPermission(dev)) {
            long now = System.currentTimeMillis();
            if (forceRequest || now - lastPermissionRequest > 10_000) {
                forceRequest = false;
                lastPermissionRequest = now;
                um.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, new Intent(PERMISSION_ACTION), 0));
                info = "USB permission missing: a dialog was requested, accept it on the head unit";
            }
            throw new IOException("waiting for USB permission (" + info + ")");
        }
        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) throw new IOException("could not open the USB device");
        ProbeTable table = UsbSerialProber.getDefaultProbeTable();
        table.addProduct(Slcan.CANABLE_VID, Slcan.CANABLE_PID, CdcAcmSerialDriver.class);
        UsbSerialDriver driver = new UsbSerialProber(table).probeDevice(dev);
        if (driver == null || driver.getPorts().isEmpty()) {
            conn.close();
            throw new IOException("no serial driver for the CANable");
        }
        UsbSerialPort port = driver.getPorts().get(0);
        try {
            port.open(conn);
            // standard USB ClearFeature(ENDPOINT_HALT) on the bulk endpoints: resets both data toggles to DATA0, so an earlier
            // cancelled transfer cannot leave the host and the adapter out of step
            if (o.clearHalt) {
                for (int ep : new int[]{0x81, 0x01, 0x82}) {
                    int r = conn.controlTransfer(0x02, 0x01, 0, ep, null, 0, 500);
                    if (r < 0) Log.d("wfabia", "clear halt on endpoint 0x" + Integer.toHexString(ep) + " failed (" + r + ")");
                }
            }
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            if (o.dtrRts) {
                try {
                    port.setDTR(true);
                    port.setRTS(true);
                    dtrInfo = "DTR/RTS set";
                } catch (UnsupportedOperationException | IOException e) {
                    dtrInfo = "DTR/RTS failed: " + e;   // shown on the card; not every CDC device implements the control lines
                }
            } else {
                dtrInfo = "DTR/RTS not set";
            }
        } catch (IOException | RuntimeException e) {
            try { port.close(); } catch (IOException ignored) { /* closing */ }
            conn.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        info = "USB permission ok, adapter opened, " + dtrInfo;
        return new UsbWire(port, conn, o.blockingReader);
    }

    /**
     * A dedicated thread does blocking reads (timeout 0) and hands the bytes over through a queue. read(dst, timeout) then
     * waits on the queue. Reading with a short USB timeout would cancel the pending bulk transfer every few milliseconds
     * whenever the bus is quiet; on a full-speed device behind a hub that can leave the IN endpoint's data toggle out of
     * step, after which the host silently drops everything the adapter sends. A PC's cdc_acm driver never cancels like that.
     */
    private static final class UsbWire implements Slcan.Wire {
        private final UsbSerialPort port;
        private final UsbDeviceConnection conn;
        private final java.util.concurrent.LinkedBlockingDeque<byte[]> inbox = new java.util.concurrent.LinkedBlockingDeque<>();
        private volatile IOException readError;
        private volatile boolean closed;

        private final boolean blocking;

        UsbWire(UsbSerialPort port, UsbDeviceConnection conn, boolean blocking) {
            this.port = port;
            this.conn = conn;
            this.blocking = blocking;
            if (!blocking) return;
            Thread t = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    while (!closed) {
                        int n = port.read(buf, 0);   // blocks until the adapter sends something
                        if (n > 0) inbox.add(java.util.Arrays.copyOf(buf, n));
                    }
                } catch (IOException | RuntimeException e) {
                    if (!closed) readError = e instanceof IOException ? (IOException) e : new IOException(e);
                }
            }, "usb-reader");
            t.setDaemon(true);
            t.start();
        }

        @Override public int read(byte[] dst, int timeoutMs) throws IOException {
            if (!blocking) return port.read(dst, timeoutMs);
            byte[] b;
            try {
                b = inbox.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
            if (b == null) {
                if (readError != null) throw readError;
                return 0;
            }
            int n = Math.min(dst.length, b.length);
            System.arraycopy(b, 0, dst, 0, n);
            if (n < b.length) inbox.addFirst(java.util.Arrays.copyOfRange(b, n, b.length));   // the rest comes with the next read
            return n;
        }

        @Override public void write(byte[] src) throws IOException { port.write(src, 2000); }

        @Override public void close() {
            closed = true;
            try { port.write("C\r".getBytes(), 200); } catch (IOException | RuntimeException ignored) { /* adapter may be gone */ }
            try {
                port.setDTR(false);   // dropping the control lines resets most CDC firmwares, like a replug does
                port.setRTS(false);
            } catch (IOException | RuntimeException ignored) { /* adapter may be gone */ }
            try { port.close(); } catch (IOException | RuntimeException ignored) { /* closing */ }
            try { conn.close(); } catch (RuntimeException ignored) { /* closing */ }
        }
    }
}
