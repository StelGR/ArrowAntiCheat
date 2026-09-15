package me.arrow.utils.math;

public class MathHelper {
    public static float sin(float value) {
        return (float) Math.sin(value);
    }

    public static float cos(float value) {
        return (float) Math.cos(value);
    }

    public static float sin(boolean fastMath, float value) {
        // fastMath flag ignored – same result
        return sin(value);
    }

    public static float cos(boolean fastMath, float value) {
        // fastMath flag ignored – same result
        return cos(value);
    }

    public static float sqrt_float(float f) {
        return (float) Math.sqrt(f);
    }
}
