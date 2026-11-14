# wFabia: build spec

You are building **wFabia**, an Android app for one specific car head unit. You have no other context, so this file
and the folders next to it are everything. Read this first, then `reference/`, then build in the order in section 9.

## 1. What it is

A companion app that runs on the head unit of a Škoda Fabia Mk1 and does four things, each with its own on/off switch:

1. **Zlink tap.** Read phone navigation and media metadata from the Zlink app (Android Auto bridge) over a local TCP socket.
2. **Maxidot display.** Turn that data plus GPS speed into two 10-character rows and show them on the car's instrument
   cluster ("Maxidot", a 2x10 dot display) by talking CAN through a USB CAN adapter (CANable, SLCAN).
3. **CAN link + broker.** The one owner of the CAN adapter. Everything else goes through it.
4. **adb CAN bridge.** A local socket that a PC reaches with `adb forward`, to sniff and send CAN frames and to stream raw
   Zlink frames, with the adapter staying plugged into the head unit.

Later (not in v1): rule-based automations (for example "close windows when parked"). Design the broker so rules can be added.

The author is Volodymyr. He already has Python prototypes of everything. **Your main job is a faithful Java port plus Android
plumbing and a UI**, not new design.

## 2. Environment (facts, not assumptions)

- Head unit: "QF K706", Android, **rooted (Magisk)**, vendor packages `com.qf.*`. minSdk/targetSdk 29 is what his sibling app wDSP uses; use 29.
- Language: **Java**, Android Studio, Gradle. His other apps (wDSP, wRight) are Java with Material Components. Keep it Java.
- The Zlink metadata socket is provided by a Magisk module that patches Zlink: it listens on **TCP 127.0.0.1:1555**, accepts
  **only one client**, and pushes frames. wFabia must be that one client.
- CAN adapter: **CANable with SLCAN firmware**, USB `16D0:117E` (CDC ACM serial), CAN **100 kbit/s**, normal (acknowledging)
  mode, its 120 ohm termination **off**. It sits on the car's **comfort CAN** (low-speed fault-tolerant) at the cluster connector.
- The real factory radio is not on that bus. wFabia plays the radio toward the cluster.
- Unverified: whether the K706's USB port is a usable host that powers the CANable. The first milestone proves it (section 9).
  Use `usb-serial-for-android` (mik3y, MIT) for the CDC ACM device; request USB permission and add a `USB_DEVICE_ATTACHED`
  intent filter with a device filter for 16D0:117E so it can start automatically.

## 3. Reference implementations (the authority)

Python, in `reference/`. Port these; where this document and the code disagree, **the code wins** and tell the author.

| Python | Java class (suggested) | Job |
|---|---|---|
| `python/navdecode.py` | `ZlinkDecoder`, `NavTracker`, `Proto` | frame + protobuf decode, step/progress state, `road_ref()` |
| `python/maxidot_sim.py` | `MaxidotRenderer`, `Feed`, `Marquee`, `MediaOverlay` | state -> two 10-char rows (all display rules) |
| `python/fox_sniff.py` | part of `ZlinkClient` | only `wire()` (protobuf field iterator) and `recvn()` are needed |
| `python/gps_speed.py` | `SpeedSource` | stub, see section 7 |
| `python/navlive.py` | not ported | PC-side debug viewer; it should work against the bridge's `zlink` channel |
| `maxidot_can/link.py`, `transport.py`, `slcan.py`, `font.py` | `FisLink`, `FisTransport`, `Slcan`, `MaxidotFont` | cluster protocol, SLCAN adapter, character encoding |
| `maxidot_can/PROTOCOL.md`, `FONT.md` | | protocol and font documentation, read in full |

License: `maxidot_can/` is GPL-3.0-or-later (the author's project, see LICENSE). A port is a derivative; keep the notices and make wFabia GPL-3.0-or-later.

## 4. Zlink frames (what the tap decodes)

Stream on TCP 1555: repeated frames `{u32 LE type, u32 LE length}` + protobuf payload.

| type | meaning |
|---|---|
| `0x20` | NaviStatus: 1 active, 2 inactive, **3 = transient during reroutes, ignore it** |
| `0x8006` | NavigationState: step{maneuver{type, exit, angle}, road{name}, lanes*, cue{text}}, destination |
| `0x8007` | NavigationProgress: next{dist{metres, text, unit}, seconds}, trip{dist, ETA text, seconds} |
| `4` | MediaPlaybackStatus (state, source app) |
| `5` | MediaPlaybackMetadata: 1 song, 2 artist, 3 album, 4 album-art PNG (ignore), 6 duration |

Facts that took real captures to learn (all encoded in `navdecode.py`, listed here so you do not "fix" them):

- Maneuver wire code = androidx Car App `Maneuver.TYPE_*` integer, codes 1..50 (30 and 31 do not exist). 0 is never forwarded.
- Distance units 1..7 = m, km, km 1 decimal, mi, mi 1 decimal, ft, yd. A missing `metres` field means 0.
- Lane shape on the wire = androidx `LaneDirection` shape minus 1. Verified on the road: 1 straight, 3 slight right, 4 left,
  5 right, 8 u-turn left. Shapes 2, 6, 7, 9 are the library's rule, never seen. `highlighted` marks the recommended lane. Lanes are ordered left to right.
- `exit` and `angle` stay on the wire on later non-roundabout steps (stale). Trust only the roundabout info the tracker derives for roundabout codes; use `angle` only for codes 33 and 35.
- Lanes come only from Google Maps, and only on about 5 of ~90 steps. Other apps never send them.
- The phone repeats identical metadata often. Dedupe.
- After a step change, hide the distance until the first `0x8007` for the new step (otherwise the previous step's distance flashes).

## 5. Display rules (implemented in `maxidot_sim.py::screen()`; port exactly)

Font: 10 cells per row, no lowercase, no diagonal arrows. `to_font()` uppercases, maps Ä Ö Ü, ß->SS, strips accents, unknown -> `?`.

- Row 1: centered block of `glyph + distance` and the **road number only** (A9, B180; never street names). Gap 2 spaces if it fits, else 1, else drop the number.
- Row 2: lanes if present (recommended lane in parentheses, lanes left to right, centered); else the motorway exit/junction name **only when within 2000 m**; else GPS speed.
- Speed field is fixed 3-digit width then ` KM/H`, centered: `'  87 KM/H '`, `' 130 KM/H '`.
- Navigation inactive or no step: row 1 blank, speed on row 2.
- Media override: show artist (row 1) and title (row 2), centered if it fits else scrolling once start to end, **only if the next turn is more than 2000 m away**. Any new step dismisses it immediately. It also ends when the scroll finishes. Constants: `MEDIA_MIN_M=2000, MEDIA_RATE=2.5, MEDIA_PAUSE=1.5, MEDIA_HOLD=1.5, MEDIA_MIN_S=4.0`.
- Distance format: <100 m in 10 m steps, <1000 m in 50 m steps, <9950 m one-decimal km (`1.2K`), else whole km.
- Glyph orderings the author chose (keep): slight/keep/ramp left `←\`, right `/→`; sharp left `↓←`, right `→↓`; u-turn `U←` / `→U`; lanes sorted left to right within a lane by `LANE_ORDER`.
- The cluster refreshes at about 4 Hz at most. Redraw at 5 Hz while text scrolls, otherwise only on change.

## 6. Cluster protocol (summary; PROTOCOL.md is the full text)

- Heartbeat: CAN id `0x439`, data `00 01 00 00 00 00`, every 230 ms, for as long as the display is enabled. Stop it and the cluster blanks.
- Wait ~3 s, then register: init message, then the `Radiodaten` message; the cluster returns a **session byte** that must be in every content message. Re-register after any error or adapter loss.
- Transport is a TP 2.0 style session: setup `0x4D9`/`0x2E8`, parameters, 7-byte data packets with opcodes 2/0/1, acks, the cluster's own reply message. Persistent connection, 3 ms packet gap works. One message per ~250 ms; never pipeline.
- Content message is 37 bytes: rows are 10 bytes each at offsets 9 and 26; every other byte is validated by the cluster.

## 7. Speed source (open item)

`gps_speed.py` is a stub returning `None`. The head unit has GPS. Preferred order: `LocationManager` (needs location permission) with
`Location.getSpeed()` in m/s times 3.6; the app is on the head unit, not the phone. Make `SpeedSource` an interface so a CAN or MCU speed
can be added. If speed is unavailable the speed field is blank. Phone navigation does not provide a speed limit.

## 8. Architecture and requirements

```
Zlink :1555 -> ZlinkClient -> NavTracker/Feed -> MaxidotRenderer(+SpeedSource) -> FisLink -> CanBroker -> Slcan(USB) -> comfort CAN
                  \-> raw frames ----------------------------> BridgeServer (:7788) <-> adb forward <-> PC
CanBroker rx fan-out -> FisLink (ids 0x2E8, 0x699, 0x40C) | BridgeServer (filter) | RuleEngine (later)
```

Requirements (R = must):

- R1 One **foreground service** (`FabiaService`) owns all work. The activity only shows state and flips switches. Start on boot and on USB attach.
- R2 **Modules** with persisted switches (SharedPreferences), changeable at runtime without restart: `zlink`, `can`, `display`, `bridge`, and display
  sub-options `nav`, `lanes`, `exit`, `media`, `speed`; bridge sub-options `sniff`, `send`. Dependencies: display and bridge need `can`; nav, lanes, exit and media need `zlink`; lanes and exit need `nav`.
  Switching a module off must release what it holds (close socket, stop heartbeat, close USB).
- R3 **CanBroker**: single reader thread (parse SLCAN lines, fan frames out to subscribers with optional id filters, ring buffer of the last 4096 frames);
  single writer thread with three priorities: heartbeat > FIS packets > everything else. Non-FIS frames are sent between FIS messages, never inside one.
  If the queue is full, drop non-FIS frames and report it, never the heartbeat. Reopen the adapter every 3 s after loss and re-register with the cluster.
- R4 **BridgeServer** on `127.0.0.1:7788` (loopback only), one JSON object per line.
  Client to app: `{"op":"sub","ch":["can","zlink"],"can_ids":["0x2E8"]}`, `{"op":"tx","id":"0x5A0","d":"01FF","ttl_ms":500}`, `{"op":"module","name":"display","enabled":false}`, `{"op":"status"}`.
  App to client: `{"ch":"can","t":<unix s>,"dir":"rx|tx","id":"0x40C","d":"19000000"}`, `{"ch":"zlink","t":..,"type":"0x8006","raw":"<hex>"}`, `{"ch":"ctl","ev":"tx_refused","why":"send_disabled"}`.
  Also a broadcast `de.wfabia.CAN_TX` (extras `id`, `data`) under the same send switch.
- R5 **Sending is off by default and resets to off on service restart.** It needs the `send` switch plus an ID allowlist (empty = nothing allowed). The FIS link's own frames are exempt. Log every sent frame.
- R6 The bridge's `zlink` channel relays raw frames so the PC debug tool `navlive.py` can keep working while the display runs. `navlive.py` expects the original binary framing, so `wfabia.py zlink --serve 1555` must turn the JSON `zlink` channel back into `{u32 type, u32 len}+payload` frames on a local port (then `adb forward` is not needed for that tool, just point navlive at it) (the 1555 socket allows one client, wFabia is it). A recorder to a `.jsonl` in the same format `navlive.py --jsonl` writes is a nice-to-have.
- R7 A **PC tool** `wfabia.py` (about 100 lines, stdlib only): `candump [--filter ids]`, `cansend ID#DATA`, `zlink [--serve PORT]` (raw to stdout, or re-serve the original binary framing for navlive), `log file.jsonl`. Document `adb forward tcp:7788 tcp:7788`.
- R8 Everything the service does shows in a one-line foreground notification (module states, link status).

## 9. Build order and acceptance

1. **Hardware proof.** Minimal app: open the CANable with usb-serial-for-android, log SLCAN frames to logcat. Done when frames from the car (or `fake_cluster.py` logic) appear. If USB host does not work, stop and report.
2. **CanBroker + BridgeServer + `wfabia.py`** (candump/cansend). Done when a PC sees live frames and a send is refused until enabled.
3. **FIS link.** Heartbeat, registration, `showRows(String,String)`. Done when fixed text shows on the cluster (or passes against `maxidot_can/fake_cluster.py` ported to a unit test: reply codes `0x27` accepted, `0x2B` rejected).
4. **ZlinkDecoder + NavTracker + Renderer.** Done when the golden tests pass (below).
5. **UI.** One screen, module cards with switches and status chips, display sub-options, live 2x10 preview. Visual target: `design/wfabia_concept.html` (open in a browser; it is interactive). Match wDSP's theme: the author will supply wDSP's `colors.xml`/`themes.xml`; until then use theme attributes and `ContextCompat.getColor()` at runtime, never hardcoded hex, so day and night themes both work. Module accent colours follow wDSP's six-colour red-to-teal group scheme.
6. **Boot start, USB attach, reconnects, settings persistence.**
7. (Later) rule engine.

### Golden tests (step 4)

`golden/captures/*` are real recordings (`.jsonl` with timestamps and `raw_hex` frames, or `.bin` at one frame per second).
`golden/expected/<capture>.rows.txt` is the output of `python maxidot_sim.py --replay <capture> --plain --gps 87`: one line per changed screen,
`'<row1>' '<row2>'   <- <debug info>`. Your Java renderer, fed the same frames with a fixed 87 km/h and no clock, must produce **the same sequence of row pairs**
(compare the two quoted strings; ignore the debug info after `<-`). Write a JUnit test that reads each capture, runs it, and diffs.
Caveat: lines that come from the media scroll (`run1.bin`, `m.bin`, `m2.bin`) depend on the time at which the scroll is sampled; compare those only for the static screens (first frame of each track) and verify scrolling separately by reasoning from `media_cells`.

Verified-on-road facts also covered by the goldens: `gmaps.jsonl` has real lane steps; `herewego`, `organic`, `osmand` have roundabouts with stale exit fields.

## 10. Do not

- Do not "improve" decoding, maneuver numbering, lane mapping or display rules. They encode captured reality. Report discrepancies instead.
- Do not send anything on the CAN bus that is not (a) FIS link traffic or (b) explicitly enabled user sends.
- Do not open the Zlink socket from more than one place.
- Do not hardcode colours. Do not add frameworks beyond AndroidX, Material Components and usb-serial-for-android.

## 11. Questions to ask the author when blocked

- wDSP / wRight `colors.xml`, `themes.xml` (for the visual match).
- Whether `adb shell dumpsys usb` lists `16d0:117e` with the CANable plugged into the head unit.
- Which location source to trust for speed on the K706.
