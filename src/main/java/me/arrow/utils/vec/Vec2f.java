package me.arrow.utils.vec;

/**
 * Minimal 2D vector class mirroring Karhu's Vec2f used in the simulation.
 */
public class Vec2f {
    public final float x;
    public final float y;

    public static final Vec2f ZERO = new Vec2f(0f, 0f);

    public Vec2f(float x, float y) {
        this.x = x;
        this.y = y;
    }
}
