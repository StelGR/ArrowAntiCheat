package me.arrow.core.movement;

import me.arrow.core.network.PacketTimestamp;

/**
 * Owns packet-derived movement state only. World collision and platform player
 * queries deliberately stay in a backend adapter.
 */
public final class MovementState {

    private MovementSnapshot current;
    private MovementSnapshot previous;
    private long tick;

    public synchronized MovementSnapshot accept(MovementFrame frame) {
        MovementSnapshot before = current;
        double x = before == null || frame.positionChanged() ? frame.x() : before.x();
        double y = before == null || frame.positionChanged() ? frame.y() : before.y();
        double z = before == null || frame.positionChanged() ? frame.z() : before.z();
        float yaw = before == null || frame.rotationChanged() ? frame.yaw() : before.yaw();
        float pitch = before == null || frame.rotationChanged() ? frame.pitch() : before.pitch();

        double deltaX = before == null ? 0.0D : x - before.x();
        double deltaY = before == null ? 0.0D : y - before.y();
        double deltaZ = before == null ? 0.0D : z - before.z();
        double deltaXZ = Math.hypot(deltaX, deltaZ);

        previous = before;
        current = new MovementSnapshot(
                frame.worldName(), x, y, z, yaw, pitch,
                frame.positionChanged(), frame.rotationChanged(),
                frame.onGround(), before != null && before.onGround(), frame.horizontalCollision(),
                deltaX, deltaY, deltaZ, deltaXZ,
                before == null ? 0.0D : before.deltaX(),
                before == null ? 0.0D : before.deltaY(),
                before == null ? 0.0D : before.deltaZ(),
                before == null ? 0.0D : before.deltaXZ(),
                before == null ? 0.0D : Math.abs(deltaY - before.deltaY()),
                before == null ? 0.0D : Math.abs(deltaXZ - before.deltaXZ()),
                PacketTimestamp.toMillis(frame.timestamp()), ++tick
        );
        return current;
    }

    public synchronized MovementSnapshot current() {
        return current;
    }

    public synchronized MovementSnapshot previous() {
        return previous;
    }
}
