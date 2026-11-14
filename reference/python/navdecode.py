#!/usr/bin/env python3
"""
navdecode.py - decode the newer Android Auto navigation messages that Zlink 6.1.06
forwards on its Fox metadata socket (patched library only).

  0x8006  NavigationState     sent when the next step changes
  0x8007  NavigationProgress  sent about once per second

Schema below is INFERRED from live captures (Zeitz, Oct 2026), not from a descriptor.
Field numbers are certain for everything seen; names and enum meanings are not:
  maneuver codes   = androidx.car.app Maneuver.TYPE_* numbering. VERIFIED 1-50 (mock app sweep, Oct 2026), except
                   0 UNKNOWN (host does not forward it).
  lane shapes      wire = androidx LaneDirection shape - 1. VERIFIED on the road: 1, 3, 4, 5, 8. Not seen: 2, 6, 7, 9.
  units            = Car App Distance units 1-7. VERIFIED 1-7 (mock app sweep).
  Only Google Maps sends lanes to the head unit (OsmAnd, Organic Maps, HERE WeGo and Car App mock apps: never).
  Roundabout exit/angle stay on the wire after a roundabout on later steps (stale): trust step["roundabout"], which is
  only filled for roundabout codes; angle only for 33/35. Organic Maps / HERE WeGo use 34 (exit only), Google Maps 35.
Unknown fields are never dropped: they end up in `extra`.

    NavigationState  { 1: step { 1: maneuver{1: type}, 2: road{1: name}, 3*: lane{1*: dir{1: shape, 2: highlighted}},
                                 4: cue{1: text} },
                       2: destination{1: name} }
    NavigationProgress { 1: next { 1: dist{1: metres, 2: display text, 3: unit}, 2: seconds },
                         2: trip { 1: dist{...}, 2: eta text, 3: seconds } }

Use as a library:
    t = NavTracker(); t.feed(0x8006, payload); t.feed(0x8007, payload); t.state  # plain dict
Or replay a capture:
    python3 navdecode.py m2.jsonl          # or m2.bin (raw framed stream)
    python3 navdecode.py m2.bin --every 1  # print every progress tick, not just changes
"""
import json, struct, sys
from fox_sniff import wire

MANEUVER = {
    0: "UNKNOWN", 1: "DEPART", 2: "NAME_CHANGE",
    3: "KEEP_LEFT", 4: "KEEP_RIGHT", 5: "TURN_SLIGHT_LEFT",
    6: "TURN_SLIGHT_RIGHT", 7: "TURN_NORMAL_LEFT", 8: "TURN_NORMAL_RIGHT",
    9: "TURN_SHARP_LEFT", 10: "TURN_SHARP_RIGHT", 11: "U_TURN_LEFT",
    12: "U_TURN_RIGHT", 13: "ON_RAMP_SLIGHT_LEFT", 14: "ON_RAMP_SLIGHT_RIGHT",
    15: "ON_RAMP_NORMAL_LEFT", 16: "ON_RAMP_NORMAL_RIGHT", 17: "ON_RAMP_SHARP_LEFT",
    18: "ON_RAMP_SHARP_RIGHT", 19: "ON_RAMP_U_TURN_LEFT", 20: "ON_RAMP_U_TURN_RIGHT",
    21: "OFF_RAMP_SLIGHT_LEFT", 22: "OFF_RAMP_SLIGHT_RIGHT", 23: "OFF_RAMP_NORMAL_LEFT",
    24: "OFF_RAMP_NORMAL_RIGHT", 25: "FORK_LEFT", 26: "FORK_RIGHT",
    27: "MERGE_LEFT", 28: "MERGE_RIGHT", 29: "MERGE_SIDE_UNSPECIFIED",
    32: "ROUNDABOUT_ENTER_AND_EXIT_CW", 33: "ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE", 34: "ROUNDABOUT_ENTER_AND_EXIT_CCW",
    35: "ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE", 36: "STRAIGHT", 37: "FERRY_BOAT",
    38: "FERRY_TRAIN", 39: "DESTINATION", 40: "DESTINATION_STRAIGHT",
    41: "DESTINATION_LEFT", 42: "DESTINATION_RIGHT", 43: "ROUNDABOUT_ENTER_CW",
    44: "ROUNDABOUT_EXIT_CW", 45: "ROUNDABOUT_ENTER_CCW", 46: "ROUNDABOUT_EXIT_CCW",
    47: "FERRY_BOAT_LEFT", 48: "FERRY_BOAT_RIGHT", 49: "FERRY_TRAIN_LEFT",
    50: "FERRY_TRAIN_RIGHT",
}  # names = androidx.car.app Maneuver.TYPE_* (list taken from the mock app send-log; 30 and 31 do not exist)
# lane direction shapes: wire value = androidx LaneDirection shape - 1. Confirmed against the phone display while
# driving: 1 straight, 3 slight right, 4 left, 5 right, 8 u-turn left. Never seen yet: 2, 6, 7, 9 (library rule only).
LANE_SHAPE = {0: "UNKNOWN", 1: "STRAIGHT", 2: "SLIGHT_LEFT", 3: "SLIGHT_RIGHT", 4: "NORMAL_LEFT",
              5: "NORMAL_RIGHT", 6: "SHARP_LEFT", 7: "SHARP_RIGHT", 8: "U_TURN_LEFT", 9: "U_TURN_RIGHT"}
LANE_VERIFIED = {1, 3, 4, 5, 8}
UNIT = {0: "UNKNOWN", 1: "METERS", 2: "KILOMETERS", 3: "KILOMETERS_P1", 4: "MILES", 5: "MILES_P1",
        6: "FEET", 7: "YARDS"}  # same numbering as the legacy DistanceUnits enum (1 and 2 confirmed)


import re
_REF = re.compile(r"\b([ABLKE])\s?(\d{1,4}[a-z]?)\b")


def road_ref(text):
    """'[A 9] [E 49] > Berlin; Leipzig' or 'B180 Kalkstraße' -> 'A9' (first German road number, else None).
    Prefers A (Autobahn) > B > L/K, E (Europastraße) only if nothing else."""
    if not text:
        return None
    best = None
    for letter, num in _REF.findall(text):
        rank = "ABLKE".index(letter)
        if best is None or rank < best[0]:
            best = (rank, letter + num)
    return best[1] if best else None


def _fields(b):
    """-> {field: [values]} (ints stay ints, length-delimited stay bytes)"""
    d = {}
    for f, w, v in wire(b):
        d.setdefault(f, []).append(v)
    return d


def _s(v):
    return v.decode("utf-8", "replace")


def _first(d, f, default=None):
    return d[f][0] if f in d else default


def _text(b):
    """message { 1: string }"""
    return _s(_first(_fields(b), 1, b"")) if b is not None else None


def _dist(b):
    d = _fields(b)
    return {"metres": _first(d, 1, 0),                       # absent on the wire == 0
            "text": _s(_first(d, 2, b"")),
            "unit": UNIT.get(_first(d, 3, 0), f"?{_first(d, 3, 0)}")}


def decode_state(payload):
    top = _fields(payload)
    out = {"step": None, "destination": None, "extra": {}}
    if 1 in top:
        s = _fields(top[1][0])
        man = _fields(s[1][0]) if 1 in s else {}
        t = _first(man, 1, 0)
        man_extra = {str(k): [x.hex() if isinstance(x, bytes) else x for x in v] for k, v in man.items() if k != 1}
        lanes = []
        for lb in s.get(3, []):
            lanes.append([{"shape": _first(_fields(x), 1, 0),
                           "arrow": LANE_SHAPE.get(_first(_fields(x), 1, 0), f"?{_first(_fields(x), 1, 0)}"),
                           "highlighted": bool(_first(_fields(x), 2, 0))}
                          for x in _fields(lb).get(1, [])])
        out["step"] = {
            "roundabout": ({"exit": man_extra["2"][0], "angle": man_extra.get("3", [None])[0]}
                           if t in (33, 35, 32, 34, 43, 44, 45, 46) and "2" in man_extra else None),
            "maneuver": MANEUVER.get(t, f"?{t}"),
            "maneuver_code": t,
            "maneuver_extra": man_extra,   # any other fields inside the maneuver (roundabout exit number / angle?)
            "road": _text(_first(s, 2)),
            "cue": _text(_first(s, 4)),
            "lanes": lanes,
        }
        extra = {k: v for k, v in s.items() if k not in (1, 2, 3, 4)}
        if extra:
            out["extra"]["step"] = {k: [x.hex() if isinstance(x, bytes) else x for x in v] for k, v in extra.items()}
    if 2 in top:
        out["destination"] = _text(top[2][0])
    extra = {k: v for k, v in top.items() if k not in (1, 2)}
    if extra:
        out["extra"]["top"] = {k: [x.hex() if isinstance(x, bytes) else x for x in v] for k, v in extra.items()}
    return out


def decode_progress(payload):
    top = _fields(payload)
    out = {"next": None, "trip": None, "extra": {}}
    if 1 in top:
        n = _fields(top[1][0])
        out["next"] = {"dist": _dist(_first(n, 1, b"")), "seconds": _first(n, 2, 0)}
    if 2 in top:
        t = _fields(top[2][0])
        out["trip"] = {"dist": _dist(_first(t, 1, b"")), "eta": _s(_first(t, 2, b"")),
                       "seconds": _first(t, 3, 0)}
    extra = {k: v for k, v in top.items() if k not in (1, 2)}
    if extra:
        out["extra"]["top"] = {k: [x.hex() if isinstance(x, bytes) else x for x in v] for k, v in extra.items()}
    return out


class NavTracker:
    """Folds the message stream into one current-state dict. Safe to feed garbage."""

    def __init__(self):
        self.state = {"active": None, "step": None, "destination": None, "next": None, "trip": None}

    def feed(self, typ, payload):
        try:
            if typ == 32:  # NaviStatus: field 1 enum, 1 = ACTIVE
                st = _first(_fields(payload), 1, 0)
                self.state["active"] = (st == 1)
                if st != 1:
                    self.state.update(step=None, destination=None, next=None, trip=None)
            elif typ == 0x8006:
                d = decode_state(payload)
                self.state["step"], self.state["destination"] = d["step"], d["destination"]
            elif typ == 0x8007:
                d = decode_progress(payload)
                self.state["next"], self.state["trip"] = d["next"], d["trip"]
            else:
                return False
        except Exception as e:  # truncated / unexpected: keep last good state
            self.state["last_error"] = f"type {typ:#x}: {e}"
            return False
        return True


def read_capture(path):
    """yield (type, payload) from a .jsonl (fox_sniff --jsonl) or a raw framed .bin"""
    if path.endswith(".jsonl"):
        for line in open(path, encoding="utf-8"):
            d = json.loads(line)
            if "raw_hex" in d:
                yield d["type"], bytes.fromhex(d["raw_hex"])
            elif d.get("type") == 32:
                yield 32, bytes([8, {"UNAVAILABLE": 0, "ACTIVE": 1, "INACTIVE": 2}.get(d["data"].get("status"), 0)])
        return
    b = open(path, "rb").read()
    i = 0
    while i + 8 <= len(b):
        typ, ln = struct.unpack_from("<II", b, i)
        if ln > (1 << 22):
            break
        yield typ, b[i + 8:i + 8 + ln]
        i += 8 + ln


_U = {'METERS': 'm', 'KILOMETERS': 'km', 'KILOMETERS_P1': 'km', 'MILES': 'mi', 'MILES_P1': 'mi', 'FEET': 'ft', 'YARDS': 'yd'}


def one_line(s):
    if not s["active"] and s["step"] is None:
        return "(no navigation)"
    st, nx, tr = s["step"] or {}, s["next"] or {}, s["trip"] or {}
    nd = nx.get("dist", {})
    td = tr.get("dist", {})
    return (f"{st.get('maneuver', '-'):<18} in {nd.get('metres', '-'):>5} m ({nd.get('text', '-')} {_U.get(nd.get('unit'), '')}) "
            f"{nx.get('seconds', '-'):>3}s | {st.get('road') or '-'} | trip {td.get('metres', '-')} m "
            f"eta {tr.get('eta', '-')} in {tr.get('seconds', '-')}s | -> {s['destination']}")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    every = "--every" in sys.argv
    if not args:
        print(__doc__)
        return
    t = NavTracker()
    last = None
    for typ, payload in read_capture(args[0]):
        if not t.feed(typ, payload):
            continue
        line = one_line(t.state)
        key = line if every else (json.dumps(t.state.get("step"), sort_keys=True), t.state["active"],
                                  (t.state["next"] or {}).get("dist", {}).get("text"))
        if key != last:
            print(line)
            if typ == 0x8006 and t.state["step"] and t.state["step"]["lanes"]:
                print("   lanes:", t.state["step"]["lanes"])
            if t.state.get("last_error"):
                print("   !!", t.state.pop("last_error"))
            last = key


if __name__ == "__main__":
    main()
