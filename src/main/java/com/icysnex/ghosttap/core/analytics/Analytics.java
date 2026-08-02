package com.icysnex.ghosttap.core.analytics;

import com.icysnex.ghosttap.core.click.Clicker;
import com.icysnex.ghosttap.core.jump.AutoJumpReset;
import com.icysnex.ghosttap.core.wtap.AutoWTap;
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

    public static void clearWTap() {
        WTapTracker.INSTANCE.clear();
        Notice.show("Cleared W-Tap analytics data.");
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
            writer.printf(Locale.ROOT, "# Delay: %.1fms (SD: %.1f, Range %.1f-%.1f)%n",
                    jumpReset.delayMeanMs, jumpReset.delayStandardDeviationMs,
                    jumpReset.delayMinMs, jumpReset.delayMaxMs);
            writer.printf(Locale.ROOT, "# Hold: %.1fms (SD: %.1f, Range %.1f-%.1f)%n",
                    jumpReset.holdMeanMs, jumpReset.holdStandardDeviationMs,
                    jumpReset.holdMinMs, jumpReset.holdMaxMs);
            writer.printf("# Filters: ground=%s, sprinting=%s, forward=%s, menus=%s%n",
                    jumpReset.onGroundOnly, jumpReset.sprintingOnly,
                    jumpReset.forwardOnly, jumpReset.allowInMenu);
            writer.println("# Negative timing/tick values mean that stage was not reached or observed before export.");
            writer.println("# --- END METADATA ---");

            writer.println("Timestamp,Outcome,HurtToVelocityMS,DecisionUS,ScheduledDelayMS,ActualPressDelayMS,HitInputTick,PressInputTick,InputTickOffset,TargetHoldMS,ActualHoldMS,OnGround,Sprinting,MovingForward,ScreenOpen,PhysicalSpaceDown,VelocityX,VelocityY,VelocityZ,HorizontalVelocity");
            for (JumpResetData d : snapshot) {
                double velocityX = d.velocityX / 8000.0;
                double velocityY = d.velocityY / 8000.0;
                double velocityZ = d.velocityZ / 8000.0;
                double horizontal = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
                writer.printf(Locale.ROOT,
                        "%d,%s,%.4f,%.3f,%d,%.4f,%d,%d,%d,%d,%.4f,%s,%s,%s,%s,%s,%.5f,%.5f,%.5f,%.5f%n",
                        d.timestamp, d.outcome.name(), nanosToMs(d.hurtToVelocityNanos),
                        nanosToUs(d.decisionNanos), d.scheduledDelayMs, nanosToMs(d.pressNanos),
                        d.hitInputTick, d.pressInputTick, d.inputTickOffset, d.targetHoldMs,
                        nanosToMs(d.actualHoldNanos), d.onGround, d.sprinting, d.movingForward,
                        d.screenOpen, d.physicalSpaceDown, velocityX, velocityY, velocityZ, horizontal);
            }
        }, success -> {
            if (success)
                Notice.show("Exported Jump Reset: " + fileName);
            else
                Notice.show("Jump Reset export failed (see console).");
        });
    }

    public static void exportWTap() {
        WTapTracker tracker = WTapTracker.INSTANCE;
        if (tracker.size() <= 0) {
            Notice.show("No W-Tap analytics data. Enable tracking and attack first.");
            return;
        }

        String fileName = "analytics_w_tap_" + System.currentTimeMillis() + ".csv";
        List<WTapData> snapshot = tracker.copy();
        AutoWTap wTap = AutoWTap.INSTANCE;

        Notice.show("Exporting W-Tap analytics...");
        FileIO.saveAsync(fileName, writer -> {
            writer.println("# --- START METADATA (W-TAP) ---");
            writer.printf("# Mode: %s%n", wTap.mode.label);
            writer.printf(Locale.ROOT, "# Chance: %.1f%%%n", wTap.chance * 100);
            writer.printf("# Reference baseline: delay=0ms, tapDuration=%dms, cooldown=%d client ticks (~%dms)%n",
                    AutoWTap.BASELINE_DURATION_MS, AutoWTap.BASELINE_COOLDOWN_TICKS,
                    AutoWTap.BASELINE_COOLDOWN_TICKS * 50);
            writer.println("# Sprint key: untouched");
            writer.println("# Stored humanization timing settings are inactive during this baseline test.");
            writer.printf("# Held items: weapons=%s, tools=%s, blocks=%s, other=%s%n",
                    wTap.weapons, wTap.tools, wTap.blocks, wTap.other);
            writer.printf("# Rules: playersOnly=%s%n", wTap.playersOnly);
            writer.println("# Negative timing values mean that stage was not reached or observed before export.");
            writer.println("# --- END METADATA ---");

            writer.println("Timestamp,Outcome,Mode,DecisionUS,ScheduledDelayMS,ActualActionDelayMS,TargetDurationMS,ActualDurationMS,TargetCooldownMS,Sprinting,MovingForward,PhysicalActionDown,PlayerTarget,HeldItem");
            for (WTapData d : snapshot) {
                writer.printf(Locale.ROOT,
                        "%d,%s,%s,%.3f,%d,%.4f,%d,%.4f,%d,%s,%s,%s,%s,%s%n",
                        d.timestamp, d.outcome.name(), d.mode.name(), nanosToUs(d.decisionNanos),
                        d.scheduledDelayMs, nanosToMs(d.actionDelayNanos),
                        d.targetDurationMs, nanosToMs(d.actualDurationNanos), d.targetCooldownMs,
                        d.sprinting, d.movingForward, d.physicalActionDown,
                        d.playerTarget, d.heldItem.name());
            }
        }, success -> {
            if (success)
                Notice.show("Exported W-Tap: " + fileName);
            else
                Notice.show("W-Tap export failed (see console).");
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
