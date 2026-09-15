package me.arrow.utils.vec;

/**
 * Minimal 3D vector class mirroring Karhu's Vec3 used in the movement simulation.
 */
public class Vec3 {
    public final double xCoord;
    public final double yCoord;
    public final double zCoord;

    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    public Vec3(double xCoord, double yCoord, double zCoord) {
        this.xCoord = xCoord;
        this.yCoord = yCoord;
        this.zCoord = zCoord;
    }

    /**
     * Returns the squared length of the vector.
     */
    public double lengthSqr() {
        return xCoord * xCoord + yCoord * yCoord + zCoord * zCoord;
    }

    /**
     * Scales this vector by the given factor (float) and returns a new Vec3.
     */
    public Vec3 scale(float factor) {
        return new Vec3(xCoord * factor, yCoord * factor, zCoord * factor);
    }

    /**
     * Normalizes the vector (making its length 1) and returns a new Vec3.
     * If the vector is zero-length, returns the original vector to avoid division by zero.
     */
    public Vec3 normalizeModern() {
        double length = Math.sqrt(lengthSqr());
        if (length == 0.0) {
            return this;
        }
        return new Vec3(xCoord / length, yCoord / length, zCoord / length);
    }
}
