package com.icysnex.ghosttap.core.analytics;

// One correlated local S19 hurt + S12 velocity event. Timing fields use
// monotonic nanoseconds internally and are converted only during CSV export.
public final class JumpResetData {

    public enum Outcome {
        TRIGGERED,
        SKIP_DISABLED,
        SKIP_MENU,
        SKIP_AIRBORNE,
        SKIP_NOT_SPRINTING,
        SKIP_NOT_FORWARD,
        SKIP_CHANCE,
        CANCELLED
    }

    public final long timestamp;
    public volatile Outcome outcome;
    public final long hurtToVelocityNanos;
    public final long decisionNanos;
    public final long scheduledDelayMs;
    public final long hitInputTick;

    public final boolean onGround;
    public final boolean sprinting;
    public final boolean movingForward;
    public final boolean screenOpen;
    public final boolean physicalSpaceDown;

    public final int velocityX;
    public final int velocityY;
    public final int velocityZ;

    public volatile long pressNanos = -1;
    public volatile long pressInputTick = -1;
    public volatile int inputTickOffset = -1;
    public volatile long targetHoldMs = -1;
    public volatile long actualHoldNanos = -1;

    public JumpResetData(long timestamp, Outcome outcome, long hurtToVelocityNanos,
                         long decisionNanos, long scheduledDelayMs, long hitInputTick,
                         boolean onGround, boolean sprinting, boolean movingForward,
                         boolean screenOpen, boolean physicalSpaceDown,
                         int velocityX, int velocityY, int velocityZ) {
        this.timestamp = timestamp;
        this.outcome = outcome;
        this.hurtToVelocityNanos = hurtToVelocityNanos;
        this.decisionNanos = decisionNanos;
        this.scheduledDelayMs = scheduledDelayMs;
        this.hitInputTick = hitInputTick;
        this.onGround = onGround;
        this.sprinting = sprinting;
        this.movingForward = movingForward;
        this.screenOpen = screenOpen;
        this.physicalSpaceDown = physicalSpaceDown;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
    }

    public void markPressed(long nowNanos, long velocityNanos, long targetHoldMs) {
        this.pressNanos = Math.max(0, nowNanos - velocityNanos);
        this.targetHoldMs = targetHoldMs;
    }

    public void markInputTick(long tick) {
        if (pressInputTick >= 0)
            return;
        pressInputTick = tick;
        long offset = tick - hitInputTick;
        inputTickOffset = offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, offset);
    }

    public void markReleased(long actualHoldNanos) {
        this.actualHoldNanos = Math.max(0, actualHoldNanos);
    }
}
