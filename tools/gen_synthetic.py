#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates synthetic Zlink captures (made-up routes, no real locations) in the fox_sniff .jsonl format.

    python tools/gen_synthetic.py            # writes golden/captures/synthetic_*.jsonl
    python tools/make_expected.py            # asks the Python reference (maxidot_sim.py) for the expected rows

Schema (see SPEC.md section 4):
  0x8006 NavigationState   { 1: step { 1: maneuver{1 type, 2 exit, 3 angle}, 2: road{1 name}, 3*: lane{1*: dir{1 shape, 2 highlighted}}, 4: cue{1 text} },
                             2: destination{1 name} }
  0x8007 NavigationProgress{ 1: next { 1: dist{1 metres, 2 text, 3 unit}, 2: seconds }, 2: trip { 1: dist, 2: eta text, 3: seconds } }
  0x20   NaviStatus        { 1: status }            (1 active, 2 inactive, 3 transient during reroutes)
  0x05   media metadata    { 1: song, 2: artist }
"""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "golden", "captures")
T0 = 1_800_000_000.0


def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def fld(f, v):
    if isinstance(v, int):
        return varint(f << 3) + varint(v)
    if isinstance(v, str):
        v = v.encode("utf-8")
    return varint(f << 3 | 2) + varint(len(v)) + v


def state(code, road=None, cue=None, exit=0, angle=0, lanes=(), dest="Zielort"):
    man = fld(1, code) + (fld(2, exit) if exit else b"") + (fld(3, angle) if angle else b"")
    step = fld(1, man)
    if road is not None:
        step += fld(2, fld(1, road))
    for lane in lanes:   # lane = [(shape, highlighted), ...]
        step += fld(3, b"".join(fld(1, fld(1, s) + (fld(2, 1) if h else b"")) for s, h in lane))
    if cue is not None:
        step += fld(4, fld(1, cue))
    return fld(1, step) + fld(2, fld(1, dest))


def dist(metres, text, unit):
    return (fld(1, metres) if metres else b"") + fld(2, text) + fld(3, unit)


def progress(next_m, next_text, unit, secs, trip_km=12, eta="10:09", trip_s=900):
    return (fld(1, fld(1, dist(next_m, next_text, unit)) + fld(2, secs))
            + fld(2, fld(1, dist(trip_km * 1000, "%d" % trip_km, 2)) + fld(2, eta) + fld(3, trip_s)))


def status(n):
    return bytes([8, n])


def media(song, artist):
    return fld(1, song) + fld(2, artist)


class Cap:
    def __init__(self):
        self.t = T0
        self.recs = []

    def add(self, typ, payload, dt=1.0):
        self.recs.append({"t": round(self.t, 4), "type": typ, "raw_hex": payload.hex()})
        self.t += dt

    def step(self, st, distances, unit=1, texts=None):
        """a 0x8006 followed by 0x8007 ticks, one per distance (the phone repeats the state about every second)"""
        self.add(0x8006, st, 0.2)
        for i, m in enumerate(distances):
            txt = texts[i] if texts else ("%d" % m if m < 1000 else "%.1f" % (m / 1000.0))
            self.add(0x8007, progress(m, txt, unit if m < 1000 else 2, max(5, m // 14)))
            if i % 2 == 1:
                self.add(0x8006, st, 0.2)

    def write(self, name):
        os.makedirs(OUT, exist_ok=True)
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
            for r in self.recs:
                f.write(json.dumps(r) + "\n")
        print(name, len(self.recs), "records")


STRAIGHT, SLIGHT_L, SLIGHT_R, LEFT, RIGHT, SHARP_L, SHARP_R, UTURN_L, UTURN_R = 1, 2, 3, 4, 5, 6, 7, 8, 9


def highway():
    c = Cap()
    c.add(0x20, status(1))
    c.step(state(1, "Musterweg", "Los geht's"), [0, 0])                                                   # DEPART: no distance
    c.step(state(4, "[A 99] [E 49] > Beispielstadt", None, lanes=[[(STRAIGHT, False)], [(STRAIGHT, False)], [(SLIGHT_R, True)]]),
           [9000, 6200, 3000])                                                                           # lanes, far: no exit name
    c.step(state(24, "Ausfahrt 12 Kreuz Beispieldorf A 99", "Ausfahrt nehmen"), [1900, 1500, 900, 400, 150, 60])   # exit name inside 2 km
    c.step(state(16, "B 999 Teststrasse"), [800, 300, 90])                                                # ramp, B road
    c.step(state(36, "Hauptstrasse"), [4500, 2200])                                                       # straight
    c.add(0x20, status(2))
    c.write("synthetic_highway.jsonl")


def lanes():
    c = Cap()
    c.add(0x20, status(1))
    layouts = [
        [[(LEFT, True)], [(STRAIGHT, False)]],                                           # two lanes, left recommended
        [[(STRAIGHT, False)], [(STRAIGHT, False)], [(SLIGHT_R, True)]],              # three lanes, slight right recommended
        [[(UTURN_L, True)], [(LEFT, False)], [(STRAIGHT, False)]],                       # u-turn lane
        [[(LEFT, True), (STRAIGHT, False)], [(STRAIGHT, False)]],                        # one lane with two arrows
        [[(SLIGHT_L, False), (STRAIGHT, True), (SLIGHT_R, False)]],      # three arrows in one lane
        [[(LEFT, False)], [(STRAIGHT, True)], [(STRAIGHT, True)], [(RIGHT, False)]],     # two adjacent recommended lanes
        [[(SHARP_L, False)], [(LEFT, False)], [(STRAIGHT, False)], [(STRAIGHT, True)], [(RIGHT, False)], [(SHARP_R, False)]],   # six lanes: needs trimming
        [[(0, False)], [(STRAIGHT, True)]],                                              # unknown shape
    ]
    for i, lay in enumerate(layouts):
        c.step(state(8, "Laneweg %d" % (i + 1), None, lanes=lay), [400, 250])
    c.add(0x20, status(2))
    c.write("synthetic_lanes.jsonl")


def roundabouts():
    c = Cap()
    c.add(0x20, status(1))
    for exit_no, angle in [(1, 90), (2, 180), (3, 270), (4, 360)]:
        c.step(state(35, "Kreiselstrasse", None, exit=exit_no, angle=angle), [300, 120, 40])             # CCW with angle
    c.step(state(34, "Ringstrasse", None, exit=3), [250, 100])                                           # CCW, exit only
    c.step(state(33, "Ringstrasse", None, exit=2, angle=90), [250, 100])                                 # CW with angle
    c.step(state(7, "Seitenstrasse", None, exit=2, angle=270), [500, 200])                               # stale exit/angle on a later step
    c.add(0x20, status(2))
    c.write("synthetic_roundabouts.jsonl")


def city():
    c = Cap()
    c.add(0x20, status(1))
    c.step(state(7, "Müllerstraße"), [850, 500, 200, 80])                       # umlaut, sharp s
    c.step(state(8, "Äußere Ringstraße"), [700, 350])
    c.add(0x20, status(3))                                                       # reroute blip: must not blank the display
    c.add(0x20, status(1))
    c.step(state(10, "B 180 Kalkstraße"), [300, 100])                           # sharp right, road number on row 1
    c.step(state(11, "Wendeweg"), [150, 60])                                    # u-turn left
    c.step(state(12, "Wendeweg 2"), [150, 60])                                  # u-turn right
    c.step(state(5, "Schräge Gasse"), [95, 45, 10])                             # small distances: 10 m steps
    c.step(state(41, "Zielstrasse"), [90, 30])                                  # destination left
    c.add(0x8006, state(1, "Anfang"), 0.2)                                      # DEPART again: no stale distance
    c.add(0x20, status(2))
    c.write("synthetic_city.jsonl")


def units_and_media():
    c = Cap()
    c.add(0x20, status(2))
    c.add(5, media("Short", "Band"), 6)                                          # fits: centered
    c.add(5, media("Short", "Band"), 1)                                          # repeated metadata: ignored
    c.add(5, media("A very long song title that must scroll", "An Equally Long Artist Name"), 12)   # scrolls once
    c.add(5, media("Déjà Vu", "Beyoncé"), 6)                                     # accents stripped
    c.add(5, media("Straße", "Mädchen & Söhne"), 6)                              # sharp s, umlauts
    c.add(5, media("lowercase song", "lowercase artist"), 6)                    # upper-cased by the font
    c.add(0x20, status(1))                                                       # navigation starts, far away: media may show
    c.step(state(4, "[A 99] > Beispielstadt"), [9000, 8500])
    c.add(5, media("Far Away Song", "Far Away Artist"), 3)
    c.add(0x8006, state(8, "Naheweg"), 0.2)                                      # a new step dismisses the media screen at once
    c.add(0x8007, progress(1500, "1.5", 2, 90))
    c.add(5, media("Near Song", "Near Artist"), 2)                              # 1500 m: still shown; next progress at 900 m dismisses
    c.add(0x8007, progress(900, "900", 1, 60))
    c.add(0x20, status(2))
    c.write("synthetic_media.jsonl")


if __name__ == "__main__":
    highway()
    lanes()
    roundabouts()
    city()
    units_and_media()
