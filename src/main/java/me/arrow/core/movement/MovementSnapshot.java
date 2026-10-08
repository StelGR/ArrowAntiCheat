package me.arrow.core.movement;

import java.util.Objects;

/** Immutable state consumed by universal movement checks. */
public final class MovementSnapshot {
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final boolean positionChanged;
    private final boolean rotationChanged;
    private final boolean onGround;
    private final boolean lastOnGround;
    private final boolean horizontalCollision;
    private final double deltaX;
    private final double deltaY;
    private final double deltaZ;
    private final double deltaXZ;
    private final double lastDeltaX;
    private final double lastDeltaY;
    private final double lastDeltaZ;
    private final double lastDeltaXZ;
    private final double accelY;
    private final double accelXZ;
    private final long timestamp;
    private final long tick;

    public MovementSnapshot(
            String worldName,
            double x, double y, double z,
            float yaw, float pitch,
            boolean positionChanged, boolean rotationChanged,
            boolean onGround, boolean lastOnGround, boolean horizontalCollision,
            double deltaX, double deltaY, double deltaZ, double deltaXZ,
            double lastDeltaX, double lastDeltaY, double lastDeltaZ, double lastDeltaXZ,
            double accelY, double accelXZ,
            long timestamp, long tick
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
        this.lastOnGround = lastOnGround;
        this.horizontalCollision = horizontalCollision;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.deltaZ = deltaZ;
        this.deltaXZ = deltaXZ;
        this.lastDeltaX = lastDeltaX;
        this.lastDeltaY = lastDeltaY;
        this.lastDeltaZ = lastDeltaZ;
        this.lastDeltaXZ = lastDeltaXZ;
        this.accelY = accelY;
        this.accelXZ = accelXZ;
        this.timestamp = timestamp;
        this.tick = tick;
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
    public boolean lastOnGround() { return lastOnGround; }
    public boolean horizontalCollision() { return horizontalCollision; }
    public double deltaX() { return deltaX; }
    public double deltaY() { return deltaY; }
    public double deltaZ() { return deltaZ; }
    public double deltaXZ() { return deltaXZ; }
    public double lastDeltaX() { return lastDeltaX; }
    public double lastDeltaY() { return lastDeltaY; }
    public double lastDeltaZ() { return lastDeltaZ; }
    public double lastDeltaXZ() { return lastDeltaXZ; }
    public double accelY() { return accelY; }
    public double accelXZ() { return accelXZ; }
    public long timestamp() { return timestamp; }
    public long tick() { return tick; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MovementSnapshot that = (MovementSnapshot) o;
        return Double.compare(that.x, x) == 0
                && Double.compare(that.y, y) == 0
                && Double.compare(that.z, z) == 0
                && Float.compare(that.yaw, yaw) == 0
                && Float.compare(that.pitch, pitch) == 0
                && positionChanged == that.positionChanged
                && rotationChanged == that.rotationChanged
                && onGround == that.onGround
                && lastOnGround == that.lastOnGround
                && horizontalCollision == that.horizontalCollision
                && Double.compare(that.deltaX, deltaX) == 0
                && Double.compare(that.deltaY, deltaY) == 0
                && Double.compare(that.deltaZ, deltaZ) == 0
                && Double.compare(that.deltaXZ, deltaXZ) == 0
                && Double.compare(that.lastDeltaX, lastDeltaX) == 0
                && Double.compare(that.lastDeltaY, lastDeltaY) == 0
                && Double.compare(that.lastDeltaZ, lastDeltaZ) == 0
                && Double.compare(that.lastDeltaXZ, lastDeltaXZ) == 0
                && Double.compare(that.accelY, accelY) == 0
                && Double.compare(that.accelXZ, accelXZ) == 0
                && timestamp == that.timestamp
                && tick == that.tick
                && Objects.equals(worldName, that.worldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldName, x, y, z, yaw, pitch, positionChanged, rotationChanged,
                onGround, lastOnGround, horizontalCollision, deltaX, deltaY, deltaZ, deltaXZ,
                lastDeltaX, lastDeltaY, lastDeltaZ, lastDeltaXZ, accelY, accelXZ, timestamp, tick);
    }
}
