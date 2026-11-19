// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import de.wfabia.core.Modules;
import de.wfabia.core.VehiclePipe;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The vehicle pipe, wFabia's side: companion apps (wRight) bind to this service and get the lights and dimmer state that the
 * CAN broker already reads. A plain Binder with Parcels, no AIDL, so the other app needs nothing but the constants of
 * docs/VEHICLE_PIPE.md.
 *
 * The caller is checked on every call: package name and signing certificate must be on the whitelist, and the module switch
 * must be on. Nothing here ever transmits on the CAN bus, and nothing here runs on the CAN reader thread.
 */
public class VehiclePipeService extends Service {
    private static final String TAG = "wfabia.vehicle";

    static final String DESCRIPTOR = "de.wfabia.IVehiclePipe";
    static final String CB_DESCRIPTOR = "de.wfabia.IVehiclePipeCallback";
    static final int VERSION = 1;
    static final int T_HELLO = IBinder.FIRST_CALL_TRANSACTION,        // in: int v, int wants, String pkg, IBinder callback; out: int result, int serverVersion
        T_REQUEST_STATE = T_HELLO + 1,                                 // out: int valid, int lightsOn, int level, long seq
        T_APPLIED = T_HELLO + 2,                                       // in: int display255, int buttonsRgb, int active (oneway, informational)
        T_BYE = T_HELLO + 3,
        T_LOCATION = T_HELLO + 4;                                      // in: int v, int flags (bit0 = has speed), float speedMs, float accuracy, String provider (oneway)
    static final int CB_STATE = IBinder.FIRST_CALL_TRANSACTION;        // in: int v, int valid, int lightsOn, int level, long seq (oneway)
    static final int R_OK = 0, R_REJECTED = 1, R_MODULE_OFF = 2;

    private static final class Client {
        final IBinder cb;
        final String pkg;
        final IBinder.DeathRecipient death;

        Client(IBinder cb, String pkg, IBinder.DeathRecipient death) {
            this.cb = cb;
            this.pkg = pkg;
            this.death = death;
        }
    }

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private StateHub hub;
    private Modules mods;
    private VehicleWhitelist whitelist;
    private final VehiclePipe.Sink sink = this::broadcast;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < T_HELLO || code > T_LOCATION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            String caller = allowedCaller();
            switch (code) {
                case T_HELLO: {
                    int v = data.readInt();
                    int wants = data.readInt();
                    String pkg = data.readString();
                    IBinder cb = data.readStrongBinder();
                    int result = caller == null ? R_REJECTED : (!mods.effective(Modules.VEHICLE) ? R_MODULE_OFF : R_OK);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result);
                        reply.writeInt(VERSION);
                    }
                    if (result == R_OK && cb != null && v >= 1) addClient(cb, caller);
                    return true;
                }
                case T_REQUEST_STATE: {
                    VehiclePipe.State s = caller == null || !mods.effective(Modules.VEHICLE) ? null : hub.vehicle.current();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(s != null && s.valid ? 1 : 0);
                        reply.writeInt(s != null && s.lightsOn ? 1 : 0);
                        reply.writeInt(s == null ? 0 : s.level);
                        reply.writeLong(s == null ? 0 : s.seq);
                    }
                    return true;
                }
                case T_APPLIED: {
                    if (caller != null) {
                        int display = data.readInt(), rgb = data.readInt(), active = data.readInt();
                        hub.vehicleInfo = caller + ": display " + display + ", buttons " + String.format("%06X", rgb & 0xFFFFFF) + (active != 0 ? ", following the dimmer" : ", idle");
                    }
                    return true;
                }
                case T_BYE: {
                    if (caller != null) removeClient(getCallingBinderClient());
                    return true;
                }
                case T_LOCATION: {
                    // wRight's GPS fix: the speed for the cluster. Only from an allowed caller, only while the module is on
                    if (caller != null && mods.effective(Modules.VEHICLE)) {
                        int v = data.readInt();
                        int fl = data.readInt();
                        float speedMs = data.readFloat();
                        float accuracy = data.readFloat();
                        String provider = data.readString();
                        if (v >= 1) hub.speed.onLocation(caller, (fl & 1) != 0, speedMs, accuracy, provider == null ? "?" : provider);
                    }
                    return true;
                }
                default:
                    return false;
            }
        }
    };

    /** a client is identified by its callback binder; BYE removes the callbacks of the calling package */
    private Client getCallingBinderClient() {
        String caller = allowedCaller();
        for (Client c : clients) if (c.pkg.equals(caller)) return c;
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        hub = StateHub.get();
        mods = hub.modules(this);
        whitelist = new VehicleWhitelist(new PrefsStore(this));
        hub.vehicle.addSink(sink);
    }

    @Override
    public void onDestroy() {
        hub.vehicle.removeSink(sink);
        for (Client c : clients) removeClient(c);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    /** the calling package if it is on the whitelist with a matching certificate, else null */
    private String allowedCaller() {
        PackageManager pm = getPackageManager();
        String[] pkgs = pm.getPackagesForUid(Binder.getCallingUid());
        if (pkgs == null) return null;
        for (String p : pkgs) if (whitelist.isAllowed(pm, p)) return p;
        return null;
    }

    private void addClient(IBinder cb, String pkg) {
        for (Client old : clients) if (old.pkg.equals(pkg)) removeClient(old);       // one connection per app, the newest wins
        final Client[] self = new Client[1];
        IBinder.DeathRecipient death = () -> removeClient(self[0]);
        try {
            cb.linkToDeath(death, 0);
        } catch (RemoteException e) {
            return;
        }
        self[0] = new Client(cb, pkg, death);
        clients.add(self[0]);
        hub.vehicleClients = clients.size();
        Log.i(TAG, pkg + " connected");
        sendTo(self[0], hub.vehicle.current());                                       // the current state at once
    }

    private void removeClient(Client c) {
        if (c == null) return;
        if (clients.remove(c)) {
            try {
                c.cb.unlinkToDeath(c.death, 0);
            } catch (RuntimeException ignored) {
                // already gone
            }
            Log.i(TAG, c.pkg + " disconnected");
        }
        hub.vehicleClients = clients.size();
        if (clients.isEmpty()) {
            hub.vehicleInfo = "";
            hub.speed.clear();          // the GPS came from the client that just left
        }
    }

    /** runs on the pipe's own delivery thread, never on the CAN reader */
    private void broadcast(VehiclePipe.State s) {
        for (Client c : clients) sendTo(c, s);
    }

    private void sendTo(Client c, VehiclePipe.State s) {
        boolean on = mods.effective(Modules.VEHICLE);
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(CB_DESCRIPTOR);
            d.writeInt(VERSION);
            d.writeInt(on && s.valid ? 1 : 0);
            d.writeInt(on && s.lightsOn ? 1 : 0);
            d.writeInt(on ? s.level : 0);
            d.writeLong(s.seq);
            c.cb.transact(CB_STATE, d, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            removeClient(c);
        } finally {
            d.recycle();
        }
    }
}
