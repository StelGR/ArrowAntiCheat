package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.autoClickerMathUtil;

import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.AbstractStatisticFunction;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.DoubleBiFunction;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.DoubleObjFunction;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.StatisticFunction;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public class Variance extends AbstractStatisticFunction {
    StatisticFunction m1 = new Mean();
    DoubleObjFunction<double[]> meanEvaluatorFunc;
    DoubleBiFunction<double[], Optional<Double>> innerVarianceFunc;

    public Variance() {
        StatisticFunction var10001 = this.m1;
        Objects.requireNonNull(var10001);
        this.meanEvaluatorFunc = var10001::evaluate;
        this.innerVarianceFunc = (data, meanOpt) -> {
            int n = data.length;
            double mean = meanOpt.orElse(this.meanEvaluatorFunc.apply(data));
            double variance = Arrays.stream(data).map((value) -> this.square(value - mean)).sum();
            variance /= this.biasCorrection ? (double)(n - 1) : (double)n;
            return variance;
        };
    }

    public double evaluate(double[] data) {
        return this.innerVarianceFunc.apply(data, Optional.empty());
    }

    public double evaluate(double[] data, double mean) {
        return this.innerVarianceFunc.apply(data, Optional.of(mean));
    }
}