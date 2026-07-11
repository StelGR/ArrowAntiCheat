package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.CollisionUtils;

public class TimerA extends Check {

    private static final long FLYING_OFFSET = 50_000_000L;
    private static final long EVIDENCE_THRESHOLD = 55_000_000L;
    private static final long NEGATIVE_BALANCE_LIMIT = 25_000_000L;
    private static final long POSITIVE_BALANCE_LIMIT = 250_000_000L;

    private static final long RECOVERY_GAP = 100_000_000L;
    private static final long MAX_RECOVERY_BUDGET = 300_000_000L;
    private static final long MAX_RECOVERY_WINDOW = 500_000_000L;

    private static final int MAX_STABLE_PING = 800;
    private static final int MAX_PING_JITTER = 150;
    private static final int MIN_STABLE_SAMPLES = 12;
    private static final double VIOLATION_DECAY_PER_SECOND = 0.10D;

    private long lastFlyingPacket;
    private long balance;
    private long recoveryBudget;
    private long recoveryUntil;
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
        // Keep the existing persistent-high-ping safeguard separate from timer evidence.
        if (profile.getConnectionData().getTransPing() >= 2500 && ready()) {
            if (increaseBuffer() > 500) {
                profile.kick("Your ping is constantly high, do something about it.");
            }
        } else {
            decreaseBufferBy(1);
        }

        if (!isFlyingPacket(event)) {
            return;
        }

        long now = System.nanoTime();

        if (lastFlyingPacket == 0L) {
            lastFlyingPacket = now;
            return;
        }

        long delta = now - lastFlyingPacket;
        lastFlyingPacket = now;

        if (delta <= 0L || !ready() || !isConnectionStable()) {
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
                        + "\ntick " + profile.getTick()
        );
    }

    private boolean isConnectionStable() {
        return profile.getConnectionData().getTransPing() <= MAX_STABLE_PING
                && profile.getConnectionData().getDropTransTime() <= MAX_PING_JITTER;
    }

    private void beginRecovery(long now, long delta) {
        long missingTime = Math.max(0L, delta - FLYING_OFFSET);

        recoveryBudget = Math.min(MAX_RECOVERY_BUDGET, missingTime);
        recoveryUntil = now + Math.min(MAX_RECOVERY_WINDOW, missingTime + FLYING_OFFSET);
        balance = 0L;
        stableSamples = 0;
        violations = 0.0D;
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
        violations = 0.0D;

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
