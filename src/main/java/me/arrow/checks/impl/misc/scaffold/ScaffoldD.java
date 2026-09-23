package me.arrow.checks.impl.misc.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.Material;

/**
 * Checks that an isolated horizontal bridge placement has an unobstructed line
 * from the player's eye to the clicked support face.
 */
@Experimental
public class ScaffoldD extends Check {

    private static final int[] NORMAL_X = {0, 0, 0, 0, -1, 1};
    private static final int[] NORMAL_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] NORMAL_Z = {0, 0, -1, 1, 0, 0};

    public ScaffoldD(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "D", "Checks that scaffold supports have a visible face");
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            return;
        }

        // Bedrock's placement and hit-selection rules differ from Java's.
        if (profile.isBedrockPlayer()) {
            decay();
            return;
        }

        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);
        int clickedX = packet.getBlockPosition().getX();
        int clickedY = packet.getBlockPosition().getY();
        int clickedZ = packet.getBlockPosition().getZ();
        int face = face(packet);

        if (face < 2 || (clickedX == -1 && clickedY == -1 && clickedZ == -1)) {
            decay();
            return;
        }

        int targetX = clickedX + NORMAL_X[face];
        int targetY = clickedY + NORMAL_Y[face];
        int targetZ = clickedZ + NORMAL_Z[face];
        MovementData movement = profile.getMovementData();
        Material clicked = profile.getBlockProcessor().getServerMaterial(clickedX, clickedY, clickedZ);
        Material placed = profile.getBlockProcessor().getBlockPlaceMaterial();
        Material target = profile.getBlockProcessor().getServerMaterial(targetX, targetY, targetZ);

        if (!isBridgePlacement(movement, clicked, placed, target, targetX, targetY, targetZ,
                clickedX, clickedY, clickedZ)) {
            decay();
            return;
        }

        TraceResult trace = traceToClickedFace(movement.getLocation(), clickedX, clickedY, clickedZ, face);
        if (trace != TraceResult.BLOCKED) {
            decay();
            return;
        }

        if (increaseBufferBy(1.0D) >= 2.0D) {
            fail("Occluded scaffold face",
                    "face " + MsgType.MAIN_THEME_COLOR.getMessage() + faceName(face)
                            + "\ntrace " + MsgType.MAIN_THEME_COLOR.getMessage() + trace);
            decreaseBufferBy(1.0D);
        }
    }

    private boolean isBridgePlacement(MovementData movement, Material clicked, Material placed, Material target,
                                      int targetX, int targetY, int targetZ,
                                      int clickedX, int clickedY, int clickedZ) {
        if (movement == null || movement.getLocation() == null || profile.shouldCancel()
                || clicked == null || !clicked.isOccluding() || placed == null || !placed.isBlock()
                || target == null || !isAir(target)) {
            return false;
        }

        if (movement.isNearWater() || movement.isNearLava() || movement.isNearWebs()
                || movement.isNearClimbable() || movement.isOnBoat() || movement.isNearBoat()) {
            return false;
        }

        int feetY = (int) Math.floor(movement.getLocation().getY());
        Material below = profile.getBlockProcessor().getServerMaterial(targetX, targetY - 1, targetZ);
        return targetY <= feetY && targetY >= feetY - 2
                && below != null && isAir(below)
                && hasOnlyClickedSupport(clickedX, clickedY, clickedZ, targetX, targetY, targetZ);
    }

    /** Only check the exposed end of a bridge, never ordinary block placement. */
    private boolean hasOnlyClickedSupport(int clickedX, int clickedY, int clickedZ,
                                          int targetX, int targetY, int targetZ) {
        int supports = 0;

        for (int face = 0; face < 6; face++) {
            int x = targetX + NORMAL_X[face];
            int y = targetY + NORMAL_Y[face];
            int z = targetZ + NORMAL_Z[face];
            Material neighbor = profile.getBlockProcessor().getServerMaterial(x, y, z);

            if (neighbor == null) {
                return false;
            }
            if (isAir(neighbor)) {
                continue;
            }
            if (x != clickedX || y != clickedY || z != clickedZ) {
                return false;
            }
            supports++;
        }

        return supports == 1;
    }

    /**
     * This is the Interact B-style trace: aim from the eye to the claimed face,
     * then inspect every crossed cached block. It intentionally does not use
     * MovementData's stored yaw/pitch, which can be one packet behind a place.
     */
    private TraceResult traceToClickedFace(CustomLocation location, int x, int y, int z, int face) {
        double eyeX = location.getX();
        double eyeY = location.getY() + eyeHeight();
        double eyeZ = location.getZ();
        double hitX = x + 0.5D + NORMAL_X[face] * 0.5D;
        double hitY = y + 0.5D + NORMAL_Y[face] * 0.5D;
        double hitZ = z + 0.5D + NORMAL_Z[face] * 0.5D;
        double distance = Math.sqrt(square(hitX - eyeX) + square(hitY - eyeY) + square(hitZ - eyeZ));

        if (distance <= 0.0D || distance > 4.75D) {
            return TraceResult.UNKNOWN;
        }

        int steps = Math.max(1, (int) Math.ceil(distance / 0.025D));
        for (int step = 1; step < steps; step++) {
            double progress = (double) step / steps;
            int blockX = floor(eyeX + (hitX - eyeX) * progress);
            int blockY = floor(eyeY + (hitY - eyeY) * progress);
            int blockZ = floor(eyeZ + (hitZ - eyeZ) * progress);

            if (blockX == x && blockY == y && blockZ == z) {
                continue;
            }

            Material material = profile.getBlockProcessor().getServerMaterial(blockX, blockY, blockZ);
            if (material == null) {
                return TraceResult.UNKNOWN;
            }
            if (material.isOccluding()) {
                return TraceResult.BLOCKED;
            }
        }

        return TraceResult.CLEAR;
    }

    private double eyeHeight() {
        ActionData actions = profile.getActionData();
        return actions != null && actions.isSneaking() ? 1.54D : 1.62D;
    }

    private int face(WrapperPlayClientPlayerBlockPlacement packet) {
        try {
            int value = packet.getFace().getFaceValue();
            return value >= 0 && value <= 5 ? value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean isAir(Material material) {
        return material == Material.AIR
                || material.name().equals("CAVE_AIR")
                || material.name().equals("VOID_AIR");
    }

    private int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private double square(double value) {
        return value * value;
    }

    private String faceName(int face) {
        return switch (face) {
            case 2 -> "NORTH";
            case 3 -> "SOUTH";
            case 4 -> "WEST";
            case 5 -> "EAST";
            default -> "UNKNOWN";
        };
    }

    private void decay() {
        decreaseBufferBy(0.25D);
    }

    private enum TraceResult {
        CLEAR,
        BLOCKED,
        UNKNOWN
    }

    @Override
    public void handle(PacketSendEvent event) {
    }
}
