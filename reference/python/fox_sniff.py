#!/usr/bin/env python3
"""
fox_sniff.py - read and decode Zlink's internal "Fox" metadata socket (TCP 1555 in Zlink 6.1.06).

Framing (inferred from disassembly of libzjL10001.so, NOT yet confirmed on a live unit):
    uint32 LE type | uint32 LE length | <length bytes of protobuf>
Message types seen in the native sender:
    1 PhoneStatus   2 NextTurnEvent   3 NextTurnDistanceEvent   4 MediaPlaybackStatus
    5 MediaPlaybackMetadata   32 NaviStatus   (6-14: CarPlay/HiCar/CarLife variants, dumped raw)
Field numbers and enums come from the protobuf-c descriptors inside the library.

Usage:
    adb forward tcp:1555 tcp:1555
    python3 fox_sniff.py                      # decode to the terminal
    python3 fox_sniff.py --capture fox.bin --jsonl fox.jsonl
    python3 fox_sniff.py --raw                # hex dump only (use if decoding looks wrong)

The server accepts ONE client. If Zlink's own app already holds that slot you will
connect but receive nothing; the script says so after --idle seconds.
"""
import argparse, json, socket, struct, sys, time

SCHEMAS = {
    1: ("PhoneStatus", {
        1: ("calls", "message", "Call", True),
        2: ("signal_strength", "uint32", None, False)}),
    2: ("NextTurnEvent", {
        1: ("road", "string", None, False),
        2: ("turn_side", "enum", "TurnSide", False),
        3: ("event", "enum", "NextTurnEnum", False),
        4: ("image", "bytes", None, False),
        5: ("turn_number", "int32", None, False),
        6: ("turn_angle", "int32", None, False)}),
    3: ("NextTurnDistanceEvent", {
        1: ("distance_meters", "int32", None, False),
        2: ("time_to_turn_seconds", "int32", None, False),
        3: ("display_distance_e3", "int32", None, False),
        4: ("display_distance_unit", "enum", "DistanceUnits", False)}),
    4: ("MediaPlaybackStatus", {
        1: ("state", "enum", "PlaybackStatus", False),
        2: ("media_source", "string", None, False),
        3: ("playback_seconds", "uint32", None, False),
        4: ("shuffle", "bool", None, False),
        5: ("repeat", "bool", None, False),
        6: ("repeat_one", "bool", None, False)}),
    5: ("MediaPlaybackMetadata", {
        1: ("song", "string", None, False),
        2: ("artist", "string", None, False),
        3: ("album", "string", None, False),
        4: ("album_art", "bytes", None, False),
        5: ("playlist", "string", None, False),
        6: ("duration_seconds", "uint32", None, False),
        7: ("rating", "uint32", None, False)}),
    32: ("NaviStatus", {
        1: ("status", "enum", "NaviStatusEnum", False)}),
}
NESTED = {"Call": {
    1: ("phone_state", "enum", "CallState", False),
    2: ("call_duration_seconds", "uint32", None, False),
    3: ("caller_number", "string", None, False),
    4: ("caller_id", "string", None, False),
    5: ("caller_number_type", "string", None, False),
    6: ("caller_thumbnail", "bytes", None, False)}}
ENUMS = {
    "TurnSide": {1: "LEFT", 2: "RIGHT", 3: "UNSPECIFIED"},
    "NextTurnEnum": {0: "UNKNOWN", 1: "DEPART", 2: "NAME_CHANGE", 3: "SLIGHT_TURN", 4: "TURN",
                     5: "SHARP_TURN", 6: "U_TURN", 7: "ON_RAMP", 8: "OFF_RAMP", 9: "FORK",
                     10: "MERGE", 11: "ROUNDABOUT_ENTER", 12: "ROUNDABOUT_EXIT",
                     13: "ROUNDABOUT_ENTER_AND_EXIT", 14: "STRAIGHT", 15: "FERRY_BOAT",
                     16: "FERRY_TRAIN", 17: "DESTINATION"},
    "DistanceUnits": {0: "UNKNOWN", 1: "METERS", 2: "KILOMETERS", 3: "KILOMETERS_P1",
                      4: "MILES", 5: "MILES_P1", 6: "FEET", 7: "YARDS"},
    "PlaybackStatus": {1: "STOPPED", 2: "PLAYING", 3: "PAUSED"},
    "NaviStatusEnum": {0: "UNAVAILABLE", 1: "ACTIVE", 2: "INACTIVE"},
    "CallState": {1: "IN_CALL", 2: "ON_HOLD", 3: "INACTIVE", 4: "INCOMING", 5: "CONFERENCED", 6: "MUTED"},
}
MAX_LEN = 4 * 1024 * 1024
try:
    import navdecode  # optional: decodes 0x8006/0x8007 when it sits next to this file
except ImportError:
    navdecode = None


def varint(b, i):
    v = s = 0
    while True:
        if i >= len(b):
            raise ValueError("truncated varint")
        c = b[i]; i += 1
        v |= (c & 0x7F) << s
        if not c & 0x80:
            return v, i
        s += 7
        if s > 70:
            raise ValueError("varint too long")


def wire(b):
    """Generic protobuf wire decode -> list of (field, wiretype, value)."""
    i, out = 0, []
    while i < len(b):
        k, i = varint(b, i)
        f, w = k >> 3, k & 7
        if w == 0:
            v, i = varint(b, i)
        elif w == 1:
            v, i = b[i:i + 8], i + 8
        elif w == 2:
            n, i = varint(b, i)
            v, i = b[i:i + n], i + n
            if len(v) != n:
                raise ValueError("truncated field")
        elif w == 5:
            v, i = b[i:i + 4], i + 4
        else:
            raise ValueError(f"bad wire type {w}")
        out.append((f, w, v))
    return out


def s32(v):  # int32 stored as 64-bit varint when negative
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v & 0x80000000 else v


def decode(schema, payload):
    res = {}
    for f, w, v in wire(payload):
        spec = schema.get(f)
        if not spec:
            res.setdefault(f"unknown_{f}", []).append(v.hex() if isinstance(v, bytes) else v)
            continue
        name, typ, sub, rep = spec
        if typ == "string":
            val = v.decode("utf-8", "replace")
        elif typ == "bytes":
            val = {"bytes": len(v)}
        elif typ == "message":
            val = decode(NESTED[sub], v)
        elif typ == "enum":
            val = ENUMS[sub].get(v, f"?{v}")
        elif typ == "bool":
            val = bool(v)
        elif typ == "int32":
            val = s32(v)
        else:
            val = v
        if rep:
            res.setdefault(name, []).append(val)
        else:
            res[name] = val
    return res


def pb_guess(b, depth=0):
    """Best-effort schema-less protobuf decode for message types we have no schema for."""
    out = []
    try:
        fields = wire(b)
    except Exception:
        return {"hex": hexdump(b, 96)}
    for f, w, v in fields:
        if w == 0:
            out.append((f, v))
        elif w == 2:
            sub = None
            if depth < 4 and v:
                try:
                    inner = wire(v)
                    if inner and sum(len(x[2]) if isinstance(x[2], bytes) else 1 for x in inner) > 0:
                        sub = pb_guess(v, depth + 1)
                        if isinstance(sub, dict) and "hex" in sub:
                            sub = None
                except Exception:
                    sub = None
            if sub is not None and not _printable(v):
                out.append((f, sub))
            elif _printable(v):
                out.append((f, v.decode("utf-8", "replace")))
            else:
                out.append((f, {"bytes": len(v), "hex": hexdump(v, 24)}))
        elif w in (1, 5):
            out.append((f, {"fixed": v.hex()}))
    return [{"f": f, "v": v} for f, v in out]


def _printable(v):
    try:
        t = v.decode("utf-8")
    except Exception:
        return False
    return len(t) > 0 and all(c.isprintable() or c in "\n\t" for c in t)


def hexdump(b, n=64):
    h = b[:n].hex(" ")
    return h + (f" ... (+{len(b) - n}B)" if len(b) > n else "")


def recvn(sock, n):
    buf = b""
    while len(buf) < n:
        c = sock.recv(n - len(buf))
        if not c:
            raise ConnectionError("peer closed")
        buf += c
    return buf


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1555)
    ap.add_argument("--raw", action="store_true", help="hex-dump the byte stream, no framing assumptions")
    ap.add_argument("--capture", help="write every received byte to this file")
    ap.add_argument("--jsonl", help="append decoded messages as JSON lines")
    ap.add_argument("--idle", type=float, default=20, help="seconds of silence before printing a hint")
    a = ap.parse_args()
    cap = open(a.capture, "ab") if a.capture else None
    jl = open(a.jsonl, "a") if a.jsonl else None

    while True:
        try:
            print(f"connecting {a.host}:{a.port} ...", flush=True)
            s = socket.create_connection((a.host, a.port), timeout=5)
            s.settimeout(a.idle)
            print("connected", flush=True)
            hinted = False
            while True:
                try:
                    if a.raw:
                        chunk = s.recv(4096)
                        if not chunk:
                            raise ConnectionError("peer closed")
                        if cap: cap.write(chunk); cap.flush()
                        print(time.strftime("%H:%M:%S"), len(chunk), "B:", hexdump(chunk, 96), flush=True)
                        continue
                    hdr = recvn(s, 8)
                    typ, ln = struct.unpack("<II", hdr)
                    if ln > MAX_LEN or (typ > 255 and not (0x8000 <= typ <= 0x80ff)):
                        print(f"!! implausible header type={typ} len={ln}; framing guess is wrong. "
                              f"raw: {hdr.hex(' ')}. Rerun with --raw and send me the capture.", flush=True)
                        rest = s.recv(256)
                        if cap: cap.write(hdr + rest); cap.flush()
                        print("   next bytes:", hexdump(rest, 128), flush=True)
                        break
                    payload = recvn(s, ln) if ln else b""
                    if cap: cap.write(hdr + payload); cap.flush()
                    t = time.strftime("%H:%M:%S")
                    if typ in SCHEMAS:
                        name, schema = SCHEMAS[typ]
                        try:
                            d = decode(schema, payload)
                        except Exception as e:
                            d = {"decode_error": str(e), "raw": hexdump(payload)}
                        print(f"[{t}] t{typ} {name}: {json.dumps(d, ensure_ascii=False)}", flush=True)
                        if jl:
                            jl.write(json.dumps({"t": time.time(), "type": typ, "name": name, "data": d},
                                                ensure_ascii=False) + "\n"); jl.flush()
                    elif typ in (0x8006, 0x8007) and navdecode:
                        d = (navdecode.decode_state if typ == 0x8006 else navdecode.decode_progress)(payload)
                        nm = "NavigationState" if typ == 0x8006 else "NavigationProgress"
                        print(f"[{t}] t{typ:#x} {nm}: {json.dumps(d, ensure_ascii=False)}", flush=True)
                        if jl:
                            jl.write(json.dumps({"t": time.time(), "type": typ, "raw_hex": payload.hex()}) + "\n"); jl.flush()
                    else:
                        g = pb_guess(payload)
                        print(f"[{t}] t{typ:#x} (no schema, {ln}B): {json.dumps(g, ensure_ascii=False)[:900]}", flush=True)
                        if jl:
                            jl.write(json.dumps({"t": time.time(), "type": typ, "raw_hex": payload.hex()}) + "\n"); jl.flush()
                except socket.timeout:
                    if not hinted:
                        hinted = True
                        print(f"-- nothing for {a.idle:.0f}s. Either no navigation/media is active on the phone, "
                              f"or Zlink's own app holds the single client slot.\n"
                              f"   check: adb shell netstat -tn | grep 7722   (an ESTABLISHED pair = slot taken)",
                              flush=True)
        except (ConnectionError, OSError) as e:
            print(f"link: {e}; retry in 3s", flush=True)
            time.sleep(3)
        except KeyboardInterrupt:
            print("bye")
            return


if __name__ == "__main__":
    main()
