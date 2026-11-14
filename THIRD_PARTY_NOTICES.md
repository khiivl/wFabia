# Third-party notices

wFabia is licensed under GPL-3.0-or-later (see `LICENSE`).

| Component | Where | Licence |
|---|---|---|
| maxidot_can / maxidot-fis (khiivl/fabia-mk1-maxidot-can): the FIS link, SLCAN, transport and font code is ported to Java from it | `app/src/main/java/de/wfabia/core/` (`FisLink`, `FisTransport`, `Slcan`, `MaxidotFont`, `FrameBuffer`) and `reference/maxidot_can/` | GPL-3.0-or-later |
| usb-serial-for-android (mik3y) | Gradle dependency | MIT |
| AndroidX, Material Components | Gradle dependencies | Apache-2.0 |
| Python reference implementations (`navdecode.py`, `maxidot_sim.py`, `fox_sniff.py`, `gps_speed.py`, `navlive.py`) by the project author | `reference/python/` | this repository's licence, unless a file says otherwise |

Open Headunit (andreknieriem/open-headunit) is **not** part of this repository and is not linked into wFabia. The two apps talk
through a broadcast (`docs/CONTRACT.md`). Open Headunit and any fork of it are licensed under AGPL-3.0.

The Android Auto protocol is Google's. Nothing here is affiliated with or endorsed by Google, Skoda, VDO or Open Headunit.
