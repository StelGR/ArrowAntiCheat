package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.ArrayDeque;
import java.util.Deque;

public class TimerA extends Check {

    private static final double EXPECTED_PACKET_DELAY_MS = 50.0D;

    /*
     * Never allow the old -800ms timer bank again.
     * Negative balance is only a tiny stabilizer, not credit the player can spend later.
     */
    private static final double MIN_BALANCE = -35.0D;

    private static final double PING_GRACE_START_MS = 50.0D;

    private static final double BASE_MAX_BALANCE = 145.0D;
    private static final double BASE_HARD_BALANCE = 380.0D;

    /*
     * True big stalls always look like queue/lag. Smaller queue-looking delays are handled
     * dynamically for every player above ~50ms ping.
     */
    private static final double HARD_LAG_SPIKE_DELAY_MS = 210.0D;

    private static final double BASE_ADAPTIVE_QUEUE_DELAY_MS = 122.0D;
    private static final double MIN_ADAPTIVE_QUEUE_DELAY_MS = 88.0D;

    private static final double MAX_STATIC_LAG_DEBT_MS = 1900.0D;
    private static final long BASE_LAG_DEBT_EXPIRE_NS = 1_250_000_000L;

    private static final double BASE_FAST_PACKET_DELAY_MS = 42.5D;
    private static final double MIN_FAST_PACKET_DELAY_MS = 36.5D;
    private static final double BASE_FAST_BURST_LIMIT_MS = 125.0D;
    private static final double MAX_FAST_BURST_MS = 500.0D;

    private static final int WINDOW_SIZE = 40;
    private static final double BASE_WINDOW_TIMER_LIMIT = 1.085D;
    private static final double BASE_WINDOW_DEVIATION_LIMIT = 22.0D;

    /*
     * Critical false-positive fix:
     * Do not count tiny early packets as timer. A 47ms packet at 300ms+ ping is
     * normal network scheduling/jitter, not proof of 1.06x timer.
     */
    private static final double BASE_EARLY_PACKET_GRACE_MS = 2.25D;
    private static final double MAX_EARLY_PACKET_GRACE_MS = 9.0D;

    /* Standing still should never create timer credit/debt. */
    private static final double IDLE_DELAY_MS = 65.0D;

    private double balance;
    private double threshold;
    private double fastBurst;

    private double lastWindowTimer = 1.0D;
    private double lastWindowDeviation;
    private double lastEarlyPacketGrace;

    private double lagDebt;
    private long lagDebtNano = -1L;

    private int longDelayStreak;
    private int queueForgivenessTicks;
    private int fastPacketStreak;
    private long lastNano = -1L;

    private final Deque<Double> samples = new ArrayDeque<>(WINDOW_SIZE);

    public TimerA(Profile profile) {
        super(profile, CheckType.TIMER, "A", "Checks for game speedup modifications");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)) {
            /*
             * Teleports/setbacks cause correction packets. Do not subtract 800ms or any
             * negative credit here; just restart accounting safely.
             */
            reset(System.nanoTime());
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isFlyingPacket(event)) {
            return;
        }

        long now = System.nanoTime();

        if (lastNano == -1L) {
            lastNano = now;
            return;
        }

        double elapsed = (now - lastNano) / 1_000_000.0D;
        lastNano = now;

        if (shouldHardReset(elapsed)) {
            reset(now);
            return;
        }

        if (shouldIgnoreIdle(elapsed)) {
            handleIdle(elapsed);
            return;
        }

        expireOldLagDebt(now);

        if (shouldTreatAsQueueDelay(elapsed)) {
            handleQueueDelay(now, elapsed);
            return;
        }

        if (elapsed <= getAdaptiveQueueDelay() * 0.72D) {
            longDelayStreak = 0;
        }

        boolean windowInvalid = handleWindow(elapsed, now);

        double rawGain = EXPECTED_PACKET_DELAY_MS - elapsed;
        double earlyGrace = getEarlyPacketGrace();
        lastEarlyPacketGrace = earlyGrace;

        if (rawGain > earlyGrace) {
            double gain = rawGain - earlyGrace;

            gain = consumeLagDebt(now, gain);
            gain = consumeQueueForgiveness(gain);

            handleFastPacket(elapsed, gain);

            if (gain > 0.0D) {
                balance = Math.min(getHardBalanceLimit() + 180.0D,
                        Math.max(MIN_BALANCE, balance + (gain * getPositiveGainMultiplier())));
            }
        } else if (rawGain > 0.0D) {
            /*
             * Legal micro-early packet. This is what your screenshot showed:
             * elapsed was ~47.6ms at ~379ms ping, while the 40-packet window was 1.001x.
             * Counting that tiny gain every tick slowly walks balance into a false flag.
             */
            handleLegalMicroEarlyPacket(elapsed);
        } else {
            /*
             * Slow packets may reduce positive balance, but they cannot bank usable credit.
             */
            fastPacketStreak = 0;
            balance = Math.max(MIN_BALANCE, balance + (rawGain * getNegativeGainMultiplier()));
            fastBurst = Math.max(0.0D, fastBurst - Math.min(28.0D, -rawGain));
        }

        double maxBalance = getMaxBalanceLimit();
        double hardBalance = getHardBalanceLimit();
        double fastBurstLimit = getFastBurstLimit();

        boolean balanceProof = hasSoftBalanceProof(fastBurstLimit);
        boolean hardProof = hasHardBalanceProof(fastBurstLimit);

        boolean balanceInvalid = balance > maxBalance + getBalanceViolationMargin() && balanceProof;
        boolean hardInvalid = balance > hardBalance && hardProof;
        boolean burstInvalid = lagDebt <= 0.0D
                && queueForgivenessTicks <= 0
                && fastBurst > fastBurstLimit;

        boolean invalid = balanceInvalid || hardInvalid || burstInvalid || windowInvalid;

        if (invalid) {
            double add = getThresholdAdd();

            if (burstInvalid) {
                add += getBurstThresholdAdd();
            }

            if (windowInvalid) {
                add += getWindowThresholdAdd();
            }

            if (hardInvalid) {
                add += 2.0D;
            }

            threshold += add;

            if (threshold > getFailThreshold()) {
                fail("Speeding up Time",
                        "balance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(balance)
                                + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(elapsed)
                                + "\nfastBurst " + MsgType.MAIN_THEME_COLOR.getMessage() + format(fastBurst)
                                + "\nfastBurstLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + format(fastBurstLimit)
                                + "\nfastPacketStreak " + MsgType.MAIN_THEME_COLOR.getMessage() + fastPacketStreak
                                + "\nlagDebt " + MsgType.MAIN_THEME_COLOR.getMessage() + format(lagDebt)
                                + "\nqueueForgivenessTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + queueForgivenessTicks
                                + "\nearlyPacketGrace " + MsgType.MAIN_THEME_COLOR.getMessage() + format(lastEarlyPacketGrace)
                                + "\nbalanceProof " + MsgType.MAIN_THEME_COLOR.getMessage() + balanceProof
                                + "\nwindowTimer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(lastWindowTimer)
                                + "\nwindowDeviation " + MsgType.MAIN_THEME_COLOR.getMessage() + format(lastWindowDeviation)
                                + "\nping " + MsgType.MAIN_THEME_COLOR.getMessage() + format(getPing())
                                + "\nthreshold " + MsgType.MAIN_THEME_COLOR.getMessage() + format(threshold));

                threshold = Math.min(getFailThreshold() + 3.0D, threshold);
                balance = Math.min(balance, maxBalance);
                fastBurst = Math.min(fastBurst, fastBurstLimit);
            }
        } else {
            threshold = Math.max(0.0D, threshold - getThresholdDecay());

            if (balance > maxBalance && !balanceProof) {
                balance = Math.max(maxBalance, balance - getLegalPacketBalanceDecay());
            }
        }

        verbose(this.getClass().getSimpleName(), balance, maxBalance,
                "balance " + format(balance)
                        + "\nelapsed " + format(elapsed)
                        + "\nfastBurst " + format(fastBurst)
                        + "\nfastBurstLimit " + format(fastBurstLimit)
                        + "\nfastPacketStreak " + fastPacketStreak
                        + "\nlagDebt " + format(lagDebt)
                        + "\nqueueForgivenessTicks " + queueForgivenessTicks
                        + "\nearlyPacketGrace " + format(lastEarlyPacketGrace)
                        + "\nbalanceProof " + balanceProof
                        + "\nwindowTimer " + format(lastWindowTimer)
                        + "\nwindowDeviation " + format(lastWindowDeviation)
                        + "\nlongDelayStreak " + longDelayStreak
                        + "\nadaptiveQueueDelay " + format(getAdaptiveQueueDelay())
                        + "\nfastPacketDelay " + format(getFastPacketDelay())
                        + "\nping " + format(getPing())
                        + "\nthreshold " + format(threshold));
    }

    private double consumeLagDebt(long now, double gain) {
        if (gain <= 0.0D || lagDebt <= 0.0D) {
            return gain;
        }

        double consumed = Math.min(gain, lagDebt);
        gain -= consumed;
        lagDebt -= consumed;
        lagDebtNano = lagDebt > 0.0D ? now : -1L;

        return gain;
    }

    private double consumeQueueForgiveness(double gain) {
        if (gain <= 0.0D || queueForgivenessTicks <= 0) {
            return gain;
        }

        double forgiven = Math.min(gain, getQueueForgivenessPerPacket());
        gain -= forgiven;
        queueForgivenessTicks--;

        return gain;
    }

    private void handleFastPacket(double elapsed, double remainingGain) {
        double fastDelay = getFastPacketDelay();

        if (remainingGain <= 0.0D || elapsed >= fastDelay || lagDebt > 0.0D || queueForgivenessTicks > 0) {
            fastPacketStreak = elapsed < fastDelay ? fastPacketStreak : 0;
            fastBurst = Math.max(0.0D, fastBurst - getFastBurstDecay());
            return;
        }

        fastPacketStreak++;

        /*
         * For >50ms ping, do not punish one or two tiny intervals. Those are usually
         * packet ordering/flush artifacts. Require a clean streak before adding burst.
         */
        if (fastPacketStreak >= getRequiredFastPacketStreak()) {
            fastBurst = Math.min(MAX_FAST_BURST_MS,
                    fastBurst + ((fastDelay - elapsed) * getFastBurstGainMultiplier()));
        } else {
            fastBurst = Math.max(0.0D, fastBurst - getFastBurstDecay() * 0.35D);
        }
    }

    private void handleLegalMicroEarlyPacket(double elapsed) {
        if (elapsed >= getFastPacketDelay()) {
            fastPacketStreak = 0;
        }

        balance = Math.max(MIN_BALANCE, balance - getLegalPacketBalanceDecay());
        fastBurst = Math.max(0.0D, fastBurst - getFastBurstDecay() * 0.75D);
    }

    private boolean handleWindow(double elapsed, long now) {
        samples.addLast(elapsed);

        if (samples.size() < WINDOW_SIZE) {
            return false;
        }

        double average = getWindowAverage();
        double timer = EXPECTED_PACKET_DELAY_MS / Math.max(1.0D, average);
        double deviation = getWindowDeviation(average);

        lastWindowTimer = timer;
        lastWindowDeviation = deviation;

        samples.clear();

        /*
         * Window layer catches sustained speed-up, but only when the sample is clean.
         * Jittery lag users generally have high deviation or recent debt, so this layer waits.
         */
        return lagDebt <= 0.0D
                && queueForgivenessTicks <= 0
                && !hasRecentLagDebt(now)
                && timer > getWindowTimerLimit()
                && deviation < getWindowDeviationLimit();
    }

    private void handleQueueDelay(long now, double elapsed) {
        samples.clear();
        longDelayStreak++;
        fastPacketStreak = 0;

        double spikeDebt = Math.max(0.0D, elapsed - EXPECTED_PACKET_DELAY_MS);
        int allowedLongDelayStreak = getAllowedLongDelayStreak();

        if (longDelayStreak <= allowedLongDelayStreak) {
            lagDebt = Math.min(getMaxLagDebt(), lagDebt + (spikeDebt * getQueueDebtMultiplier(elapsed)));
            lagDebtNano = now;
        } else {
            /*
             * Repeated slow intervals should not mint unlimited catch-up credit.
             */
            lagDebt = Math.min(lagDebt, getMaxLagDebt() * 0.38D);
        }

        queueForgivenessTicks = Math.max(queueForgivenessTicks, getQueueForgivenessTicks());

        /*
         * Queue delays are allowed to cool the check, but not create negative timer credit.
         */
        balance = Math.max(0.0D, balance - Math.min(30.0D, spikeDebt * 0.18D));
        fastBurst = Math.max(0.0D, fastBurst - getFastBurstDecay() * 4.0D);
        threshold = Math.max(0.0D, threshold - getThresholdDecay() * 1.75D);

        verbose(this.getClass().getSimpleName(), balance, getMaxBalanceLimit(),
                "balance " + format(balance)
                        + "\nelapsed " + format(elapsed)
                        + "\nfastBurst " + format(fastBurst)
                        + "\nlagDebt " + format(lagDebt)
                        + "\nqueueForgivenessTicks " + queueForgivenessTicks
                        + "\nwindowDeviation " + format(lastWindowDeviation)
                        + "\nlongDelayStreak " + longDelayStreak
                        + "\nallowedLongDelayStreak " + allowedLongDelayStreak
                        + "\nadaptiveQueueDelay " + format(getAdaptiveQueueDelay())
                        + "\nping " + format(getPing())
                        + "\nthreshold " + format(threshold)
                        + "\nmode queue-delay");
    }

    private boolean shouldTreatAsQueueDelay(double elapsed) {
        if (elapsed >= HARD_LAG_SPIKE_DELAY_MS) {
            return true;
        }

        return getPing() >= PING_GRACE_START_MS && elapsed >= getAdaptiveQueueDelay();
    }

    private double getAdaptiveQueueDelay() {
        double ping = getPing();

        if (ping < PING_GRACE_START_MS) {
            return HARD_LAG_SPIKE_DELAY_MS;
        }

        double over = Math.max(0.0D, ping - PING_GRACE_START_MS);
        return Math.max(MIN_ADAPTIVE_QUEUE_DELAY_MS, BASE_ADAPTIVE_QUEUE_DELAY_MS - (over * 0.12D));
    }

    private double getQueueDebtMultiplier(double elapsed) {
        double ping = getPing();
        double over = Math.max(0.0D, ping - PING_GRACE_START_MS);

        double multiplier = 0.28D + Math.min(0.42D, over * 0.0014D);

        if (elapsed >= HARD_LAG_SPIKE_DELAY_MS) {
            multiplier += 0.18D;
        }

        return Math.min(0.86D, multiplier);
    }

    private int getAllowedLongDelayStreak() {
        double ping = getPing();

        if (ping < PING_GRACE_START_MS) {
            return 2;
        }

        return Math.min(7, 2 + (int) ((ping - PING_GRACE_START_MS) / 95.0D));
    }

    private int getQueueForgivenessTicks() {
        double ping = getPing();

        if (ping < PING_GRACE_START_MS) {
            return 1;
        }

        return Math.min(8, 2 + (int) ((ping - PING_GRACE_START_MS) / 85.0D));
    }

    private double getQueueForgivenessPerPacket() {
        double ping = getPing();

        if (ping < PING_GRACE_START_MS) {
            return 6.0D;
        }

        return 10.0D + Math.min(22.0D, (ping - PING_GRACE_START_MS) * 0.075D);
    }

    private double getMaxLagDebt() {
        double ping = getPing();

        if (ping < PING_GRACE_START_MS) {
            return 350.0D;
        }

        return Math.min(MAX_STATIC_LAG_DEBT_MS, 450.0D + (ping * 2.25D));
    }

    private boolean hasRecentLagDebt(long now) {
        return lagDebtNano != -1L && now - lagDebtNano <= getLagDebtExpireNs();
    }

    private long getLagDebtExpireNs() {
        double ping = getPing();
        return BASE_LAG_DEBT_EXPIRE_NS + (long) Math.min(2_500_000_000L, Math.max(0.0D, ping - PING_GRACE_START_MS) * 5_000_000L);
    }

    private double getMaxBalanceLimit() {
        double ping = getPing();
        return BASE_MAX_BALANCE + Math.min(165.0D, Math.max(0.0D, ping - PING_GRACE_START_MS) * 0.34D);
    }

    private double getHardBalanceLimit() {
        double ping = getPing();
        return BASE_HARD_BALANCE + Math.min(260.0D, Math.max(0.0D, ping - PING_GRACE_START_MS) * 0.48D);
    }

    private double getPositiveGainMultiplier() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 1.0D;
        }

        return Math.max(0.58D, 1.0D - ((ping - PING_GRACE_START_MS) * 0.0012D));
    }

    private double getNegativeGainMultiplier() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 0.20D;
        }

        return Math.max(0.08D, 0.20D - ((ping - PING_GRACE_START_MS) * 0.00022D));
    }

    private double getFastPacketDelay() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return BASE_FAST_PACKET_DELAY_MS;
        }

        return Math.max(MIN_FAST_PACKET_DELAY_MS, BASE_FAST_PACKET_DELAY_MS - ((ping - PING_GRACE_START_MS) * 0.018D));
    }

    private int getRequiredFastPacketStreak() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 1;
        }

        return Math.min(5, 2 + (int) ((ping - PING_GRACE_START_MS) / 125.0D));
    }

    private double getFastBurstLimit() {
        double ping = getPing();
        return BASE_FAST_BURST_LIMIT_MS + Math.min(155.0D, Math.max(0.0D, ping - PING_GRACE_START_MS) * 0.32D);
    }

    private double getFastBurstGainMultiplier() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 1.0D;
        }

        return Math.max(0.55D, 1.0D - ((ping - PING_GRACE_START_MS) * 0.001D));
    }

    private double getFastBurstDecay() {
        double ping = getPing();
        return 2.5D + Math.min(6.5D, Math.max(0.0D, ping - PING_GRACE_START_MS) * 0.018D);
    }

    private double getEarlyPacketGrace() {
        double ping = getPing();
        double over = Math.max(0.0D, ping - PING_GRACE_START_MS);

        double pingGrace = Math.min(4.25D, over * 0.013D);
        double deviationGrace = Math.min(2.50D, lastWindowDeviation * 0.18D);
        double stableWindowGrace = lastWindowTimer <= 1.035D ? 1.00D : 0.0D;

        return Math.min(MAX_EARLY_PACKET_GRACE_MS,
                BASE_EARLY_PACKET_GRACE_MS + pingGrace + deviationGrace + stableWindowGrace);
    }

    private double getLegalPacketBalanceDecay() {
        double ping = getPing();
        return 2.25D + Math.min(6.0D, Math.max(0.0D, ping - PING_GRACE_START_MS) * 0.014D);
    }

    private double getBalanceViolationMargin() {
        double ping = getPing();
        return 4.0D + Math.min(36.0D, Math.max(0.0D, ping - PING_GRACE_START_MS) * 0.075D);
    }

    private boolean hasSoftBalanceProof(double fastBurstLimit) {
        if (getPing() < PING_GRACE_START_MS) {
            return true;
        }

        return lastWindowTimer > getSoftBalanceWindowFloor()
                || fastPacketStreak >= getRequiredFastPacketStreak()
                || fastBurst > fastBurstLimit * 0.65D;
    }

    private boolean hasHardBalanceProof(double fastBurstLimit) {
        if (getPing() < PING_GRACE_START_MS) {
            return true;
        }

        return lastWindowTimer > 1.025D
                || fastPacketStreak >= Math.max(2, getRequiredFastPacketStreak() - 1)
                || fastBurst > fastBurstLimit * 0.45D;
    }

    private double getSoftBalanceWindowFloor() {
        double ping = getPing();
        return 1.025D + Math.min(0.070D, Math.max(0.0D, ping - PING_GRACE_START_MS) * 0.00020D);
    }

    private double getWindowTimerLimit() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return BASE_WINDOW_TIMER_LIMIT;
        }

        return BASE_WINDOW_TIMER_LIMIT + Math.min(0.10D, (ping - PING_GRACE_START_MS) * 0.00023D);
    }

    private double getWindowDeviationLimit() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return BASE_WINDOW_DEVIATION_LIMIT;
        }

        return BASE_WINDOW_DEVIATION_LIMIT + Math.min(65.0D, (ping - PING_GRACE_START_MS) * 0.13D);
    }

    private double getFailThreshold() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 5.0D;
        }

        return 5.0D + Math.min(5.5D, (ping - PING_GRACE_START_MS) * 0.012D);
    }

    private double getThresholdAdd() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 1.0D;
        }

        return Math.max(0.48D, 1.0D - ((ping - PING_GRACE_START_MS) * 0.0015D));
    }

    private double getBurstThresholdAdd() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 0.70D;
        }

        return Math.max(0.20D, 0.70D - ((ping - PING_GRACE_START_MS) * 0.0013D));
    }

    private double getWindowThresholdAdd() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 0.70D;
        }

        return Math.max(0.22D, 0.70D - ((ping - PING_GRACE_START_MS) * 0.0011D));
    }

    private double getThresholdDecay() {
        double ping = getPing();

        if (ping <= PING_GRACE_START_MS) {
            return 0.35D;
        }

        return 0.35D + Math.min(0.55D, (ping - PING_GRACE_START_MS) * 0.0015D);
    }

    private double getPing() {
        try {
            if (profile.getConnectionData() != null) {
                return Math.max(0.0D, profile.getConnectionData().getTransPing());
            }
        } catch (Throwable ignored) {
        }

        return 0.0D;
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean shouldHardReset(double elapsed) {
        if (elapsed <= 0.0D || elapsed > 10_000.0D) {
            return true;
        }

        if (profile.getTick() < 120) {
            return true;
        }

        if (profile.shouldCancel()) {
            return true;
        }

        return profile.isExempt().isTeleports();
    }

    private boolean shouldIgnoreIdle(double elapsed) {
        try {
            if (profile.getMovementData() == null) {
                return false;
            }

            return !profile.getMovementData().isMoving() && elapsed > IDLE_DELAY_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void handleIdle(double elapsed) {
        /*
         * Do not inspect timer while fully idle. More importantly, do not create lag debt
         * or negative balance here. This closes the stand-still slowtimer -> fast burst bank.
         */
        balance = Math.max(0.0D, balance - 32.0D);
        fastBurst = Math.max(0.0D, fastBurst - 45.0D);
        threshold = Math.max(0.0D, threshold - getThresholdDecay() * 2.0D);

        lagDebt = 0.0D;
        lagDebtNano = -1L;
        longDelayStreak = 0;
        queueForgivenessTicks = 0;
        fastPacketStreak = 0;
        samples.clear();

        verbose(this.getClass().getSimpleName(), balance, getMaxBalanceLimit(),
                "balance " + format(balance)
                        + "\nelapsed " + format(elapsed)
                        + "\nfastBurst " + format(fastBurst)
                        + "\nlagDebt " + format(lagDebt)
                        + "\nqueueForgivenessTicks " + queueForgivenessTicks
                        + "\nping " + format(getPing())
                        + "\nthreshold " + format(threshold)
                        + "\nmode idle");
    }

    private void expireOldLagDebt(long now) {
        if (lagDebt <= 0.0D || lagDebtNano == -1L) {
            return;
        }

        if (now - lagDebtNano > getLagDebtExpireNs()) {
            lagDebt = 0.0D;
            lagDebtNano = -1L;
            queueForgivenessTicks = 0;
        }
    }

    private void reset(long now) {
        balance = 0.0D;
        threshold = 0.0D;
        fastBurst = 0.0D;
        lastWindowTimer = 1.0D;
        lastWindowDeviation = 0.0D;
        lagDebt = 0.0D;
        lagDebtNano = -1L;
        longDelayStreak = 0;
        queueForgivenessTicks = 0;
        fastPacketStreak = 0;
        samples.clear();
        lastNano = now;
    }

    private double getWindowAverage() {
        if (samples.isEmpty()) {
            return EXPECTED_PACKET_DELAY_MS;
        }

        double total = 0.0D;

        for (double sample : samples) {
            total += sample;
        }

        return total / samples.size();
    }

    private double getWindowDeviation(double average) {
        if (samples.isEmpty()) {
            return 0.0D;
        }

        double total = 0.0D;

        for (double sample : samples) {
            double diff = sample - average;
            total += diff * diff;
        }

        return Math.sqrt(total / samples.size());
    }

    private String format(double value) {
        return String.format("%.3f", value);
    }
}
