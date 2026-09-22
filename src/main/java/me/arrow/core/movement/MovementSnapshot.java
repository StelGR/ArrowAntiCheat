package me.arrow.core.movement;

/** Immutable state consumed by universal movement checks. */
public record MovementSnapshot(
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
}
