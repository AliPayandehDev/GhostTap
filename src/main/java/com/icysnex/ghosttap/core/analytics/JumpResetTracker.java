package com.icysnex.ghosttap.core.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class JumpResetTracker {

    public static final JumpResetTracker INSTANCE = new JumpResetTracker();
    private static final int MAX_HISTORY = 2000;

    private final ConcurrentLinkedQueue<JumpResetData> history = new ConcurrentLinkedQueue<>();

    private JumpResetTracker() {
    }

    public JumpResetData record(JumpResetData data) {
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
        return history.stream().filter(d -> d.outcome == JumpResetData.Outcome.TRIGGERED).count();
    }

    public long sameTick() {
        return history.stream().filter(d -> d.inputTickOffset == 0).count();
    }

    public long observedPresses() {
        return history.stream().filter(d -> d.inputTickOffset >= 0).count();
    }

    public double sameTickPercent() {
        long observed = observedPresses();
        return observed == 0 ? 0 : sameTick() * 100.0 / observed;
    }

    public double averagePressDelayMs() {
        return history.stream()
                .filter(d -> d.pressNanos >= 0)
                .mapToDouble(d -> d.pressNanos / 1_000_000.0)
                .average().orElse(0);
    }

    public List<JumpResetData> copy() {
        return new ArrayList<>(history);
    }
}
