#!/usr/bin/env python3
"""
maxidot_sim.py - 2x10 MaxiDot emulator + navigation -> text formatter.

    python maxidot_sim.py --replay gmaps.jsonl            # replay a capture in real time (jsonl keeps timestamps)
    python maxidot_sim.py --replay run1.bin --speed 4     # .bin has no timestamps: 1 frame per second
    python maxidot_sim.py --replay x.jsonl --plain        # print the two rows whenever they change (no screen redraw)
    python maxidot_sim.py                                 # live: connects to 127.0.0.1:1555 like navlive.py

The formatter (`screen(state, t)` -> two strings) is what the real cluster code should call; the emulator only draws it.
The cell glyphs are restricted to what the real font has (maxidot/font.py in khiivl/fabia-mk1-maxidot-can):
    A-Z 0-9 space ! " # $ % & ' ( ) * + , - . / : ; < = > ? @ [ \\ ] ^   Ä Ö Ü   ' _ ° ✓ ▶   ↑ ↓ → ←   ▲ ▼
    no lowercase, no diagonal arrows, no U-turn / roundabout symbols  ->  composed from letters and arrows below.

Row 1:  <maneuver glyph> <distance>   [road number right aligned]       e.g.  "→ 300M  B91"
Row 2:  lanes if the phone sends them for this step (recommended lane in parentheses)  e.g. "↑↑/(/)"
        otherwise only a motorway exit / junction name, and only within 2 km of it (scrolls if longer than 10 cells)
        otherwise the GPS speed, centered ("  87 KM/H "), or empty if no speed is known. Street names are never shown, only road numbers (A9, B91, L163) on row 1.

Maneuver glyphs (all composed from the cells that exist):
    ↑ straight   ← → turn   ←\\ /→ slight/keep/fork/ramp   ↓← →↓ sharp   U← →U u-turn   ▶ depart   ✓ destination
    O2→ roundabout: O + exit number + direction from the exit angle (90 → , 180 ↑ , 270 ← , 360 ↓)
Lanes: ↑ straight, ← →, \\ slight left, / slight right, U u-turn
"""
import argparse, json, os, re, socket, struct, sys, time, unicodedata
from navdecode import NavTracker, read_capture, road_ref, MANEUVER, _fields, _s

ROW = 10
try:
    from fox_sniff import recvn
except Exception:
    def recvn(s, n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c:
                raise ConnectionError("closed")
            b += c
        return b

# ---- the font (same rules as maxidot.font.encode) ----------------------------------------------------------
FONT_EXTRA = set("ÄÖÜ’_°✓▶↑↓→←▲▼")


def font_ok(ch):
    return ch in FONT_EXTRA or 0x20 <= ord(ch) <= 0x5E


# ---- Cyrillic -> Latin capitals (the font has no Cyrillic cells) ------------------------------------------
# Ukrainian: the national standard (Cabinet of Ministers resolution 55 of 2010): Київ KYIV, Згурівка ZGHURIVKA, Ялта YALTA,
# Україна UKRAINA (є ї й ю я are YE YI Y YU YA at the start of a word, IE I I IU IA inside it; apostrophe and ь not written).
# Russian: the common readable scheme: Щукин SHCHUKIN, Королёв KOROLYOV, Горбачёв GORBACHOV, Толстой TOLSTOY, Мышь MYSH.
# і ї є ґ are only Ukrainian, ы э ё ъ only Russian; the more frequent kind wins, a text with neither is read as Ukrainian.
_UK_ONLY, _RU_ONLY, _APOSTROPHES = "іїєґ", "ыэёъ", "'’ʼ`"
_UK = dict(а="A", б="B", в="V", г="H", ґ="G", д="D", е="E", є=("YE", "IE"), ж="ZH", з="Z", и="Y", і="I", ї=("YI", "I"),
           й=("Y", "I"), к="K", л="L", м="M", н="N", о="O", п="P", р="R", с="S", т="T", у="U", ф="F", х="KH", ц="TS",
           ч="CH", ш="SH", щ="SHCH", ь="", ю=("YU", "IU"), я=("YA", "IA"), ы="Y", э="E", ё="YO", ъ="")
_RU = dict(а="A", б="B", в="V", г="G", д="D", е="E", ё="YO", ж="ZH", з="Z", и="I", й="Y", к="K", л="L", м="M", н="N",
           о="O", п="P", р="R", с="S", т="T", у="U", ф="F", х="KH", ц="TS", ч="CH", ш="SH", щ="SHCH", ъ="", ы="Y", ь="",
           э="E", ю="YU", я="YA", і="I", ї="YI", є="YE", ґ="G")


def _is_cyr(ch):
    return 0x0400 <= ord(ch) <= 0x04FF


def cyr_lang(text):
    uk = sum(1 for c in text.lower() if c in _UK_ONLY)
    ru = sum(1 for c in text.lower() if c in _RU_ONLY)
    return "ru" if ru > uk else "uk"


def _cyr_letter(c, prev, initial, lang):
    if lang == "ru":
        if c == "ё" and prev and prev in "жчшщц":
            return "O"
        return _RU.get(c)
    if c == "г" and prev == "з":
        return "GH"
    v = _UK.get(c)
    return v[0 if initial else 1] if isinstance(v, tuple) else v


def cyr_latin(text):
    """Cyrillic letters -> Latin capitals (Ukrainian or Russian, see above); anything else unchanged"""
    if not any(_is_cyr(ch) for ch in text):
        return text
    lang = cyr_lang(text)
    out, in_word, prev = [], False, ""
    for i, ch in enumerate(text):
        if ch in _APOSTROPHES and prev and i + 1 < len(text) and _is_cyr(text[i + 1]):
            continue                      # м'ясо, п’ять: the apostrophe inside a word is not written
        if not _is_cyr(ch):
            out.append(ch)
            in_word, prev = ch.isalpha(), ""
            continue
        c = ch.lower()
        lat = _cyr_letter(c, prev, not in_word, lang)
        if lat is None:                   # another Cyrillic alphabet: its base letter if it has one (ў -> у), else unchanged
            base = "".join(x for x in unicodedata.normalize("NFKD", c) if not unicodedata.combining(x))
            if len(base) == 1 and base != c and _is_cyr(base):
                lat = _cyr_letter(base, prev, not in_word, lang)
            if lat is None:
                lat = ch
        out.append(lat)
        in_word, prev = True, c
    return "".join(out)


def to_font(text):
    """str -> str that the display can show (upper case, Cyrillic transliterated, ß->SS, accents stripped, missing -> '?')"""
    text = cyr_latin(text)
    out = []
    for ch in text:
        if ch == "ß":
            out.append("SS")
        elif ch in "äöü":
            out.append(ch.upper())
        elif font_ok(ch):
            out.append(ch)
        else:
            base = "".join(c for c in unicodedata.normalize("NFKD", ch) if not unicodedata.combining(c)).upper()
            out.append("".join(c if font_ok(c) else "?" for c in base) or "?")
    return "".join(out)


# ---- maneuver glyphs ---------------------------------------------------------------------------------------
def maneuver_glyph(step):
    code = step["maneuver_code"]
    n = MANEUVER.get(code, "")
    side = "L" if n.endswith("LEFT") else "R" if n.endswith("RIGHT") else ""
    rb = step.get("roundabout")
    if "ROUNDABOUT" in n:
        g = "O" + (str(rb["exit"]) if rb and rb.get("exit") else "")
        if rb and rb.get("angle") and code in (33, 35):
            a = rb["angle"]
            g += "→" if a <= 135 else "↑" if a <= 225 else "←" if a <= 315 else "↓"
        return g
    if n.startswith("DESTINATION"):
        return "✓" + ("←" if side == "L" else "→" if side == "R" else "")
    if n == "DEPART":
        return "▶"
    if n in ("NAME_CHANGE", "STRAIGHT", "MERGE_SIDE_UNSPECIFIED"):
        return "↑"
    if n.startswith("FERRY"):
        return "F" + ("←" if side == "L" else "→" if side == "R" else "")
    if not side:
        return "?"
    if "U_TURN" in n:
        return "U←" if side == "L" else "→U"
    if "SHARP" in n:
        return "↓←" if side == "L" else "→↓"
    if any(k in n for k in ("SLIGHT", "KEEP", "FORK", "MERGE")):
        return "←\\" if side == "L" else "/→"
    if "RAMP" in n:  # normal on/off ramps: treat like a slight turn, they leave a road at a shallow angle
        return "←\\" if side == "L" else "/→"
    return "←" if side == "L" else "→"


def fmt_dist(m):
    if m is None:
        return ""
    if m < 100:
        return f"{int(round(m / 10.0)) * 10}M"
    if m < 1000:
        return f"{int(round(m / 50.0)) * 50}M"
    if m < 9950:
        return f"{m / 1000.0:.1f}K"
    return f"{int(round(m / 1000.0))}K"


# ---- lanes -------------------------------------------------------------------------------------------------
LANE_CH = {1: "↑", 2: "\\", 3: "/", 4: "←", 5: "→", 6: "←", 7: "→", 8: "U", 9: "U", 0: "?"}


# arrows inside one lane are ordered by direction, left to right: u-turn, sharp, left, slight left, straight, ...
LANE_ORDER = {8: 0, 6: 1, 4: 2, 2: 3, 1: 4, 3: 5, 5: 6, 7: 7, 9: 8, 0: 9}


def lanes_text(lanes):
    """[[{shape, highlighted}]] -> string of at most ROW cells. Recommended lanes in parentheses (adjacent ones share)."""
    def build(lns, first_only):
        out, i = "", 0
        while i < len(lns):
            def arrows(l, hl):
                ds = sorted(l, key=lambda d: LANE_ORDER.get(d["shape"], 9))
                ds = ds if (hl or not first_only) else ds[:1]
                return "".join(LANE_CH.get(d["shape"], "?") for d in ds)
            hl = any(d["highlighted"] for d in lns[i])
            if hl:
                j, s = i, ""
                while j < len(lns) and any(d["highlighted"] for d in lns[j]):
                    s += arrows(lns[j], True)
                    j += 1
                out += "(" + s + ")"
                i = j
            else:
                out += arrows(lns[i], False)
                i += 1
        return out

    lns = [l for l in lanes if l]
    t = build(lns, False)
    if len(t) <= ROW:
        return t
    t = build(lns, True)
    if len(t) <= ROW:
        return t
    # still too wide: trim non-recommended lanes from the outside in, keep the recommended ones
    hl_idx = [i for i, l in enumerate(lns) if any(d["highlighted"] for d in l)] or [0]
    # keep the recommended lanes, then widen to neighbours while it still fits
    lo, hi = min(hl_idx), max(hl_idx)
    best = build(lns[lo:hi + 1], True)
    while True:
        grown = False
        for nlo, nhi in ((lo - 1, hi), (lo, hi + 1)):
            if nlo >= 0 and nhi < len(lns):
                c = build(lns[nlo:nhi + 1], True)
                if len(c) <= ROW:
                    lo, hi, best, grown = nlo, nhi, c, True
                    break
        if not grown:
            break
    return best[:ROW]


# ---- screen ------------------------------------------------------------------------------------------------
def marquee(text, t, width=ROW, rate=2.5, pause=1.5):
    if len(text) <= width:
        return text.center(width)
    pad = text + "   "
    n = len(pad)
    k = max(0.0, t - pause)
    off = int(k * rate) % n if k > 0 else 0
    return (pad + pad)[off:off + width]


def clean_name(text):
    """'[A 9] [E 49] > Berlin; Leipzig' -> 'Berlin; Leipzig' ; '[L 163]' -> '' (road numbers go to row 1)"""
    t = re.sub(r"\[[^\]]*\]", " ", text or "")
    return re.sub(r"\s+", " ", t).strip(" >")


EXIT_NAME_M = 2000                      # show the exit / junction name on row 2 only this close
_REF_ANY = re.compile(r"\b[ABLKE]\s?\d{1,4}[a-z]?\b")
_EXIT_WORDS = re.compile(r"ausfahrt|kreuz|dreieck|anschlussstelle|abzweig", re.I)


def exit_text(st):
    """Name of a motorway exit / junction for row 2, else ''. Street names are deliberately not shown."""
    n = st["maneuver"]
    for src in (st["road"], st["cue"]):
        if not src:
            continue
        isexit = bool(_EXIT_WORDS.search(src)) or (
            n.startswith(("OFF_RAMP", "FORK")) and (road_ref(src) or "").startswith("A"))
        if not isexit:
            continue
        t = clean_name(src)
        t = re.sub(r"(?i)^ausfahrt\s*", "", t)
        t = re.sub(r"\s+", " ", _REF_ANY.sub(" ", t)).strip(" ;>/")
        if t:
            return t
    return ""


def screen(state, t, speed=None):
    """NavTracker.state + time -> (row1, row2), each exactly ROW cells of font characters"""
    if state.get("_media"):
        return media_rows(state["_media"])
    st = state.get("step")
    if not state.get("active") or not st:
        return " " * ROW, (f"{int(round(speed)):>3} KM/H".center(ROW) if speed is not None else " " * ROW)
    nxt = (state.get("next") or {}).get("dist") or {}
    glyph = maneuver_glyph(st)
    dist = fmt_dist(nxt.get("metres")) if nxt else ""
    if st["maneuver"] == "DEPART" or not state.get("_fresh", True):
        dist = ""
    left = (glyph + " " + dist).strip()
    ref = road_ref(st["road"] or "") or ""
    row1 = left
    if ref:  # "glyph dist" and the road number as one block, two spaces apart if that fits, else one
        for gap in (2, 1):
            if len(left) + gap + len(ref) <= ROW:
                row1 = left + " " * gap + ref
                break
    row1 = row1.center(ROW)
    row1 = to_font(row1)[:ROW].ljust(ROW)
    row2 = ""
    if st["lanes"]:
        row2 = lanes_text(st["lanes"]).center(ROW)
    else:
        m = nxt.get("metres") if nxt else None
        if state.get("_fresh", True) and m is not None and m <= EXIT_NAME_M:
            name = exit_text(st)
            if name:
                row2 = marquee(to_font(name), t)
    if not row2.strip() and speed is not None:   # nothing else to say: GPS speed from the head unit
        row2 = f"{int(round(speed)):>3} KM/H".center(ROW)
    return row1, to_font(row2)[:ROW].ljust(ROW)


# ---- media (artist / track) ------------------------------------------------------------------------------------
MEDIA_MIN_M = 2000      # media may take over the display only while the next turn is further away than this
MEDIA_RATE = 2.5        # cells per second while scrolling
MEDIA_PAUSE = 1.5       # seconds standing still before scrolling starts
MEDIA_HOLD = 1.5        # seconds standing still once the end of the text is visible
MEDIA_MIN_S = 4.0       # shortest time a track is shown


def media_scroll_s(text):
    """seconds until `text` has scrolled all the way through (and rested at its end)"""
    n = len(to_font(text))
    return MEDIA_MIN_S if n <= ROW else max(MEDIA_MIN_S, MEDIA_PAUSE + (n - ROW) / MEDIA_RATE + MEDIA_HOLD)


def media_cells(text, elapsed):
    """one row of a track display: centered when it fits, else scrolled once from the start to the end"""
    t = to_font(text)
    if len(t) <= ROW:
        return t.center(ROW)
    off = min(max(0, int((elapsed - MEDIA_PAUSE) * MEDIA_RATE)), len(t) - ROW)
    return t[off:off + ROW]


def media_rows(m):
    artist, song = m.get("artist") or "", m.get("song") or ""
    if not artist:
        artist, song = song, ""
    return media_cells(artist, m["elapsed"]), media_cells(song, m["elapsed"])


def media_payload(song, artist):
    """build a type-5 payload (song, artist) from decoded jsonl records"""
    def f(n, v):
        b = v.encode("utf-8")
        return bytes([n << 3 | 2, len(b)]) + b if len(b) < 128 else bytes([n << 3 | 2, 0x80 | len(b) & 0x7F, len(b) >> 7]) + b
    return f(1, song or "") + f(2, artist or "")


class Feed:
    """NavTracker + 'fresh' flag: the distance of the previous step is stale until the first progress message after a
    step change, so it is not shown in between (otherwise every new step flashes '0M')."""

    def __init__(self):
        self.tr, self.key, self.fresh, self.live = NavTracker(), None, True, False
        self.media, self.media_id = None, None

    def _media_meta(self, pl, now):
        f = _fields(pl)
        song = _s(f[1][0]) if 1 in f and isinstance(f[1][0], bytes) else ""
        artist = _s(f[2][0]) if 2 in f and isinstance(f[2][0], bytes) else ""
        if (song, artist) == self.media_id or not (song or artist):   # the phone repeats the same metadata
            return
        self.media_id = (song, artist)
        dur = max(media_scroll_s(artist), media_scroll_s(song))
        self.media = {"song": song, "artist": artist, "start": now or 0.0, "dur": dur, "dismissed": False}

    def feed(self, typ, pl, now=None):
        if typ == 5:
            self._media_meta(pl, now)
            return True
        if typ == 4:
            return False
        if typ == 32:  # NaviStatus: 1 active, 2 inactive, 3 = seen during reroutes (ignore, do not blank the display)
            status = pl[1] if len(pl) > 1 else 0
            if status == 3:
                return False
            self.live = status == 1
        elif typ == 0x8006:
            self.live = True  # a step only arrives while navigating
        ok = self.tr.feed(typ, pl)
        if ok and typ == 0x8006 and self.tr.state.get("step"):
            st = self.tr.state["step"]
            key = (st["maneuver_code"], st["road"], json.dumps(st.get("roundabout")))
            if key != self.key:
                self.key, self.fresh = key, False
                if self.media:                      # the navigation always wins when another step arrives
                    self.media["dismissed"] = True
        elif ok and typ == 0x8007:
            self.fresh = True
        return ok

    def view(self, now):
        """state for screen(): like .state, plus '_media' = {song, artist, elapsed} while a track is being shown"""
        s = self.state
        s["_media"] = None
        m = self.media
        if m and not m["dismissed"]:
            d = ((s.get("next") or {}).get("dist") or {}).get("metres")
            if s.get("active") and s.get("step") and d is not None and d <= MEDIA_MIN_M:
                m["dismissed"] = True               # too close to the next turn: navigation keeps the display
            elif now - m["start"] >= m["dur"]:
                m["dismissed"] = True               # scrolled through once
            else:
                s["_media"] = {"song": m["song"], "artist": m["artist"], "elapsed": now - m["start"]}
        return s

    @property
    def state(self):
        return dict(self.tr.state, _fresh=self.fresh, active=self.live)


# ---- GPS speed (km/h) ----------------------------------------------------------------------------------------
class Speed:
    """Speed source. --gps 87 = fixed, --gps-demo = simulated, --gps-dumpsys = read it on the Android head unit.
    The real cluster code should replace .kmh() with the head unit's own GPS speed (None = unknown -> row stays empty)."""

    def __init__(self, a):
        self.fixed = a.gps
        self.demo = a.gps_demo
        self.dumpsys = a.gps_dumpsys
        self.t0, self.cache, self.cache_t = time.time(), None, 0.0

    def kmh(self, t=None):
        if self.fixed is not None:
            return self.fixed
        if self.demo:
            import math
            return 60 + 40 * math.sin((time.time() - self.t0) / 6.0)
        if self.dumpsys and time.time() - self.cache_t > 1.0:
            self.cache, self.cache_t = gps_dumpsys(), time.time()
            return self.cache
        if self.cache is not None:
            return self.cache
        try:
            from gps_speed import get_gps_speed_kmh   # the stub: fill it in for the real head unit
            return get_gps_speed_kmh()
        except Exception:
            return None


def gps_dumpsys():
    """Best effort, UNTESTED on this head unit: parse the last GPS fix from `dumpsys location` (Location.toString has
    'vel=<m/s>' on most Android versions). Returns km/h or None."""
    import subprocess
    try:
        out = subprocess.run(["dumpsys", "location"], capture_output=True, text=True, timeout=3).stdout
    except Exception:
        return None
    best = None
    for m in re.finditer(r"Location\[gps [^\]]*?(?:vel|speed)=([0-9.]+)", out):
        best = float(m.group(1)) * 3.6
    return best


# ---- drawing -----------------------------------------------------------------------------------------------
def draw(r1, r2):
    top = "┌" + "┬".join("───" for _ in range(ROW)) + "┐"
    mid = "├" + "┼".join("───" for _ in range(ROW)) + "┤"
    bot = "└" + "┴".join("───" for _ in range(ROW)) + "┘"
    cells = lambda r: "│" + "│".join(f" {c} " for c in r) + "│"
    return "\n".join([top, cells(r1), mid, cells(r2), bot])


def paint(txt, plain):
    if plain:
        print(txt, flush=True)
    else:
        sys.stdout.write("\x1b[H\x1b[J" + txt + "\n")
        sys.stdout.flush()


def info(state):
    st = state.get("step") or {}
    return (f"code {st.get('maneuver_code', '-')} {st.get('maneuver', '-')}  road: {st.get('road')}"
            f"{'  lanes: ' + str(len(st.get('lanes') or [])) if st.get('lanes') else ''}")


def replay(a):
    recs = []
    if a.replay.endswith(".jsonl"):
        for line in open(a.replay, encoding="utf-8"):
            d = json.loads(line)
            if "raw_hex" in d:
                recs.append((d["t"], d["type"], bytes.fromhex(d["raw_hex"])))
            elif d.get("type") == 32:
                recs.append((d["t"], 32, bytes([8, {"ACTIVE": 1, "INACTIVE": 2}.get(d["data"].get("status"), 0)])))
            elif d.get("type") == 5 and "data" in d:
                recs.append((d["t"], 5, media_payload(d["data"].get("song"), d["data"].get("artist"))))
    else:
        recs = [(float(i), typ, pl) for i, (typ, pl) in enumerate(read_capture(a.replay))]
    tr, last, prev, spd = Feed(), None, None, Speed(a)
    for t, typ, pl in recs:
        if prev is not None and not a.plain:       # wait for the next frame, redrawing 5x a second so text scrolls
            w0 = time.time()
            wait = max(0, min(2.0, (t - prev) / a.speed))
            while time.time() - w0 < wait:
                time.sleep(min(0.2, max(0.0, wait - (time.time() - w0))))
                v = prev + (time.time() - w0) * a.speed
                paint(draw(*screen(tr.view(v), v, spd.kmh())) + "\n" + info(tr.state) + "\n(Ctrl+C to quit)", False)
        prev = t
        if not tr.feed(typ, pl, t):
            continue
        rows = screen(tr.view(t), 0.0 if a.plain else t, spd.kmh())
        if a.plain:
            if rows != last:
                print(f"{rows[0]!r:>14} {rows[1]!r}   <- {info(tr.state)}")
        else:
            paint(draw(*rows) + "\n" + info(tr.state) + "\n(Ctrl+C to quit)", False)
        last = rows


def live(a):
    tr, spd = Feed(), Speed(a)
    while True:
        try:
            s = socket.create_connection((a.host, a.port), timeout=5)
            s.settimeout(1.0)
            paint("connected, waiting for navigation data ...", False)
            while True:
                try:
                    typ, ln = struct.unpack("<II", recvn(s, 8))
                    pl = recvn(s, ln) if ln else b""
                    tr.feed(typ, pl, time.time())
                except socket.timeout:
                    pass
                paint(draw(*screen(tr.view(time.time()), time.time(), spd.kmh())) + "\n" + info(tr.state) + "\n(Ctrl+C to quit)", False)
        except (ConnectionError, OSError) as e:
            paint(f"link: {e}; retry in 3 s", False)
            time.sleep(3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1555)
    ap.add_argument("--replay")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--plain", action="store_true")
    ap.add_argument("--gps", type=float, help="fixed GPS speed in km/h (for testing)")
    ap.add_argument("--gps-demo", action="store_true", help="simulated GPS speed")
    ap.add_argument("--gps-dumpsys", action="store_true", help="on the head unit: read speed from dumpsys location (untested)")
    a = ap.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    os.system("")
    try:
        replay(a) if a.replay else live(a)
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
