package me.arrow.managers.profiler;

import lombok.Getter;

public class ProfilerData {

    private long totalTime;
    @Getter
    private long maxTime;
    @Getter
    private long calls;

    public void add(long nanos) {
        totalTime += nanos;
        calls++;

        if (nanos > maxTime) {
            maxTime = nanos;
        }
    }

    public double getAverageTime() {
        return calls == 0 ? 0 : (double) totalTime / calls;
    }

    public void reset() {
        totalTime = 0;
        maxTime = 0;
        calls = 0;
    }
}