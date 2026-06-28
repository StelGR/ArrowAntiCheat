package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.autoClickerMathUtil;

import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.AbstractStatisticFunction;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.StatisticFunction;

import java.util.Arrays;

public class Skewness extends AbstractStatisticFunction {
    private final StatisticFunction m1 = new Mean();
    private final StatisticFunction m2 = new Variance();

    public Skewness() {
    }

    public double evaluate(double[] data) {
        double n = data.length;
        if (n < 3.0) {
            return Double.NaN;
        } else {
            double mean = this.m1.evaluate(data);
            double variance = this.m2.evaluate(data, mean);
            double stdDev = Math.sqrt(variance);
            double cubicDeltaSum = Arrays.stream(data).map((value) -> this.cube(value - mean)).sum();
            double skewness = n / (n - 1.0) / (n - 2.0);
            skewness *= cubicDeltaSum / (variance * stdDev);
            return skewness;
        }
    }
}
