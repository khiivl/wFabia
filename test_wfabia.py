#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests wfabia.py against a fake bridge that speaks the app's JSON-lines protocol.  python test_wfabia.py"""
import contextlib
import io
import json
import socket
import struct
import threading
import time
import unittest

import wfabia


class FakeApp:
    """minimal stand-in for the app's BridgeServer"""

    def __init__(self, send_enabled=False):
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(4)
        self.port = self.srv.getsockname()[1]
        self.send_enabled = send_enabled
        self.received = []
        self.script = []   # lines pushed to every client after its first request
        self.delay = 0
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        while True:
            try:
                c, _ = self.srv.accept()
            except OSError:
                return
            threading.Thread(target=self.client, args=(c,), daemon=True).start()

    def client(self, c):
        f = c.makefile("rb")
        first = True
        for line in f:
            req = json.loads(line)
            self.received.append(req)
            if req["op"] == "tx":
                ev = {"ch": "ctl", "ev": "tx_queued"} if self.send_enabled else {"ch": "ctl", "ev": "tx_refused", "why": "send_disabled"}
                c.sendall((json.dumps(ev) + "\n").encode())
            elif req["op"] == "module":
                ok = req["name"] != "send"
                c.sendall((json.dumps({"ch": "ctl", "ev": "module_ok" if ok else "module_refused"}) + "\n").encode())
            elif req["op"] == "status":
                c.sendall((json.dumps({"ch": "ctl", "ev": "status", "status": {"can_up": True}}) + "\n").encode())
            elif first:
                time.sleep(self.delay)
                for s in self.script:
                    c.sendall((json.dumps(s) + "\n").encode())
                c.shutdown(socket.SHUT_WR)
            first = False


def run(app, *argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = wfabia.main(["--port", str(app.port)] + list(argv))
    return code, out.getvalue(), err.getvalue()


class Tests(unittest.TestCase):
    def test_candump_filter_and_format(self):
        app = FakeApp()
        app.script = [{"ch": "can", "t": 1761731034.512, "dir": "rx", "id": "0x40C", "d": "19000000"},
                      {"ch": "can", "t": 1761731034.6, "dir": "tx", "id": "0x439", "d": "000100000000"}]
        code, out, err = run(app, "candump", "--filter", "0x40C,0x439")
        self.assertEqual(app.received[0], {"op": "sub", "ch": ["can"], "can_ids": ["0x40C", "0x439"]})
        self.assertIn("40C   RX [4]  19 00 00 00", out)
        self.assertIn("439   TX [6]  00 01 00 00 00 00", out)

    def test_cansend_refused_then_queued(self):
        app = FakeApp(send_enabled=False)
        code, out, err = run(app, "cansend", "5A0#01 FF")
        self.assertEqual(code, 1)
        self.assertIn("send_disabled", err)
        self.assertEqual(app.received[0], {"op": "tx", "id": "0x5A0", "d": "01FF", "ttl_ms": 500})
        app2 = FakeApp(send_enabled=True)
        code, out, err = run(app2, "cansend", "0x5A0#01FF", "--ttl", "100")
        self.assertEqual(code, 0)
        self.assertIn("queued", out)
        self.assertEqual(app2.received[0]["ttl_ms"], 100)

    def test_zlink_plain_and_serve(self):
        app = FakeApp()
        app.script = [{"ch": "zlink", "t": 1.5, "type": "0x8006", "raw": "0a1f"}]
        code, out, err = run(app, "zlink")
        self.assertIn("0x8006 0a1f", out)

        # --serve: a navlive-style client must get the original binary framing
        app = FakeApp()
        app.delay = 1.0     # push the frame only after the client below has connected
        app.script = [{"ch": "zlink", "t": 1.5, "type": "0x8007", "raw": "0a07"}]
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        threading.Thread(target=lambda: run(app, "zlink", "--serve", str(port)), daemon=True).start()
        c = None
        for _ in range(100):
            try:
                c = socket.create_connection(("127.0.0.1", port), timeout=3)
                break
            except OSError:
                time.sleep(0.05)
        self.assertIsNotNone(c)
        want = struct.pack("<II", 0x8007, 2) + bytes.fromhex("0a07")
        self.assertEqual(c.recv(100), want)

    def test_log_formats(self):
        import os
        import tempfile
        app = FakeApp()
        app.script = [{"ch": "zlink", "t": 2.0, "type": "0x8006", "raw": "0a1f"},
                      {"ch": "can", "t": 3.0, "dir": "rx", "id": "0x2E8", "d": "39D099"}]
        path = os.path.join(tempfile.mkdtemp(), "drive.jsonl")
        run(app, "log", path)
        lines = [json.loads(l) for l in open(path, encoding="utf-8")]
        self.assertEqual(lines[0], {"t": 2.0, "type": 0x8006, "raw_hex": "0a1f"})   # fox_sniff format
        self.assertEqual(lines[1]["ch"], "can")
        self.assertNotIn("type", lines[1])

    def test_module_and_status(self):
        app = FakeApp()
        code, out, err = run(app, "module", "display", "off")
        self.assertEqual(code, 0)
        self.assertEqual(app.received[0], {"op": "module", "name": "display", "enabled": False})
        code, out, err = run(FakeApp(), "module", "send", "on")
        self.assertEqual(code, 1)
        code, out, err = run(FakeApp(), "status")
        self.assertIn('"can_up": true', out)

    def test_no_adb_forward_message(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = wfabia.main(["--port", str(port), "status"])
        self.assertEqual(code, 3)
        self.assertIn("adb forward", err.getvalue())


if __name__ == "__main__":
    unittest.main()
