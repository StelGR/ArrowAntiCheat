package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil;

import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.autoClickerMathUtil.*;

import java.util.Collection;

public class Stats {
    private static final StatisticFunction ENTROPY = new Entropy();
    private static final StatisticFunction KURTOSIS = new Kurtosis();
    private static final StatisticFunction MAX = new Max();
    private static final StatisticFunction MEAN = new Mean();
    private static final StatisticFunction MIDPOINT = new Midpoint();
    private static final StatisticFunction MIN = new Min();
    private static final StatisticFunction MODE = new Mode();
    private static final StatisticFunction RANGE = new Range();
    private static final StatisticFunction SKEWNESS = new Skewness();
    private static final StatisticFunction STANDARD_DEVIATION = new StandardDeviation();
    private static final StatisticFunction SUM = new Sum();
    private static final StatisticFunction VARIANCE = new Variance();

    public static double entropy(double[] data) {
        return ENTROPY.evaluate(data);
    }

    public static double entropy(Collection<? extends Number> data) {
        return ENTROPY.evaluate(data);
    }

    public static double kurtosis(double[] data) {
        return KURTOSIS.evaluate(data);
    }

    public static double kurtosis(Collection<? extends Number> data) {
        return KURTOSIS.evaluate(data);
    }

    public static double max(double[] data) {
        return MAX.evaluate(data);
    }

    public static double max(Collection<? extends Number> data) {
        return MAX.evaluate(data);
    }

    public static double mean(double[] data) {
        return MEAN.evaluate(data);
    }

    public static double mean(Collection<? extends Number> data) {
        return MEAN.evaluate(data);
    }

    public static double midpoint(double[] data) {
        return MIDPOINT.evaluate(data);
    }

    public static double midpoint(Collection<? extends Number> data) {
        return MIDPOINT.evaluate(data);
    }

    public static double min(double[] data) {
        return MIN.evaluate(data);
    }

    public static double min(Collection<? extends Number> data) {
        return MIN.evaluate(data);
    }

    public static double mode(double[] data) {
        return MODE.evaluate(data);
    }

    public static double mode(Collection<? extends Number> data) {
        return MODE.evaluate(data);
    }

    public static double range(double[] data) {
        return RANGE.evaluate(data);
    }

    public static double range(Collection<? extends Number> data) {
        return RANGE.evaluate(data);
    }

    public static double skewness(double[] data) {
        return SKEWNESS.evaluate(data);
    }

    public static double skewness(Collection<? extends Number> data) {
        return SKEWNESS.evaluate(data);
    }

    public static double stdDev(double[] data) {
        return STANDARD_DEVIATION.evaluate(data);
    }

    public static double stdDev(Collection<? extends Number> data) {
        return STANDARD_DEVIATION.evaluate(data);
    }

    public static double sum(double[] data) {
        return SUM.evaluate(data);
    }

    public static double sum(Collection<? extends Number> data) {
        return SUM.evaluate(data);
    }

    public static double variance(double[] data) {
        return VARIANCE.evaluate(data);
    }

    public static double variance(Collection<? extends Number> data) {
        return VARIANCE.evaluate(data);
    }

    private Stats() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
