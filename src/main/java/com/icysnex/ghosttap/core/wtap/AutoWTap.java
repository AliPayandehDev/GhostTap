package com.icysnex.ghosttap.core.wtap;

import com.icysnex.ghosttap.config.ConfigCodec;
import com.icysnex.ghosttap.core.Defaults;
import com.icysnex.ghosttap.core.analytics.Tracker;
import com.icysnex.ghosttap.core.analytics.WTapData;
import com.icysnex.ghosttap.core.analytics.WTapTracker;
import com.icysnex.ghosttap.core.click.Variance;
import com.icysnex.ghosttap.core.input.InputKeyboard;
import net.minecraft.client.Minecraft;
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
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// Uses the same attack-event/next-client-tick cadence as AutoSprintReset, then
// routes every altered key through LWJGL so vanilla and Keystrokes HUDs agree.
public final class AutoWTap {

    public static final AutoWTap INSTANCE = new AutoWTap(Defaults.W_TAP);
    private static final String TOKEN = "ghosttap-w-tap";
    public static final int BASELINE_COOLDOWN_TICKS = 10;
    public static final long BASELINE_DURATION_MS = 20;

    public WTapMode mode;
    public double chance;
    public double delayMinMs;
    public double delayMaxMs;
    public double durationMeanMs;
    public double durationStandardDeviationMs;
    public double durationMinMs;
    public double durationMaxMs;
    public double cooldownMeanMs;
    public double cooldownStandardDeviationMs;
    public double cooldownMinMs;
    public double cooldownMaxMs;
    public boolean playersOnly;
    public boolean weapons;
    public boolean tools;
    public boolean blocks;
    public boolean other;

    private final Defaults.WTapProfile defaults;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean enabled;
    private volatile PendingAttack pendingAttack;
    private volatile ActiveAction activeAction;
    private volatile long completedClientTicks;
    private int baselineTicks;
    private int baselineAttacks;

    private AutoWTap(Defaults.WTapProfile defaults) {
        this.defaults = defaults;
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "GhostTap-WTap");
                thread.setDaemon(true);
                return thread;
            }
        });
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
        WTapMode attackMode = mode;
        boolean playerTarget = event.target instanceof EntityPlayer;
        boolean sprinting = mc.thePlayer.isSprinting();
        boolean movingForward = mc.thePlayer.movementInput != null
                && mc.thePlayer.movementInput.moveForward > 0;
        WTapData.HeldItem heldItem = heldItem(mc.thePlayer.getHeldItem());
        int actionKey = actionKey(mc, attackMode);
        boolean physicalActionDown = InputKeyboard.real(actionKey) == InputKeyboard.STATE_DOWN;

        WTapData.Outcome outcome = outcome(playerTarget, heldItem);
        if (outcome != WTapData.Outcome.TRIGGERED) {
            record(outcome, attackMode, attackNanos, -1, sprinting, movingForward,
                    physicalActionDown, playerTarget, heldItem);
            return;
        }

        double probability = Math.max(0, Math.min(1, chance));
        if (probability <= 0 || (probability < 1 && !Variance.chance(probability))) {
            record(WTapData.Outcome.SKIP_CHANCE, attackMode, attackNanos, -1,
                    sprinting, movingForward, physicalActionDown, playerTarget, heldItem);
            return;
        }

        long delayMs = 0;
        WTapData data = record(WTapData.Outcome.TRIGGERED, attackMode, attackNanos,
                delayMs, sprinting, movingForward, physicalActionDown, playerTarget, heldItem);
        PendingAttack attack = new PendingAttack(attackNanos, attackMode,
                actionKey, data);

        synchronized (this) {
            baselineAttacks++;
            if (pendingAttack != null) {
                if (data != null)
                    data.outcome = WTapData.Outcome.SKIP_ALREADY_QUEUED;
                return;
            }
            pendingAttack = attack;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Minecraft mc = Minecraft.getMinecraft();
            if (enabled && mc.thePlayer != null && mc.theWorld != null)
                runBaselineTick();
        }
        else if (event.phase == TickEvent.Phase.END) {
            completedClientTicks++;
            finishActionIfReady();
        }
    }

    // Deliberately mirrors the reference mod: ticks accumulate while enabled,
    // all attacks inside the gate collapse into one reset, and the counter is
    // cleared when that reset fires.
    private synchronized void runBaselineTick() {
        baselineTicks++;
        if (baselineTicks < BASELINE_COOLDOWN_TICKS || baselineAttacks <= 0)
            return;

        PendingAttack attack = pendingAttack;
        pendingAttack = null;
        baselineTicks = 0;
        baselineAttacks = 0;
        if (attack != null)
            beginAction(generation.get(), attack);
    }

    private synchronized void beginAction(long expectedGeneration, PendingAttack attack) {
        if (generation.get() != expectedGeneration) {
            cancel(attack.data);
            return;
        }
        if (!enabled) {
            cancel(attack.data);
            return;
        }

        long now = System.nanoTime();
        long durationMs = BASELINE_DURATION_MS;

        byte realAction = InputKeyboard.real(attack.actionKey);
        boolean usable = attack.mode == WTapMode.W_TAP
                ? realAction == InputKeyboard.STATE_DOWN
                : realAction == InputKeyboard.STATE_UP;
        if (!usable) {
            if (attack.data != null)
                attack.data.outcome = WTapData.Outcome.SKIP_KEY_STATE;
            return;
        }

        if (attack.mode == WTapMode.W_TAP) {
            InputKeyboard.forceUp(attack.actionKey);
        } else {
            InputKeyboard.forceDown(attack.actionKey);
        }

        ActiveAction action = new ActiveAction(attack, now, InputKeyboard.pollSerial,
                completedClientTicks);
        activeAction = action;
        if (attack.data != null)
            attack.data.markStarted(now, attack.attackNanos, durationMs,
                    BASELINE_COOLDOWN_TICKS * 50L);

        scheduler.schedule(() -> {
            action.durationElapsed = true;
            finishActionIfReady();
        }, durationMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void finishActionIfReady() {
        ActiveAction action = activeAction;
        if (action == null || !action.durationElapsed
                || InputKeyboard.pollSerial <= action.startPoll
                || completedClientTicks <= action.startCompletedTick)
            return;

        PendingAttack attack = action.attack;
        InputKeyboard.release(attack.actionKey);

        if (attack.data != null)
            attack.data.markEnded(System.nanoTime() - action.startNanos);
        activeAction = null;
    }

    private WTapData.Outcome outcome(boolean playerTarget, WTapData.HeldItem heldItem) {
        if (!enabled)
            return WTapData.Outcome.SKIP_DISABLED;
        if (playersOnly && !playerTarget)
            return WTapData.Outcome.SKIP_NOT_PLAYER;
        if (!allowed(heldItem))
            return WTapData.Outcome.SKIP_ITEM;
        return WTapData.Outcome.TRIGGERED;
    }

    private boolean allowed(WTapData.HeldItem heldItem) {
        switch (heldItem) {
            case WEAPON: return weapons;
            case TOOL: return tools;
            case BLOCK: return blocks;
            default: return other;
        }
    }

    private WTapData record(WTapData.Outcome outcome, WTapMode mode, long attackNanos,
                            long delayMs, boolean sprinting, boolean movingForward,
                            boolean physicalActionDown, boolean playerTarget,
                            WTapData.HeldItem heldItem) {
        if (!Tracker.enabled)
            return null;
        long decisionNanos = Math.max(0, System.nanoTime() - attackNanos);
        return WTapTracker.INSTANCE.record(new WTapData(System.currentTimeMillis(), outcome,
                mode, decisionNanos, delayMs, sprinting, movingForward,
                physicalActionDown, playerTarget, heldItem));
    }

    private static int actionKey(Minecraft mc, WTapMode mode) {
        switch (mode) {
            case S_TAP: return mc.gameSettings.keyBindBack.getKeyCode();
            case SHIFT_TAP: return mc.gameSettings.keyBindSneak.getKeyCode();
            default: return mc.gameSettings.keyBindForward.getKeyCode();
        }
    }

    private static WTapData.HeldItem heldItem(ItemStack stack) {
        if (stack == null || stack.getItem() == null)
            return WTapData.HeldItem.OTHER;
        Item item = stack.getItem();
        if (item instanceof ItemSword || item instanceof ItemAxe)
            return WTapData.HeldItem.WEAPON;
        if (item instanceof ItemPickaxe || item instanceof ItemSpade
                || item instanceof ItemHoe || item instanceof ItemShears)
            return WTapData.HeldItem.TOOL;
        if (item instanceof ItemBlock)
            return WTapData.HeldItem.BLOCK;
        return WTapData.HeldItem.OTHER;
    }

    private synchronized void cancelAll() {
        generation.incrementAndGet();
        if (pendingAttack != null)
            cancel(pendingAttack.data);
        pendingAttack = null;
        baselineAttacks = 0;

        ActiveAction action = activeAction;
        if (action != null) {
            InputKeyboard.release(action.attack.actionKey);
            cancel(action.attack.data);
        }
        activeAction = null;
    }

    private static void cancel(WTapData data) {
        if (data != null)
            data.outcome = WTapData.Outcome.CANCELLED;
    }

    public void reset() {
        mode = defaults.mode;
        chance = defaults.chance;
        delayMinMs = defaults.delayMinMs;
        delayMaxMs = defaults.delayMaxMs;
        durationMeanMs = defaults.durationMeanMs;
        durationStandardDeviationMs = defaults.durationStandardDeviationMs;
        durationMinMs = defaults.durationMinMs;
        durationMaxMs = defaults.durationMaxMs;
        cooldownMeanMs = defaults.cooldownMeanMs;
        cooldownStandardDeviationMs = defaults.cooldownStandardDeviationMs;
        cooldownMinMs = defaults.cooldownMinMs;
        cooldownMaxMs = defaults.cooldownMaxMs;
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
        ConfigCodec.put(values, "durationMeanMs", durationMeanMs);
        ConfigCodec.put(values, "durationStandardDeviationMs", durationStandardDeviationMs);
        ConfigCodec.put(values, "durationMinMs", durationMinMs);
        ConfigCodec.put(values, "durationMaxMs", durationMaxMs);
        ConfigCodec.put(values, "cooldownMeanMs", cooldownMeanMs);
        ConfigCodec.put(values, "cooldownStandardDeviationMs", cooldownStandardDeviationMs);
        ConfigCodec.put(values, "cooldownMinMs", cooldownMinMs);
        ConfigCodec.put(values, "cooldownMaxMs", cooldownMaxMs);
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
                mode = WTapMode.valueOf(values.get("mode"));
        } catch (IllegalArgumentException ignored) {
        }
        chance = ConfigCodec.number(values, "chance", chance);
        delayMinMs = ConfigCodec.number(values, "delayMinMs", delayMinMs);
        delayMaxMs = ConfigCodec.number(values, "delayMaxMs", delayMaxMs);
        durationMeanMs = ConfigCodec.number(values, "durationMeanMs", durationMeanMs);
        durationStandardDeviationMs = ConfigCodec.number(values, "durationStandardDeviationMs", durationStandardDeviationMs);
        durationMinMs = ConfigCodec.number(values, "durationMinMs", durationMinMs);
        durationMaxMs = ConfigCodec.number(values, "durationMaxMs", durationMaxMs);
        cooldownMeanMs = ConfigCodec.number(values, "cooldownMeanMs", cooldownMeanMs);
        cooldownStandardDeviationMs = ConfigCodec.number(values, "cooldownStandardDeviationMs", cooldownStandardDeviationMs);
        cooldownMinMs = ConfigCodec.number(values, "cooldownMinMs", cooldownMinMs);
        cooldownMaxMs = ConfigCodec.number(values, "cooldownMaxMs", cooldownMaxMs);
        playersOnly = ConfigCodec.flag(values, "playersOnly", playersOnly);
        weapons = ConfigCodec.flag(values, "weapons", weapons);
        tools = ConfigCodec.flag(values, "tools", tools);
        blocks = ConfigCodec.flag(values, "blocks", blocks);
        other = ConfigCodec.flag(values, "other", other);
        return true;
    }

    private static final class PendingAttack {
        final long attackNanos;
        final WTapMode mode;
        final int actionKey;
        final WTapData data;

        PendingAttack(long attackNanos, WTapMode mode,
                      int actionKey, WTapData data) {
            this.attackNanos = attackNanos;
            this.mode = mode;
            this.actionKey = actionKey;
            this.data = data;
        }
    }

    private static final class ActiveAction {
        final PendingAttack attack;
        final long startNanos;
        final long startPoll;
        final long startCompletedTick;
        volatile boolean durationElapsed;

        ActiveAction(PendingAttack attack, long startNanos, long startPoll,
                     long startCompletedTick) {
            this.attack = attack;
            this.startNanos = startNanos;
            this.startPoll = startPoll;
            this.startCompletedTick = startCompletedTick;
        }
    }
}
