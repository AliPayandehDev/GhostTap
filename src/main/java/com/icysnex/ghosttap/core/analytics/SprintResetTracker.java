package com.icysnex.ghosttap.core.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class SprintResetTracker {

    public static final SprintResetTracker INSTANCE = new SprintResetTracker();
    private static final int MAX_HISTORY = 2000;

    private final ConcurrentLinkedQueue<SprintResetData> history = new ConcurrentLinkedQueue<>();

    private SprintResetTracker() {
    }

    public SprintResetData record(SprintResetData data) {
        if (!Tracker.enabled)
            return null;

        if (history.size() >= MAX_HISTORY)
            history.poll();
        history.add(data);
        return data;
    }

    public void clear() {
        history.clear();
    }

    public int size() {
        return history.size();
    }

    public long triggered() {
        return history.stream().filter(d -> d.outcome == SprintResetData.Outcome.TRIGGERED).count();
    }

    public long expired() {
        return history.stream().filter(d -> d.outcome == SprintResetData.Outcome.EXPIRED).count();
    }

    public double averageConfirmationMs() {
        return history.stream()
                .filter(d -> d.confirmationDelayNanos >= 0)
                .mapToDouble(d -> d.confirmationDelayNanos / 1_000_000.0)
                .average().orElse(0);
    }

    public double averageActionDelayMs() {
        return history.stream()
                .filter(d -> d.actionDelayNanos >= 0)
                .mapToDouble(d -> d.actionDelayNanos / 1_000_000.0)
                .average().orElse(0);
    }

    public double averageDurationMs() {
        return history.stream()
                .filter(d -> d.actualDurationNanos >= 0)
                .mapToDouble(d -> d.actualDurationNanos / 1_000_000.0)
                .average().orElse(0);
    }

    public List<SprintResetData> copy() {
        return new ArrayList<>(history);
    }
}
