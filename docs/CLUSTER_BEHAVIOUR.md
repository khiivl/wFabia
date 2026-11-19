# What the cluster does around the ignition

Measured on the car (Fabia Mk1 MaxiDot) from a full capture of the radio conversation with the key moved through its positions
(2026-11-16, 599 s, 91 000 frames) and from the logs of the key-flick tests of 2026-11-19. This is the reason the display loop
works the way it does; the protocol itself is in `reference/maxidot_can/PROTOCOL.md`.

## Key position: frame 0x271, byte 0

| value | key | bits |
|---|---|---|
| `01` | key in, ACC off, ignition off | bit 0 |
| `05` | ACC | bit 2 |
| `07` | ignition on (position 2) | bit 1 |
| `03` | the contacts between 1 and 2, or position 3 | bit 1 without bit 2 |

The head unit only gets ACC_OFF when the key is taken out. A quick key flick (2 to 1 to 2) never reaches the app as an event.

## Replies to a content message depend on the key

| key | reply | meaning |
|---|---|---|
| ignition on | `27 SS 01` | drawn (405 of 405 in the capture) |
| `05` / `01` | `23 SS 00` | accepted, the display is off, the session byte SS is still valid (51 of 51) |
| any | `2B SS 03` | refused: stale session byte or a malformed message |

The registration reply has the same shape: `23 SS 01` with the ignition on, `23 SS 00` otherwise. The last byte is the "display on" flag.
`23 SS 00` is **not** an error. The old display loop took anything but `27` for a lost session, registered again every second while the
key was off, and ran into the limit below.

## The cluster accepts three registrations per presence of the radio

In every episode of the capture: three registrations accepted (sessions C5, C6, C7), then `2B FF 09` for every further attempt
(60 refusals), until the heartbeat had been off for about 3 s (the old app's pauses were 3.09 to 3.26 s). After such a pause, three
more were accepted. Pauses of 1.5 s and 2.0 s did not clear it in the tests of 2026-11-19, 3.0 s did every time.

So a registration is never repeated blindly. After three, or after a refusal, the heartbeat goes off for 3 s first.

## Heartbeat and the radio flag (frame 0x40C, byte 0)

- The flag goes `19` to `0C` to `00` about 0.33 s after the last heartbeat frame, whatever else happens, and back to `19` about 0.2 s after the
  heartbeat starts again.
- Stopping the heartbeat is what blanks the display ("when the heartbeat stops the display blanks shortly after"). Keeping it running
  through a key flick is what a real radio does in ACC.
- The session counter ran on monotonically through all the flicks (CC, CD, CE, ... FE, 01, 02, ...): the cluster does not reset its radio
  state on a flick, so the session stays valid across it.

## What the display loop does

- `23 SS 00` (display off): nothing is wrong. Keep the session and the heartbeat, offer the rows once a second, and at once (then every
  0.25 s for 2 s) when frame 0x271 shows the ignition again.
- `2B SS 03` or no answer: the session is gone: register again quickly (the heartbeat keeps running).
- No answer to the connection setup: the cluster is not up yet: try again every 0.5 s, the heartbeat keeps running.
- Refusal `2B FF 09`, or the third registration done: switch the heartbeat off for 3 s, then register.

## Not known

- Whether a registration is accepted by a cluster that has just reset while the heartbeat was running during its start.
- What frame 0x271 does while the starter turns.
- What the cluster's own C0 setup requests on 0x2E8 (`39 C0 99`, every 50 ms for 3 s after the key goes to 0) want from the radio.
