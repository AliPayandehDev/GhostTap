package com.icysnex.ghosttap.core.analytics;

import com.icysnex.ghosttap.core.wtap.WTapMode;

// One attack considered by Auto W-Tap. Timing fields are completed as the
// selected key action begins and ends.
public final class WTapData {

    public enum Outcome {
        TRIGGERED,
        SKIP_DISABLED,
        SKIP_NOT_PLAYER,
        SKIP_ITEM,
        SKIP_CHANCE,
        SKIP_ALREADY_QUEUED,
        SKIP_KEY_STATE,
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
    public final WTapMode mode;
    public final long decisionNanos;
    public final long scheduledDelayMs;
    public final boolean sprinting;
    public final boolean movingForward;
    public final boolean physicalActionDown;
    public final boolean playerTarget;
    public final HeldItem heldItem;

    public volatile long actionDelayNanos = -1;
    public volatile long targetDurationMs = -1;
    public volatile long actualDurationNanos = -1;
    public volatile long targetCooldownMs = -1;

    public WTapData(long timestamp, Outcome outcome, WTapMode mode,
                    long decisionNanos, long scheduledDelayMs,
                    boolean sprinting, boolean movingForward,
                    boolean physicalActionDown, boolean playerTarget,
                    HeldItem heldItem) {
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
    }

    public void markStarted(long nowNanos, long attackNanos,
                            long targetDurationMs, long targetCooldownMs) {
        actionDelayNanos = Math.max(0, nowNanos - attackNanos);
        this.targetDurationMs = targetDurationMs;
        this.targetCooldownMs = targetCooldownMs;
    }

    public void markEnded(long durationNanos) {
        actualDurationNanos = Math.max(0, durationNanos);
    }
}
