#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""wfabia.py - PC side of the wFabia adb CAN bridge (stdlib only).

    adb forward tcp:7788 tcp:7788

    python wfabia.py candump [--filter 0x2E8,0x699]    live CAN frames, candump style (rx and tx)
    python wfabia.py cansend 5A0#01FF [--ttl 500]      one frame; refused unless Send is switched on in the app
    python wfabia.py zlink                             raw Zlink frames, one line each: time type hex
    python wfabia.py zlink --serve 1555                re-serve the Zlink frames in the original binary framing,
                                                       so navlive.py keeps working: python navlive.py --port 1555
    python wfabia.py log drive.jsonl                   CAN + Zlink in one timestamped file; Zlink records use the
                                                       fox_sniff format {"t","type","raw_hex"}, CAN records {"t","ch":"can",...}
    python wfabia.py status                            module states and link status
    python wfabia.py module display off                switch a module (the send switch can only be changed in the app)

The app speaks one JSON object per line on 127.0.0.1:7788 (see SPEC.md section 8, R4).
"""
import argparse
import json
import socket
import struct
import sys
import threading
import time


class Bridge:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.settimeout(None)
        self.f = self.sock.makefile("rwb", buffering=0)

    def send(self, obj):
        self.f.write((json.dumps(obj) + "\n").encode("utf-8"))

    def messages(self):
        """yield decoded JSON objects until the connection closes"""
        buf = b""
        while True:
            chunk = self.sock.recv(65536)
            if not chunk:
                return
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if line.strip():
                    try:
                        yield json.loads(line)
                    except ValueError:
                        print("bad line from the app:", line[:80], file=sys.stderr)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def ctl_note(m):
    """print control events that need the user's attention to stderr"""
    ev = m.get("ev", "")
    if ev in ("sub_refused", "tx_refused", "module_refused", "error"):
        print("app: %s%s" % (ev, " (%s)" % m["why"] if "why" in m else ""), file=sys.stderr)


def fmt_can(m):
    d = bytes.fromhex(m["d"])
    return "(%.3f)  can0  %-5s %s [%d]  %s" % (m["t"], m["id"][2:], "TX" if m.get("dir") == "tx" else "RX", len(d), " ".join("%02X" % b for b in d))


def cmd_candump(a):
    b = Bridge(a.host, a.port)
    sub = {"op": "sub", "ch": ["can"]}
    if a.filter:
        sub["can_ids"] = [x.strip() for x in a.filter.split(",") if x.strip()]
    b.send(sub)
    try:
        for m in b.messages():
            if m.get("ch") == "can":
                print(fmt_can(m), flush=True)
            elif m.get("ch") == "ctl":
                ctl_note(m)
    except KeyboardInterrupt:
        pass


def parse_frame(text):
    """'5A0#01FF' or '0x5A0#01 FF' -> ('0x5A0', '01FF')"""
    if "#" not in text:
        raise SystemExit("frame must look like ID#DATA, for example 5A0#01FF")
    cid, data = text.split("#", 1)
    cid = cid.strip()
    if not cid.lower().startswith("0x"):
        cid = "0x" + cid
    return cid, data.replace(" ", "")


def cmd_cansend(a):
    cid, data = parse_frame(a.frame)
    b = Bridge(a.host, a.port)
    b.send({"op": "tx", "id": cid, "d": data, "ttl_ms": a.ttl})
    deadline = time.time() + 3
    b.sock.settimeout(3)
    try:
        for m in b.messages():
            if m.get("ch") == "ctl" and m.get("ev") in ("tx_queued", "tx_refused"):
                if m["ev"] == "tx_queued":
                    print("queued %s#%s" % (cid, data))
                    return 0
                print("refused: %s" % m.get("why"), file=sys.stderr)
                return 1
            if time.time() > deadline:
                break
    except socket.timeout:
        pass
    print("no answer from the app", file=sys.stderr)
    return 2


def cmd_zlink(a):
    b = Bridge(a.host, a.port)
    b.send({"op": "sub", "ch": ["zlink"]})
    clients, lock = [], threading.Lock()
    if a.serve:
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", a.serve))
        srv.listen(4)

        def accept():
            while True:
                c, _ = srv.accept()
                with lock:
                    clients.append(c)
                print("client connected (%d)" % len(clients), file=sys.stderr)

        threading.Thread(target=accept, daemon=True).start()
        print("serving Zlink frames on 127.0.0.1:%d (point navlive.py at it with --port %d)" % (a.serve, a.serve), file=sys.stderr)
    try:
        for m in b.messages():
            if m.get("ch") == "zlink":
                typ, raw = int(m["type"], 16), bytes.fromhex(m["raw"])
                if a.serve:
                    frame = struct.pack("<II", typ, len(raw)) + raw
                    with lock:
                        for c in list(clients):
                            try:
                                c.sendall(frame)
                            except OSError:
                                clients.remove(c)
                else:
                    print("%.3f 0x%04X %s" % (m["t"], typ, m["raw"]), flush=True)
            elif m.get("ch") == "ctl":
                ctl_note(m)
    except KeyboardInterrupt:
        pass


def cmd_log(a):
    b = Bridge(a.host, a.port)
    b.send({"op": "sub", "ch": ["can", "zlink"]})
    n = 0
    with open(a.file, "a", encoding="utf-8") as out:
        try:
            for m in b.messages():
                ch = m.get("ch")
                if ch == "zlink":
                    out.write(json.dumps({"t": m["t"], "type": int(m["type"], 16), "raw_hex": m["raw"]}) + "\n")
                elif ch == "can":
                    out.write(json.dumps({"t": m["t"], "ch": "can", "dir": m["dir"], "id": m["id"], "d": m["d"]}) + "\n")
                elif ch == "ctl":
                    ctl_note(m)
                    continue
                n += 1
                if n % 200 == 0:
                    out.flush()
                    print("\r%d records" % n, end="", file=sys.stderr)
        except KeyboardInterrupt:
            pass
    print("\n%d records in %s" % (n, a.file), file=sys.stderr)


def one_reply(a, req, ev):
    b = Bridge(a.host, a.port)
    b.send(req)
    b.sock.settimeout(3)
    try:
        for m in b.messages():
            if m.get("ch") == "ctl" and m.get("ev") in ev:
                return m
    except socket.timeout:
        pass
    return None


def cmd_status(a):
    m = one_reply(a, {"op": "status"}, ("status",))
    if not m:
        print("no answer from the app", file=sys.stderr)
        return 2
    print(json.dumps(m["status"], indent=2))


def cmd_module(a):
    m = one_reply(a, {"op": "module", "name": a.name, "enabled": a.state == "on"}, ("module_ok", "module_refused"))
    if not m:
        print("no answer from the app", file=sys.stderr)
        return 2
    if m["ev"] == "module_refused":
        print("refused: %s can not be changed from the PC" % a.name, file=sys.stderr)
        return 1
    print("%s %s" % (a.name, a.state))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=7788)
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("candump")
    p.add_argument("--filter", help="comma separated ids, hex")
    p = sp.add_parser("cansend")
    p.add_argument("frame")
    p.add_argument("--ttl", type=int, default=500, help="drop the frame if it could not be sent within this many ms")
    p = sp.add_parser("zlink")
    p.add_argument("--serve", type=int, metavar="PORT")
    p = sp.add_parser("log")
    p.add_argument("file")
    sp.add_parser("status")
    p = sp.add_parser("module")
    p.add_argument("name")
    p.add_argument("state", choices=["on", "off"])
    a = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:
        pass
    try:
        return {"candump": cmd_candump, "cansend": cmd_cansend, "zlink": cmd_zlink, "log": cmd_log,
                "status": cmd_status, "module": cmd_module}[a.cmd](a) or 0
    except ConnectionRefusedError:
        print("cannot reach the app: run  adb forward tcp:%d tcp:%d  and check the bridge is switched on" % (a.port, a.port), file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
