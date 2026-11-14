#!/usr/bin/env python3
"""
navlive.py - live console view of what the head unit receives, to check the decoding against the phone.

    python navlive.py                      # connect to 127.0.0.1:1555 (adb forward tcp:1555 tcp:1555 first)
    python navlive.py --jsonl drive.jsonl  # same, and record every frame (fox_sniff format, works with navcheck/navlabel)
    python navlive.py --replay gmaps.jsonl [--speed 4]   # replay a capture (.jsonl with timestamps, or .bin at 1 frame/s)

Shows: maneuver (code, name, arrow), distance, road, roundabout exit/angle, lanes drawn left to right.
Lanes appear only on some steps, so the last lane layout stays on screen (dimmed, with its age) until a new one arrives.
Lane drawing: every lane is a column, [ ] around a highlighted (recommended) lane. Raw wire shape numbers are shown
under it so you can compare "what I decoded" with "what the phone shows".
Only ONE client can read port 1555: close fox_sniff / navlabel first.
"""
import argparse, json, os, socket, struct, sys, time
from navdecode import decode_state, decode_progress, MANEUVER, read_capture

try:
    from fox_sniff import recvn
except Exception:  # keep working without fox_sniff next to us
    def recvn(s, n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c:
                raise ConnectionError("closed")
            b += c
        return b

# wire lane shape -> arrow. Unverified shapes (no real example seen yet) are marked with ?
LANE_ARROW = {0: "?", 1: "↑", 2: "↖", 3: "↗", 4: "←", 5: "→", 6: "↙", 7: "↘", 8: "↶", 9: "↷"}
LANE_UNVERIFIED = {0, 2, 6, 7, 9}


def man_glyph(code, rb=None):
    n = MANEUVER.get(code, "")
    if "ROUNDABOUT" in n:
        return ("⟳" if "CW" in n and "CCW" not in n else "⟲") + (f"{rb['exit']}" if rb and rb.get("exit") else "")
    if n.startswith("DESTINATION"):
        return "⚑" + ("←" if n.endswith("LEFT") else "→" if n.endswith("RIGHT") else "")
    if n == "DEPART":
        return "▶"
    if n == "NAME_CHANGE" or n == "STRAIGHT":
        return "↑"
    if n.startswith("FERRY"):
        return "⛴"
    if "U_TURN" in n:
        return "↶" if n.endswith("LEFT") else "↷"
    side = "L" if n.endswith("LEFT") else "R" if n.endswith("RIGHT") else ""
    if not side:
        return "?"
    if "SHARP" in n:
        return "↙" if side == "L" else "↘"
    if "SLIGHT" in n or "KEEP" in n or "FORK" in n or "RAMP" in n or "MERGE" in n:
        return "↖" if side == "L" else "↗"
    return "←" if side == "L" else "→"


def fmt_dist(d):
    if not d:
        return "-"
    m = d.get("metres", 0)
    return f"{m} m" + (f"  (phone says: {d.get('text')} {d.get('unit')})" if d.get("text") else "")


def draw_lanes(lanes, width=7):
    """lanes: [[{shape, highlighted}]] -> list of text lines"""
    cols = []
    for lane in lanes:
        arrows = "".join(LANE_ARROW.get(d["shape"], "?") for d in lane)
        hl = any(d["highlighted"] for d in lane)
        raw = ",".join(f"{d['shape']}{'*' if d['highlighted'] else ''}" for d in lane)
        shown = ("[" + arrows + "]") if hl else (" " + arrows + " ")
        cols.append((shown.center(width), raw.center(width), ("REC" if hl else "").center(width)))
    sep = "│"
    return [sep.join(c[i] for c in cols) for i in range(3)]


class View:
    def __init__(self):
        self.step = self.dest = self.nxt = self.trip = None
        self.status = "?"
        self.last_lanes = None      # (layout lines, code, road, t, raw)
        self.frames = 0
        self.cur_lane_key = None

    def feed(self, typ, pl, now):
        self.frames += 1
        if typ == 32:
            try:
                self.status = {1: "ACTIVE", 2: "INACTIVE"}.get(pl[1], f"?{pl[1]}") if len(pl) > 1 else "?"
            except Exception:
                pass
            if self.status != "ACTIVE":
                self.step = self.nxt = self.trip = None
        elif typ == 0x8006:
            d = decode_state(pl)
            self.step, self.dest = d["step"], d["destination"]
            if self.step and self.step["lanes"]:
                self.last_lanes = (draw_lanes(self.step["lanes"]), self.step["maneuver_code"], self.step["road"], now,
                                   self.step["lanes"])
        elif typ == 0x8007:
            d = decode_progress(pl)
            self.nxt, self.trip = d["next"], d["trip"]

    def render(self, now):
        L = []
        L.append(f"NaviStatus: {self.status}    frames: {self.frames}")
        L.append("─" * 60)
        st = self.step
        if not st:
            L.append("(no active step)")
        else:
            code = st["maneuver_code"]
            rb = st.get("roundabout")
            L.append(f"  {man_glyph(code, rb)}   code {code}  {st['maneuver']}")
            if rb:
                L.append(f"      roundabout: exit {rb['exit']}" + (f", angle {rb['angle']}" if rb.get("angle") else ""))
            L.append(f"  road: {st['road']}")
            if st["cue"] and st["cue"] != st["road"]:
                L.append(f"  cue:  {st['cue']}")
            L.append(f"  in:   {fmt_dist((self.nxt or {}).get('dist'))}")
            if self.dest:
                L.append(f"  to:   {self.dest}")
            tr = self.trip or {}
            if tr:
                L.append(f"  trip: {fmt_dist(tr.get('dist'))}   ETA {tr.get('eta')}")
        L.append("─" * 60)
        cur = st["lanes"] if st else None
        if cur:
            L.append("LANES (now):   [ ] = recommended   numbers = wire shape")
            L += ["  " + x for x in draw_lanes(cur)]
            bad = sorted({d["shape"] for l in cur for d in l if d["shape"] in LANE_UNVERIFIED})
            if bad:
                L.append(f"  (shapes {bad} are not verified yet)")
        else:
            L.append("LANES (now):   none sent for this step")
            if self.last_lanes:
                lines, code, road, t, _ = self.last_lanes
                L.append(f"last lanes seen {int(now - t)} s ago on code {code} ({road}):")
                L += ["  " + x for x in lines]
        L.append("─" * 60)
        L.append("Ctrl+C to quit")
        return "\n".join(L)


def paint(txt, plain):
    if plain:
        print(txt + "\n")
    else:
        sys.stdout.write("\x1b[H\x1b[J" + txt + "\n")
        sys.stdout.flush()


def replay(a, view):
    t0 = None
    prev = None
    recs = []
    if a.replay.endswith(".jsonl"):
        for line in open(a.replay, encoding="utf-8"):
            d = json.loads(line)
            if "raw_hex" in d:
                recs.append((d["t"], d["type"], bytes.fromhex(d["raw_hex"])))
            elif d.get("type") == 32:
                recs.append((d["t"], 32, bytes([8, {"ACTIVE": 1, "INACTIVE": 2}.get(d["data"].get("status"), 0)])))
    else:
        for i, (typ, pl) in enumerate(read_capture(a.replay)):
            recs.append((float(i), typ, pl))
    for t, typ, pl in recs:
        if prev is not None and not a.plain:
            time.sleep(max(0, min(2.0, (t - prev) / a.speed)))
        prev = t
        view.feed(typ, pl, t)
        if typ in (0x8006, 0x8007, 32) and (not a.plain or typ == 0x8006):
            paint(view.render(t), a.plain)


def live(a, view):
    out = open(a.jsonl, "a", encoding="utf-8") if a.jsonl else None
    while True:
        try:
            paint(f"connecting {a.host}:{a.port} ... (adb forward tcp:{a.port} tcp:{a.port}; close other sniffers)", False)
            s = socket.create_connection((a.host, a.port), timeout=5)
            s.settimeout(30)
            paint("connected, waiting for navigation data ...", False)
            while True:
                try:
                    typ, ln = struct.unpack("<II", recvn(s, 8))
                    pl = recvn(s, ln) if ln else b""
                except socket.timeout:
                    continue
                now = time.time()
                if out and typ in (32, 0x8006, 0x8007):
                    out.write(json.dumps({"t": now, "type": typ, "raw_hex": pl.hex()}) + "\n")
                    out.flush()
                elif out and typ == 5:   # media metadata: keep song / artist, drop the album art
                    try:
                        from navdecode import _fields, _s
                        f = _fields(pl)
                        g = lambda k: _s(f[k][0]) if k in f and isinstance(f[k][0], bytes) else ""
                        out.write(json.dumps({"t": now, "type": 5, "name": "MediaPlaybackMetadata",
                                              "data": {"song": g(1), "artist": g(2)}}, ensure_ascii=False) + "\n")
                        out.flush()
                    except Exception:
                        pass
                view.feed(typ, pl, now)
                if typ in (32, 0x8006, 0x8007):
                    paint(view.render(now), False)
        except (ConnectionError, OSError) as e:
            paint(f"link: {e}; retry in 3 s", False)
            time.sleep(3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1555)
    ap.add_argument("--jsonl", help="also record frames to this file")
    ap.add_argument("--replay", help="replay a capture instead of connecting")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--plain", action="store_true", help="no screen clearing; print each step change (for logs)")
    a = ap.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    os.system("")  # enables ANSI escapes in the Windows console
    view = View()
    try:
        replay(a, view) if a.replay else live(a, view)
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
