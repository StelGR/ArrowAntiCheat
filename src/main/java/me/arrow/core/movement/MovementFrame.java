package me.arrow.core.movement;

import java.util.Objects;

/** Platform-neutral representation of one client movement packet. */
public final class MovementFrame {
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final boolean positionChanged;
    private final boolean rotationChanged;
    private final boolean onGround;
    private final boolean horizontalCollision;
    private final long timestamp;

    public MovementFrame(
            String worldName,
            double x, double y, double z,
            float yaw, float pitch,
            boolean positionChanged, boolean rotationChanged,
            boolean onGround, boolean horizontalCollision,
            long timestamp
    ) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.positionChanged = positionChanged;
        this.rotationChanged = rotationChanged;
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
        this.timestamp = timestamp;
    }

    public String worldName() { return worldName; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public boolean positionChanged() { return positionChanged; }
    public boolean rotationChanged() { return rotationChanged; }
    public boolean onGround() { return onGround; }
    public boolean horizontalCollision() { return horizontalCollision; }
    public long timestamp() { return timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MovementFrame that = (MovementFrame) o;
        return Double.compare(that.x, x) == 0
                && Double.compare(that.y, y) == 0
                && Double.compare(that.z, z) == 0
                && Float.compare(that.yaw, yaw) == 0
                && Float.compare(that.pitch, pitch) == 0
                && positionChanged == that.positionChanged
                && rotationChanged == that.rotationChanged
                && onGround == that.onGround
                && horizontalCollision == that.horizontalCollision
                && timestamp == that.timestamp
                && Objects.equals(worldName, that.worldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldName, x, y, z, yaw, pitch, positionChanged, rotationChanged, onGround, horizontalCollision, timestamp);
    }

    @Override
    public String toString() {
        return "MovementFrame[" +
                "worldName=" + worldName +
                ", x=" + x + ", y=" + y + ", z=" + z +
                ", yaw=" + yaw + ", pitch=" + pitch +
                ", positionChanged=" + positionChanged +
                ", rotationChanged=" + rotationChanged +
                ", onGround=" + onGround +
                ", horizontalCollision=" + horizontalCollision +
                ", timestamp=" + timestamp + ']';
    }
}
