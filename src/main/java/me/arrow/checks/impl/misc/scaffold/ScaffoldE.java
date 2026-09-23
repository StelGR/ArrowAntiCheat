package me.arrow.checks.impl.misc.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.Material;
import org.bukkit.util.Vector;

/**
 * Requires a scaffold placement to match the visible face of its clicked block.
 * Partial blocks are deliberately skipped: their collision shapes need block-state
 * data, while normal scaffold supports are full cubes.
 */
@Experimental
public class ScaffoldE extends Check {

    private static final int[] NORMAL_X = {0, 0, 0, 0, -1, 1};
    private static final int[] NORMAL_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] NORMAL_Z = {0, 0, -1, 1, 0, 0};

    public ScaffoldE(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "E", "Checks that scaffold placements use a visible block face");
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            return;
        }

        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);
        int x = packet.getBlockPosition().getX();
        int y = packet.getBlockPosition().getY();
        int z = packet.getBlockPosition().getZ();
        int face = face(packet);

        if (face < 0 || (x == -1 && y == -1 && z == -1)) {
            return;
        }

        MovementData movement = profile.getMovementData();
        Material clicked = profile.getBlockProcessor().getServerMaterial(x, y, z);
        Material placed = profile.getBlockProcessor().getBlockPlaceMaterial();

        if (!isScaffoldContext(movement)
                || clicked == null || !clicked.isOccluding()
                || placed == null || !placed.isBlock()
                || !matchesTrackedPlacement(x, y, z, face)) {
            decay();
            return;
        }

        CustomLocation location = movement.getLocation();
        double eyeHeight = eyeHeight();
        double eyeX = location.getX();
        double eyeY = location.getY() + eyeHeight;
        double eyeZ = location.getZ();

        if (inside(eyeX, eyeY, eyeZ, x, y, z)) {
            decay();
            return;
        }

        double[] direction = direction(location);
        double reach = profile.isBedrockPlayer() ? 6.0D : 4.75D;
        double tolerance = profile.isBedrockPlayer() ? 0.18D : 0.06D;
        double faceDistance = distanceToFace(eyeX, eyeY, eyeZ, direction, x, y, z, face, reach, tolerance);
        double firstBlockDistance = rayBoxEntry(eyeX, eyeY, eyeZ, direction, x, y, z, reach);

        boolean missesFace = faceDistance < 0.0D
                || firstBlockDistance < 0.0D
                || Math.abs(faceDistance - firstBlockDistance) > tolerance;

        if (!missesFace) {
            // An unloaded/occluded ray is not reliable evidence. Do not guess.
            if (!isRayClear(eyeX, eyeY, eyeZ, direction, faceDistance, x, y, z)) {
                decay();
                return;
            }
            decay();
            return;
        }

        if (increaseBufferBy(1.0D) > 3.0D) {
            fail("Invisible scaffold face",
                    "face " + MsgType.MAIN_THEME_COLOR.getMessage() + faceName(face)
                            + "\nfaceDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + round(faceDistance)
                            + "\nfirstBlockDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + round(firstBlockDistance)
                            + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + round(location.getPitch())
                            + "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + round(location.getYaw())
                            + "\nbedrock " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.isBedrockPlayer());
            decreaseBufferBy(1.0D);
        }
    }

    private boolean isScaffoldContext(MovementData movement) {
        if (movement == null || movement.getLocation() == null || profile.shouldCancel()) {
            return false;
        }

        if (movement.getDeltaXZ() < (profile.isBedrockPlayer() ? 0.035D : 0.055D)
                || movement.isNearWater() || movement.isNearLava() || movement.isNearWebs()
                || movement.isNearClimbable() || movement.isOnBoat() || movement.isNearBoat()) {
            return false;
        }

        return profile.isAirBridging(movement.getLocation().toBukkit());
    }

    private boolean matchesTrackedPlacement(int x, int y, int z, int face) {
        Vector tracked = profile.getBlockProcessor().getCurrentBlockCords();
        return tracked != null
                && tracked.getBlockX() == x + NORMAL_X[face]
                && tracked.getBlockY() == y + NORMAL_Y[face]
                && tracked.getBlockZ() == z + NORMAL_Z[face];
    }

    private double eyeHeight() {
        ActionData actions = profile.getActionData();
        if (profile.isBedrockPlayer()) {
            return 1.55D;
        }
        return actions != null && actions.isSneaking() ? 1.54D : 1.62D;
    }

    private double[] direction(CustomLocation location) {
        double yaw = Math.toRadians(location.getYaw());
        double pitch = Math.toRadians(location.getPitch());
        double horizontal = Math.cos(pitch);
        return new double[]{
                -horizontal * Math.sin(yaw),
                -Math.sin(pitch),
                horizontal * Math.cos(yaw)
        };
    }

    private double distanceToFace(double eyeX, double eyeY, double eyeZ, double[] direction,
                                  int x, int y, int z, int face, double reach, double tolerance) {
        int axis = face <= 1 ? 1 : face <= 3 ? 2 : 0;
        double origin = axis == 0 ? eyeX : axis == 1 ? eyeY : eyeZ;
        double component = direction[axis];
        double plane = axis == 0 ? x + (face == 5 ? 1.0D : 0.0D)
                : axis == 1 ? y + (face == 1 ? 1.0D : 0.0D)
                : z + (face == 3 ? 1.0D : 0.0D);
        double normalDot = direction[0] * NORMAL_X[face]
                + direction[1] * NORMAL_Y[face]
                + direction[2] * NORMAL_Z[face];

        if (Math.abs(component) < 1.0E-7D || normalDot >= -0.01D) {
            return -1.0D;
        }

        double distance = (plane - origin) / component;
        if (distance <= 0.0D || distance > reach) {
            return -1.0D;
        }

        double hitX = eyeX + direction[0] * distance;
        double hitY = eyeY + direction[1] * distance;
        double hitZ = eyeZ + direction[2] * distance;

        return within(hitX, x, x + 1.0D, tolerance)
                && within(hitY, y, y + 1.0D, tolerance)
                && within(hitZ, z, z + 1.0D, tolerance)
                ? distance : -1.0D;
    }

    private double rayBoxEntry(double eyeX, double eyeY, double eyeZ, double[] direction,
                               int x, int y, int z, double reach) {
        double entry = 0.0D;
        double exit = reach;
        double[] origin = {eyeX, eyeY, eyeZ};
        double[] min = {x, y, z};
        double[] max = {x + 1.0D, y + 1.0D, z + 1.0D};

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0E-7D) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return -1.0D;
                }
                continue;
            }

            double first = (min[axis] - origin[axis]) / direction[axis];
            double second = (max[axis] - origin[axis]) / direction[axis];
            entry = Math.max(entry, Math.min(first, second));
            exit = Math.min(exit, Math.max(first, second));

            if (entry > exit) {
                return -1.0D;
            }
        }

        return entry <= reach ? entry : -1.0D;
    }

    private boolean isRayClear(double eyeX, double eyeY, double eyeZ, double[] direction,
                               double distance, int targetX, int targetY, int targetZ) {
        int steps = Math.max(1, (int) Math.ceil(distance / 0.20D));

        for (int step = 1; step < steps; step++) {
            double progress = distance * step / steps;
            int x = (int) Math.floor(eyeX + direction[0] * progress);
            int y = (int) Math.floor(eyeY + direction[1] * progress);
            int z = (int) Math.floor(eyeZ + direction[2] * progress);

            if (x == targetX && y == targetY && z == targetZ) {
                continue;
            }

            Material material = profile.getBlockProcessor().getServerMaterial(x, y, z);
            if (material == null || material.isOccluding()) {
                return false;
            }
        }

        return true;
    }

    private boolean inside(double x, double y, double z, int blockX, int blockY, int blockZ) {
        return x > blockX && x < blockX + 1.0D
                && y > blockY && y < blockY + 1.0D
                && z > blockZ && z < blockZ + 1.0D;
    }

    private boolean within(double value, double min, double max, double tolerance) {
        return value >= min - tolerance && value <= max + tolerance;
    }

    private int face(WrapperPlayClientPlayerBlockPlacement packet) {
        try {
            int value = packet.getFace().getFaceValue();
            return value >= 0 && value <= 5 ? value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String faceName(int face) {
        return switch (face) {
            case 0 -> "DOWN";
            case 1 -> "UP";
            case 2 -> "NORTH";
            case 3 -> "SOUTH";
            case 4 -> "WEST";
            case 5 -> "EAST";
            default -> "UNKNOWN";
        };
    }

    private String round(double value) {
        return value < 0.0D ? "none" : String.format("%.3f", value);
    }

    private void decay() {
        decreaseBufferBy(0.25D);
    }

    @Override
    public void handle(PacketSendEvent event) {
    }
}
