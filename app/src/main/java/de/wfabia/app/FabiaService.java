// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import de.wfabia.R;
import de.wfabia.core.BridgeServer;
import de.wfabia.core.CanBroker;
import de.wfabia.core.CanFrame;
import de.wfabia.core.DisplayController;
import de.wfabia.core.Hex;
import de.wfabia.core.Modules;
import de.wfabia.core.NavModel;
import de.wfabia.core.TxGate;
import de.wfabia.core.ZlinkClient;
import java.io.IOException;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns all the work: Zlink tap, CAN broker, Maxidot display, adb bridge. The activity only shows state and flips switches. */
public class FabiaService extends Service {
    private static final String TAG = "wfabia";
    static final String ACTION_STOP = "de.wfabia.STOP";
    static final String ACTION_CAN_TX = "de.wfabia.CAN_TX";
    /** the head unit's ignition (accessory) line, broadcast by the vendor firmware, as wRight also listens to it */
    static final String ACTION_ACC_ON = "com.qf.action.ACC_ON";
    static final String ACTION_ACC_OFF = "com.qf.action.ACC_OFF";
    static final String ACTION_USB_PERMISSION = "de.wfabia.USB_PERMISSION";
    static final String ACTION_ASK_USB = "de.wfabia.ASK_USB";
    static final int BRIDGE_PORT = 7788;
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL = "wfabia";

    private StateHub hub;
    private Modules mods;
    private NavModel nav;
    private CanBroker broker;
    private ZlinkClient zlink;
    private TxGate gate;
    private BridgeServer bridge;
    private DisplayController display;
    private LocationSpeedSource speed;
    private UsbWireFactory usb;
    private OpenHuSource ohu;
    private MediaSessionSource msess;
    private HandlerThread ctlThread;
    private Handler ctl;
    /** false between ACC_OFF and ACC_ON: the CAN adapter is closed and the display is off, so nothing is sent while the head unit sleeps */
    private boolean accOn = true;
    private boolean zlinkRunning, canRunning, displayRunning, bridgeRunning, speedRunning, ohuRunning, msessRunning;
    private String lastNotif = "";
    private int loggedFrames;
    private int usbEventCount;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        hub = StateHub.get();
        mods = hub.modules(this);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));
        // a location foreground service may read the location in the background on Android 10 (the speed row needs it)
        startForeground(NOTIF_ID, notification("starting"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        ctlThread = new HandlerThread("wfabia-control");
        ctlThread.start();
        ctl = new Handler(ctlThread.getLooper());

        nav = new NavModel();
        usb = new UsbWireFactory(this);
        broker = new CanBroker(usb, new CanBroker.Status() {
            @Override public void onLink(boolean up, String detail) {
                hub.canUp = up;
                // closing the adapter for ACC_OFF is not a lost signal: the companion app (wRight) gets the last state until it goes
                // stale on its own, not an instant "invalid" that it could take for the lights going off before it has seen ACC_OFF
                if (up || hub.accOn) hub.vehicle.setCanUp(up);
                hub.canState = up ? ("open".equals(detail) ? "adapter open, 100 kbit/s" : "adapter open: " + detail) : detail;
                Log.i(TAG, "CAN link " + (up ? "up" : "down: " + detail));
            }

            @Override public void onDropped(long total) { hub.canDropped = total; }
        });
        // hardware proof: the first frames of every session go to logcat
        broker.subscribe(f -> {
            hub.lastCan = f.toString();
            if (loggedFrames < 300) {
                loggedFrames++;
                Log.d(TAG, "can " + f);
            }
        }, null, true, false);
        // the instrument illumination (lights and dimmer wheel) for the vehicle pipe: receive only, no extra USB traffic
        broker.subscribe(hub.vehicle::onFrame, new java.util.HashSet<>(java.util.Arrays.asList(
            de.wfabia.core.VehiclePipe.ID_ILLUM, de.wfabia.core.VehiclePipe.ID_ILLUM_ALT)), true, false);

        gate = new TxGate(broker, new TxGate.Policy() {
            @Override public boolean sendEnabled() { return mods.effective(Modules.SEND); }

            @Override public Set<Integer> allowlist() { return mods.allowlist(); }
        });
        bridge = new BridgeServer(broker, gate, new BridgeServer.Host() {
            @Override public boolean sniffEnabled() { return mods.effective(Modules.SNIFF); }

            @Override public boolean setModule(String name, boolean enabled) {
                // the send switch and its allowlist can only be changed in the app, never from the PC
                if (name.equals(Modules.SEND)) return false;
                for (String k : new String[]{Modules.ZLINK, Modules.CAN, Modules.DISPLAY, Modules.BRIDGE, Modules.NAV, Modules.LANES,
                    Modules.EXIT, Modules.MEDIA, Modules.SPEED, Modules.SNIFF, Modules.LISTEN, Modules.NOTX, Modules.DIAG, Modules.RECOVER, Modules.OHU, Modules.MSESS}) {
                    if (k.equals(name)) {
                        mods.set(name, enabled);
                        return true;
                    }
                }
                return false;
            }

            @Override public String statusJson() { return status(); }
        });
        zlink = new ZlinkClient("127.0.0.1", 1555, 3000, new ZlinkClient.Listener() {
            @Override public void onFrame(int type, byte[] payload) {
                nav.feed(type, payload);
                bridge.publishZlink(type, payload);
            }

            @Override public void onState(boolean connected, String detail) {
                hub.zlinkUp = connected;
                hub.zlinkState = detail;
                if (!connected) nav.reset();   // stale steps must not stay on the cluster
            }
        });
        ohu = new OpenHuSource(this, nav, bridge);
        msess = new MediaSessionSource(this, nav);
        speed = new LocationSpeedSource(this);
        display = new DisplayController(broker, nav, mods::options, speed,
            rows -> hub.rows = rows,
            s -> {
                hub.displayState = s;
                hub.displayLinked = s.startsWith("linked");
            }, 3.0);

        mods.addListener(() -> ctl.post(this::apply));
        registerReceiver(canTxReceiver, new IntentFilter(ACTION_CAN_TX));
        IntentFilter acc = new IntentFilter(ACTION_ACC_ON);
        acc.addAction(ACTION_ACC_OFF);
        registerReceiver(accReceiver, acc);
        // the CANable leaving and coming back (Zlink resets the USB bus when it starts): ask for permission again at once
        IntentFilter usbEvents = new IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbEvents.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                android.hardware.usb.UsbDevice d = i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
                if (d == null || d.getVendorId() != de.wfabia.core.Slcan.CANABLE_VID || d.getProductId() != de.wfabia.core.Slcan.CANABLE_PID) return;
                boolean attached = android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction());
                hub.usbEvents = (attached ? "attached " : "detached ") + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + " (" + (++usbEventCount) + " events)";
                android.util.Log.i(TAG, "CANable " + (attached ? "attached" : "detached"));
                if (attached) usb.requestPermissionNow();
            }
        }, usbEvents);
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                usb.onPermissionResult(i.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false));
            }
        }, new IntentFilter(ACTION_USB_PERMISSION));
        hub.serviceRunning = true;
        ctl.post(this::apply);
        ctl.postDelayed(ticker, 1000);
        ctl.post(previewTicker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ASK_USB.equals(intent.getAction())) usb.requestPermissionNow();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ctl.post(this::apply);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hub.serviceRunning = false;
        hub.vehicle.setCanUp(false);
        try { unregisterReceiver(canTxReceiver); } catch (IllegalArgumentException ignored) { /* not registered */ }
        try { unregisterReceiver(accReceiver); } catch (IllegalArgumentException ignored) { /* not registered */ }
        ctl.removeCallbacksAndMessages(null);
        ctlThread.quitSafely();
        display.stop();
        bridge.stop();
        zlink.stop();
        ohu.stop();
        msess.stop();
        speed.stop();
        broker.stop();
        hub.zlinkState = hub.canState = hub.displayState = hub.bridgeState = "off";
        hub.zlinkUp = hub.canUp = hub.displayLinked = hub.bridgeRunning = false;
        super.onDestroy();
    }

    /** Brings the running parts in line with the switches. Dependents stop first and start last. */
    private void apply() {
        // ACC_OFF is the signal to shut everything down: the head unit goes into deep sleep. Nothing runs until ACC_ON.
        boolean wantCan = mods.effective(Modules.CAN) && accOn, wantZlink = mods.effective(Modules.ZLINK) && accOn,
            wantDisplay = mods.effective(Modules.DISPLAY) && accOn, wantBridge = mods.effective(Modules.BRIDGE) && accOn,
            wantSpeed = mods.effective(Modules.SPEED) && accOn, wantOhu = mods.effective(Modules.OHU) && accOn,
            wantMsess = mods.effective(Modules.MSESS) && accOn;

        if (!wantDisplay && displayRunning) { display.stop(); displayRunning = false; hub.displayLinked = false; if (!accOn) hub.displayState = "paused (ACC off)"; }
        if (!wantBridge && bridgeRunning) { bridge.stop(); bridgeRunning = false; hub.bridgeRunning = false; hub.bridgeState = accOn ? "off" : "paused (ACC off)"; }
        if (!wantCan && canRunning) { broker.stop(); canRunning = false; hub.canState = accOn ? "off" : "paused (ACC off)"; hub.canUp = false; }
        if (!wantZlink && zlinkRunning) { zlink.stop(); zlinkRunning = false; nav.reset(); hub.zlinkState = accOn ? "off" : "paused (ACC off)"; hub.zlinkUp = false; }
        if (!wantSpeed && speedRunning) { speed.stop(); speedRunning = false; }
        if (!wantOhu && ohuRunning) { ohu.stop(); ohuRunning = false; nav.reset(); }
        if (!wantMsess && msessRunning) { msess.stop(); msessRunning = false; }

        broker.setListenOnly(mods.wanted(Modules.LISTEN));
        broker.setNoTx(mods.wanted(Modules.NOTX));
        broker.setDiag(mods.wanted(Modules.DIAG));
        broker.setRecover(mods.wanted(Modules.RECOVER));
        if (wantCan && !canRunning) { broker.start(); canRunning = true; hub.canState = "opening the adapter"; }
        if (wantZlink && !zlinkRunning) { zlink.start(); zlinkRunning = true; hub.zlinkState = "connecting"; }
        if (wantDisplay && !displayRunning) { display.start(); displayRunning = true; }
        if (wantBridge && !bridgeRunning) {
            try {
                bridge.start(BRIDGE_PORT);
                bridgeRunning = true;
                hub.bridgeRunning = true;
                hub.bridgeState = "listening on 127.0.0.1:" + BRIDGE_PORT;
            } catch (IOException e) {
                hub.bridgeState = "cannot listen: " + e.getMessage();
            }
        }
        if (wantSpeed && !speedRunning) { speed.start(); speedRunning = speed.isRunning(); }
        if (wantOhu && !ohuRunning) { nav.reset(); ohu.start(); ohuRunning = true; }
        if (wantMsess && !msessRunning) { msess.start(); msessRunning = true; }
        updateNotification();
    }

    private final BroadcastReceiver accReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final boolean on = ACTION_ACC_ON.equals(intent.getAction());
            Log.i(TAG, "ACC " + (on ? "on" : "off"));
            ctl.post(() -> {
                accOn = on;
                hub.accOn = on;
                apply();
                if (on && tickerIdle) { tickerIdle = false; ctl.post(ticker); }
                if (on && previewIdle) { previewIdle = false; ctl.post(previewTicker); }
            });
        }
    };

    /** the periodic status updates stop with ACC_OFF and start again with ACC_ON (both on the control thread) */
    private boolean tickerIdle, previewIdle;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!accOn) { tickerIdle = true; return; }
            hub.canRx = broker.rxCount();
            hub.canTx = broker.txCount();
            hub.canDropped = broker.droppedCount();
            hub.canOpens = broker.generation();
            hub.adapterInfo = broker.adapterInfo();
            hub.speedInfo = speed.info();
            hub.usbInfo = usb.info;
            hub.txInfo = broker.txInfo();
            hub.zlinkFrames = zlink.frames();
            hub.bridgeClients = bridge.clientCount();
            hub.navSummary = nav.summary();
            hub.ohuInfo = ohu.info();
            hub.ohuFrames = ohu.frames();
            hub.ohuLastMs = ohu.lastMs();
            hub.msessInfo = msess.info();
            hub.vehicle.tick();
            if (wantSpeedButNotRunning()) { speed.start(); speedRunning = speed.isRunning(); }   // permission may have been granted since
            updateNotification();
            ctl.postDelayed(this, 1000);
        }
    };

    /**
     * While a cluster is linked, the display controller publishes the rows it sends. Otherwise (no CANable, cluster off, display
     * module off) the preview shows what the cluster would show, at the controller's rate, so the app can be tested on a phone.
     */
    private final Runnable previewTicker = new Runnable() {
        @Override public void run() {
            if (!accOn) { previewIdle = true; return; }
            if (!hub.displayLinked) {
                try {
                    hub.rows = DisplayController.render(nav, mods.previewOptions(), speed);
                } catch (RuntimeException e) {
                    Log.w(TAG, "preview: " + e);
                }
            }
            ctl.postDelayed(this, 200);
        }
    };

    private boolean wantSpeedButNotRunning() { return mods.effective(Modules.SPEED) && accOn && !speedRunning; }

    private final BroadcastReceiver canTxReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra("id"), data = intent.getStringExtra("data");
            if (id == null) {
                Log.w(TAG, "CAN_TX without id");
                return;
            }
            try {
                String why = gate.submit("broadcast", Hex.parseId(id), Hex.parse(data == null ? "" : data), intent.getLongExtra("ttl_ms", 500));
                Log.i(TAG, "CAN_TX " + id + " " + data + " -> " + (why == null ? "queued" : "refused: " + why));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "CAN_TX bad frame: " + e.getMessage());
            }
        }
    };

    private String status() {
        try {
            JSONObject o = new JSONObject();
            for (String k : new String[]{Modules.ZLINK, Modules.CAN, Modules.DISPLAY, Modules.BRIDGE, Modules.NAV, Modules.LANES,
                Modules.EXIT, Modules.MEDIA, Modules.SPEED, Modules.SNIFF, Modules.SEND}) {
                o.put(k, mods.effective(k));
            }
            o.put("can_up", hub.canUp).put("can_state", hub.canState).put("zlink_up", hub.zlinkUp).put("display", hub.displayState)
                .put("can_rx", broker.rxCount()).put("can_tx", broker.txCount()).put("can_dropped", broker.droppedCount());
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private void updateNotification() {
        String text = "zlink " + dot(hub.zlinkUp) + "  can " + dot(hub.canUp) + "  display " + dot(hub.displayLinked)
            + "  bridge " + dot(hub.bridgeRunning) + (mods.effective(Modules.SEND) ? "  SEND ARMED" : "");
        if (text.equals(lastNotif)) return;
        lastNotif = text;
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, notification(text));
    }

    private static String dot(boolean on) { return on ? "●" : "○"; }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FabiaService.class).setAction(ACTION_STOP), 0);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("wFabia")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build();
    }
}
