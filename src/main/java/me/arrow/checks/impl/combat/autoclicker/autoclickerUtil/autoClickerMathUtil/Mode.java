package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.autoClickerMathUtil;

import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.AbstractStatisticFunction;

import java.util.Arrays;
import java.util.stream.IntStream;

public class Mode extends AbstractStatisticFunction {
    public Mode() {
    }

    public double evaluate(double[] data) {
        int n = data.length;
        double[] sortedData = (data).clone();
        Arrays.sort(sortedData);
        double[] counts = IntStream.range(0, n).mapToDouble((i) -> (double)IntStream.range(0, n).filter((j) -> sortedData[j] == sortedData[i]).count()).toArray();
        return Arrays.stream(counts).max().orElse(Double.NaN);
    }
}
