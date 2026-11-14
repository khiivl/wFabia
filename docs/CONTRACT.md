# Frame contract: Android Auto receiver app -> companion apps

Version 1. This is the interface between an app that terminates Android Auto on the head unit (Open Headunit, or a fork)
and any companion app that wants the phone's **navigation** and **media** data (wFabia is one such app).

The sender stays universal: it forwards the Android Auto protobuf messages **unchanged**. All parsing happens in the
receiving app. A new receiving app needs no change in the sender except a line in the sender's whitelist.

## The broadcast

An explicit broadcast (`setPackage`) to every whitelisted receiving app, once per message:

| | |
|---|---|
| action | `com.andrerinas.openheadunit.AAP_FRAME` |
| `v` | `int`, contract version. `1` |
| `channel` | `String`, `"nav"` or `"media"`. The Android Auto message ids repeat per channel, so the channel is needed |
| `msg` | `int`, the Android Auto message id as on the wire |
| `payload` | `byte[]`, the protobuf payload of that message, unchanged |
| `seq` | `long`, a counter that runs up by one per forwarded message, so a receiver can see gaps |

A receiver ignores frames with a `v` higher than it knows, and ignores `channel`/`msg` combinations it does not use.

### Messages forwarded

| channel | msg | Android Auto message | payload |
|---|---|---|---|
| `nav` | 32769 | instrument cluster start | |
| `nav` | 32770 | instrument cluster stop | |
| `nav` | 32771 | navigation status | `{1: status}`: 0 unavailable, 1 active, 2 inactive, 3 rerouting |
| `nav` | 32774 | `NavigationState` | steps (maneuver type, roundabout exit and angle, road, **lanes**, cue), destinations |
| `nav` | 32775 | `NavigationCurrentPosition` | distance and time to the step, current road, destination distance, ETA |
| `media` | 32771 | `MediaMetaData` | song (1), artist (2), album (3), album art (4), duration (6) |
| `media` | 32769 | `MediaPlaybackStatus` | state, position |

wFabia's decoder is the reference for the field layout (`ZlinkDecoder.java`); these are the same messages and numbers as
Zlink's patched metadata socket delivers (`0x8006`, `0x8007`, ...).

## Whitelist and privacy (what the sender must do)

- **Nothing is sent to an app that is not on the whitelist, and the whitelist is empty by default.** The user picks the receiving
  apps from the list of apps installed on the head unit.
- **The receiver is identified by package name and signing certificate.** When an app is added, the sender stores the SHA-256
  digest of its signing certificate and, before every broadcast, checks that the installed package still has it. If the package
  was replaced by an app signed with another key, nothing is sent. This takes the place of a signature-level permission, which
  would force every receiving app to be signed with the sender's key.
- **Album art is off by default.** A switch in the sender adds it: with it off, field 4 of `MediaMetaData` is removed before
  the payload leaves the sender. Titles, artists and navigation text are sent as is.
- Nothing is written to the sender's log or storage, and the sender does not forward anything while no phone is projecting.
- The navigation payload carries the destination name. A receiver that does not need it must not store or log it. wFabia does neither.

## Receiving (what a companion app must do)

```java
registerReceiver(receiver, new IntentFilter("com.andrerinas.openheadunit.AAP_FRAME"));
// in onReceive: channel = i.getStringExtra("channel"); msg = i.getIntExtra("msg", 0); payload = i.getByteArrayExtra("payload");
```

Optionally listen to the sender's session broadcast (`com.andrerinas.headunitrevived.SESSION_STATE` or
`com.andrerinas.openheadunit.SESSION_STATE`, extra `state`: `connecting`, `connected`, `projecting`, `disconnected`, `failed`) and clear
all navigation state on `disconnected` and `failed`, so that no old step stays on screen.

## Changing the contract

New fields are added as new extras, new messages as new `channel`/`msg` pairs. Anything that breaks a receiver bumps `v`.
