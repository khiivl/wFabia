"""
gps_speed.py - the one place that knows where the GPS speed comes from. STUB: returns None (= speed unknown, the
cluster's row 2 stays empty) until you fill in get_gps_speed_kmh() with the head unit's real source.

Contract:
    get_gps_speed_kmh() -> float | None
        km/h, as fresh as possible. Must be fast and must never raise or block (it is called about once per display
        refresh, ~4x a second). Return None when there is no fix or the value is older than a few seconds.

Ideas for the head unit (none of these is implemented or tested yet):
    - dumpsys location: maxidot_sim.gps_dumpsys() already parses 'vel=<m/s>' from the last GPS fix (slow: ~100+ ms, call
      it from a background thread and cache).
    - the head unit's own GPS service / NMEA stream (a $GPRMC / $GPVTG sentence has the speed over ground);
    - CAN: the car's own speed signal, if you ever want it independent of GPS.
"""
import time

_last = {"v": None, "t": 0.0}


def get_gps_speed_kmh():
    # TODO: replace with the real source. Example shape for a cached, non-blocking implementation:
    #   if time.time() - _last["t"] > 1.0: start a background read that updates _last
    #   return _last["v"] if time.time() - _last["t"] < 5.0 else None
    return None
