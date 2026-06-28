package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.*;

import static me.arrow.utils.customutils.Math.MathUtil.getAverage;
import static me.arrow.utils.customutils.Math.MathUtil.getStandardDeviation;

@Experimental
public class AutoClickerD extends Check {

    final Deque<Double> samples = new ArrayDeque<>();

    double threshold;
    int SAMPLE_SIZE = 75;
    double MIN_CPS = 9.0D;
    double MIN_ENTROPY = 0.635D;
    double MIN_DEVIATION = 0.5D;

    public AutoClickerD(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "D", "Checks for low click randomization");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            return;
        }

        if (shouldIgnore()) {
            clearSamples();
            return;
        }

        samples.add(profile.getCombatData().getCurrentCps());

        if (samples.size() >= SAMPLE_SIZE) {
            double entropy = getEntropy(samples);

            if (entropy >= MIN_ENTROPY) {
                clearSamples();
                return;
            }

            double deviation = getStandardDeviation(samples);

            if (deviation >= MIN_DEVIATION) {
                clearSamples();
                return;
            }

            double average = getAverage(samples);

            if (average <= 0.0D) {
                clearSamples();
                return;
            }

            double estimatedCps = 20.0D / average;

            if (estimatedCps > MIN_CPS
                    && estimatedCps != 20.0D
                    && profile.getCombatData().getCurrentCps() > MIN_CPS
                    && entropy < MIN_ENTROPY
                    && deviation < MIN_DEVIATION) {

                if (++threshold > 1.0D) {
                    fail("Low randomization",
                            "std " + MsgType.MAIN_THEME_COLOR.getMessage() + deviation
                                    + "\nentropy " + MsgType.MAIN_THEME_COLOR.getMessage() + entropy
                                    + "\ncps " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getCombatData().getCurrentCps()
                                    + "\nestimated " + MsgType.MAIN_THEME_COLOR.getMessage() + estimatedCps
                                    + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage() + samples.size());
                }
            } else {
                threshold -= Math.min(threshold, 1.0D);
            }

            clearSamples();
        }
    }

    private boolean shouldIgnore() {
        return profile.getPredictionData().isDigging()
                || profile.shouldCancel();
    }

    private void clearSamples() {
        samples.clear();
    }
    public static final double LN_2 = Math.log(2.0);

    public static double getEntropy(Collection<? extends Number> values) {
        double n = values.size();
        if (n < 2.0) {
            return Double.NaN;
        } else {
            Map<Integer, Integer> map = new HashMap<>();
            values.stream().mapToInt(Number::intValue).forEach(value -> map.computeIfAbsent(value, k -> 0));
            double entropy = map.values().stream().mapToDouble(freq -> (double) freq / n).map(probability -> probability * log2(probability)).sum();
            return -entropy;
        }
    }

    private static double log2(double n) {
        return Math.log(n) / LN_2;
    }
}
