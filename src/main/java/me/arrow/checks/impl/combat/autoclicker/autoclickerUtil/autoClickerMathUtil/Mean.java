package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.autoClickerMathUtil;

import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.AbstractStatisticFunction;

public class Mean extends AbstractStatisticFunction {
    public Mean() {
    }

    public double evaluate(double[] data) {
        if (data == null || data.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double d : data) {
            sum += d;
        }
        return sum / data.length;
    }
}
