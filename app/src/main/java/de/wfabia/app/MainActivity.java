// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import de.wfabia.R;
import de.wfabia.core.Modules;
import java.util.ArrayList;
import java.util.List;

/** One screen: the live 2x10 preview and one card per module with its switch, status chip and options. */
public class MainActivity extends AppCompatActivity implements Modules.Listener {

    private static final class Card {
        String key;
        SwitchMaterial sw;
        TextView chip, detail;
        final List<String> subKeys = new ArrayList<>();
        final List<SwitchMaterial> subSwitches = new ArrayList<>();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Card> cards = new ArrayList<>();
    private final TextView[][] cells = new TextView[2][10];
    private TextView previewNote, helpText;
    private EditText allowEdit;
    private SwitchMaterial wrightSwitch;
    private VehicleWhitelist whitelist;
    private Modules mods;
    private StateHub hub;
    private boolean refreshing;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            main.postDelayed(this, 500);
        }
    };

    /** the cells follow the display's own rate (text scrolls at about 5 Hz), the cards the slower poll */
    private final Runnable previewPoll = new Runnable() {
        @Override public void run() {
            refreshPreview();
            main.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hub = StateHub.get();
        mods = hub.modules(this);
        whitelist = new VehicleWhitelist(new PrefsStore(this));
        buildUi();
        ContextCompat.startForegroundService(this, new Intent(this, FabiaService.class));
    }

    @Override protected void onStart() {
        super.onStart();
        mods.addListener(this);
        main.post(poll);
        main.post(previewPoll);
    }

    @Override protected void onStop() {
        main.removeCallbacks(poll);
        main.removeCallbacks(previewPoll);
        super.onStop();
    }

    @Override public void onModulesChanged() { main.post(this::refresh); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    // ------------------------------------------------------------------------------------------------------
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private int color(int res) { return ContextCompat.getColor(this, res); }

    private TextView text(String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("wFabia", 28, R.color.text, true));
        root.addView(text("Zlink tap, Maxidot display, CAN link and adb bridge, each with its own switch.", 14, R.color.muted, false));

        // preview
        LinearLayout panel = panel();
        panel.addView(text("Maxidot preview  (2 × 10 cells)", 14, R.color.muted, false));
        LinearLayout cluster = new LinearLayout(this);
        cluster.setOrientation(LinearLayout.VERTICAL);
        cluster.setBackgroundColor(color(R.color.cluster_bg));
        cluster.setPadding(dp(10), dp(10), dp(10), dp(10));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 10; c++) {
                TextView t = new TextView(this);
                t.setGravity(Gravity.CENTER);
                t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                t.setTextSize(22);
                t.setTextColor(color(R.color.dot));
                t.setBackgroundResource(R.drawable.cell_bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(t, lp);
                cells[r][c] = t;
            }
            cluster.addView(row);
        }
        panel.addView(cluster, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        previewNote = text("", 14, R.color.muted, false);
        panel.addView(previewNote);
        root.addView(panel, margin());

        addCard(root, Modules.ZLINK, "Zlink tap", "Navigation and media from the phone, 127.0.0.1:1555", R.color.g1, false);
        addCard(root, Modules.OHU, "Open Headunit source", "Navigation and media from Open Headunit (with the frame forwarder) instead of Zlink", R.color.g2, false);
        addCard(root, Modules.MSESS, "MediaSession media", "The track that is playing on the head unit now, from any app", R.color.g5, false);
        addCard(root, Modules.DISPLAY, "Maxidot display", "Two rows on the instrument cluster", R.color.g3, false,
            new String[][]{{Modules.NAV, "Navigation"}, {Modules.LANES, "Lanes"}, {Modules.EXIT, "Exit names"}, {Modules.MEDIA, "Media"}, {Modules.SPEED, "GPS speed"}});
        addCard(root, Modules.CAN, "CAN link", "CANable (16D0:117E), comfort bus, 100 kbit/s", R.color.g4, false,
            new String[][]{{Modules.NOTX, "Receive only, but acknowledge frames (sends nothing)"}, {Modules.LISTEN, "Listen only, silent: no ACK, nothing sent"},
                {Modules.DIAG, "Diagnostics: query the adapter (V, F)"}, {Modules.RECOVER, "Auto-recovery: reset / reopen when silent"}});
        Card bridge = addCard(root, Modules.BRIDGE, "adb CAN bridge", "Sniff and send from the PC: adb forward tcp:7788 tcp:7788", R.color.g6, false,
            new String[][]{{Modules.SNIFF, "Sniff stream"}, {Modules.SEND, "Allow send"}});
        LinearLayout allowRow = new LinearLayout(this);
        allowRow.setOrientation(LinearLayout.HORIZONTAL);
        allowEdit = new EditText(this);
        allowEdit.setHint("allowed ids, hex: 5A0, 2E8   (empty = nothing may be sent)");
        allowEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        allowEdit.setTextSize(13);
        allowEdit.setText(mods.allowlistText());
        allowRow.addView(allowEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button apply = new Button(this);
        apply.setText("Apply");
        apply.setOnClickListener(v -> {
            mods.setAllowlist(allowEdit.getText().toString());
            allowEdit.setText(mods.allowlistText());
        });
        allowRow.addView(apply);
        ((LinearLayout) bridge.detail.getParent()).addView(allowRow);
        Card vehicle = addCard(root, Modules.VEHICLE, "Vehicle pipe", "Lights and dimmer level from the CAN bus for wRight. Receive only, nothing is sent", R.color.g2, false);
        wrightSwitch = new SwitchMaterial(this);
        wrightSwitch.setText("Allow wRight (" + VehicleWhitelist.WRIGHT + ")");
        wrightSwitch.setTextSize(14);
        wrightSwitch.setChecked(whitelist.isListed(VehicleWhitelist.WRIGHT));
        wrightSwitch.setOnCheckedChangeListener((b, on) -> {
            if (refreshing) return;
            if (on) {
                if (!whitelist.allow(getPackageManager(), VehicleWhitelist.WRIGHT)) {
                    android.widget.Toast.makeText(this, "wRight is not installed", android.widget.Toast.LENGTH_LONG).show();
                    b.setChecked(false);
                }
            } else {
                whitelist.revoke(VehicleWhitelist.WRIGHT);
            }
        });
        ((LinearLayout) vehicle.detail.getParent()).addView(wrightSwitch);
        addCard(root, "auto", "Automations", "Rules, for example windows up when parked (later)", R.color.g5, true);

        Button askUsb = new Button(this);
        askUsb.setText("Ask for USB permission again");
        askUsb.setOnClickListener(v -> startService(new Intent(this, FabiaService.class).setAction(FabiaService.ACTION_ASK_USB)));
        root.addView(askUsb, margin());

        Button leaveDfu = new Button(this);
        leaveDfu.setText("Exit DFU mode (start the CANable's firmware)");
        leaveDfu.setOnClickListener(v -> new Thread(() -> {
            String msg = new UsbWireFactory(this).leaveDfu();
            runOnUiThread(() -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show());
        }, "leave-dfu").start());
        root.addView(leaveDfu, margin());

        Button serialTest = new Button(this);
        serialTest.setText("Serial test (talk to the CANable by hand)");
        serialTest.setOnClickListener(v -> startActivity(new Intent(this, SerialTestActivity.class)));
        root.addView(serialTest, margin());

        Button stop = new Button(this);
        stop.setText("Stop service");
        stop.setOnClickListener(v -> startService(new Intent(this, FabiaService.class).setAction(FabiaService.ACTION_STOP)));
        root.addView(stop, margin());

        final java.io.File crash = App.crashFile(this);
        if (crash.exists()) {
            String trace;
            try {
                trace = new String(java.nio.file.Files.readAllBytes(crash.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                trace = "unreadable: " + e;
            }
            final TextView t = text("Last crash (send me this):\n" + trace, 11, R.color.bad, false);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextIsSelectable(true);
            root.addView(t, margin());
            Button clear = new Button(this);
            clear.setText("Clear crash report");
            clear.setOnClickListener(v -> {
                crash.delete();
                t.setVisibility(View.GONE);
                clear.setVisibility(View.GONE);
            });
            root.addView(clear, margin());
        }

        helpText = text("PC tool: python wfabia.py candump | cansend ID#DATA | zlink --serve 1555 | log file.jsonl", 12, R.color.muted, false);
        root.addView(helpText, margin());
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        return lp;
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(14), dp(14), dp(14));
        p.setBackgroundColor(color(R.color.surface));
        return p;
    }

    private Card addCard(LinearLayout root, String key, String title, String sub, int accent, boolean planned, String[]... subs) {
        Card card = new Card();
        card.key = key;
        MaterialCardView mc = new MaterialCardView(this);
        mc.setCardBackgroundColor(color(R.color.surface));
        mc.setStrokeColor(color(R.color.line));
        mc.setStrokeWidth(dp(1));
        mc.setRadius(dp(10));
        mc.setCardElevation(0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View stripe = new View(this);
        stripe.setBackgroundColor(color(accent));
        row.addView(stripe, new LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(title, 17, R.color.text, true));
        names.addView(text(sub, 12, R.color.muted, false));
        top.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.chip = text("", 11, R.color.muted, false);
        card.chip.setTypeface(Typeface.MONOSPACE);
        card.chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        top.addView(card.chip);
        card.sw = new SwitchMaterial(this);
        card.sw.setContentDescription(title);
        top.addView(card.sw);
        body.addView(top);

        card.detail = text("", 12, R.color.muted, false);
        card.detail.setTypeface(Typeface.MONOSPACE);
        body.addView(card.detail);

        if (planned) {
            card.sw.setEnabled(false);
        } else {
            card.sw.setOnCheckedChangeListener((b, on) -> {
                if (!refreshing) mods.set(key, on);
            });
        }
        for (String[] s : subs) {
            SwitchMaterial ss = new SwitchMaterial(this);
            ss.setText(s[1]);
            ss.setTextSize(14);
            final String sk = s[0];
            ss.setOnCheckedChangeListener((b, on) -> {
                if (!refreshing) mods.set(sk, on);
            });
            body.addView(ss);
            card.subKeys.add(sk);
            card.subSwitches.add(ss);
        }
        mc.addView(row);
        root.addView(mc, margin());
        cards.add(card);
        return card;
    }

    // ------------------------------------------------------------------------------------------------------
    private String statusOf(Card c) {
        if (c.key.equals("auto")) return "planned";
        if (!mods.wanted(c.key)) return "off";
        String need = mods.blockedBy(c.key);
        if (need != null) return "needs " + need;
        switch (c.key) {
            case Modules.ZLINK: return hub.zlinkUp ? "connected" : "waiting";
            case Modules.OHU: return hub.ohuFrames > 0 && hub.ohuLastMs >= 0 && hub.ohuLastMs < 5000 ? "receiving" : "waiting";
            case Modules.MSESS: return hub.msessInfo.startsWith("playing") ? "playing" : hub.msessInfo.startsWith("needs") ? "needs access" : "listening";
            case Modules.CAN: return hub.canState + "\nrx " + hub.canRx + "  tx " + hub.canTx + "  dropped " + hub.canDropped + "  opened " + hub.canOpens + "x\n" + hub.txInfo + "\nusb: " + hub.usbInfo + (hub.usbEvents.isEmpty() ? "" : "  [" + hub.usbEvents + "]") + "\nadapter: " + hub.adapterInfo + "\n" + hub.lastCan;
            case Modules.DISPLAY: return hub.displayLinked ? "linked" : "not linked";
            case Modules.BRIDGE: return hub.bridgeRunning ? hub.bridgeClients + " client(s)" : "not listening";
            case Modules.VEHICLE: return hub.vehicleClients + " app(s) connected";
            default: return "";
        }
    }

    private String detailOf(Card c) {
        switch (c.key) {
            case Modules.ZLINK: return hub.zlinkState + (hub.zlinkUp ? "  frames " + hub.zlinkFrames + "\n" + hub.navSummary : "");
            case Modules.CAN: return hub.canState + "\nrx " + hub.canRx + "  tx " + hub.canTx + "  dropped " + hub.canDropped + "\n" + hub.lastCan;
            case Modules.OHU: return hub.ohuInfo;
            case Modules.MSESS: return hub.msessInfo;
            case Modules.DISPLAY: return hub.displayState + (mods.effective(Modules.SPEED) ? "\n" + hub.speedInfo : "");
            case Modules.BRIDGE: return hub.bridgeState + (mods.effective(Modules.SEND) ? "\nSEND ARMED (resets to off when the service restarts)" : "");
            case Modules.VEHICLE: {
                de.wfabia.core.VehiclePipe.State s = hub.vehicle.current();
                return (s.valid ? "lights " + (s.lightsOn ? "on, dimmer " + s.level + " %" : "off") : "no illumination signal")
                    + (hub.vehicleInfo.isEmpty() ? "" : "\n" + hub.vehicleInfo);
            }
            default: return "";
        }
    }

    private void refresh() {
        refreshing = true;
        try {
            for (Card c : cards) {
                boolean planned = c.key.equals("auto");
                c.sw.setChecked(!planned && mods.wanted(c.key));
                c.chip.setText(statusOf(c));
                c.detail.setText(detailOf(c));
                c.detail.setVisibility(c.detail.getText().length() == 0 ? View.GONE : View.VISIBLE);
                boolean parentOn = mods.wanted(c.key);
                for (int i = 0; i < c.subKeys.size(); i++) {
                    String k = c.subKeys.get(i);
                    SwitchMaterial s = c.subSwitches.get(i);
                    s.setChecked(mods.wanted(k));
                    s.setEnabled(parentOn && mods.blockedBy(k) == null);
                }
            }
            if (wrightSwitch != null) wrightSwitch.setChecked(whitelist.isListed(VehicleWhitelist.WRIGHT));
            refreshPreview();
        } finally {
            refreshing = false;
        }
    }

    /** the 2x10 cells: what the cluster shows, or, with no cluster linked, what it would show */
    private void refreshPreview() {
        String[] rows = hub.rows;
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 10; c++) {
                char ch = rows[r].length() > c ? rows[r].charAt(c) : ' ';
                cells[r][c].setText(ch == ' ' ? "" : String.valueOf(ch));
            }
        }
        String note;
        if (hub.displayLinked) note = hub.displayState;
        else if (!mods.wanted(Modules.DISPLAY)) note = "Preview only: the display module is off, so the cluster stays blank.";
        else if (mods.blockedBy(Modules.DISPLAY) != null) note = "Preview only: the display module needs " + mods.blockedBy(Modules.DISPLAY) + ", so the cluster stays blank.";
        else note = "Preview only, no cluster linked: " + hub.displayState;
        if (!note.equals(previewNote.getText().toString())) previewNote.setText(note);
    }
}
