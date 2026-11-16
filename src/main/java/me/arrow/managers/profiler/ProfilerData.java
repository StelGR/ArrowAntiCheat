package me.arrow.managers.profiler;

public class ProfilerData {
    private long totalTime;
    private long maxTime;
    private long calls;

    public synchronized void add(long nanos) {
        totalTime += nanos;
        calls++;

        if (nanos > maxTime) {
            maxTime = nanos;
        }
    }

    public synchronized long getTotalTime() {
        return totalTime;
    }

    public synchronized long getMaxTime() {
        return maxTime;
    }

    public synchronized long getCalls() {
        return calls;
    }

    public synchronized double getAverageTime() {
        return calls == 0 ? 0 : (double) totalTime / calls;
    }

    public synchronized void reset() {
        totalTime = 0;
        maxTime = 0;
        calls = 0;
    }
}
