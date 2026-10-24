package com.clean.tvhome;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

/** The TV's own inputs (HDMI, AV), shared by Home's rail and the screensaver. */
final class TvSources {

    private TvSources() {}

    /** A physical input: a passthrough source that isn't a child of another (an HDMI-CEC device is). */
    static boolean isSource(TvInputInfo info) {
        return info.isPassthroughInput() && info.getParentId() == null;
    }

    /** The TV reports a device on this input as switched on. */
    static boolean isConnected(TvInputManager tvInputs, String inputId) {
        try {
            return tvInputs.getInputState(inputId) == TvInputManager.INPUT_STATE_CONNECTED;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String label(Context context, TvInputInfo info) {
        CharSequence l = info.loadCustomLabel(context);
        if (l == null || l.length() == 0) l = info.loadLabel(context);
        // Firmware labels read "HDMI1"; space them like the TV's own source menu.
        return l == null ? info.getId() : l.toString().replaceAll("^(HDMI|AV)(\\d)", "$1 $2");
    }

    /** The first input with a device switched on, or null. */
    static String connectedLabel(Context context, TvInputManager tvInputs) {
        if (tvInputs == null) return null;
        for (TvInputInfo info : tvInputs.getTvInputList()) {
            if (isSource(info) && isConnected(tvInputs, info.getId())) return label(context, info);
        }
        return null;
    }
}
