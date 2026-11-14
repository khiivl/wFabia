// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.core;

/**
 * The frame contract between an Android Auto receiver app (Open Headunit, or a fork) and an app that wants the raw
 * navigation and media messages (wFabia). See docs/CONTRACT.md.
 *
 * The receiver app sends, for every message on the navigation and media-playback channels, an explicit broadcast
 * (setPackage) to each app on its whitelist:
 *
 *   action   {@value #ACTION_FRAME}
 *   v        int     contract version, 1
 *   channel  String  "nav" or "media"   (the Android Auto message ids repeat per channel)
 *   msg      int     Android Auto message id, as on the wire (for example 32774)
 *   payload  byte[]  the protobuf payload of that message, unchanged (media metadata without the album art unless the user allowed it)
 *   seq      long    running counter, to notice gaps
 *
 * The payloads are the Android Auto protobufs, so the receiver does all the parsing: the sender stays universal.
 */
public final class OpenHuContract {
    private OpenHuContract() {}

    public static final String ACTION_FRAME = "com.andrerinas.openheadunit.AAP_FRAME";
    public static final int VERSION = 1;
    public static final String EXTRA_VERSION = "v", EXTRA_CHANNEL = "channel", EXTRA_MSG = "msg", EXTRA_PAYLOAD = "payload", EXTRA_SEQ = "seq";
    public static final String CH_NAV = "nav", CH_MEDIA = "media";

    /** Open Headunit's own session-state broadcast (state: connecting, connected, projecting, disconnected, failed) */
    public static final String[] SESSION_ACTIONS = {
        "com.andrerinas.headunitrevived.SESSION_STATE", "com.andrerinas.openheadunit.SESSION_STATE"};
    public static final String EXTRA_SESSION_STATE = "state", EXTRA_SESSION_TRANSPORT = "transport", EXTRA_SESSION_REASON = "reason";

    /** package names Open Headunit is known under (Play build, GitHub build) */
    public static final String[] PACKAGES = {"com.andrerinas.openheadunit", "com.andrerinas.headunitrevived"};

    // Android Auto message ids (NavigationStatus.MsgType and MediaPlayback), as in Open Headunit's protocol classes
    public static final int NAV_START = 32769, NAV_STOP = 32770, NAV_STATUS = 32771, NAV_NEXT_TURN = 32772, NAV_NEXT_DISTANCE = 32773,
        NAV_STATE = 32774, NAV_POSITION = 32775;
    public static final int MEDIA_STATUS = 32769, MEDIA_INPUT = 32770, MEDIA_METADATA = 32771;

    /** a frame in the form the Zlink tap delivers: the decoder, tracker and renderer take it unchanged */
    public static final class Frame {
        public final int type;
        public final byte[] payload;

        Frame(int type, byte[] payload) { this.type = type; this.payload = payload; }
    }

    /** @return the matching Zlink-style frame, or null for messages the display does not use */
    public static Frame translate(String channel, int msg, byte[] payload) {
        if (channel == null || payload == null) return null;
        if (CH_NAV.equals(channel)) {
            switch (msg) {
                case NAV_STATE: return new Frame(ZlinkDecoder.T_STATE, payload);           // 0x8006, the same number
                case NAV_POSITION: return new Frame(ZlinkDecoder.T_PROGRESS, payload);     // 0x8007, the same number
                case NAV_STATUS: return new Frame(ZlinkDecoder.T_NAVI_STATUS, payload);    // status 1 active, 2 inactive, 3 rerouting
                case NAV_STOP: return new Frame(ZlinkDecoder.T_NAVI_STATUS, new byte[]{0x08, 0x02});
                default: return null;                                                      // start, legacy next-turn messages
            }
        }
        if (CH_MEDIA.equals(channel)) {
            switch (msg) {
                case MEDIA_METADATA: return new Frame(ZlinkDecoder.T_MEDIA_META, payload);
                case MEDIA_STATUS: return new Frame(ZlinkDecoder.T_MEDIA_STATUS, payload);
                default: return null;
            }
        }
        return null;
    }
}
