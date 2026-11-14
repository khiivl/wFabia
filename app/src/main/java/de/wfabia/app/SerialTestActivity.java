// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import de.wfabia.core.Modules;
import de.wfabia.core.Slcan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A raw terminal for the CANable, like opening its serial port on a PC: open it, send V / C / S3 / O by hand, watch every
 * byte it sends back. The CAN link is switched off while this screen is open so nothing else holds the port.
 */
public class SerialTestActivity extends AppCompatActivity {
    private Modules mods;
    private boolean canWasOn;
    private volatile Slcan.Wire wire;
    private volatile boolean running = true;
    private TextView log;
    private ScrollView scroll;
    private final StringBuilder text = new StringBuilder();
    private long bytesIn;
    private TextView counter;
    private final UsbWireFactory.Opts options = new UsbWireFactory.Opts();   // clear halt off, DTR/RTS on, short-timeout reads

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mods = StateHub.get().modules(this);
        canWasOn = mods.wanted(Modules.CAN);
        mods.set(Modules.CAN, false);   // release the adapter: the service closes it
        buildUi();
        append("CAN link switched off while this screen is open. Wait a second, then press Open port.");
    }

    @Override
    protected void onDestroy() {
        running = false;
        Slcan.Wire w = wire;
        wire = null;
        if (w != null) w.close();
        mods.set(Modules.CAN, canWasOn);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("Serial test: the CANable's own serial port, by hand");
        title.setTextSize(18);
        root.addView(title);
        counter = new TextView(this);
        counter.setText("bytes received: 0");
        root.addView(counter);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            android.widget.CheckBox c = new android.widget.CheckBox(this);
            c.setText(new String[]{"clear halt", "DTR/RTS", "blocking reader"}[i]);
            c.setChecked(k == 0 ? options.clearHalt : k == 1 ? options.dtrRts : options.blockingReader);
            c.setOnCheckedChangeListener((b, on) -> {
                if (k == 0) options.clearHalt = on;
                else if (k == 1) options.dtrRts = on;
                else options.blockingReader = on;
            });
            opts.addView(c);
        }
        root.addView(opts);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(button("Open port", () -> open()));
        r1.addView(button("Close port", () -> closePort()));
        r1.addView(button("Clear log", () -> { text.setLength(0); log.setText(""); }));
        r1.addView(button("Exit DFU", () -> new Thread(() -> append(new UsbWireFactory(this).leaveDfu()), "leave-dfu").start()));
        root.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        for (String c : new String[]{"V", "C", "S3", "O", "L", "F"}) r2.addView(button(c, () -> send(c)));
        root.addView(r2);

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        EditText custom = new EditText(this);
        custom.setHint("any command (a CR is added)");
        custom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        r3.addView(custom, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r3.addView(button("Send", () -> send(custom.getText().toString())));
        root.addView(r3);

        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextIsSelectable(true);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private Button button(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void append(String line) {
        runOnUiThread(() -> {
            text.append(new java.text.SimpleDateFormat("HH:mm:ss.SSS ", java.util.Locale.US).format(new java.util.Date())).append(line).append('\n');
            if (text.length() > 12000) text.delete(0, text.length() - 12000);
            log.setText(text);
            scroll.post(() -> scroll.fullScroll(View_FOCUS_DOWN));
            counter.setText("bytes received: " + bytesIn);
        });
    }

    private static final int View_FOCUS_DOWN = android.view.View.FOCUS_DOWN;

    private void open() {
        if (wire != null) {
            append("already open");
            return;
        }
        new Thread(() -> {
            try {
                Slcan.Wire w = new UsbWireFactory(this).open(options);
                wire = w;
                append("port opened: clear halt " + options.clearHalt + ", DTR/RTS " + options.dtrRts + ", blocking reader " + options.blockingReader);
                startReader(w);
            } catch (IOException e) {
                append("open failed: " + e.getMessage());
            }
        }, "serial-test-open").start();
    }

    private void closePort() {
        Slcan.Wire w = wire;
        wire = null;
        if (w != null) {
            w.close();
            append("port closed");
        }
    }

    private void send(String cmd) {
        final Slcan.Wire w = wire;
        if (w == null) {
            append("port is not open");
            return;
        }
        new Thread(() -> {
            try {
                w.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
                append("> " + cmd + "<CR>");
            } catch (IOException e) {
                append("write failed: " + e.getMessage());
            }
        }, "serial-test-write").start();
    }

    private void startReader(Slcan.Wire w) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1024];
            try {
                while (running && wire == w) {
                    int n = w.read(buf, 200);
                    if (n > 0) {
                        bytesIn += n;
                        append("< " + printable(buf, n));
                    }
                }
            } catch (IOException e) {
                append("read failed: " + e.getMessage());
            }
        }, "serial-test-read");
        t.setDaemon(true);
        t.start();
    }

    private static String printable(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 13) sb.append("<CR>");
            else if (c == 7) sb.append("<BEL>");
            else if (c < 32 || c > 126) sb.append(String.format("<%02X>", c));
            else sb.append((char) c);
        }
        return sb.toString();
    }
}
