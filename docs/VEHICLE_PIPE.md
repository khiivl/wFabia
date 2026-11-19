# Vehicle pipe: wFabia -> companion apps

Version 1. wFabia is the only app that talks to the CANable. The vehicle pipe hands what it already reads on the comfort CAN to
a companion app (wRight) without that app touching USB or the bus.

**Receive only.** The pipe adds no CAN transmit and no USB call. The CAN reader thread only feeds a small filter; delivery to
clients runs on a separate thread with at most one delivery queued, so a slow or dead client cannot slow the USB read loop.

## What it carries
The instrument illumination of the car (CAN `0x470` byte 2, fallback `0x635` byte 0 until a `0x470` frame has been seen), cleaned up:

| field | meaning |
|---|---|
| `valid` | the CAN link is up and illumination frames are arriving. When false, ignore the rest |
| `lightsOn` | the light switch is on (the signal is `0` with the lights off or the ignition off) |
| `level` | `0` with the lights off, else `34..100`: the dimmer wheel, 34 darkest, 100 brightest |
| `seq` | counter of published states |

There is no time window, so the only delay is one CAN frame (50 ms). `lightsOn` follows the first non-zero / zero sample. `level`
rises at once; a drop is taken one frame later, and only if the signal has not started rising again, because the signal carries a
ripple that dips below the true level and recovers upward within about 450 ms (the dip and its recovery ramp are ignored, a real
move of the wheel keeps going down or stays down). A state change is published at once, at most one per 40 ms, and a state is sent at
least every 2 s as a keepalive. A client that has seen no state for 6 s must treat the pipe as invalid.

## Access
- Module switch **Vehicle pipe** in wFabia (needs the CAN link). Off by default.
- **Whitelist**, empty by default: an app is identified by package name and the SHA-256 digest of its signing certificate. The card in
  wFabia has a one-tap switch for `com.radiorubka.wright`. A package replaced by an app with another key gets nothing.
- Every call is checked with `Binder.getCallingUid()`. A caller that is not allowed gets `R_REJECTED`.

## The binder
`bindService(new Intent("de.wfabia.action.VEHICLE_PIPE").setPackage("de.wfabia"), ...)`. Plain `Binder` and `Parcel`, no AIDL.

Service, descriptor `de.wfabia.IVehiclePipe`, codes from `IBinder.FIRST_CALL_TRANSACTION` (1):

| code | name | in | out |
|---|---|---|---|
| 1 | `HELLO` | `int v` (1), `int wants` (bit0 = dimmer), `String pkg`, `IBinder callback` | `int result` (0 ok, 1 rejected, 2 module off), `int serverVersion` |
| 2 | `REQUEST_STATE` | | `int valid, int lightsOn, int level, long seq` |
| 3 | `APPLIED` (oneway, informational) | `int display255, int buttonsRgb, int active` | |
| 4 | `BYE` | | |
| 5 | `LOCATION` (oneway) | `int v` (1), `int flags` (bit0 = has speed), `float speedMs`, `float accuracy` (-1 unknown), `String provider` | |

`LOCATION` goes the other way: wRight is the only app that asks the head unit's location service for fixes, and passes the speed on
at the fix rate (about 1 Hz). wFabia uses it for the speed row of the cluster display and holds no location registration or
permission of its own. It is accepted only from an allowed caller while the vehicle module is on, and a speed older than 5 s counts as
unknown.

Callback, descriptor `de.wfabia.IVehiclePipeCallback`, implemented by the client (`Binder.onTransact`), oneway:

| code | name | in |
|---|---|---|
| 1 | `STATE` | `int v, int valid, int lightsOn, int level, long seq` |

After a successful `HELLO` the current state is sent at once. The client should check `Binder.getCallingUid()` of `STATE` against the uid
of `de.wfabia`. Re-bind with exponential backoff when wFabia is not running. New fields are appended to the parcels; a breaking change
bumps `v`.
