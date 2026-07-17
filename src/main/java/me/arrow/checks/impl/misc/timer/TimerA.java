package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.tasks.TickTask;
import me.arrow.utils.CollisionUtils;

public class TimerA extends Check {

    private static final long FLYING_OFFSET = 50_000_000L;
    private static final long EVIDENCE_THRESHOLD = 55_000_000L;
    private static final long NEGATIVE_BALANCE_LIMIT = 25_000_000L;
    private static final long POSITIVE_BALANCE_LIMIT = 250_000_000L;

    private static final long RECOVERY_GAP = 70_000_000L;
    private static final long MAX_RECOVERY_BUDGET = 300_000_000L;
    private static final long MAX_RECOVERY_WINDOW = 500_000_000L;

    private static final double MIN_STABLE_TPS = 19.0D;
    private static final long MAX_STABLE_TICK_TIME = 65L;
    private static final long SERVER_LAG_GRACE = 2_000_000_000L;
    private static final long SEVERE_LAG_GRACE = 3_000_000_000L;
    private static final long MIN_NETWORK_GRACE = 750_000_000L;
    private static final long MAX_NETWORK_GRACE = 3_000_000_000L;

    private static final int MAX_STABLE_PING = 800;
    private static final int MAX_PING_JITTER = 150;
    private static final int PING_SPIKE_THRESHOLD = 100;
    private static final int MIN_STABLE_SAMPLES = 12;
    private static final double VIOLATION_DECAY_PER_SECOND = 0.10D;

    private long lastFlyingPacket;
    private long balance;
    private long recoveryBudget;
    private long recoveryUntil;
    private long serverLagUntil;
    private long networkLagUntil;
    private int stableSamples;
    private double violations;

    public TimerA(Profile profile) {
        super(profile, CheckType.TIMER, "A", "Checks for game speedup modifications");
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

        if (profile.getConnectionData().getTransPing() >= 1500 && ready()) {
            if (increaseBuffer() > 750) {
                profile.kick("Your ping is constantly high, do something about it.");
            }
        } else {
            decreaseBufferBy(50);
        }


        long now = System.nanoTime();

        if (!ready()) {
            resetTimingState();
            return;
        }

        if (isLagCompensated(now)) {
            // Keep following the live packet stream while laggy, but do not let
            // packets queued by the server/network become timer evidence when
            // the connection recovers.
            lastFlyingPacket = now;
            resetEvidence();
            return;
        }

        if (lastFlyingPacket == 0L) {
            lastFlyingPacket = now;
            return;
        }

        long delta = now - lastFlyingPacket;
        lastFlyingPacket = now;

        if (delta <= 0L) {
            resetEvidence();
            return;
        }

        if (delta >= RECOVERY_GAP) {
            beginRecovery(now, delta);
            return;
        }

        if (isRecovering(now)) {
            consumeRecovery(now, delta);
            return;
        }

        stableSamples++;

        // Normal jitter pays itself back. Its negative side is deliberately
        // capped, so a delayed packet cannot be saved and spent much later.
        balance = clamp(balance + (FLYING_OFFSET - delta),
                -NEGATIVE_BALANCE_LIMIT, POSITIVE_BALANCE_LIMIT);

        if (stableSamples >= MIN_STABLE_SAMPLES && balance > EVIDENCE_THRESHOLD) {
            long observedBalance = balance;
            long excess = observedBalance - EVIDENCE_THRESHOLD;
            violations = Math.min(10.0D, violations + 1.0D + Math.min(1.0D, (double) excess / EVIDENCE_THRESHOLD));

            if (violations > 3.0D) {
                fail("Speeding up game clock (uncapped)",
                        "balance " + MsgType.MAIN_THEME_COLOR.getMessage() + millis(observedBalance)
                                + "\nmaxBalance " + MsgType.MAIN_THEME_COLOR.getMessage() + millis(EVIDENCE_THRESHOLD)
                                + "\nrate " + MsgType.MAIN_THEME_COLOR.getMessage() + rate(delta)
                                + "\ndelay " + MsgType.MAIN_THEME_COLOR.getMessage() + millis(FLYING_OFFSET - delta)
                                + "\ndiff " + MsgType.MAIN_THEME_COLOR.getMessage() + millis(delta)
                                + "\nstableSamples " + MsgType.MAIN_THEME_COLOR.getMessage() + stableSamples
                                + "\ntps " + MsgType.MAIN_THEME_COLOR.getMessage() + TickTask.getTPS()
                                + "\ntickTime " + MsgType.MAIN_THEME_COLOR.getMessage() + TickTask.getTickTime()
                                + "\ntransPing " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getConnectionData().getTransPing()
                                + "\npingJitter " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getConnectionData().getDropTransTime()
                                + "\ntick " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getTick()
                );
            }

            balance = Math.max(0L, observedBalance - EVIDENCE_THRESHOLD);
        } else {
            decayViolations(delta);
        }

        verbose(
                getClass().getSimpleName(),
                balance / 1_000_000.0D,
                EVIDENCE_THRESHOLD / 1_000_000.0D,
                "balance " + millis(balance)
                        + "\ndelay " + millis(FLYING_OFFSET - delta)
                        + "\ndiff " + millis(delta)
                        + "\nrate " + rate(delta)
                        + "\nrecovery " + millis(recoveryBudget)
                        + "\nstableSamples " + stableSamples
                        + "\nviolations " + violations
                        + "\ntps " + TickTask.getTPS()
                        + "\ntickTime " + TickTask.getTickTime()
                        + "\ntransPing " + profile.getConnectionData().getTransPing()
                        + "\npingJitter " + profile.getConnectionData().getDropTransTime()
                        + "\ntick " + profile.getTick()
        );
    }

    private boolean isLagCompensated(long now) {
        updateServerLagWindow(now);
        updateNetworkLagWindow(now);
        return now < serverLagUntil || now < networkLagUntil;
    }

    private void updateServerLagWindow(long now) {
        double tps = TickTask.getTPS();
        long tickTime = TickTask.getTickTime();

        if (!Double.isFinite(tps)
                || tps < MIN_STABLE_TPS
                || tickTime > MAX_STABLE_TICK_TIME) {
            serverLagUntil = Math.max(serverLagUntil, now + SERVER_LAG_GRACE);
        }

        if (TickTask.getLastLagSpike() < 2_000L) {
            serverLagUntil = Math.max(serverLagUntil, now + SEVERE_LAG_GRACE);
        }
    }

    private void updateNetworkLagWindow(long now) {
        int ping = Math.max(0, profile.getConnectionData().getTransPing());
        int lastPing = Math.max(0, profile.getConnectionData().getLastTransPing());
        int jitter = Math.max(profile.getConnectionData().getDropTransTime(), Math.abs(ping - lastPing));

        boolean unstable = ping > MAX_STABLE_PING
                || jitter > MAX_PING_JITTER
                || (lastPing > 0 && ping > lastPing + PING_SPIKE_THRESHOLD);

        if (!unstable) {
            return;
        }

        long measuredLag = Math.max(ping, lastPing) + (long) jitter;
        long grace = clamp(measuredLag * 2_000_000L,
                MIN_NETWORK_GRACE, MAX_NETWORK_GRACE);
        networkLagUntil = Math.max(networkLagUntil, now + grace);
    }

    private void beginRecovery(long now, long delta) {
        long missingTime = Math.max(0L, delta - FLYING_OFFSET);

        recoveryBudget = Math.min(MAX_RECOVERY_BUDGET, missingTime);
        recoveryUntil = now + Math.min(MAX_RECOVERY_WINDOW, missingTime + FLYING_OFFSET);
        balance = 0L;
        stableSamples = 0;
        decayViolations(delta);
    }

    private boolean isRecovering(long now) {
        if (recoveryBudget <= 0L || now > recoveryUntil) {
            recoveryBudget = 0L;
            recoveryUntil = 0L;
            return false;
        }

        return true;
    }

    private void consumeRecovery(long now, long delta) {
        long fastTime = Math.max(0L, FLYING_OFFSET - delta);

        if (fastTime == 0L) {
            // Recovery packets arrive together. A normal interval ends the
            // recovery window instead of leaving spare allowance behind.
            recoveryBudget = 0L;
            recoveryUntil = 0L;
        } else {
            recoveryBudget = Math.max(0L, recoveryBudget - fastTime);
        }

        balance = 0L;
        stableSamples = 0;

        if (now >= recoveryUntil || recoveryBudget == 0L) {
            recoveryBudget = 0L;
            recoveryUntil = 0L;
        }
    }

    private void decayViolations(long delta) {
        double decay = (delta / 1_000_000_000.0D) * VIOLATION_DECAY_PER_SECOND;
        violations = Math.max(0.0D, violations - decay);
    }

    private void resetTimingState() {
        lastFlyingPacket = 0L;
        serverLagUntil = 0L;
        networkLagUntil = 0L;
        resetEvidence();
    }

    private void resetEvidence() {
        balance = 0L;
        recoveryBudget = 0L;
        recoveryUntil = 0L;
        stableSamples = 0;
        violations = 0.0D;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double rate(long delta) {
        return Math.min((double) FLYING_OFFSET / Math.max(1L, delta), 10.0D);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private boolean ready() {
        return profile.getTick() > 100
                && !profile.shouldCancel()
                && !profile.isExempt().isTeleports()
                && !profile.isExempt().vehicle()
                && CollisionUtils.isChunkLoaded(profile.getMovementData().getLocation());
    }
}
