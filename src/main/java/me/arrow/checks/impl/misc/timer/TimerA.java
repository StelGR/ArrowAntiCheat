package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.profile.Profile;
import me.arrow.tasks.TickTask;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class TimerA extends Check {

    private static final long FLYING_OFFSET = 50_000_000L;
    private static final long EVIDENCE_THRESHOLD = 55_000_000L;
    private static final long NEGATIVE_BALANCE_LIMIT = 3_000_000_000L;
    private static final long POSITIVE_BALANCE_LIMIT = 500_000_000L;

    private static final long RECOVERY_GAP = 70_000_000L;
    private static final long MAX_RECOVERY_BUDGET = 1_000_000_000L;
    private static final long MAX_RECOVERY_WINDOW = 3_000_000_000L;
    private static final long BUNCHED_PACKET_GAP = 5_000_000L;

    private static final double MIN_STABLE_TPS = 19.0D;
    private static final long MAX_STABLE_TICK_TIME = 65L;

    private static final int MAX_PING_JITTER = 700;
    private static final int MIN_STABLE_SAMPLES = 20;
    private static final double VIOLATION_DECAY_PER_SECOND = 0.10D;

    private long lastFlyingPacket;
    private long balance;
    private long recoveryBudget;
    private long recoveryUntil;
    private int observedServerTick = -1;
    private int observedTransactionCount = -1;
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

        if (profile.getConnectionData().getTransPing() >= 1500 && ready() && !profile.isPingkicked()) {
            if (increaseBuffer() > 750) {
                profile.kick("Your ping is constantly very high (> 1500), do something about it.");
                profile.setPingkicked(true);
                for (Player staff : Bukkit.getOnlinePlayers()) {

                    final Profile staffProfile = Arrow.getInstance().getProfileManager().getProfile(staff);

                    if (staffProfile == null || !staffProfile.isAlerts()) {
                        continue;
                    }

                    if (!staff.hasPermission(Permissions.ALERTS.getPermission())) {
                        continue;
                    }

                    staff.sendMessage(OtherUtility.translate(MsgType.PREFIX.getMessage() + profile.getPlayer().getDisplayName() + MsgType.MAIN_THEME_COLOR.getMessage() + " was kicked due to ping timeout."));
                }
            }
        } else {
            decreaseBufferBy(50);
        }

//        if (profile.getConnectionData().getTransPing() >= 2000 && profile.getPing() > 1000 && ready() && !profile.isPingkicked()) {
//            if (increaseBuffer() > 50) {
//                profile.kick("Your ping is constantly high, do something about it.");
//                profile.setPingkicked(true);
//                for (Player staff : Bukkit.getOnlinePlayers()) {
//
//                    final Profile staffProfile = Arrow.getInstance().getProfileManager().getProfile(staff);
//
//                    if (staffProfile == null || !staffProfile.isAlerts()) {
//                        continue;
//                    }
//
//                    if (!staff.hasPermission(Permissions.ALERTS.getPermission())) {
//                        continue;
//                    }
//
//                    staff.sendMessage(OtherUtility.translate(MsgType.PREFIX.getMessage() + profile.getPlayer().getDisplayName() + MsgType.MAIN_THEME_COLOR.getMessage() + " was kicked due to ping timeout. (2)"));
//                }
//            }
//        }
//        else decreaseBufferBy(10);


        long now = System.nanoTime();

        if (!ready()) {
            resetTimingState();
            return;
        }

        if (lastFlyingPacket == 0L) {
            lastFlyingPacket = now;
            return;
        }

        long delta = now - lastFlyingPacket;
        lastFlyingPacket = now;

        if (delta <= 0L) {
            clearTimingWindow();
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

        boolean serverLagSignal = pollServerLagSignal();
        boolean networkLagSignal = pollNetworkLagSignal();

        /*
         * Only discard an interval when a fresh lag signal coincides with an
         * actual queued-packet burst. Static low TPS/high ping cannot renew a
         * full exemption, and previous timer evidence is deliberately kept.
         */
        if ((serverLagSignal || networkLagSignal) && delta < BUNCHED_PACKET_GAP) {
            clearTimingWindow();
            decayViolations(delta);
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

    private boolean pollServerLagSignal() {
        int currentTick = TickTask.getCurrentTick();

        if (currentTick == observedServerTick) {
            return false;
        }

        observedServerTick = currentTick;
        double tps = TickTask.getTPS();
        long tickTime = TickTask.getTickTime();

        return !Double.isFinite(tps)
                || tps < MIN_STABLE_TPS
                || tickTime > MAX_STABLE_TICK_TIME
                || TickTask.getLastLagSpike() < 2_000L;
    }

    private boolean pollNetworkLagSignal() {
        int transactionCount = profile.getConnectionData().getLastFlyingReceived();

        if (transactionCount == observedTransactionCount) {
            return false;
        }

        observedTransactionCount = transactionCount;
        int ping = Math.max(0, profile.getConnectionData().getTransPing());
        int lastPing = Math.max(0, profile.getConnectionData().getLastTransPing());
        int jitter = Math.max(profile.getConnectionData().getDropTransTime(), Math.abs(ping - lastPing));

        return lastPing > 0 && jitter > MAX_PING_JITTER;
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
        observedServerTick = TickTask.getCurrentTick();
        observedTransactionCount = profile.getConnectionData().getLastFlyingReceived();
        resetEvidence();
    }

    private void resetEvidence() {
        clearTimingWindow();
        violations = 0.0D;
    }

    private void clearTimingWindow() {
        balance = 0L;
        recoveryBudget = 0L;
        recoveryUntil = 0L;
        stableSamples = 0;
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
                && profile.isExempt().isRespawned()
                && !profile.isExempt().isDead()
                && CollisionUtils.isChunkLoaded(profile.getMovementData().getLocation());
    }
}
