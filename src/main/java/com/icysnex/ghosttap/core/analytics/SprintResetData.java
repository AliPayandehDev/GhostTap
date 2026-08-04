package com.icysnex.ghosttap.core.analytics;

import com.icysnex.ghosttap.core.sprintreset.SprintResetMode;

// One entity attack considered by Auto Sprint Reset. A pending attack becomes
// TRIGGERED only after the server's target-hurt status is observed.
public final class SprintResetData {

    public enum Outcome {
        PENDING,
        TRIGGERED,
        EXPIRED,
        SKIP_DISABLED,
        SKIP_NOT_PLAYER,
        SKIP_ITEM,
        SKIP_NOT_SPRINTING,
        SKIP_NOT_FORWARD,
        SKIP_HURT_TIME,
        SKIP_CHANCE,
        SKIP_ALREADY_QUEUED,
        SKIP_KEY_STATE,
        INTERRUPTED_INCOMING_HIT,
        CANCELLED
    }

    public enum HeldItem {
        WEAPON,
        TOOL,
        BLOCK,
        OTHER
    }

    public final long timestamp;
    public volatile Outcome outcome;
    public final SprintResetMode mode;
    public final long decisionNanos;
    public final long scheduledDelayMs;
    public final boolean sprinting;
    public final boolean movingForward;
    public final boolean physicalActionDown;
    public final boolean playerTarget;
    public final HeldItem heldItem;
    public final double attackGap;
    public final double attackClosingSpeed;
    public final int targetHurtTime;

    public volatile long confirmationDelayNanos = -1;
    public volatile String confirmationSource = "NONE";
    public volatile double confirmedGap = Double.NaN;
    public volatile double confirmedClosingSpeed = Double.NaN;
    public volatile long actionDelayNanos = -1;
    public volatile double baseDurationTicks = -1;
    public volatile double durationMultiplier = -1;
    public volatile int targetDurationTicks = -1;
    public volatile long actualDurationNanos = -1;
    public volatile boolean backstep;
    public volatile boolean automaticBackstep;
    public volatile int comboStreakBefore = -1;

    public SprintResetData(long timestamp, Outcome outcome, SprintResetMode mode,
                           long decisionNanos, long scheduledDelayMs,
                           boolean sprinting, boolean movingForward,
                           boolean physicalActionDown, boolean playerTarget,
                           HeldItem heldItem, double attackGap,
                           double attackClosingSpeed, int targetHurtTime) {
        this.timestamp = timestamp;
        this.outcome = outcome;
        this.mode = mode;
        this.decisionNanos = decisionNanos;
        this.scheduledDelayMs = scheduledDelayMs;
        this.sprinting = sprinting;
        this.movingForward = movingForward;
        this.physicalActionDown = physicalActionDown;
        this.playerTarget = playerTarget;
        this.heldItem = heldItem;
        this.attackGap = attackGap;
        this.attackClosingSpeed = attackClosingSpeed;
        this.targetHurtTime = targetHurtTime;
    }

    public void markConfirmed(long nowNanos, long attackNanos, String confirmationSource,
                              double confirmedGap, double confirmedClosingSpeed,
                              boolean backstep, boolean automaticBackstep,
                              int comboStreakBefore) {
        outcome = Outcome.TRIGGERED;
        confirmationDelayNanos = Math.max(0, nowNanos - attackNanos);
        this.confirmationSource = confirmationSource;
        this.confirmedGap = confirmedGap;
        this.confirmedClosingSpeed = confirmedClosingSpeed;
        this.backstep = backstep;
        this.automaticBackstep = automaticBackstep;
        this.comboStreakBefore = comboStreakBefore;
    }

    public void markStarted(long nowNanos, long attackNanos,
                            double baseDurationTicks, double durationMultiplier,
                            int targetDurationTicks) {
        actionDelayNanos = Math.max(0, nowNanos - attackNanos);
        this.baseDurationTicks = baseDurationTicks;
        this.durationMultiplier = durationMultiplier;
        this.targetDurationTicks = targetDurationTicks;
    }

    public void markEnded(long durationNanos) {
        actualDurationNanos = Math.max(0, durationNanos);
    }
}
