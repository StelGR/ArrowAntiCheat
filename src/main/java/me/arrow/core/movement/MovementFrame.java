package me.arrow.core.movement;

/** Platform-neutral representation of one client movement packet. */
public record MovementFrame(
        String worldName,
        double x, double y, double z,
        float yaw, float pitch,
        boolean positionChanged, boolean rotationChanged,
        boolean onGround, boolean horizontalCollision,
        long timestamp
) {
}
