package com.icysnex.ghosttap.core.analytics;

// One correlated local S19 hurt + S12 velocity event. A triggered row records
// both the injected key action and Forge's observation of the resulting jump.
public final class JumpResetData {

    public enum Outcome {
        TRIGGERED,
        SKIP_DISABLED,
        SKIP_MENU,
        SKIP_AIRBORNE,
        SKIP_NOT_SPRINTING,
        SKIP_NOT_FORWARD,
        SKIP_WEAK_VELOCITY,
        SKIP_ENVIRONMENT,
        SKIP_JUMP_HELD,
        SKIP_KEY_BINDING,
        SKIP_ALREADY_QUEUED,
        SKIP_CHANCE,
        CANCELLED
    }

    public final long timestamp;
    public volatile Outcome outcome;
    public final long hurtToVelocityNanos;
    public final long decisionNanos;
    public final boolean onGround;
    public final boolean sprinting;
    public final boolean movingForward;
    public final boolean screenOpen;
    public final boolean physicalJumpDown;
    public volatile String environment;
    public final int jumpKey;
    public final int velocityX;
    public final int velocityY;
    public final int velocityZ;

    public volatile long pressNanos = -1;
    public volatile long jumpNanos = -1;
    public volatile long actualHoldNanos = -1;
    public volatile long targetHoldMs = -1;
    public volatile boolean jumpApplied;
    public volatile boolean sprintResetDeferred;
    public volatile double preMotionX = Double.NaN;
    public volatile double preMotionY = Double.NaN;
    public volatile double preMotionZ = Double.NaN;
    public volatile double jumpMotionX = Double.NaN;
    public volatile double jumpMotionY = Double.NaN;
    public volatile double jumpMotionZ = Double.NaN;
    public volatile double endMotionX = Double.NaN;
    public volatile double endMotionY = Double.NaN;
    public volatile double endMotionZ = Double.NaN;

    public JumpResetData(long timestamp, Outcome outcome, long hurtToVelocityNanos,
                         long decisionNanos, boolean onGround, boolean sprinting,
                         boolean movingForward, boolean screenOpen,
                         boolean physicalJumpDown, String environment,
                         int jumpKey,
                         int velocityX, int velocityY, int velocityZ) {
        this.timestamp = timestamp;
        this.outcome = outcome;
        this.hurtToVelocityNanos = hurtToVelocityNanos;
        this.decisionNanos = decisionNanos;
        this.onGround = onGround;
        this.sprinting = sprinting;
        this.movingForward = movingForward;
        this.screenOpen = screenOpen;
        this.physicalJumpDown = physicalJumpDown;
        this.environment = environment;
        this.jumpKey = jumpKey;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
    }

    public void markPressed(long nowNanos, long velocityNanos,
                            double motionX, double motionY, double motionZ,
                            boolean sprintResetDeferred, long targetHoldMs) {
        pressNanos = velocityNanos >= 0 ? Math.max(0, nowNanos - velocityNanos) : -1;
        preMotionX = motionX;
        preMotionY = motionY;
        preMotionZ = motionZ;
        this.sprintResetDeferred = sprintResetDeferred;
        this.targetHoldMs = targetHoldMs;
    }

    public void markJumped(long nowNanos, long velocityNanos,
                           double motionX, double motionY, double motionZ) {
        jumpApplied = true;
        jumpNanos = velocityNanos >= 0 ? Math.max(0, nowNanos - velocityNanos) : -1;
        jumpMotionX = motionX;
        jumpMotionY = motionY;
        jumpMotionZ = motionZ;
    }

    public void observeEndMotion(double motionX, double motionY, double motionZ) {
        endMotionX = motionX;
        endMotionY = motionY;
        endMotionZ = motionZ;
    }

    public void markCompleted(long holdNanos, double motionX, double motionY, double motionZ) {
        actualHoldNanos = Math.max(0, holdNanos);
        observeEndMotion(motionX, motionY, motionZ);
    }
}
