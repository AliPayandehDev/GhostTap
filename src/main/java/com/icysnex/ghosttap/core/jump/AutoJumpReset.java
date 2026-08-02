package com.icysnex.ghosttap.core.jump;

import com.icysnex.ghosttap.config.ConfigCodec;
import com.icysnex.ghosttap.core.Defaults;

import java.util.Map;

// Configuration and activation state for the future auto jump reset module.
// No gameplay behavior is implemented here yet.
public final class AutoJumpReset {

    public static final AutoJumpReset INSTANCE = new AutoJumpReset(Defaults.JUMP_RESET);
    private static final String TOKEN = "ghosttap-jump-reset";

    public double chance;
    public double delayMeanMs;
    public double delayStandardDeviationMs;
    public double delayMinMs;
    public double delayMaxMs;

    private final Defaults.JumpResetProfile defaults;
    private volatile boolean enabled;

    private AutoJumpReset(Defaults.JumpResetProfile defaults) {
        this.defaults = defaults;
        reset();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    public void reset() {
        chance = defaults.chance;
        delayMeanMs = defaults.delayMeanMs;
        delayStandardDeviationMs = defaults.delayStandardDeviationMs;
        delayMinMs = defaults.delayMinMs;
        delayMaxMs = defaults.delayMaxMs;
    }

    public String export() {
        Map<String, String> values = ConfigCodec.map();
        ConfigCodec.put(values, "chance", chance);
        ConfigCodec.put(values, "delayMeanMs", delayMeanMs);
        ConfigCodec.put(values, "delayStandardDeviationMs", delayStandardDeviationMs);
        ConfigCodec.put(values, "delayMinMs", delayMinMs);
        ConfigCodec.put(values, "delayMaxMs", delayMaxMs);
        return ConfigCodec.encode(TOKEN, values);
    }

    public boolean importFrom(String token) {
        Map<String, String> values = ConfigCodec.decode(TOKEN, token);
        if (values == null)
            return false;

        chance = ConfigCodec.number(values, "chance", chance);
        delayMeanMs = ConfigCodec.number(values, "delayMeanMs", delayMeanMs);
        delayStandardDeviationMs = ConfigCodec.number(values, "delayStandardDeviationMs", delayStandardDeviationMs);
        delayMinMs = ConfigCodec.number(values, "delayMinMs", delayMinMs);
        delayMaxMs = ConfigCodec.number(values, "delayMaxMs", delayMaxMs);
        return true;
    }
}
