#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes golden/expected/<capture>.rows.txt with the Python reference (reference/python/maxidot_sim.py --plain --gps 87).

    python tools/make_expected.py              # all captures in golden/captures
    python tools/make_expected.py some.jsonl   # one capture

The Java renderer must produce the same sequence of row pairs (GoldenTest). Only the Python reference is the authority:
regenerate these files only when the reference itself changed on purpose.
"""
import os
import subprocess
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
REF = os.path.join(ROOT, "reference", "python")
CAPS = os.path.join(ROOT, "golden", "captures")
EXPECTED = os.path.join(ROOT, "golden", "expected")


def run(name):
    cap = os.path.abspath(os.path.join(CAPS, name))
    p = subprocess.run([sys.executable, "-X", "utf8", "maxidot_sim.py", "--replay", cap, "--plain", "--gps", "87"],
                       cwd=REF, capture_output=True, check=True)
    os.makedirs(EXPECTED, exist_ok=True)
    out = os.path.join(EXPECTED, name + ".rows.txt")
    text = p.stdout.decode("utf-8").replace("\r\n", "\n")   # the same bytes on every OS
    with open(out, "wb") as f:
        f.write(text.encode("utf-8"))
    print(name, "->", len(p.stdout.decode("utf-8").splitlines()), "screens")


if __name__ == "__main__":
    names = sys.argv[1:] or sorted(n for n in os.listdir(CAPS) if n.endswith((".jsonl", ".bin")))
    for n in names:
        run(n)
