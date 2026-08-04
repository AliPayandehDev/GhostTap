package com.icysnex.ghosttap.core.jump;

import com.icysnex.ghosttap.config.ConfigCodec;
import com.icysnex.ghosttap.core.Defaults;
import com.icysnex.ghosttap.core.analytics.JumpResetData;
import com.icysnex.ghosttap.core.analytics.JumpResetTracker;
import com.icysnex.ghosttap.core.analytics.Tracker;
import com.icysnex.ghosttap.core.click.Variance;
import com.icysnex.ghosttap.core.input.InputKeyboard;
import com.icysnex.ghosttap.core.sprintreset.AutoSprintReset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

// Correlates the local player's vanilla hurt and velocity packets, then applies
// the response at the next movement boundary. The sampled hold is never released
// before one complete movement update. KeyBinding drives gameplay while the
// LWJGL override mirrors the same state to Keystrokes HUDs.
public final class AutoJumpReset {

    public static final AutoJumpReset INSTANCE = new AutoJumpReset(Defaults.JUMP_RESET);
    private static final String TOKEN = "ghosttap-jump-reset";

    public double chance;
    public double holdMeanMs;
    public double holdStandardDeviationMs;
    public double holdMinMs;
    public double holdMaxMs;
    public double minimumHorizontalVelocity;
    public boolean onGroundOnly;
    public boolean sprintingOnly;
    public boolean forwardOnly;
    public boolean allowInMenu;

    private final Defaults.JumpResetProfile defaults;
    private final ScheduledExecutorService releaseScheduler;
    private boolean enabled;
    private boolean pendingHurtStatus;
    private long pendingHurtNanos;
    private ArmedJump armedJump;
    private ActiveJump activeJump;

    private AutoJumpReset(Defaults.JumpResetProfile defaults) {
        this.defaults = defaults;
        releaseScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "GhostTap-JumpReset-Release");
                thread.setDaemon(true);
                return thread;
            }
        });
        reset();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled)
            cancelAll();
    }

    public void toggle() {
        setEnabled(!isEnabled());
    }

    public synchronized boolean isMovementProtected() {
        return activeJump != null && !activeJump.movementSampled;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START)
            runStartTick();
        else
            runEndTick();
    }

    private synchronized void runStartTick() {
        Minecraft mc = Minecraft.getMinecraft();

        // A sampled hold may end between ticks, but the press cannot be released
        // until Minecraft has consumed it in at least one movement update.
        if (activeJump != null && activeJump.releaseRequested
                && activeJump.movementSampled)
            finishActive(mc);

        // Vanilla normally queues the paired S19 and S12 tasks before START.
        // A later S12 must not consume an unrelated hurt status.
        pendingHurtStatus = false;

        ArmedJump armed = armedJump;
        if (armed == null)
            return;
        if (activeJump != null)
            return;
        armedJump = null;

        EntityPlayerSP player = mc.thePlayer;
        if (!enabled || player == null || mc.theWorld == null) {
            cancel(armed.data);
            return;
        }

        String environment = blockedEnvironment(player);
        if (!"NORMAL".equals(environment)) {
            if (armed.data != null) {
                armed.data.outcome = JumpResetData.Outcome.SKIP_ENVIRONMENT;
                armed.data.environment = environment;
            }
            return;
        }

        if (onGroundOnly && !player.onGround) {
            setOutcome(armed.data, JumpResetData.Outcome.SKIP_AIRBORNE);
            return;
        }
        if (sprintingOnly && !player.isSprinting()) {
            setOutcome(armed.data, JumpResetData.Outcome.SKIP_NOT_SPRINTING);
            return;
        }
        if (forwardOnly && (player.movementInput == null || player.movementInput.moveForward <= 0)) {
            setOutcome(armed.data, JumpResetData.Outcome.SKIP_NOT_FORWARD);
            return;
        }

        int jumpKey = mc.gameSettings.keyBindJump.getKeyCode();
        if (jumpKey < 0 || jumpKey >= InputKeyboard.KEY_COUNT) {
            setOutcome(armed.data, JumpResetData.Outcome.SKIP_KEY_BINDING);
            return;
        }
        if (InputKeyboard.real(jumpKey) == InputKeyboard.STATE_DOWN) {
            setOutcome(armed.data, JumpResetData.Outcome.SKIP_JUMP_HELD);
            return;
        }

        boolean sprintResetDeferred = AutoSprintReset.INSTANCE.deferForJumpReset();
        InputKeyboard.forceDownPolled(jumpKey);
        KeyBinding.setKeyBindState(jumpKey, true);

        long now = System.nanoTime();
        long targetHoldMs = sampleHoldMs();
        ActiveJump active = new ActiveJump(armed, jumpKey, now, targetHoldMs);
        activeJump = active;
        if (armed.data != null) {
            armed.data.markPressed(now, armed.velocityNanos, player.motionX,
                    player.motionY, player.motionZ, sprintResetDeferred, targetHoldMs);
        }
        scheduleRelease(active);
    }

    private synchronized void runEndTick() {
        Minecraft mc = Minecraft.getMinecraft();
        ActiveJump active = activeJump;
        if (active == null)
            return;
        active.movementSampled = true;
        if (mc.thePlayer != null)
            active.armed.dataIfPresentEndMotion(mc.thePlayer);
        if (active.releaseRequested)
            finishActive(mc);
    }

    @SubscribeEvent
    public synchronized void onLivingJump(LivingEvent.LivingJumpEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (activeJump == null || mc.thePlayer == null || event.entityLiving != mc.thePlayer)
            return;
        if (activeJump.armed.data != null) {
            activeJump.armed.data.markJumped(System.nanoTime(), activeJump.armed.velocityNanos,
                    mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
        }
    }

    // Called from the packet-handler mixin after the local hurt status was applied.
    public synchronized void onLocalHurtStatus() {
        pendingHurtStatus = true;
        pendingHurtNanos = Tracker.enabled ? System.nanoTime() : -1;
    }

    // Called at S12 TAIL, after Minecraft has installed the velocity on the player.
    public synchronized void onLocalVelocity(EntityPlayerSP player, boolean screenOpen,
                                             int velocityX, int velocityY, int velocityZ,
                                             long velocityNanos) {
        if (!pendingHurtStatus)
            return;
        pendingHurtStatus = false;

        long hurtToVelocityNanos = velocityNanos >= 0 && pendingHurtNanos >= 0
                ? Math.max(0, velocityNanos - pendingHurtNanos) : -1;
        boolean onGround = player.onGround;
        boolean sprinting = player.isSprinting();
        boolean movingForward = player.movementInput != null && player.movementInput.moveForward > 0;
        Minecraft mc = Minecraft.getMinecraft();
        int jumpKey = mc.gameSettings.keyBindJump.getKeyCode();
        boolean physicalJumpDown = jumpKey >= 0 && jumpKey < InputKeyboard.KEY_COUNT
                && InputKeyboard.real(jumpKey) == InputKeyboard.STATE_DOWN;
        String environment = blockedEnvironment(player);
        double horizontalVelocity = Math.sqrt((double) velocityX * velocityX
                + (double) velocityZ * velocityZ) / 8000.0;

        JumpResetData.Outcome outcome = outcome(screenOpen, onGround, sprinting,
                movingForward, physicalJumpDown, environment, horizontalVelocity, jumpKey);
        if (outcome != JumpResetData.Outcome.TRIGGERED) {
            record(outcome, velocityNanos, hurtToVelocityNanos, onGround, sprinting,
                    movingForward, screenOpen, physicalJumpDown, environment, jumpKey,
                    velocityX, velocityY, velocityZ);
            return;
        }

        if (armedJump != null || activeJump != null) {
            record(JumpResetData.Outcome.SKIP_ALREADY_QUEUED, velocityNanos,
                    hurtToVelocityNanos, onGround, sprinting, movingForward, screenOpen,
                    physicalJumpDown, environment, jumpKey, velocityX, velocityY, velocityZ);
            return;
        }

        double probability = clamp(chance, 0, 1);
        if (probability <= 0 || (probability < 1 && !Variance.chance(probability))) {
            record(JumpResetData.Outcome.SKIP_CHANCE, velocityNanos, hurtToVelocityNanos,
                    onGround, sprinting, movingForward, screenOpen, physicalJumpDown,
                    environment, jumpKey, velocityX, velocityY, velocityZ);
            return;
        }

        JumpResetData data = record(JumpResetData.Outcome.TRIGGERED, velocityNanos,
                hurtToVelocityNanos, onGround, sprinting, movingForward, screenOpen,
                physicalJumpDown, environment, jumpKey, velocityX, velocityY, velocityZ);
        armedJump = new ArmedJump(velocityNanos, data);
    }

    private JumpResetData.Outcome outcome(boolean screenOpen, boolean onGround,
                                          boolean sprinting, boolean movingForward,
                                          boolean physicalJumpDown, String environment,
                                          double horizontalVelocity, int jumpKey) {
        if (!enabled)
            return JumpResetData.Outcome.SKIP_DISABLED;
        if (!allowInMenu && screenOpen)
            return JumpResetData.Outcome.SKIP_MENU;
        if (jumpKey < 0 || jumpKey >= InputKeyboard.KEY_COUNT)
            return JumpResetData.Outcome.SKIP_KEY_BINDING;
        if (physicalJumpDown)
            return JumpResetData.Outcome.SKIP_JUMP_HELD;
        if (!"NORMAL".equals(environment))
            return JumpResetData.Outcome.SKIP_ENVIRONMENT;
        if (onGroundOnly && !onGround)
            return JumpResetData.Outcome.SKIP_AIRBORNE;
        if (sprintingOnly && !sprinting)
            return JumpResetData.Outcome.SKIP_NOT_SPRINTING;
        if (forwardOnly && !movingForward)
            return JumpResetData.Outcome.SKIP_NOT_FORWARD;
        if (horizontalVelocity < clamp(minimumHorizontalVelocity, 0, 0.5))
            return JumpResetData.Outcome.SKIP_WEAK_VELOCITY;
        return JumpResetData.Outcome.TRIGGERED;
    }

    private JumpResetData record(JumpResetData.Outcome outcome, long velocityNanos,
                                 long hurtToVelocityNanos, boolean onGround,
                                 boolean sprinting, boolean movingForward,
                                 boolean screenOpen, boolean physicalJumpDown,
                                 String environment, int jumpKey,
                                 int velocityX, int velocityY, int velocityZ) {
        if (!Tracker.enabled)
            return null;
        long decisionNanos = velocityNanos >= 0
                ? Math.max(0, System.nanoTime() - velocityNanos) : -1;
        return JumpResetTracker.INSTANCE.record(new JumpResetData(System.currentTimeMillis(),
                outcome, hurtToVelocityNanos, decisionNanos, onGround, sprinting,
                movingForward, screenOpen, physicalJumpDown, environment, jumpKey,
                velocityX, velocityY, velocityZ));
    }

    private long sampleHoldMs() {
        double min = Math.max(1, Math.min(holdMinMs, holdMaxMs));
        double max = Math.max(min, Math.max(holdMinMs, holdMaxMs));
        double deviation = Math.max(0, holdStandardDeviationMs);
        double sampled = deviation == 0 ? holdMeanMs
                : Variance.gaussian(holdMeanMs, deviation);
        return Math.max(1, Math.round(clamp(sampled, min, max)));
    }

    public synchronized HudSnapshot hudSnapshot() {
        if (!enabled)
            return new HudSnapshot(false, false, "IDLE");

        ActiveJump active = activeJump;
        if (active != null)
            return new HudSnapshot(true, true, "JUMP");

        ArmedJump armed = armedJump;
        if (armed != null)
            return new HudSnapshot(true, true, "ARMED");

        if (pendingHurtStatus)
            return new HudSnapshot(true, true, "HIT");
        return new HudSnapshot(true, false, "IDLE");
    }

    private void scheduleRelease(final ActiveJump active) {
        releaseScheduler.schedule(new Runnable() {
            @Override
            public void run() {
                Minecraft mc = Minecraft.getMinecraft();
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (AutoJumpReset.this) {
                            if (activeJump != active)
                                return;
                            active.releaseRequested = true;
                            if (active.movementSampled)
                                finishActive(Minecraft.getMinecraft());
                        }
                    }
                });
            }
        }, active.targetHoldMs, TimeUnit.MILLISECONDS);
    }

    private void finishActive(Minecraft mc) {
        ActiveJump active = activeJump;
        activeJump = null;
        InputKeyboard.releasePolled(active.jumpKey);
        KeyBinding.setKeyBindState(active.jumpKey,
                InputKeyboard.real(active.jumpKey) == InputKeyboard.STATE_DOWN);

        EntityPlayerSP player = mc.thePlayer;
        if (active.armed.data != null) {
            if (player != null)
                active.armed.data.markCompleted(System.nanoTime() - active.startedNanos,
                        player.motionX, player.motionY, player.motionZ);
            else
                active.armed.data.markCompleted(System.nanoTime() - active.startedNanos,
                        Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private void cancelAll() {
        pendingHurtStatus = false;
        if (armedJump != null)
            cancel(armedJump.data);
        armedJump = null;
        if (activeJump != null) {
            InputKeyboard.releasePolled(activeJump.jumpKey);
            KeyBinding.setKeyBindState(activeJump.jumpKey,
                    InputKeyboard.real(activeJump.jumpKey) == InputKeyboard.STATE_DOWN);
            cancel(activeJump.armed.data);
        }
        activeJump = null;
    }

    private static String blockedEnvironment(EntityPlayerSP player) {
        if (player.capabilities.isFlying)
            return "FLYING";
        if (player.ridingEntity != null)
            return "RIDING";
        if (player.isInWater())
            return "WATER";
        if (player.isInLava())
            return "LAVA";
        if (player.isOnLadder())
            return "LADDER";
        return "NORMAL";
    }

    private static void setOutcome(JumpResetData data, JumpResetData.Outcome outcome) {
        if (data != null)
            data.outcome = outcome;
    }

    private static void cancel(JumpResetData data) {
        setOutcome(data, JumpResetData.Outcome.CANCELLED);
    }

    public void reset() {
        chance = defaults.chance;
        holdMeanMs = defaults.holdMeanMs;
        holdStandardDeviationMs = defaults.holdStandardDeviationMs;
        holdMinMs = defaults.holdMinMs;
        holdMaxMs = defaults.holdMaxMs;
        minimumHorizontalVelocity = defaults.minimumHorizontalVelocity;
        onGroundOnly = defaults.onGroundOnly;
        sprintingOnly = defaults.sprintingOnly;
        forwardOnly = defaults.forwardOnly;
        allowInMenu = defaults.allowInMenu;
    }

    public String export() {
        Map<String, String> values = ConfigCodec.map();
        ConfigCodec.put(values, "chance", chance);
        ConfigCodec.put(values, "holdMeanMs", holdMeanMs);
        ConfigCodec.put(values, "holdStandardDeviationMs", holdStandardDeviationMs);
        ConfigCodec.put(values, "holdMinMs", holdMinMs);
        ConfigCodec.put(values, "holdMaxMs", holdMaxMs);
        ConfigCodec.put(values, "minimumHorizontalVelocity", minimumHorizontalVelocity);
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
        holdMeanMs = ConfigCodec.number(values, "holdMeanMs", holdMeanMs);
        holdStandardDeviationMs = ConfigCodec.number(values,
                "holdStandardDeviationMs", holdStandardDeviationMs);
        holdMinMs = ConfigCodec.number(values, "holdMinMs", holdMinMs);
        holdMaxMs = ConfigCodec.number(values, "holdMaxMs", holdMaxMs);
        minimumHorizontalVelocity = ConfigCodec.number(values,
                "minimumHorizontalVelocity", minimumHorizontalVelocity);
        onGroundOnly = ConfigCodec.flag(values, "onGroundOnly", onGroundOnly);
        sprintingOnly = ConfigCodec.flag(values, "sprintingOnly", sprintingOnly);
        forwardOnly = ConfigCodec.flag(values, "forwardOnly", forwardOnly);
        allowInMenu = ConfigCodec.flag(values, "allowInMenu", allowInMenu);
        return true;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ArmedJump {
        final long velocityNanos;
        final JumpResetData data;

        ArmedJump(long velocityNanos, JumpResetData data) {
            this.velocityNanos = velocityNanos;
            this.data = data;
        }

        void dataIfPresentEndMotion(EntityPlayerSP player) {
            if (data != null)
                data.observeEndMotion(player.motionX, player.motionY, player.motionZ);
        }
    }

    private static final class ActiveJump {
        final ArmedJump armed;
        final int jumpKey;
        final long startedNanos;
        final long targetHoldMs;
        boolean movementSampled;
        boolean releaseRequested;

        ActiveJump(ArmedJump armed, int jumpKey, long startedNanos, long targetHoldMs) {
            this.armed = armed;
            this.jumpKey = jumpKey;
            this.startedNanos = startedNanos;
            this.targetHoldMs = targetHoldMs;
        }
    }

    public static final class HudSnapshot {
        public final boolean enabled;
        public final boolean active;
        public final String state;

        HudSnapshot(boolean enabled, boolean active, String state) {
            this.enabled = enabled;
            this.active = active;
            this.state = state;
        }
    }
}
