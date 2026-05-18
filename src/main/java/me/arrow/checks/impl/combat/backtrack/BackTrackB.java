package me.arrow.checks.impl.combat.backtrack;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

// My idea for a sample based backtrack check, it flags 100 ms very well, it's capped at 100 ms cus it falses, although you can false it regardless, just much harder above 100 ms spikes, this shouldn't ban though
// it should just kick

@Experimental
public class BackTrackB extends Check {

    private static final int BASELINE_SIZE = 180;
    private static final int MIN_BASELINE = 60;

    private static final long COMBAT_MS = 1250L;
    private static final long BASELINE_GRACE_MS = 2250L;

    private static final double MIN_SPIKE_MS = 100.0D;
    private static final double LARGE_SPIKE_MS = 200.0D;
    private static final double EXTREME_SPIKE_MS = 300.0D;

    private static final double MAX_BUFFER = 10.0D;
    private static final double FAIL_BUFFER = 7.0D;

    private final Deque<Integer> baselineTransactions = new ArrayDeque<>(BASELINE_SIZE);

    private long lastAttackTime = -1L;
    private int lastTransactionSequence = -1;

    private int consecutiveSpikes;
    private int cleanTransactions;
    private int trustCooldownTicks;

    private double buffer;
    private double highestSpike;

    public BackTrackB(Profile profile) {
        super(profile, CheckType.BACKTRACK, "B", "Combat-only transaction delay analysis");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttackTime = System.currentTimeMillis();
            }

            return;
        }

        if (!isMovement(event.getPacketType())) {
            return;
        }

        if (profile == null
                || profile.getPlayer() == null
                || profile.getConnectionData() == null
                || profile.getCombatData() == null
                || profile.getMovementData() == null) {
            reset();
            return;
        }

        if (isExempt()) {
            decayHard();
            return;
        }

        ConnectionData connectionData = profile.getConnectionData();

        int transactionSequence = connectionData.getLastFlyingReceived();
        int transactionPing = connectionData.getTransPing();

        if (transactionPing <= 0) {
            decaySoft();
            return;
        }

        if (transactionSequence == lastTransactionSequence) {
            return;
        }

        lastTransactionSequence = transactionSequence;

        long now = System.currentTimeMillis();
        boolean inCombat = isInCombat(now);

        if (!inCombat) {
            if (now - lastAttackTime > BASELINE_GRACE_MS) {
                addSample(baselineTransactions, transactionPing);
            }

            consecutiveSpikes = Math.max(0, consecutiveSpikes - 1);
            cleanTransactions++;

            buffer = Math.max(0.0D, buffer - 0.04D);

            if (profile.getTick() % 60 == 0 && cleanTransactions > 40 && buffer < 1.0D) {
                try {
                    profile.getTrustFactor().increaseTrustBy(0.0025D);
                } catch (Throwable ignored) {
                }
            }

            return;
        }

        if (baselineTransactions.size() < MIN_BASELINE) {
            return;
        }

        Stats stats = buildStats();

        if (!stats.valid()) {
            return;
        }

        double spike = transactionPing - stats.median;
        double noiseRequirement = Math.max(MIN_SPIKE_MS, Math.min(220.0D, 70.0D + (stats.mad * 4.0D)));
        boolean unusualForBaseline = transactionPing > stats.p95 + 25.0D || spike >= LARGE_SPIKE_MS;
        boolean suspicious = spike > MIN_SPIKE_MS && spike > noiseRequirement && unusualForBaseline;

        if (spike > highestSpike) {
            highestSpike = spike;
        }

        if (suspicious) {
            consecutiveSpikes++;
            cleanTransactions = 0;

            double added = getBufferIncrease(spike);

            if (consecutiveSpikes >= 3) {
                added *= 1.20D;
            }

            if (consecutiveSpikes >= 6) {
                added *= 1.35D;
            }

            buffer = Math.min(MAX_BUFFER, buffer + added);
            decreaseTrust(spike);
        } else {
            consecutiveSpikes = Math.max(0, consecutiveSpikes - 1);
            cleanTransactions++;
            buffer = Math.max(0.0D, buffer - 0.08D);
        }

        verbose(this.getClass().getSimpleName(), buffer, FAIL_BUFFER,
                MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (BackTrack B)"
                        + "\n * transPing " + MsgType.MAIN_THEME_COLOR.getMessage() + transactionPing
                        + "\n * baselineMedian " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stats.median)
                        + "\n * baselineP95 " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stats.p95)
                        + "\n * baselineMAD " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stats.mad)
                        + "\n * spike " + MsgType.MAIN_THEME_COLOR.getMessage() + format(spike)
                        + "\n * required " + MsgType.MAIN_THEME_COLOR.getMessage() + format(noiseRequirement)
                        + "\n * unusualForBaseline " + MsgType.MAIN_THEME_COLOR.getMessage() + unusualForBaseline
                        + "\n * suspicious " + MsgType.MAIN_THEME_COLOR.getMessage() + suspicious
                        + "\n * consecutiveSpikes " + MsgType.MAIN_THEME_COLOR.getMessage() + consecutiveSpikes
                        + "\n * buffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(buffer)
                        + "\n * baselineSamples " + MsgType.MAIN_THEME_COLOR.getMessage() + baselineTransactions.size()
                        + "\n * highestSpike " + MsgType.MAIN_THEME_COLOR.getMessage() + format(highestSpike));

        boolean extremeRepeated = spike >= EXTREME_SPIKE_MS && consecutiveSpikes >= 2;
        boolean persistent = consecutiveSpikes >= 4 && buffer > FAIL_BUFFER;

        if (extremeRepeated || persistent) {
            fail("Combat-only transaction delay",
                    "transPing " + MsgType.MAIN_THEME_COLOR.getMessage() + transactionPing
                            + "\nbaselineMedian " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stats.median)
                            + "\nbaselineP95 " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stats.p95)
                            + "\nbaselineMAD " + MsgType.MAIN_THEME_COLOR.getMessage() + format(stats.mad)
                            + "\nspike " + MsgType.MAIN_THEME_COLOR.getMessage() + format(spike)
                            + "\nrequired " + MsgType.MAIN_THEME_COLOR.getMessage() + format(noiseRequirement)
                            + "\nconsecutiveSpikes " + MsgType.MAIN_THEME_COLOR.getMessage() + consecutiveSpikes
                            + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(buffer)
                            + "\nbaselineSamples " + MsgType.MAIN_THEME_COLOR.getMessage() + baselineTransactions.size()
                            + "\nhighestSpike " + MsgType.MAIN_THEME_COLOR.getMessage() + format(highestSpike));

            buffer = Math.min(buffer, FAIL_BUFFER * 0.45D);
            consecutiveSpikes = Math.max(0, consecutiveSpikes / 2);
        }
    }

    private boolean isInCombat(long now) {
        return profile.getCombatData().getAttackedTicks() <= 10;
    }

    private boolean isExempt() {
        if (profile.getTick() < 100) {
            return true;
        }

        if (profile.shouldCancel()) {
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            return true;
        }

        if (profile.isExempt().vehicle()) {
            return true;
        }

        if (profile.getPlayer().isInsideVehicle()) {
            return true;
        }

        MovementData movementData = profile.getMovementData();

        if (movementData == null) {
            return true;
        }

        return movementData.isRiptiding()
                || movementData.getSinceRiptidingTicks() < 20
                || movementData.getSinceGlidingTicks() < 20
                || movementData.isNearBoat()
                || movementData.isOnBoat();
    }

    private double getBufferIncrease(double spike) {
        if (spike >= 500.0D) {
            return 2.25D;
        }

        if (spike >= EXTREME_SPIKE_MS) {
            return 1.35D + Math.min(0.65D, (spike - EXTREME_SPIKE_MS) / 300.0D);
        }

        if (spike >= LARGE_SPIKE_MS) {
            return 0.65D + Math.min(0.55D, (spike - LARGE_SPIKE_MS) / 150.0D);
        }

        return 0.25D + Math.min(0.35D, (spike - MIN_SPIKE_MS) / 120.0D);
    }

    private void decreaseTrust(double spike) {
        if (profile.getTrustFactor() == null) {
            return;
        }

        if (trustCooldownTicks > 0 && spike < EXTREME_SPIKE_MS) {
            trustCooldownTicks--;
            return;
        }

        double decrease;

        if (spike >= 500.0D) {
            decrease = 2.0D;
            trustCooldownTicks = 1;
        } else if (spike >= EXTREME_SPIKE_MS) {
            decrease = 1.0D + Math.min(1.0D, consecutiveSpikes * 0.08D);
            trustCooldownTicks = 1;
        } else if (spike >= LARGE_SPIKE_MS) {
            decrease = 0.25D + Math.min(0.35D, consecutiveSpikes * 0.025D);
            trustCooldownTicks = 2;
        } else {
            decrease = 0.05D + Math.min(0.12D, consecutiveSpikes * 0.01D);
            trustCooldownTicks = 4;
        }

        try {
            profile.getTrustFactor().decreaseTrustBy(decrease);
        } catch (Throwable ignored) {
            try {
                profile.getTrustFactor().decreaseTrust();
            } catch (Throwable ignored2) {
            }
        }
    }

    private Stats buildStats() {
        List<Double> values = new ArrayList<>();

        for (Integer value : baselineTransactions) {
            if (value != null && value > 0) {
                values.add((double) value);
            }
        }

        if (values.size() < MIN_BASELINE) {
            return Stats.invalid();
        }

        Collections.sort(values);

        double median = percentile(values, 0.50D);
        double p95 = percentile(values, 0.95D);

        List<Double> deviations = new ArrayList<>();

        for (double value : values) {
            deviations.add(Math.abs(value - median));
        }

        Collections.sort(deviations);

        double mad = percentile(deviations, 0.50D);

        return new Stats(median, p95, mad);
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0D;
        }

        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }

        double index = percentile * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {
            return sortedValues.get(lower);
        }

        double weight = index - lower;
        return sortedValues.get(lower) * (1.0D - weight) + sortedValues.get(upper) * weight;
    }

    private void addSample(Deque<Integer> deque, int value) {
        if (value <= 0) {
            return;
        }

        if (deque.size() >= BASELINE_SIZE) {
            deque.pollFirst();
        }

        deque.addLast(value);
    }

    private boolean isMovement(Object packetType) {
        return packetType.equals(PacketType.Play.Client.PLAYER_FLYING)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private void decaySoft() {
        consecutiveSpikes = Math.max(0, consecutiveSpikes - 1);
        buffer = Math.max(0.0D, buffer - 0.04D);
    }

    private void decayHard() {
        consecutiveSpikes = Math.max(0, consecutiveSpikes - 2);
        buffer = Math.max(0.0D, buffer - 0.12D);
    }

    private void reset() {
        baselineTransactions.clear();
        lastAttackTime = -1L;
        lastTransactionSequence = -1;
        consecutiveSpikes = 0;
        cleanTransactions = 0;
        trustCooldownTicks = 0;
        buffer = 0.0D;
        highestSpike = 0.0D;
    }

    private String format(double input) {
        return new DecimalFormat("###.###").format(input);
    }

    private static final class Stats {

        private final double median;
        private final double p95;
        private final double mad;

        private Stats(double median, double p95, double mad) {
            this.median = median;
            this.p95 = p95;
            this.mad = mad;
        }

        private static Stats invalid() {
            return new Stats(-1.0D, -1.0D, -1.0D);
        }

        private boolean valid() {
            return median > 0.0D && p95 > 0.0D && mad >= 0.0D;
        }
    }
}