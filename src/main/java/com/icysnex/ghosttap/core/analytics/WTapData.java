package com.icysnex.ghosttap.core.analytics;

// One attack considered by W-Tap. Measured timing fields are filled as the
// synthetic W release and resume are observed.
public final class WTapData {

    public enum Outcome {
        TRIGGERED,
        SKIP_DISABLED,
        SKIP_NOT_SPRINTING,
        SKIP_NOT_FORWARD,
        SKIP_NOT_PLAYER,
        SKIP_ITEM,
        SKIP_CHANCE,
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
    public final long decisionNanos;
    public final long scheduledDelayMs;
    public final boolean sprinting;
    public final boolean movingForward;
    public final boolean physicalWDown;
    public final boolean playerTarget;
    public final HeldItem heldItem;

    public volatile long releaseDelayNanos = -1;
    public volatile long targetReleaseMs = -1;
    public volatile long actualReleaseNanos = -1;

    public WTapData(long timestamp, Outcome outcome, long decisionNanos,
                    long scheduledDelayMs, boolean sprinting, boolean movingForward,
                    boolean physicalWDown, boolean playerTarget, HeldItem heldItem) {
        this.timestamp = timestamp;
        this.outcome = outcome;
        this.decisionNanos = decisionNanos;
        this.scheduledDelayMs = scheduledDelayMs;
        this.sprinting = sprinting;
        this.movingForward = movingForward;
        this.physicalWDown = physicalWDown;
        this.playerTarget = playerTarget;
        this.heldItem = heldItem;
    }

    public void markReleased(long nowNanos, long attackNanos, long targetReleaseMs) {
        releaseDelayNanos = Math.max(0, nowNanos - attackNanos);
        this.targetReleaseMs = targetReleaseMs;
    }

    public void markResumed(long actualReleaseNanos) {
        this.actualReleaseNanos = Math.max(0, actualReleaseNanos);
    }
}
