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
