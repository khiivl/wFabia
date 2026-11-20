# What the cluster does around the ignition

Measured on the car (Fabia Mk1 MaxiDot) from two full captures of the radio conversation with the key moved through its positions
(2026-11-16, 599 s, 91 000 frames, and 2026-11-20, 192 s) and from the logs of the key-flick tests of 2026-11-19. This is the reason
the display loop works the way it does; the protocol itself is in `reference/maxidot_can/PROTOCOL.md`.

## Key position: frame 0x271, byte 0

| value | key | bits |
|---|---|---|
| `01` | position 0: key in, ACC off, ignition off | bit 0 |
| `05` | position 1: ACC | bit 2 |
| `07` | position 2: ignition on | bit 1 |
| `03` | the contacts for a few ms between 0 and 2 (ACC closes after the ignition), or position 3 | bit 1 without bit 2 |

Bit 2 (ACC) is the power of a real radio: off at position 0, running at 1 and 2. The head unit only gets ACC_OFF when the key is taken
out, so a quick key flick never reaches the app as an event; the CAN frame is the only signal.

## Replies to a content message depend on the key

| key | reply | meaning |
|---|---|---|
| ignition on | `27 SS 01` | drawn (405 of 405 in the first capture) |
| `05` / `01` | `23 SS 00` | accepted, the display is off, the session byte SS is still valid (51 of 51) |
| any | `2B SS 03` | refused: stale session byte or a malformed message |

The registration reply has the same shape: `23 SS 01` with the ignition on, `23 SS 00` otherwise. The last byte is the "display on" flag.
`23 SS 00` is **not** an error. The first version of the display loop took anything but `27` for a lost session, registered again every
second while the key was off, and ran into the limit below.

## The cluster accepts three registrations per presence of the radio

In every episode of the first capture: three registrations accepted (sessions C5, C6, C7), then `2B FF 09` for every further attempt
(60 refusals), until the heartbeat had been off for about 3 s (the old app's pauses were 3.09 to 3.26 s). After such a pause, three
more were accepted. Pauses of 1.5 s and 2.0 s did not clear it in the tests of 2026-11-19, 3.0 s did every time.

## The cluster polls the radio when it powers up or down, and disables one that does not answer

In both captures, after a change of the key through position 0 the cluster sends its own connection requests to the radio, `39 C0 99` on
`0x2E8` every 50 ms for 3.1 to 4.8 s, and answers none of the radio's own setup requests meanwhile. Nothing in the repo shows how a
real radio answers; we do not answer them.

What follows is the important part: **every session registered after such a burst, while the radio had been present all the time,
stays at display flag `00` for as long as the radio stays present** (55 s in the 2026-11-20 capture with the key at position 2, content
reply `23 1E 00` every second). A radio that is gone for 3 s and then appears again gets an active display at once: the same stuck unit
linked with `display on` right after a restart of the app (session 0x1F).

Bursts were seen at: key to position 0 with a registered radio present (2026-11-16: 0.11 s after the key frame), and, on 2026-11-20, after
three of the four ON pulses from position 0 (0.3 to 0.5 s after the key frame), not after an ACC to ignition step (first capture), and
not after the very first pulse. We do not know the exact trigger.

## What the display loop does about it

- **The radio follows ACC.** Frame 0x271 shows ACC falling: the heartbeat stops at once (the broker does that at the frame, whatever the
  display loop is busy with), an open channel is closed with A8, nothing is sent. ACC rising: the radio "boots": the heartbeat starts again
  when the cluster has been without it for 3 s (`debug.wfabia.off`) and has had `BOOT_SECONDS` = 2.5 s (`debug.wfabia.boot`) to power up;
  then wait for the cluster's radio flag, register, send the first rows with the registration. The welcome shows at every boot.
- **The boot delay is learned.** A session that is stuck at flag 00 with the ignition on for 3 s, within 20 s of a radio boot, means the
  radio appeared before the cluster was ready: the radio disappears for 3 s again and appears again, and the next boot waits 1 s longer
  (up to 6 s).
- **ACC dips** (key position 2 to 1 and back): nothing is stopped. `23 SS 00` while the ignition is off, the rows are offered once a
  second and at once (then every 0.25 s for 2 s) when the ignition bit is back, and `27 SS 01` brings the text back within a few
  hundred ms. No registration.
- `2B SS 03`: the session is gone: register again quickly (the heartbeat keeps running).
- No answer to the rows: the cluster is starting or busy: ask again with the same session for 6 s before registering again.
- No answer to the connection setup: the cluster is not up yet: try again every 0.5 s, the heartbeat keeps running.
- Refusal `2B FF 09`, or the third registration done: switch the heartbeat off for 3 s, then register.

## Heartbeat and the radio flag (frame 0x40C, byte 0)

- The flag goes `19` to `0C` to `00` about 0.33 s after the last heartbeat frame, whatever else happens, and back to `19` about 0.2 s after the
  heartbeat starts again.
- Stopping the heartbeat is what blanks the display ("when the heartbeat stops the display blanks shortly after").
- The session counter ran on monotonically through all the flicks of 2026-11-19, with one exception: after a burst a re-registration
  returned the same byte once (0x16 twice).

## Answering the cluster's polling (on by default, tested 2026-11-20)

Every `39 C0 99` the cluster sends on `0x2E8` is answered with the mirror of our own setup request, `08 D0 B9` on `0x4D9` (08 = cluster, D0 = accepted,
B9 = our data ID 0x6B9), at most three times per burst, and only while the display loop is the radio (heartbeat running). It was a guess, and
it worked: the cluster answers within 25 ms with its channel parameters, `A0 04 8A 85 43 94` on `0x699` (we do not answer these yet), stops polling
(one burst ended after a single request, the others after 1.7 and 2.1 s instead of 3 to 5 s), accepts our next ordinary setup, and draws on the **old
session** (`27 SS 01`) 1.26 to 2.17 s after the key came back, with no registration and no break in the heartbeat. Three key-0 flicks of about 0.5 s.

With this on the radio is not cut when ACC falls any more (nothing to hide from the polling). `adb shell setprop debug.wfabia.answerc0 0` switches it off
(read once a second) and brings back the radio that follows ACC (heartbeat off at the ACC fall, back 3 s later plus 2.5 s of power-up, learned upward).
The stuck-session recovery stays in both modes.

The cluster's `A0 04 8A 85 43 94` read as TP 2.0 timers (top 2 bits: unit 0.1, 1, 10 or 100 ms; low 6 bits: count) is T1..T4 = 100, 50, 3 and 200 ms; ours, `A0 04 82 84 82 B2`, is 20, 40, 20 and 500 ms.
T1 is how long a sender waits for an ack and T3 the least gap between packets in classic TP 2.0 (the cluster takes 3 ms gaps, even 0); T2 and T4 are normally `FF` there. Unverified.

Next steps if it should be quicker: answer the cluster's `A0` (channel parameters) with `A1 04 82 84 82 B2` on `0x6B9` and ack its data packets, and
offer the rows sooner after the key returns than the 0.25 s to 1 s probing of the display loop.

## Looking for other display modes (opt-in, off by default)

`adb shell setprop debug.wfabia.sweep 1` (read once a second) runs `ContentSweep` once, as soon as the display is linked and drawing with the key steady at position 2.
The content message is `09 SS`, line records of 17 bytes (`57 0F LL 00 00 XX 00` and 10 cells), and `08`; the real one has the lines 02 and 03 with XX = 00 and 0A, which
looks like the offset of the first cell. The sweep sends about 430 messages that differ from it in one respect, each followed by the real message again to check that the
session still draws: first **lines** (28: other line numbers, one, three or four records instead of two), then **types** (253: every other first byte, but not the init
and registration types 15 and 02), then **bytes** (149: every single-bit flip, 00, FF and the neighbours of each of the 15 other fixed bytes). The bottom row of a probe says
what it is (`LINE 4`, `T=0B`, `07=0B`). A refusal (`2B SS 03`) draws nothing and is only counted per group. Any other reply is logged (`sweep: type 0B (37 bytes): reply 27 14 01`) and the
probe stays on the display for 1.2 s: watch the cluster and write down what changes. It stops at the first message that gets no answer or after which the session no
longer draws (the display loop then links again as usual); `setprop debug.wfabia.sweep 0` stops it between two messages and arms it again. About 3.5 minutes when
everything is refused. Log: `adb logcat -d | grep -i sweep`.

Never sent: another session byte, and the init and registration messages (each registration uses up one of the three the cluster allows, so those need a heartbeat pause per try).

## Simulation

`app/src/test/java/de/wfabia/core/ClusterSim.java` runs the real `DisplayController` on a simulated cluster built from the rules above
(compile it with the core package and `FakeCluster`/`FakeWire`, run `de.wfabia.core.ClusterSim 1.5 <power-up ms>`). It reproduces the
14 to 18 s the first version needed after a key flick and shows the new loop at 2.7 to 2.9 s (key to 0 and back), 30 to 120 ms (ACC dip),
and the learning of a slower power-up. The model is only as good as the rules; the key-0 power-up timing has not been measured yet.

## Not known

- How long the cluster needs after ACC returns before it accepts a radio that has just appeared (2.5 s is the default and is learned upward).
- What exactly triggers the polling burst, and whether it happens when no radio is present.
- How a real radio answers the cluster's `39 C0 99` requests (a capture of a real radio during a power-up would show it).
- What frame 0x271 does while the starter turns, and what a radio that follows ACC does then (it stops for the 3 s).
