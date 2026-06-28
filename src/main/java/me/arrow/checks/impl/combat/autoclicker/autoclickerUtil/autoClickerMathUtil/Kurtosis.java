package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.autoClickerMathUtil;

import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.AbstractStatisticFunction;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.StatisticFunction;

import java.util.Arrays;

public class Kurtosis extends AbstractStatisticFunction {
    private final StatisticFunction m1 = new Mean();
    private final StatisticFunction m2 = new Variance();

    public Kurtosis() {
    }

    public double evaluate(double[] data) {
        double n = data.length;
        if (n < 4.0) {
            return Double.NaN;
        } else {
            double mean = this.m1.evaluate(data);
            double stdDev = Math.sqrt(this.m2.evaluate(data, mean));
            double quarticDeltaSum = Arrays.stream(data).map((value) -> this.quartic(value - mean)).sum();
            double kurtosis = n * (n + 1.0) / (n - 1.0) / (n - 2.0) / (n - 3.0);
            kurtosis *= quarticDeltaSum / this.quartic(stdDev);
            kurtosis -= 3.0 * this.square(n - 1.0) / (n * (n - 3.0) - 2.0 * (n - 3.0));
            return kurtosis;
        }
    }
}