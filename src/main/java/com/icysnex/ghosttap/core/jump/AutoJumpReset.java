package com.icysnex.ghosttap.core.jump;

import com.icysnex.ghosttap.config.ConfigCodec;
import com.icysnex.ghosttap.core.Defaults;
import com.icysnex.ghosttap.core.click.Variance;
import com.icysnex.ghosttap.core.input.InputKeyboard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// Detects a new local-player hurt animation, applies the configured humanized
// delay, then injects a real-looking Space press at LWJGL's keyboard layer.
public final class AutoJumpReset {

    public static final AutoJumpReset INSTANCE = new AutoJumpReset(Defaults.JUMP_RESET);
    private static final String TOKEN = "ghosttap-jump-reset";

    public double chance;
    public double delayMeanMs;
    public double delayStandardDeviationMs;
    public double delayMinMs;
    public double delayMaxMs;
    public double holdMeanMs;
    public double holdStandardDeviationMs;
    public double holdMinMs;
    public double holdMaxMs;

    private final Defaults.JumpResetProfile defaults;
    private volatile boolean enabled;
    private final AtomicLong scheduleGeneration = new AtomicLong();
    private final ScheduledExecutorService scheduler;
    private int previousHurtTime;
    private volatile int observedSyntheticTicks;
    private volatile boolean holdElapsed;

    private AutoJumpReset(Defaults.JumpResetProfile defaults) {
        this.defaults = defaults;
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "GhostTap-JumpReset");
                thread.setDaemon(true);
                return thread;
            }
        });
        reset();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            scheduleGeneration.incrementAndGet();
            InputKeyboard.upSpace();
            observedSyntheticTicks = 0;
            holdElapsed = false;
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;

        observeSyntheticPress();

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            previousHurtTime = 0;
            return;
        }

        int hurtTime = player.hurtTime;
        boolean newlyHurt = hurtTime > previousHurtTime;
        previousHurtTime = hurtTime;

        if (!enabled || mc.currentScreen != null || player.isDead || !newlyHurt)
            return;

        double probability = Math.max(0, Math.min(1, chance));
        if (!Variance.chance(probability))
            return;

        schedulePress(sampleDelayMs(), sampleHoldMs());
    }

    private long sampleDelayMs() {
        double delay = Variance.gaussian(delayMeanMs, Math.max(0, delayStandardDeviationMs));
        delay = Math.max(delayMinMs, Math.min(delayMaxMs, delay));
        return Math.max(0, Math.round(delay));
    }

    private long sampleHoldMs() {
        double hold = Variance.gaussian(holdMeanMs, Math.max(0, holdStandardDeviationMs));
        hold = Math.max(holdMinMs, Math.min(holdMaxMs, hold));
        return Math.max(0, Math.round(hold));
    }

    private void schedulePress(long delayMs, final long holdMs) {
        final long generation = scheduleGeneration.incrementAndGet();
        scheduler.schedule(() -> {
            if (enabled && scheduleGeneration.get() == generation) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.thePlayer != null && !mc.thePlayer.isDead && mc.currentScreen == null) {
                    observedSyntheticTicks = 0;
                    holdElapsed = false;
                    InputKeyboard.downSpace();
                    scheduler.schedule(() -> {
                        if (enabled && scheduleGeneration.get() == generation) {
                            holdElapsed = true;
                            releaseIfReady(generation);
                        }
                    }, holdMs, TimeUnit.MILLISECONDS);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // A sampled hold may finish at sub-tick precision, but release only after the
    // press has crossed a complete client tick so Minecraft cannot miss it.
    private void observeSyntheticPress() {
        if (!InputKeyboard.isSpaceDown()) {
            observedSyntheticTicks = 0;
            return;
        }

        observedSyntheticTicks++;
        releaseIfReady(scheduleGeneration.get());
    }

    private void releaseIfReady(long generation) {
        if (scheduleGeneration.get() == generation && holdElapsed && observedSyntheticTicks >= 2) {
            InputKeyboard.upSpace();
            observedSyntheticTicks = 0;
            holdElapsed = false;
        }
    }

    public void reset() {
        chance = defaults.chance;
        delayMeanMs = defaults.delayMeanMs;
        delayStandardDeviationMs = defaults.delayStandardDeviationMs;
        delayMinMs = defaults.delayMinMs;
        delayMaxMs = defaults.delayMaxMs;
        holdMeanMs = defaults.holdMeanMs;
        holdStandardDeviationMs = defaults.holdStandardDeviationMs;
        holdMinMs = defaults.holdMinMs;
        holdMaxMs = defaults.holdMaxMs;
    }

    public String export() {
        Map<String, String> values = ConfigCodec.map();
        ConfigCodec.put(values, "chance", chance);
        ConfigCodec.put(values, "delayMeanMs", delayMeanMs);
        ConfigCodec.put(values, "delayStandardDeviationMs", delayStandardDeviationMs);
        ConfigCodec.put(values, "delayMinMs", delayMinMs);
        ConfigCodec.put(values, "delayMaxMs", delayMaxMs);
        ConfigCodec.put(values, "holdMeanMs", holdMeanMs);
        ConfigCodec.put(values, "holdStandardDeviationMs", holdStandardDeviationMs);
        ConfigCodec.put(values, "holdMinMs", holdMinMs);
        ConfigCodec.put(values, "holdMaxMs", holdMaxMs);
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
        holdMeanMs = ConfigCodec.number(values, "holdMeanMs", holdMeanMs);
        holdStandardDeviationMs = ConfigCodec.number(values, "holdStandardDeviationMs", holdStandardDeviationMs);
        holdMinMs = ConfigCodec.number(values, "holdMinMs", holdMinMs);
        holdMaxMs = ConfigCodec.number(values, "holdMaxMs", holdMaxMs);
        return true;
    }
}
