package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.tasks.TickTask;

import java.util.Arrays;

@Experimental
public class TimerB extends Check {

    private static final int SAMPLE_SIZE = 20;
    private static final int MIN_SAMPLES = 16;

    private static final double EXPECTED_INTERVAL_MS = 50.0D;
    private static final double MIN_SLOW_MEDIAN_MS = 62.0D;
    private static final double MIN_SLOW_SAMPLE_MS = 58.0D;
    private static final double MIN_SLOW_RATIO = 0.75D;

    private static final double MIN_STABLE_TPS = 19.0D;
    private static final long MAX_STABLE_TICK_TIME_MS = 70L;
    private static final long SERVER_LAG_GRACE_NANOS = 1_500_000_000L;
    private static final int NETWORK_JITTER_THRESHOLD_MS = 100;
    private static final long MIN_NETWORK_GRACE_NANOS = 500_000_000L;
    private static final long MAX_NETWORK_GRACE_NANOS = 2_000_000_000L;

    private final double[] intervals = new double[SAMPLE_SIZE];

    private long lastPacketNanos;
    private long lastPacketWallClock;
    private long serverLagUntil;
    private long networkLagUntil;
    private int sampleCount;
    private int sampleIndex;
    private int observedTransactionCount = -1;
    private double threshold;

    public TimerB(Profile profile) {
        super(profile, CheckType.TIMER, "B", "Checks for slowed down game time");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            resetTimingState();
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isFlyingPacket(event)) {
            return;
        }

        final long now = System.nanoTime();
        final long wallClock = System.currentTimeMillis();

        if (!ready()) {
            resetTimingState();
            return;
        }

        // Modern clients do not provide a useful stationary cadence. Reset the
        // baseline as well as returning, otherwise the first moving packet
        // includes all of the time that the player stood still.
        if (!profile.getMovementData().isMoving()) {
            resetTimingState();
            return;
        }

        updateLagWindows(now);

        if (now < serverLagUntil || now < networkLagUntil) {
            followPacket(now, wallClock);
            clearEvidence();
            return;
        }

        if (lastPacketNanos == 0L) {
            followPacket(now, wallClock);
            return;
        }

        final double delta = (now - lastPacketNanos) / 1_000_000.0D;
        final long previousPacketWallClock = lastPacketWallClock;
        followPacket(now, wallClock);

        if (!Double.isFinite(delta) || delta <= 0.0D) {
            clearEvidence();
            return;
        }

        addInterval(delta);

        if (sampleCount < MIN_SAMPLES) {
            return;
        }

        TimingWindow window = analyzeWindow();
        double allowedDeviation = Math.max(4.0D, window.median * 0.14D);
        boolean consistentlySlow = window.median > MIN_SLOW_MEDIAN_MS
                && window.slowRatio >= MIN_SLOW_RATIO
                && window.medianAbsoluteDeviation <= allowedDeviation;

        if (consistentlySlow) {
            double severity = Math.min(1.5D,
                    Math.max(0.5D, (window.median - EXPECTED_INTERVAL_MS) / EXPECTED_INTERVAL_MS));

            if ((threshold += severity) > 4.0D) {
                fail("Slowed Down Time",
                        "delta " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.round(delta)
                                + "\nlastPacket " + MsgType.MAIN_THEME_COLOR.getMessage() + previousPacketWallClock);
                threshold = 0.0D;
            }
        } else {
            threshold = Math.max(0.0D, threshold - 0.65D);
        }
    }

    private void updateLagWindows(long now) {
        if (!Double.isFinite(TickTask.getTPS())
                || TickTask.getTPS() < MIN_STABLE_TPS
                || TickTask.getTickTime() > MAX_STABLE_TICK_TIME_MS
                || TickTask.getLastLagSpike() < 2_000L) {
            serverLagUntil = Math.max(serverLagUntil, now + SERVER_LAG_GRACE_NANOS);
        }

        int transactionCount = profile.getConnectionData().getLastFlyingReceived();

        // Only react once when a new transaction reports a spike. Static high
        // ping does not disable the check; only a measured change grants a
        // short window for packets that may have been queued or bunched.
        if (transactionCount == observedTransactionCount) {
            return;
        }

        observedTransactionCount = transactionCount;
        int ping = Math.max(0, profile.getConnectionData().getTransPing());
        int lastPing = Math.max(0, profile.getConnectionData().getLastTransPing());
        int jitter = Math.abs(ping - lastPing);

        if (lastPing <= 0 || jitter <= NETWORK_JITTER_THRESHOLD_MS) {
            return;
        }

        long grace = Math.max(MIN_NETWORK_GRACE_NANOS,
                Math.min(MAX_NETWORK_GRACE_NANOS, jitter * 2_000_000L));
        networkLagUntil = Math.max(networkLagUntil, now + grace);
    }

    private TimingWindow analyzeWindow() {
        double[] sorted = Arrays.copyOf(intervals, sampleCount);
        Arrays.sort(sorted);

        double median = median(sorted);
        double[] deviations = new double[sorted.length];
        int slowSamples = 0;

        for (int i = 0; i < sorted.length; i++) {
            deviations[i] = Math.abs(sorted[i] - median);

            if (sorted[i] > MIN_SLOW_SAMPLE_MS) {
                slowSamples++;
            }
        }

        Arrays.sort(deviations);
        return new TimingWindow(
                median,
                median(deviations),
                (double) slowSamples / sorted.length
        );
    }

    private double median(double[] sorted) {
        int middle = sorted.length / 2;

        if ((sorted.length & 1) == 0) {
            return (sorted[middle - 1] + sorted[middle]) * 0.5D;
        }

        return sorted[middle];
    }

    private void addInterval(double delta) {
        intervals[sampleIndex] = delta;
        sampleIndex = (sampleIndex + 1) % SAMPLE_SIZE;

        if (sampleCount < SAMPLE_SIZE) {
            sampleCount++;
        }
    }

    private void followPacket(long now, long wallClock) {
        lastPacketNanos = now;
        lastPacketWallClock = wallClock;
    }

    private void resetTimingState() {
        lastPacketNanos = 0L;
        lastPacketWallClock = 0L;
        serverLagUntil = 0L;
        networkLagUntil = 0L;
        observedTransactionCount = profile.getConnectionData().getLastFlyingReceived();
        clearEvidence();
    }

    private void clearEvidence() {
        sampleCount = 0;
        sampleIndex = 0;
        threshold = 0.0D;
    }

    private boolean ready() {
        return profile.getTick() > 100
                && !profile.shouldCancel()
                && !profile.isExempt().isTeleports()
                && !profile.isExempt().vehicle();
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private static class TimingWindow {
        private final double median;
        private final double medianAbsoluteDeviation;
        private final double slowRatio;

        private TimingWindow(double median, double medianAbsoluteDeviation, double slowRatio) {
            this.median = median;
            this.medianAbsoluteDeviation = medianAbsoluteDeviation;
            this.slowRatio = slowRatio;
        }
    }
}
