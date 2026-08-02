package com.icysnex.ghosttap.core.wtap;

import com.icysnex.ghosttap.config.ConfigCodec;
import com.icysnex.ghosttap.core.Defaults;

import java.util.Map;

// Settings-only shell for a possible future W-Tap implementation.
public final class AutoWTap {

    public static final AutoWTap INSTANCE = new AutoWTap(Defaults.W_TAP);
    private static final String TOKEN = "ghosttap-w-tap";

    public double chance;
    public double delayMeanMs;
    public double delayStandardDeviationMs;
    public double delayMinMs;
    public double delayMaxMs;
    public double releaseMeanMs;
    public double releaseStandardDeviationMs;
    public double releaseMinMs;
    public double releaseMaxMs;
    public boolean playersOnly;
    public boolean weapons;
    public boolean tools;
    public boolean blocks;
    public boolean other;

    private final Defaults.WTapProfile defaults;
    private boolean enabled;

    private AutoWTap(Defaults.WTapProfile defaults) {
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
        releaseMeanMs = defaults.releaseMeanMs;
        releaseStandardDeviationMs = defaults.releaseStandardDeviationMs;
        releaseMinMs = defaults.releaseMinMs;
        releaseMaxMs = defaults.releaseMaxMs;
        playersOnly = defaults.playersOnly;
        weapons = defaults.weapons;
        tools = defaults.tools;
        blocks = defaults.blocks;
        other = defaults.other;
    }

    public String export() {
        Map<String, String> values = ConfigCodec.map();
        ConfigCodec.put(values, "chance", chance);
        ConfigCodec.put(values, "delayMeanMs", delayMeanMs);
        ConfigCodec.put(values, "delayStandardDeviationMs", delayStandardDeviationMs);
        ConfigCodec.put(values, "delayMinMs", delayMinMs);
        ConfigCodec.put(values, "delayMaxMs", delayMaxMs);
        ConfigCodec.put(values, "releaseMeanMs", releaseMeanMs);
        ConfigCodec.put(values, "releaseStandardDeviationMs", releaseStandardDeviationMs);
        ConfigCodec.put(values, "releaseMinMs", releaseMinMs);
        ConfigCodec.put(values, "releaseMaxMs", releaseMaxMs);
        ConfigCodec.put(values, "playersOnly", playersOnly);
        ConfigCodec.put(values, "weapons", weapons);
        ConfigCodec.put(values, "tools", tools);
        ConfigCodec.put(values, "blocks", blocks);
        ConfigCodec.put(values, "other", other);
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
        releaseMeanMs = ConfigCodec.number(values, "releaseMeanMs", releaseMeanMs);
        releaseStandardDeviationMs = ConfigCodec.number(values, "releaseStandardDeviationMs", releaseStandardDeviationMs);
        releaseMinMs = ConfigCodec.number(values, "releaseMinMs", releaseMinMs);
        releaseMaxMs = ConfigCodec.number(values, "releaseMaxMs", releaseMaxMs);
        playersOnly = ConfigCodec.flag(values, "playersOnly", playersOnly);
        weapons = ConfigCodec.flag(values, "weapons", weapons);
        tools = ConfigCodec.flag(values, "tools", tools);
        blocks = ConfigCodec.flag(values, "blocks", blocks);
        other = ConfigCodec.flag(values, "other", other);
        return true;
    }
}
