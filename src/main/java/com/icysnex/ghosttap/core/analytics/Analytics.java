package com.icysnex.ghosttap.core.analytics;

import com.icysnex.ghosttap.core.click.Clicker;
import com.icysnex.ghosttap.core.jump.AutoJumpReset;
import com.icysnex.ghosttap.core.sprintreset.AutoSprintReset;
import com.icysnex.ghosttap.utils.FileIO;
import com.icysnex.ghosttap.utils.Notice;

import java.util.List;
import java.util.Locale;

// Export/clear used by the config UI; one CSV per button.
public class Analytics {

    private static int totalSize() {
        return Clicker.LEFT.tracker.size() + Clicker.RIGHT.tracker.size();
    }

    public static void clear() {
        Clicker.LEFT.tracker.clear();
        Clicker.RIGHT.tracker.clear();
        Notice.show("Cleared analytics data.");
    }

    public static void clear(Clicker clicker) {
        clicker.tracker.clear();
        Notice.show("Cleared " + label(clicker) + " analytics data.");
    }

    public static void clearJumpReset() {
        JumpResetTracker.INSTANCE.clear();
        Notice.show("Cleared Jump Reset analytics data.");
    }

    public static void clearSprintReset() {
        SprintResetTracker.INSTANCE.clear();
        Notice.show("Cleared Sprint Reset analytics data.");
    }

    public static void export() {
        if (totalSize() <= 0) {
            Notice.show("No analytics data. Enable tracking and click first.");
            return;
        }

        Notice.show("Exporting analytics...");
        exportButton(Clicker.LEFT, "left");
        exportButton(Clicker.RIGHT, "right");
    }

    public static void export(Clicker clicker) {
        String label = label(clicker);
        if (clicker.tracker.size() <= 0) {
            Notice.show("No " + label + " analytics data. Enable tracking and click first.");
            return;
        }

        Notice.show("Exporting " + label + " analytics...");
        exportButton(clicker, label);
    }

    public static void exportJumpReset() {
        JumpResetTracker tracker = JumpResetTracker.INSTANCE;
        if (tracker.size() <= 0) {
            Notice.show("No Jump Reset analytics data. Enable tracking and receive a hit first.");
            return;
        }

        String fileName = "analytics_jump_reset_" + System.currentTimeMillis() + ".csv";
        List<JumpResetData> snapshot = tracker.copy();
        AutoJumpReset jumpReset = AutoJumpReset.INSTANCE;

        Notice.show("Exporting Jump Reset analytics...");
        FileIO.saveAsync(fileName, writer -> {
            writer.println("# --- START METADATA (JUMP RESET) ---");
            writer.printf(Locale.ROOT, "# Chance: %.1f%%%n", jumpReset.chance * 100);
            writer.printf(Locale.ROOT, "# Hold: %.1fms (variation: %.1f, range %.1f-%.1f)%n",
                    jumpReset.holdMeanMs, jumpReset.holdStandardDeviationMs,
                    jumpReset.holdMinMs, jumpReset.holdMaxMs);
            writer.println("# Timing: next movement boundary; every press crosses at least one movement sample");
            writer.printf(Locale.ROOT, "# Minimum horizontal velocity: %.3f blocks/tick%n",
                    jumpReset.minimumHorizontalVelocity);
            writer.printf("# Filters: ground=%s, sprinting=%s, forward=%s, menus=%s%n",
                    jumpReset.onGroundOnly, jumpReset.sprintingOnly,
                    jumpReset.forwardOnly, jumpReset.allowInMenu);
            writer.println("# Environment guards: flying, riding, water, lava and ladders");
            writer.println("# Negative timing values mean that stage was not reached or observed before export.");
            writer.println("# --- END METADATA ---");

            writer.println("Timestamp,Outcome,HurtToVelocityMS,DecisionUS,ActualPressDelayMS,TargetHoldMS,ActualHoldMS,JumpApplied,JumpDelayMS,SprintResetDeferred,OnGround,Sprinting,MovingForward,ScreenOpen,PhysicalJumpDown,Environment,JumpKey,VelocityX,VelocityY,VelocityZ,HorizontalVelocity,PreMotionX,PreMotionY,PreMotionZ,JumpMotionX,JumpMotionY,JumpMotionZ,EndMotionX,EndMotionY,EndMotionZ");
            for (JumpResetData d : snapshot) {
                double velocityX = d.velocityX / 8000.0;
                double velocityY = d.velocityY / 8000.0;
                double velocityZ = d.velocityZ / 8000.0;
                double horizontal = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
                writer.printf(Locale.ROOT,
                        "%d,%s,%.4f,%.3f,%.4f,%d,%.4f,%s,%.4f,%s,%s,%s,%s,%s,%s,%s,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f%n",
                        d.timestamp, d.outcome.name(),
                        nanosToMs(d.hurtToVelocityNanos),
                        nanosToUs(d.decisionNanos), nanosToMs(d.pressNanos),
                        d.targetHoldMs, nanosToMs(d.actualHoldNanos), d.jumpApplied,
                        nanosToMs(d.jumpNanos),
                        d.sprintResetDeferred, d.onGround, d.sprinting, d.movingForward,
                        d.screenOpen, d.physicalJumpDown, d.environment, d.jumpKey,
                        velocityX, velocityY, velocityZ, horizontal,
                        d.preMotionX, d.preMotionY, d.preMotionZ,
                        d.jumpMotionX, d.jumpMotionY, d.jumpMotionZ,
                        d.endMotionX, d.endMotionY, d.endMotionZ);
            }
        }, success -> {
            if (success)
                Notice.show("Exported Jump Reset: " + fileName);
            else
                Notice.show("Jump Reset export failed (see console).");
        });
    }

    public static void exportSprintReset() {
        SprintResetTracker tracker = SprintResetTracker.INSTANCE;
        if (tracker.size() <= 0) {
            Notice.show("No Sprint Reset analytics data. Enable tracking and attack first.");
            return;
        }

        String fileName = "analytics_sprint_reset_" + System.currentTimeMillis() + ".csv";
        List<SprintResetData> snapshot = tracker.copy();
        AutoSprintReset sprintReset = AutoSprintReset.INSTANCE;

        Notice.show("Exporting Sprint Reset analytics...");
        FileIO.saveAsync(fileName, writer -> {
            writer.println("# --- START METADATA (SPRINT RESET) ---");
            writer.printf("# Mode: %s%n", sprintReset.mode.label);
            writer.printf(Locale.ROOT, "# Chance: %.1f%%%n", sprintReset.chance * 100);
            writer.printf(Locale.ROOT, "# Delay: uniform min=%.1fms, max=%.1fms%n",
                    sprintReset.delayMinMs, sprintReset.delayMaxMs);
            writer.printf(Locale.ROOT, "# Tap duration: mean=%.2ft, variation=%.2ft, min=%.1ft, max=%.1ft%n",
                    sprintReset.durationMeanTicks, sprintReset.durationStandardDeviationTicks,
                    sprintReset.durationMinTicks, sprintReset.durationMaxTicks);
            writer.printf(Locale.ROOT, "# Duration adaptation: strength=%.1f%%, close multiplier=%.1f%%; never shortens the baseline%n",
                    sprintReset.durationAdaptiveness * 100,
                    sprintReset.durationCloseMultiplier * 100);
            writer.printf(Locale.ROOT, "# Spacing assist: enabled=%s, desired horizontal hitbox gap=%.2f%n",
                    sprintReset.spacingAssist, sprintReset.spacingGap);
            writer.println("# Spacing prediction: one movement tick using relative closing speed");
            writer.println("# Trigger policy: matching server target-hurt status; target hurtTime gates repeated attempts");
            writer.println("# Sprint key: untouched");
            writer.printf("# Held items: weapons=%s, tools=%s, blocks=%s, other=%s%n",
                    sprintReset.weapons, sprintReset.tools, sprintReset.blocks, sprintReset.other);
            writer.printf("# Rules: playersOnly=%s%n", sprintReset.playersOnly);
            writer.println("# Negative timing values mean that stage was not reached or observed before export.");
            writer.println("# --- END METADATA ---");

            writer.println("Timestamp,Outcome,Mode,DecisionUS,ScheduledDelayMS,ConfirmationSource,ConfirmationDelayMS,ActualActionDelayMS,BaseDurationTicks,DurationMultiplier,TargetDurationTicks,ActualDurationMS,Backstep,JumpDeferrals,SprintAtActionStart,SprintStoppedDuringAction,SprintAfterRestore,Sprinting,MovingForward,PhysicalActionDown,PlayerTarget,HeldItem,AttackGap,AttackClosingSpeed,ConfirmedGap,ConfirmedClosingSpeed,ProjectedGap,FinalGap,TargetHurtTime");
            for (SprintResetData d : snapshot) {
                writer.printf(Locale.ROOT,
                        "%d,%s,%s,%.3f,%d,%s,%.4f,%.4f,%.4f,%.4f,%d,%.4f,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%.4f,%.6f,%.4f,%.6f,%.4f,%.4f,%d%n",
                        d.timestamp, d.outcome.name(), d.mode.name(), nanosToUs(d.decisionNanos),
                        d.scheduledDelayMs, d.confirmationSource,
                        nanosToMs(d.confirmationDelayNanos),
                        nanosToMs(d.actionDelayNanos), d.baseDurationTicks,
                        d.durationMultiplier, d.targetDurationTicks,
                        nanosToMs(d.actualDurationNanos), d.backstep, d.jumpDeferrals,
                        d.sprintAtActionStart, d.sprintStoppedDuringAction,
                        d.sprintAfterRestore,
                        d.sprinting, d.movingForward, d.physicalActionDown,
                        d.playerTarget, d.heldItem.name(), d.attackGap,
                        d.attackClosingSpeed, d.confirmedGap,
                        d.confirmedClosingSpeed, d.projectedGap, d.finalGap,
                        d.targetHurtTime);
            }
        }, success -> {
            if (success)
                Notice.show("Exported Sprint Reset: " + fileName);
            else
                Notice.show("Sprint Reset export failed (see console).");
        });
    }

    private static double nanosToMs(long nanos) {
        return nanos < 0 ? -1 : nanos / 1_000_000.0;
    }

    private static double nanosToUs(long nanos) {
        return nanos < 0 ? -1 : nanos / 1_000.0;
    }

    private static String label(Clicker clicker) {
        return clicker == Clicker.LEFT ? "left" : "right";
    }

    private static void exportButton(Clicker c, String label) {
        if (c.tracker.size() <= 0)
            return;

        String fileName = "analytics_" + label + "_" + System.currentTimeMillis() + ".csv";
        List<ClickData> snapshot = c.tracker.copy();

        FileIO.saveAsync(fileName, (writer) -> {
            writer.printf("# --- START METADATA (%s) ---%n", label.toUpperCase());
            writer.printf("# CPS: %.1f (SD: %.1f, Range %.1f-%.1f +-%.1f)%n", c.cpsMean, c.cpsStandardDeviation, c.cpsMin, c.cpsMax, c.cpsMinMaxFallout);
            writer.printf("# - Spike %.1f%% (%.1f-%.1f) %n", c.spikeChance * 100, c.spikeMin, c.spikeMax);
            writer.printf("# - Stutter %.1f%% (%.1f-%.1f) %n", c.stutterChance * 100, c.stutterMin, c.stutterMax);
            writer.printf("# Hold: %.1fms (SD: %.1f, Range %.1f-%.1f)%n", c.holdMsMean, c.holdMsStandardDeviation, c.holdMsMin, c.holdMsMax);
            writer.printf("# - Heavy: %.1f%% (%.1f-%.1f)%n", c.holdMsHeavyChance, c.holdMsHeavyMin, c.holdMsHeavyMax);
            writer.printf("# Rythm: %.1f%% %.1f%n", c.rhythmVolatility, c.rhythmTension);
            writer.println("# --- END METADATA ---");

            writer.println("Timestamp,TargetCPS,ActualCPS,HoldMS,IntervalMS,Trend");
            for (ClickData d : snapshot) {
                writer.printf("%d,%.2f,%.2f,%.2f,%.2f,%.2f%n", d.timestamp, d.targetCps, d.actualCps, d.holdNanos / 1_000_000.0, d.intervalNanos / 1_000_000.0, d.trend);
            }
        }, (success) -> {
            if (success) {
                Notice.show("Exported " + label + ": " + fileName);
            } else {
                Notice.show("Export failed for " + label + " (see console).");
            }
        });
    }
}
