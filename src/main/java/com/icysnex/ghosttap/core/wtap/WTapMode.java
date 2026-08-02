package com.icysnex.ghosttap.core.wtap;

public enum WTapMode {
    W_TAP("W-Tap"),
    S_TAP("S-Tap"),
    SHIFT_TAP("Shift-Tap");

    public final String label;

    WTapMode(String label) {
        this.label = label;
    }
}
