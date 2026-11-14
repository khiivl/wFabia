# wFabia

Companion app for the QF K706 head unit (Android 10, Unisoc) in the Škoda Fabia Mk1. It reads the phone's navigation and media
data and puts it on the instrument cluster's Maxidot display (2 × 10 cells), through a CANable on the comfort CAN. One foreground
service, each part with its own switch:

| Module | What it does |
|---|---|
| **Zlink tap** | navigation and media from the head unit's Android Auto app (Zlink, with the `zlink_nav_meta` Magisk patch), `127.0.0.1:1555` |
| **Open Headunit source** | the same data from [Open Headunit](https://github.com/andreknieriem/open-headunit) (a build with the frame forwarder), see [`docs/CONTRACT.md`](docs/CONTRACT.md). Exclusive with Zlink: only one Android Auto receiver is active at a time |
| **MediaSession media** | the track that is playing on the head unit right now, from any app, including Open Headunit's own session |
| **Maxidot display** | the two rows (navigation, lanes, exit names, media, GPS speed) and the cluster link |
| **CAN link** | the one owner of the CANable (SLCAN, 100 kbit/s) |
| **adb CAN bridge** | sniff and send CAN frames from the PC, relay the Android Auto frames |

Spec: [`SPEC.md`](SPEC.md). Java, GPL-3.0-or-later (the cluster link is a port of `maxidot_can`). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant de.wfabia android.permission.ACCESS_FINE_LOCATION
adb shell pm grant de.wfabia android.permission.ACCESS_BACKGROUND_LOCATION     # Android 10: otherwise location stops in the background
adb shell am start -n de.wfabia/.app.MainActivity
```

For the MediaSession source, give wFabia notification access (it needs it to list other apps' media sessions):

```bash
adb shell cmd notification allow_listener de.wfabia/de.wfabia.app.MediaListenerService
```

Tests (JVM, no device): `./gradlew :app:testDebugUnitTest` and `python test_wfabia.py`.

## Choosing the source

Switch **Zlink tap** or **Open Headunit source** on; turning one on turns the other off. The display options (navigation, lanes,
exit names, media) work from either. With Open Headunit as the receiver, Zlink must not be running (Open Headunit can disable
OEM apps like Zlink through its ADB option); Zlink-only parts such as the `zlink_nav_meta` patch are then not needed.

Open Headunit has to be a build that forwards the frames and has wFabia on its whitelist; the card shows what is missing
(not installed, no frames yet, session state). The contract is documented in [`docs/CONTRACT.md`](docs/CONTRACT.md).

## First run on the car

1. Plug the CANable in, then `adb shell dumpsys usb`. Android prints USB ids in decimal: look for `vendor_id=5840` (`16d0`) and `product_id=4478` (`117e`).
2. Open wFabia. The CAN link card shows the adapter state and a frame counter. The first 300 frames go to logcat: `adb logcat -s wfabia:D`.
3. If the adapter shows up as `0483:df11` ("DFU in FS Mode"), it is in the STM32 bootloader. wFabia leaves it by itself; the **Exit DFU mode** button does the same by hand.
4. Cluster: the display card says "linked, session 0x…" and the cluster shows `MAXINAV` / `AKTIV` for a few seconds. After an ignition cycle it links again by itself.

## Using it

- Switches apply at once and persist. Switching a module off releases what it holds.
- Dependencies: display and bridge need CAN; navigation, lanes, exit names need a source (Zlink or Open Headunit) and navigation; media needs a source or the MediaSession.
- **Sending is off by default and resets to off when the service restarts.** It needs the *Allow send* switch **and** an id on the allowlist (empty = nothing can be sent). Every frame sent is logged (`adb logcat -s wfabia.tx`). The cluster link's own traffic is exempt. The send switch and the allowlist can only be changed in the app.
- The speed row shows `0 KM/H` while the GPS speed is 3 km/h or less, or unknown.
- `Serial test` talks to the CANable by hand, with switches for the USB handling variants; `Diagnostics` and `Auto-recovery` are off by default.

## PC tool

```bash
adb forward tcp:7788 tcp:7788
python wfabia.py candump --filter 0x2E8,0x699
python wfabia.py cansend 5A0#01FF
python wfabia.py zlink --serve 1555            # then:  python reference/python/navlive.py --port 1555
python wfabia.py log drive.jsonl               # Android Auto frames in fox_sniff format, CAN as {"t","ch":"can",...}
python wfabia.py status
```

Without the PC tool: `adb shell am broadcast -a de.wfabia.CAN_TX --es id 0x5A0 --es data 01FF` (same switch and allowlist).
The bridge listens on loopback only; any app on the head unit can still connect to it.

## Layout

| Path | |
|---|---|
| `app/src/main/java/de/wfabia/core/` | plain Java, no Android imports, unit-tested: decoder, tracker, feed, renderer, SLCAN, broker, FIS link, display loop, bridge, contract, MediaSession policy |
| `app/src/main/java/de/wfabia/app/` | Android: service, UI, USB, location, the Open Headunit and MediaSession sources |
| `golden/` | **synthetic** captures (made-up routes) and the rows `maxidot_sim.py` produces for them; `GoldenTest` requires the Java renderer to match |
| `golden-private/` | real recordings of real drives: **never committed** (git-ignored); `GoldenTest` runs them when the folder exists |
| `tools/` | `gen_synthetic.py` (writes the captures), `make_expected.py` (asks the Python reference for the expected rows) |
| `reference/` | the Python prototypes and the cluster protocol documentation; the authority when code and spec disagree |
| `docs/CONTRACT.md` | the frame contract for Android Auto receiver apps |

### What the golden tests are

A capture is a recording of the data the phone sends (`0x8006` navigation step, `0x8007` distance and time, `0x20` status, media metadata).
The expected rows are what the Python reference formatter shows on the display for that recording. `GoldenTest` feeds the same
recording through the Java code and requires the same sequence of row pairs, so a change that alters what the cluster shows fails the build.
`SourcesTest` repeats it through the Open Headunit contract path.
