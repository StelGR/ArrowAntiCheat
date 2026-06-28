package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.autoClickerMathUtil;

import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.AbstractStatisticFunction;
import org.apache.commons.math3.stat.descriptive.summary.Sum;

public class Mean extends AbstractStatisticFunction {
    public Mean() {
    }

    public double evaluate(double[] data) {
        int n = data.length;
        double mean = (new Sum()).evaluate(data);
        mean /= n;
        return mean;
    }
}
