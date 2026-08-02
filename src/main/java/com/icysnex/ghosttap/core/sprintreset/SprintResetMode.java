package com.icysnex.ghosttap.core.sprintreset;

public enum SprintResetMode {
    W_TAP("W-Tap"),
    S_TAP("S-Tap"),
    SHIFT_TAP("Shift-Tap");

    public final String label;

    SprintResetMode(String label) {
        this.label = label;
    }
}
