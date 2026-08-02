package com.icysnex.ghosttap.core.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class WTapTracker {

    public static final WTapTracker INSTANCE = new WTapTracker();
    private static final int MAX_HISTORY = 2000;

    private final ConcurrentLinkedQueue<WTapData> history = new ConcurrentLinkedQueue<>();

    private WTapTracker() {
    }

    public WTapData record(WTapData data) {
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
        return history.stream().filter(d -> d.outcome == WTapData.Outcome.TRIGGERED).count();
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

    public List<WTapData> copy() {
        return new ArrayList<>(history);
    }
}
