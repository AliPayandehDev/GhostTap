package com.icysnex.ghosttap.core.jump;

import com.icysnex.ghosttap.config.ConfigCodec;
import com.icysnex.ghosttap.core.Defaults;
import com.icysnex.ghosttap.core.analytics.JumpResetData;
import com.icysnex.ghosttap.core.analytics.JumpResetTracker;
import com.icysnex.ghosttap.core.analytics.Tracker;
import com.icysnex.ghosttap.core.click.Variance;
import com.icysnex.ghosttap.core.input.InputKeyboard;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// Correlates the local player's vanilla hurt and velocity packets, applies the
// configured delay, then injects a real-looking Space press at LWJGL's layer.
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
    public boolean onGroundOnly;
    public boolean sprintingOnly;
    public boolean forwardOnly;
    public boolean allowInMenu;

    private final Defaults.JumpResetProfile defaults;
    private volatile boolean enabled;
    private final AtomicLong scheduleGeneration = new AtomicLong();
    private final ScheduledExecutorService scheduler;
    private boolean pendingHurtStatus;
    private long pendingHurtNanos;
    private volatile long clientTick;
    private volatile int observedSyntheticTicks;
    private volatile boolean holdElapsed;
    private volatile JumpResetData awaitingInputTick;
    private volatile JumpResetData activeAnalytics;
    private volatile long activePressStartNanos;

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
            pendingHurtStatus = false;
            scheduleGeneration.incrementAndGet();
            finishActiveAnalytics();
            awaitingInputTick = null;
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
        if (event.phase == TickEvent.Phase.START) {
            clientTick++;
            JumpResetData awaiting = awaitingInputTick;
            if (awaiting != null && InputKeyboard.isSpaceDown()) {
                awaiting.markInputTick(clientTick);
                awaitingInputTick = null;
            }

            // Vanilla queues S19 and S12 together before START. Do not let an
            // unrelated status packet arm a velocity packet from a later tick.
            pendingHurtStatus = false;
            return;
        }

        if (event.phase == TickEvent.Phase.END)
            observeSyntheticPress();
    }

    // Called from the packet-handler mixin after the local player's hurt status
    // has been applied. Both callbacks run on Minecraft's main thread.
    public void onLocalHurtStatus() {
        pendingHurtStatus = true;
        pendingHurtNanos = Tracker.enabled ? System.nanoTime() : -1;
    }

    // S19 + S12 is the vanilla melee-hit sequence. Trigger directly from S12's
    // tail so a zero-delay press is visible to this tick's keyboard/movement pass.
    public void onLocalVelocity(EntityPlayerSP player, boolean screenOpen,
                                int velocityX, int velocityY, int velocityZ,
                                long velocityNanos) {
        if (!pendingHurtStatus)
            return;
        pendingHurtStatus = false;

        long hurtToVelocityNanos = velocityNanos >= 0 && pendingHurtNanos >= 0
                ? Math.max(0, velocityNanos - pendingHurtNanos) : -1;
        long hitInputTick = clientTick + 1;
        boolean onGround = player.onGround;
        boolean sprinting = player.isSprinting();
        boolean movingForward = player.movementInput != null && player.movementInput.moveForward > 0;
        boolean physicalSpaceDown = Tracker.enabled
                && InputKeyboard.realSpace == InputKeyboard.STATE_DOWN;

        JumpResetData.Outcome outcome = outcome(screenOpen, onGround, sprinting, movingForward);
        if (outcome != JumpResetData.Outcome.TRIGGERED) {
            record(outcome, velocityNanos, hurtToVelocityNanos, -1, hitInputTick,
                    onGround, sprinting, movingForward, screenOpen, physicalSpaceDown,
                    velocityX, velocityY, velocityZ);
            return;
        }

        double probability = Math.max(0, Math.min(1, chance));
        if (probability <= 0 || (probability < 1 && !Variance.chance(probability))) {
            record(JumpResetData.Outcome.SKIP_CHANCE, velocityNanos, hurtToVelocityNanos, -1, hitInputTick,
                    onGround, sprinting, movingForward, screenOpen, physicalSpaceDown,
                    velocityX, velocityY, velocityZ);
            return;
        }

        long delayMs = sampleDelayMs();
        JumpResetData data = record(JumpResetData.Outcome.TRIGGERED, velocityNanos,
                hurtToVelocityNanos, delayMs, hitInputTick, onGround, sprinting,
                movingForward, screenOpen, physicalSpaceDown, velocityX, velocityY, velocityZ);
        schedulePress(delayMs, velocityNanos, data);
    }

    private JumpResetData.Outcome outcome(boolean screenOpen, boolean onGround,
                                          boolean sprinting, boolean movingForward) {
        if (!enabled)
            return JumpResetData.Outcome.SKIP_DISABLED;
        if (!allowInMenu && screenOpen)
            return JumpResetData.Outcome.SKIP_MENU;
        if (onGroundOnly && !onGround)
            return JumpResetData.Outcome.SKIP_AIRBORNE;
        if (sprintingOnly && !sprinting)
            return JumpResetData.Outcome.SKIP_NOT_SPRINTING;
        if (forwardOnly && !movingForward)
            return JumpResetData.Outcome.SKIP_NOT_FORWARD;
        return JumpResetData.Outcome.TRIGGERED;
    }

    private JumpResetData record(JumpResetData.Outcome outcome, long velocityNanos,
                                 long hurtToVelocityNanos, long delayMs, long hitInputTick,
                                 boolean onGround, boolean sprinting, boolean movingForward,
                                 boolean screenOpen, boolean physicalSpaceDown,
                                 int velocityX, int velocityY, int velocityZ) {
        if (!Tracker.enabled)
            return null;

        long decisionNanos = velocityNanos >= 0 ? Math.max(0, System.nanoTime() - velocityNanos) : -1;
        JumpResetData data = new JumpResetData(System.currentTimeMillis(), outcome,
                hurtToVelocityNanos, decisionNanos, delayMs, hitInputTick,
                onGround, sprinting, movingForward, screenOpen, physicalSpaceDown,
                velocityX, velocityY, velocityZ);
        return JumpResetTracker.INSTANCE.record(data);
    }

    private long sampleDelayMs() {
        double deviation = Math.max(0, delayStandardDeviationMs);
        double delay = deviation == 0 ? delayMeanMs : Variance.gaussian(delayMeanMs, deviation);
        delay = Math.max(delayMinMs, Math.min(delayMaxMs, delay));
        return Math.max(0, Math.round(delay));
    }

    private long sampleHoldMs() {
        double deviation = Math.max(0, holdStandardDeviationMs);
        double hold = deviation == 0 ? holdMeanMs : Variance.gaussian(holdMeanMs, deviation);
        hold = Math.max(holdMinMs, Math.min(holdMaxMs, hold));
        return Math.max(0, Math.round(hold));
    }

    private void schedulePress(long delayMs, long velocityNanos, JumpResetData data) {
        final long generation = scheduleGeneration.incrementAndGet();
        if (delayMs == 0) {
            beginPress(generation, velocityNanos, data);
            return;
        }

        scheduler.schedule(() -> beginPress(generation, velocityNanos, data), delayMs, TimeUnit.MILLISECONDS);
    }

    private void beginPress(final long generation, long velocityNanos, JumpResetData data) {
        if (!enabled || scheduleGeneration.get() != generation) {
            if (data != null)
                data.outcome = JumpResetData.Outcome.CANCELLED;
            return;
        }

        observedSyntheticTicks = 0;
        holdElapsed = false;
        InputKeyboard.downSpace();
        long pressStartNanos = System.nanoTime();
        // Hold sampling is deliberately after the down edge: it cannot influence
        // eligibility or press timing, so it stays off the latency-critical path.
        long holdMs = sampleHoldMs();
        if (data != null) {
            data.markPressed(pressStartNanos, velocityNanos, holdMs);
            awaitingInputTick = data;
            activeAnalytics = data;
            activePressStartNanos = pressStartNanos;
        }
        scheduler.schedule(() -> {
            if (enabled && scheduleGeneration.get() == generation) {
                holdElapsed = true;
                releaseIfReady(generation);
            }
        }, holdMs, TimeUnit.MILLISECONDS);
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
            finishActiveAnalytics();
            InputKeyboard.upSpace();
            observedSyntheticTicks = 0;
            holdElapsed = false;
        }
    }

    private void finishActiveAnalytics() {
        JumpResetData data = activeAnalytics;
        if (data != null) {
            data.markReleased(System.nanoTime() - activePressStartNanos);
            activeAnalytics = null;
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
        onGroundOnly = defaults.onGroundOnly;
        sprintingOnly = defaults.sprintingOnly;
        forwardOnly = defaults.forwardOnly;
        allowInMenu = defaults.allowInMenu;
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
        ConfigCodec.put(values, "onGroundOnly", onGroundOnly);
        ConfigCodec.put(values, "sprintingOnly", sprintingOnly);
        ConfigCodec.put(values, "forwardOnly", forwardOnly);
        ConfigCodec.put(values, "allowInMenu", allowInMenu);
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
        onGroundOnly = ConfigCodec.flag(values, "onGroundOnly", onGroundOnly);
        sprintingOnly = ConfigCodec.flag(values, "sprintingOnly", sprintingOnly);
        forwardOnly = ConfigCodec.flag(values, "forwardOnly", forwardOnly);
        allowInMenu = ConfigCodec.flag(values, "allowInMenu", allowInMenu);
        return true;
    }
}
