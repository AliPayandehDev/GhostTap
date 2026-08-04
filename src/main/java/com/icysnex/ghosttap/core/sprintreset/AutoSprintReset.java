package com.icysnex.ghosttap.core.sprintreset;

import com.icysnex.ghosttap.config.ConfigCodec;
import com.icysnex.ghosttap.core.Defaults;
import com.icysnex.ghosttap.core.analytics.SprintResetData;
import com.icysnex.ghosttap.core.analytics.SprintResetTracker;
import com.icysnex.ghosttap.core.analytics.Tracker;
import com.icysnex.ghosttap.core.click.Variance;
import com.icysnex.ghosttap.core.input.InputKeyboard;
import com.icysnex.ghosttap.core.jump.AutoJumpReset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.concurrent.TimeUnit;

// Arms on a client attack, confirms it from the server's target-hurt status,
// then applies the reset for complete movement ticks. KeyBinding is the game
// authority; InputKeyboard mirrors the same state to Keystrokes-style HUDs.
public final class AutoSprintReset {

    public static final AutoSprintReset INSTANCE = new AutoSprintReset(Defaults.SPRINT_RESET);
    private static final String TOKEN = "ghosttap-w-tap";
    private static final long CONFIRM_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final double CLOSE_GAP = 0.8;
    private static final double FAR_GAP = 2.4;
    private static final double CLOSING_EPSILON = 0.005;

    public SprintResetMode mode;
    public double chance;
    public double delayMinMs;
    public double delayMaxMs;
    public double durationMeanTicks;
    public double durationStandardDeviationTicks;
    public double durationMinTicks;
    public double durationMaxTicks;
    public double durationAdaptiveness;
    public double durationCloseMultiplier;
    public boolean spacingAssist;
    public double spacingGap;
    public boolean playersOnly;
    public boolean weapons;
    public boolean tools;
    public boolean blocks;
    public boolean other;

    private final Defaults.SprintResetProfile defaults;
    private volatile boolean enabled;
    private PendingHit pendingHit;
    private ConfirmedAction armedAction;
    private ActiveAction activeAction;

    private AutoSprintReset(Defaults.SprintResetProfile defaults) {
        this.defaults = defaults;
        reset();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled)
            cancelAll();
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || event.entityPlayer != mc.thePlayer || event.target == null)
            return;

        long attackNanos = System.nanoTime();
        EntityPlayerSP player = mc.thePlayer;
        Entity target = event.target;
        SprintResetMode attackMode = mode;
        boolean playerTarget = target instanceof EntityPlayer;
        boolean sprinting = player.isSprinting();
        boolean movingForward = player.movementInput != null && player.movementInput.moveForward > 0;
        SprintResetData.HeldItem heldItem = heldItem(player.getHeldItem());
        int selectedKey = selectedActionKey(mc, attackMode);
        boolean physicalActionDown = InputKeyboard.real(selectedKey) == InputKeyboard.STATE_DOWN;
        double targetGap = horizontalGap(player, target);
        double closingSpeed = closingSpeed(player, target);
        int targetHurtTime = target instanceof EntityLivingBase
                ? ((EntityLivingBase) target).hurtTime : 0;

        SprintResetData.Outcome outcome = eligibility(playerTarget, heldItem);
        if (outcome != SprintResetData.Outcome.PENDING) {
            record(outcome, attackMode, attackNanos, -1, sprinting, movingForward,
                    physicalActionDown, playerTarget, heldItem, targetGap, closingSpeed, targetHurtTime);
            return;
        }

        synchronized (this) {
            if (pendingHit != null && pendingHit.targetEntityId == target.getEntityId()) {
                pendingHit.extendConfirmationWindow(attackNanos);
                record(SprintResetData.Outcome.SKIP_ALREADY_QUEUED, attackMode, attackNanos, 0,
                        sprinting, movingForward, physicalActionDown, playerTarget, heldItem,
                        targetGap, closingSpeed, targetHurtTime);
                return;
            }
            if (pendingHit != null || armedAction != null || activeAction != null) {
                record(SprintResetData.Outcome.SKIP_ALREADY_QUEUED, attackMode, attackNanos, 0,
                        sprinting, movingForward, physicalActionDown, playerTarget, heldItem,
                        targetGap, closingSpeed, targetHurtTime);
                return;
            }
        }

        double probability = clamp(chance, 0, 1);
        if (probability <= 0 || (probability < 1 && !Variance.chance(probability))) {
            record(SprintResetData.Outcome.SKIP_CHANCE, attackMode, attackNanos, -1,
                    sprinting, movingForward, physicalActionDown, playerTarget, heldItem,
                    targetGap, closingSpeed, targetHurtTime);
            return;
        }

        long delayMs = sampleDelayMs();
        synchronized (this) {
            if (pendingHit != null || armedAction != null || activeAction != null) {
                record(SprintResetData.Outcome.SKIP_ALREADY_QUEUED, attackMode, attackNanos, delayMs,
                        sprinting, movingForward, physicalActionDown, playerTarget, heldItem,
                        targetGap, closingSpeed, targetHurtTime);
                return;
            }

            SprintResetData data = record(SprintResetData.Outcome.PENDING, attackMode, attackNanos,
                    delayMs, sprinting, movingForward, physicalActionDown, playerTarget, heldItem,
                    targetGap, closingSpeed, targetHurtTime);
            pendingHit = new PendingHit(target.getEntityId(), attackNanos,
                    saturatedAdd(attackNanos, CONFIRM_TIMEOUT_NANOS), attackMode, delayMs,
                    targetGap, closingSpeed, targetHurtTime, data);
        }
    }

    // Called at the tail of NetHandlerPlayClient.handleEntityStatus on the
    // client thread. Opcode 2 is the server-observed hurt animation.
    public synchronized void onTargetHurtStatus(Entity target) {
        confirmPending(target, System.nanoTime(), "HURT_STATUS");
    }

    public synchronized void onTargetVelocity(int targetEntityId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled || pendingHit == null || mc.theWorld == null
                || pendingHit.targetEntityId != targetEntityId)
            return;
        confirmPending(mc.theWorld.getEntityByID(targetEntityId),
                System.nanoTime(), "VELOCITY");
    }

    private void confirmPending(Entity target, long now, String confirmationSource) {
        if (!enabled || target == null || pendingHit == null
                || pendingHit.targetEntityId != target.getEntityId())
            return;

        PendingHit pending = pendingHit;
        if (now > pending.confirmDeadlineNanos) {
            expire(pending);
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null)
            return;

        double confirmedGap = horizontalGap(mc.thePlayer, target);
        double confirmedClosingSpeed = closingSpeed(mc.thePlayer, target);
        double observedClosingSpeed = Math.min(pending.closingSpeed, confirmedClosingSpeed);
        double projectedGap = Math.max(0, confirmedGap + Math.min(0, observedClosingSpeed));
        boolean adaptiveBackstep = spacingAssist
                && pending.mode != SprintResetMode.S_TAP
                && projectedGap < clamp(spacingGap, 0.5, 3.0)
                && observedClosingSpeed < -CLOSING_EPSILON;
        boolean backstep = pending.mode == SprintResetMode.S_TAP || adaptiveBackstep;
        double multiplier = durationMultiplier(projectedGap);

        if (pending.data != null)
            pending.data.markConfirmed(now, pending.attackNanos, confirmationSource,
                    confirmedGap, confirmedClosingSpeed, projectedGap, backstep);
        armedAction = new ConfirmedAction(pending, saturatedAdd(now,
                TimeUnit.MILLISECONDS.toNanos(pending.delayMs)), multiplier, backstep);
        pendingHit = null;
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
        if (!enabled || mc.thePlayer == null || mc.theWorld == null) {
            if (pendingHit != null || armedAction != null || activeAction != null)
                cancelAll();
            return;
        }

        long now = System.nanoTime();
        observePendingHurtTime(mc, now);
        if (pendingHit != null && now > pendingHit.confirmDeadlineNanos)
            expire(pendingHit);

        ActiveAction active = activeAction;
        if (AutoJumpReset.INSTANCE.isMovementProtected()) {
            deferForJumpReset();
            return;
        }

        if (active != null && active.pausedForJump) {
            active.resume(now);
            active.plan.apply();
        }

        if (active != null && !active.gameplayFinished && active.remainingTicks <= 0)
            finishGameplay(active, now, mc);

        ConfirmedAction armed = armedAction;
        if (armed != null && activeAction == null && now >= armed.readyAtNanos) {
            armedAction = null;
            beginAction(mc, armed, now);
        }
    }

    private synchronized void runEndTick() {
        ActiveAction active = activeAction;
        if (active != null && !active.gameplayFinished && !active.pausedForJump) {
            active.remainingTicks--;
            Minecraft mc = Minecraft.getMinecraft();
            if (active.action.pending.data != null && mc.thePlayer != null
                    && !mc.thePlayer.isSprinting())
                active.action.pending.data.sprintStoppedDuringAction = true;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (active != null && active.gameplayFinished
                && active.action.pending.data != null && mc.thePlayer != null)
            active.action.pending.data.sprintAfterRestore = mc.thePlayer.isSprinting();
        finishVisualIfReady();
    }

    private void beginAction(Minecraft mc, ConfirmedAction action, long now) {
        ActionPlan plan = ActionPlan.forAction(mc, action.pending.mode, action.backstep);
        if (!plan.usable()) {
            if (action.pending.data != null)
                action.pending.data.outcome = SprintResetData.Outcome.SKIP_KEY_STATE;
            return;
        }

        double configuredMinTicks = clamp(durationMinTicks, 1, 3);
        double configuredMaxTicks = clamp(durationMaxTicks, configuredMinTicks, 3);
        double baseTicks = sampleGaussian(clamp(durationMeanTicks, configuredMinTicks, configuredMaxTicks),
                clamp(durationStandardDeviationTicks, 0, 1),
                configuredMinTicks, configuredMaxTicks);
        int minimumTicks = Math.max(1, (int) Math.round(configuredMinTicks));
        int maximumTicks = Math.max(minimumTicks,
                (int) Math.round(configuredMaxTicks));
        int durationTicks = stochasticRound(baseTicks * action.durationMultiplier);
        durationTicks = Math.max(minimumTicks, Math.min(maximumTicks, durationTicks));
        double effectiveMultiplier = baseTicks > 0 ? durationTicks / baseTicks : 1.0;

        plan.apply();
        ActiveAction active = new ActiveAction(action, plan, now, InputKeyboard.pollSerial,
                durationTicks);
        activeAction = active;
        if (action.pending.data != null)
            action.pending.data.markStarted(now, action.pending.attackNanos, baseTicks,
                    effectiveMultiplier, durationTicks, mc.thePlayer.isSprinting());
    }

    private void finishGameplay(ActiveAction action, long now, Minecraft mc) {
        if (activeAction != action)
            return;
        action.plan.restoreGameplay();
        action.gameplayFinished = true;
        if (action.action.pending.data != null) {
            Entity target = mc.theWorld == null ? null
                    : mc.theWorld.getEntityByID(action.action.pending.targetEntityId);
            double finalGap = target == null || mc.thePlayer == null
                    ? Double.NaN : horizontalGap(mc.thePlayer, target);
            action.action.pending.data.markEnded(action.activeDuration(now), finalGap);
        }
    }

    // Jump Reset owns the movement keys for its one complete tick. An armed
    // Sprint Reset waits; an active one is restored and resumed without losing
    // one of its configured gameplay ticks.
    public synchronized boolean deferForJumpReset() {
        boolean deferred = false;
        if (armedAction != null) {
            if (!armedAction.jumpDeferred && armedAction.pending.data != null)
                armedAction.pending.data.markJumpDeferred();
            armedAction.jumpDeferred = true;
            deferred = true;
        }

        ActiveAction active = activeAction;
        if (active != null) {
            if (active.gameplayFinished) {
                active.plan.releaseVisual();
                activeAction = null;
            } else if (!active.pausedForJump) {
                active.plan.restoreGameplay();
                active.plan.releaseVisual();
                active.pause(System.nanoTime());
                if (active.action.pending.data != null)
                    active.action.pending.data.markJumpDeferred();
                deferred = true;
            }
        }
        return deferred;
    }

    private void finishVisualIfReady() {
        ActiveAction action = activeAction;
        if (action == null || !action.gameplayFinished || InputKeyboard.pollSerial <= action.startPoll)
            return;
        action.plan.releaseVisual();
        activeAction = null;
    }

    private void observePendingHurtTime(Minecraft mc, long now) {
        PendingHit pending = pendingHit;
        if (pending == null)
            return;
        Entity target = mc.theWorld.getEntityByID(pending.targetEntityId);
        if (!(target instanceof EntityLivingBase))
            return;
        int hurtTime = ((EntityLivingBase) target).hurtTime;
        if (hurtTime > pending.lastObservedHurtTime) {
            confirmPending(target, now, "HURT_TIME");
            return;
        }
        pending.lastObservedHurtTime = hurtTime;
    }

    private SprintResetData.Outcome eligibility(boolean playerTarget,
                                                 SprintResetData.HeldItem heldItem) {
        if (!enabled)
            return SprintResetData.Outcome.SKIP_DISABLED;
        if (playersOnly && !playerTarget)
            return SprintResetData.Outcome.SKIP_NOT_PLAYER;
        if (!allowed(heldItem))
            return SprintResetData.Outcome.SKIP_ITEM;
        return SprintResetData.Outcome.PENDING;
    }

    private void expire(PendingHit pending) {
        if (pendingHit != pending)
            return;
        if (pending.data != null)
            pending.data.outcome = SprintResetData.Outcome.EXPIRED;
        pendingHit = null;
    }

    private long sampleDelayMs() {
        double min = clamp(Math.min(delayMinMs, delayMaxMs), 0, 500);
        double max = clamp(Math.max(delayMinMs, delayMaxMs), min, 500);
        return Math.max(0, Math.round(Variance.range(min, max)));
    }

    private static double sampleGaussian(double mean, double standardDeviation,
                                         double configuredMin, double configuredMax) {
        double min = Math.max(0, Math.min(configuredMin, configuredMax));
        double max = Math.max(min, Math.max(configuredMin, configuredMax));
        double deviation = Math.max(0, standardDeviation);
        double sampled = deviation == 0 ? mean : Variance.gaussian(mean, deviation);
        return Math.max(min, Math.min(max, sampled));
    }

    public synchronized HudSnapshot hudSnapshot() {
        SprintResetMode currentMode = mode;
        if (!enabled)
            return new HudSnapshot(false, false, "IDLE", currentMode.label);

        ActiveAction active = activeAction;
        if (active != null && !active.gameplayFinished) {
            return new HudSnapshot(true, true, "TAP",
                    active.action.label() + " " + active.targetDurationTicks + "t");
        }
        if (armedAction != null)
            return new HudSnapshot(true, true, "ARMED",
                    armedAction.label() + " next tick");
        if (pendingHit != null)
            return new HudSnapshot(true, true, "CONFIRM", pendingHit.mode.label);
        return new HudSnapshot(true, false, "IDLE", currentMode.label);
    }

    private boolean allowed(SprintResetData.HeldItem heldItem) {
        switch (heldItem) {
            case WEAPON: return weapons;
            case TOOL: return tools;
            case BLOCK: return blocks;
            default: return other;
        }
    }

    private SprintResetData record(SprintResetData.Outcome outcome, SprintResetMode mode,
                                   long attackNanos, long delayMs, boolean sprinting,
                                   boolean movingForward, boolean physicalActionDown,
                                   boolean playerTarget, SprintResetData.HeldItem heldItem,
                                   double targetGap, double closingSpeed, int targetHurtTime) {
        if (!Tracker.enabled)
            return null;
        long decisionNanos = Math.max(0, System.nanoTime() - attackNanos);
        return SprintResetTracker.INSTANCE.record(new SprintResetData(System.currentTimeMillis(), outcome,
                mode, decisionNanos, delayMs, sprinting, movingForward, physicalActionDown,
                playerTarget, heldItem, targetGap, closingSpeed, targetHurtTime));
    }

    // Adaptiveness is deliberately one-sided: distance may lengthen a reset
    // near the target, but it can never shorten the sampled baseline.
    private double durationMultiplier(double horizontalGap) {
        double normalized = clamp((horizontalGap - CLOSE_GAP) / (FAR_GAP - CLOSE_GAP), 0, 1);
        double smooth = normalized * normalized * (3 - 2 * normalized);
        double closePressure = 1 - smooth;
        double closeMultiplier = clamp(durationCloseMultiplier, 1, 2);
        return 1 + clamp(durationAdaptiveness, 0, 1)
                * closePressure * (closeMultiplier - 1);
    }

    private static double horizontalGap(Entity first, Entity second) {
        AxisAlignedBB a = first.getEntityBoundingBox();
        AxisAlignedBB b = second.getEntityBoundingBox();
        double gapX = Math.max(0, Math.max(b.minX - a.maxX, a.minX - b.maxX));
        double gapZ = Math.max(0, Math.max(b.minZ - a.maxZ, a.minZ - b.maxZ));
        return Math.sqrt(gapX * gapX + gapZ * gapZ);
    }

    // Negative means the entities are moving toward one another along their
    // horizontal separation axis.
    private static double closingSpeed(Entity player, Entity target) {
        double dx = target.posX - player.posX;
        double dz = target.posZ - player.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-6)
            return 0;
        double relativeX = (target.posX - target.lastTickPosX)
                - (player.posX - player.lastTickPosX);
        double relativeZ = (target.posZ - target.lastTickPosZ)
                - (player.posZ - player.lastTickPosZ);
        return (relativeX * dx + relativeZ * dz) / distance;
    }

    private static int selectedActionKey(Minecraft mc, SprintResetMode mode) {
        switch (mode) {
            case S_TAP: return mc.gameSettings.keyBindBack.getKeyCode();
            case SHIFT_TAP: return mc.gameSettings.keyBindSneak.getKeyCode();
            default: return mc.gameSettings.keyBindForward.getKeyCode();
        }
    }

    private static SprintResetData.HeldItem heldItem(ItemStack stack) {
        if (stack == null || stack.getItem() == null)
            return SprintResetData.HeldItem.OTHER;
        Item item = stack.getItem();
        if (item instanceof ItemSword || item instanceof ItemAxe)
            return SprintResetData.HeldItem.WEAPON;
        if (item instanceof ItemPickaxe || item instanceof ItemSpade
                || item instanceof ItemHoe || item instanceof ItemShears)
            return SprintResetData.HeldItem.TOOL;
        if (item instanceof ItemBlock)
            return SprintResetData.HeldItem.BLOCK;
        return SprintResetData.HeldItem.OTHER;
    }

    private synchronized void cancelAll() {
        if (pendingHit != null)
            cancel(pendingHit.data);
        pendingHit = null;
        if (armedAction != null)
            cancel(armedAction.pending.data);
        armedAction = null;
        if (activeAction != null) {
            if (!activeAction.pausedForJump)
                activeAction.plan.restoreGameplay();
            activeAction.plan.releaseVisual();
            if (!activeAction.gameplayFinished)
                cancel(activeAction.action.pending.data);
        }
        activeAction = null;
    }

    private static void cancel(SprintResetData data) {
        if (data != null)
            data.outcome = SprintResetData.Outcome.CANCELLED;
    }

    public void reset() {
        mode = defaults.mode;
        chance = defaults.chance;
        delayMinMs = defaults.delayMinMs;
        delayMaxMs = defaults.delayMaxMs;
        durationMeanTicks = defaults.durationMeanTicks;
        durationStandardDeviationTicks = defaults.durationStandardDeviationTicks;
        durationMinTicks = defaults.durationMinTicks;
        durationMaxTicks = defaults.durationMaxTicks;
        durationAdaptiveness = defaults.durationAdaptiveness;
        durationCloseMultiplier = defaults.durationCloseMultiplier;
        spacingAssist = defaults.spacingAssist;
        spacingGap = defaults.spacingGap;
        playersOnly = defaults.playersOnly;
        weapons = defaults.weapons;
        tools = defaults.tools;
        blocks = defaults.blocks;
        other = defaults.other;
    }

    public String export() {
        Map<String, String> values = ConfigCodec.map();
        ConfigCodec.put(values, "mode", mode.name());
        ConfigCodec.put(values, "chance", chance);
        ConfigCodec.put(values, "delayMinMs", delayMinMs);
        ConfigCodec.put(values, "delayMaxMs", delayMaxMs);
        ConfigCodec.put(values, "durationMeanTicks", durationMeanTicks);
        ConfigCodec.put(values, "durationStandardDeviationTicks", durationStandardDeviationTicks);
        ConfigCodec.put(values, "durationMinTicks", durationMinTicks);
        ConfigCodec.put(values, "durationMaxTicks", durationMaxTicks);
        ConfigCodec.put(values, "durationAdaptiveness", durationAdaptiveness);
        ConfigCodec.put(values, "durationCloseMultiplier", durationCloseMultiplier);
        ConfigCodec.put(values, "spacingAssist", spacingAssist);
        ConfigCodec.put(values, "spacingGap", spacingGap);
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
        try {
            if (values.containsKey("mode"))
                mode = SprintResetMode.valueOf(values.get("mode"));
        } catch (IllegalArgumentException ignored) {
        }
        chance = ConfigCodec.number(values, "chance", chance);
        delayMinMs = ConfigCodec.number(values, "delayMinMs", delayMinMs);
        delayMaxMs = ConfigCodec.number(values, "delayMaxMs", delayMaxMs);
        durationMeanTicks = durationValue(values, "durationMeanTicks", "durationMeanMs", durationMeanTicks);
        durationStandardDeviationTicks = durationValue(values, "durationStandardDeviationTicks",
                "durationStandardDeviationMs", durationStandardDeviationTicks);
        durationMinTicks = durationValue(values, "durationMinTicks", "durationMinMs", durationMinTicks);
        durationMaxTicks = durationValue(values, "durationMaxTicks", "durationMaxMs", durationMaxTicks);
        durationAdaptiveness = ConfigCodec.number(values, "durationAdaptiveness", durationAdaptiveness);
        durationCloseMultiplier = ConfigCodec.number(values, "durationCloseMultiplier", durationCloseMultiplier);
        spacingAssist = ConfigCodec.flag(values, "spacingAssist", spacingAssist);
        spacingGap = ConfigCodec.number(values, "spacingGap", spacingGap);
        playersOnly = ConfigCodec.flag(values, "playersOnly", playersOnly);
        weapons = ConfigCodec.flag(values, "weapons", weapons);
        tools = ConfigCodec.flag(values, "tools", tools);
        blocks = ConfigCodec.flag(values, "blocks", blocks);
        other = ConfigCodec.flag(values, "other", other);
        return true;
    }

    private static double durationValue(Map<String, String> values, String ticksKey,
                                        String legacyMsKey, double fallback) {
        if (values.containsKey(ticksKey))
            return ConfigCodec.number(values, ticksKey, fallback);
        if (values.containsKey(legacyMsKey))
            return ConfigCodec.number(values, legacyMsKey, fallback * 50.0) / 50.0;
        return fallback;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right)
            return Long.MAX_VALUE;
        return left + right;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int stochasticRound(double value) {
        int lower = (int) Math.floor(value);
        double fraction = value - lower;
        return lower + (fraction > 0 && Variance.chance(fraction) ? 1 : 0);
    }

    private static final class PendingHit {
        final int targetEntityId;
        final long attackNanos;
        long confirmDeadlineNanos;
        final SprintResetMode mode;
        final long delayMs;
        final double targetGap;
        final double closingSpeed;
        final SprintResetData data;
        int lastObservedHurtTime;

        PendingHit(int targetEntityId, long attackNanos, long confirmDeadlineNanos,
                   SprintResetMode mode, long delayMs, double targetGap,
                   double closingSpeed, int targetHurtTime, SprintResetData data) {
            this.targetEntityId = targetEntityId;
            this.attackNanos = attackNanos;
            this.confirmDeadlineNanos = confirmDeadlineNanos;
            this.mode = mode;
            this.delayMs = delayMs;
            this.targetGap = targetGap;
            this.closingSpeed = closingSpeed;
            this.data = data;
            this.lastObservedHurtTime = targetHurtTime;
        }

        void extendConfirmationWindow(long attackNanos) {
            confirmDeadlineNanos = saturatedAdd(attackNanos, CONFIRM_TIMEOUT_NANOS);
        }
    }

    private static final class ConfirmedAction {
        final PendingHit pending;
        final long readyAtNanos;
        final double durationMultiplier;
        final boolean backstep;
        boolean jumpDeferred;

        ConfirmedAction(PendingHit pending, long readyAtNanos,
                        double durationMultiplier, boolean backstep) {
            this.pending = pending;
            this.readyAtNanos = readyAtNanos;
            this.durationMultiplier = durationMultiplier;
            this.backstep = backstep;
        }

        String label() {
            return backstep && pending.mode != SprintResetMode.S_TAP
                    ? "Spacing" : pending.mode.label;
        }
    }

    private static final class ActiveAction {
        final ConfirmedAction action;
        final ActionPlan plan;
        final long startNanos;
        final long startPoll;
        final int targetDurationTicks;
        int remainingTicks;
        boolean gameplayFinished;
        boolean pausedForJump;
        long pauseStartedNanos;
        long totalPausedNanos;

        ActiveAction(ConfirmedAction action, ActionPlan plan, long startNanos,
                     long startPoll, int targetDurationTicks) {
            this.action = action;
            this.plan = plan;
            this.startNanos = startNanos;
            this.startPoll = startPoll;
            this.targetDurationTicks = targetDurationTicks;
            this.remainingTicks = targetDurationTicks;
        }

        void pause(long now) {
            pausedForJump = true;
            pauseStartedNanos = now;
        }

        void resume(long now) {
            if (!pausedForJump)
                return;
            totalPausedNanos += Math.max(0, now - pauseStartedNanos);
            pauseStartedNanos = 0;
            pausedForJump = false;
        }

        long activeDuration(long now) {
            long paused = totalPausedNanos;
            if (pausedForJump)
                paused += Math.max(0, now - pauseStartedNanos);
            return Math.max(0, now - startNanos - paused);
        }
    }

    private static final class ActionPlan {
        final int forwardKey;
        final int backKey;
        final int sneakKey;
        final boolean suppressForward;
        final boolean pressBack;
        final boolean pressSneak;

        private ActionPlan(int forwardKey, int backKey, int sneakKey,
                           boolean suppressForward, boolean pressBack, boolean pressSneak) {
            this.forwardKey = forwardKey;
            this.backKey = backKey;
            this.sneakKey = sneakKey;
            this.suppressForward = suppressForward;
            this.pressBack = pressBack;
            this.pressSneak = pressSneak;
        }

        static ActionPlan forAction(Minecraft mc, SprintResetMode mode, boolean backstep) {
            boolean pressBack = backstep || mode == SprintResetMode.S_TAP;
            boolean suppressForward = pressBack || mode == SprintResetMode.W_TAP;
            boolean pressSneak = !backstep && mode == SprintResetMode.SHIFT_TAP;
            return new ActionPlan(mc.gameSettings.keyBindForward.getKeyCode(),
                    mc.gameSettings.keyBindBack.getKeyCode(),
                    mc.gameSettings.keyBindSneak.getKeyCode(),
                    suppressForward, pressBack, pressSneak);
        }

        boolean usable() {
            if (InputKeyboard.real(forwardKey) != InputKeyboard.STATE_DOWN)
                return false;
            if (pressBack && InputKeyboard.real(backKey) != InputKeyboard.STATE_UP)
                return false;
            return !pressSneak || InputKeyboard.real(sneakKey) == InputKeyboard.STATE_UP;
        }

        void apply() {
            if (suppressForward) {
                InputKeyboard.forceUpPolled(forwardKey);
                KeyBinding.setKeyBindState(forwardKey, false);
            }
            if (pressBack) {
                InputKeyboard.forceDownPolled(backKey);
                KeyBinding.setKeyBindState(backKey, true);
            }
            if (pressSneak) {
                InputKeyboard.forceDownPolled(sneakKey);
                KeyBinding.setKeyBindState(sneakKey, true);
            }
        }

        void restoreGameplay() {
            if (suppressForward)
                KeyBinding.setKeyBindState(forwardKey,
                        InputKeyboard.real(forwardKey) == InputKeyboard.STATE_DOWN);
            if (pressBack)
                KeyBinding.setKeyBindState(backKey,
                        InputKeyboard.real(backKey) == InputKeyboard.STATE_DOWN);
            if (pressSneak)
                KeyBinding.setKeyBindState(sneakKey,
                        InputKeyboard.real(sneakKey) == InputKeyboard.STATE_DOWN);
        }

        void releaseVisual() {
            if (suppressForward)
                InputKeyboard.releasePolled(forwardKey);
            if (pressBack)
                InputKeyboard.releasePolled(backKey);
            if (pressSneak)
                InputKeyboard.releasePolled(sneakKey);
        }
    }

    public static final class HudSnapshot {
        public final boolean enabled;
        public final boolean active;
        public final String state;
        public final String detail;

        HudSnapshot(boolean enabled, boolean active, String state, String detail) {
            this.enabled = enabled;
            this.active = active;
            this.state = state;
            this.detail = detail;
        }
    }
}
