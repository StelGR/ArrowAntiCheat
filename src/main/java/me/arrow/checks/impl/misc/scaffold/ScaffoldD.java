package me.arrow.checks.impl.misc.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import me.arrow.checks.types.Check;
import me.arrow.checks.impl.misc.interact.InteractD;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Checks that a side-face block placement is aimed at a visible face of its
 * clicked support.
 */
@Experimental
public class ScaffoldD extends Check {

    private static final int[] NORMAL_X = {0, 0, 0, 0, -1, 1};
    private static final int[] NORMAL_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] NORMAL_Z = {0, 0, -1, 1, 0, 0};

    public ScaffoldD(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "D", "Checks for an invalid block face");
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

        if (!isSidePlacement(movement, clicked, placed, target)) {
            decay();
            return;
        }

        PlacementSnapshot placement = snapshotAtPlacement(movement, profile.getRotationData());
        InteractD.FaceTraceResult trace = placement == null ? InteractD.FaceTraceResult.UNKNOWN
                : traceEyeSight(placement, clickedX, clickedY, clickedZ, face);
        if (trace == InteractD.FaceTraceResult.CLEAR || trace == InteractD.FaceTraceResult.UNKNOWN) {
            decay();
            return;
        }

        if (increaseBufferBy(1.0D) >= 2.0D) {
            fail("Invalid Block Face",
                    "face " + MsgType.MAIN_THEME_COLOR.getMessage() + faceName(face)
                            + "\nfaceLocation " + MsgType.MAIN_THEME_COLOR.getMessage()
                            + faceLocation(clickedX, clickedY, clickedZ, face)
                            + "\nclickedBlock " + MsgType.MAIN_THEME_COLOR.getMessage()
                            + location(clickedX, clickedY, clickedZ)
                            + "\nclickedType " + MsgType.MAIN_THEME_COLOR.getMessage() + clicked
                            + "\nplacedBlock " + MsgType.MAIN_THEME_COLOR.getMessage()
                            + location(targetX, targetY, targetZ)
                            + "\nplacedType " + MsgType.MAIN_THEME_COLOR.getMessage() + placed
                            + "\ntrace " + MsgType.MAIN_THEME_COLOR.getMessage() + trace
                            + "\neye " + MsgType.MAIN_THEME_COLOR.getMessage() + placement.location()
                            + "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + round(placement.yaw)
                            + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + round(placement.pitch)
                            + "\nreachAllowance " + MsgType.MAIN_THEME_COLOR.getMessage() + round(placement.reachAllowance));
            decreaseBufferBy(1.0D);
        }
    }

    private boolean isSidePlacement(MovementData movement, Material clicked, Material placed, Material target) {
        if (movement == null || movement.getLocation() == null || profile.shouldCancel()
                || clicked == null || !clicked.isOccluding() || placed == null || !placed.isBlock()
                || target == null || !isAir(target)) {
            return false;
        }

        if (movement.isNearWater() || movement.isNearLava() || movement.isNearWebs()
                || movement.isNearClimbable() || movement.isOnBoat() || movement.isNearBoat()) {
            return false;
        }

        return true;
    }

    /**
     * Trace the immutable movement/rotation state which existed when the
     * placement packet arrived.  We intentionally never re-check against a
     * later rotation: a fast spin after sending the packet cannot make an
     * invisible face look valid.
     */
    private InteractD.FaceTraceResult traceEyeSight(PlacementSnapshot placement,
                                                     int x, int y, int z, int face) {
        if (placement.world == null) {
            return InteractD.FaceTraceResult.UNKNOWN;
        }

        double yawRadians = Math.toRadians(placement.yaw);
        double pitchRadians = Math.toRadians(placement.pitch);
        double horizontal = Math.cos(pitchRadians);
        Vector direction = new Vector(
                -horizontal * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians)
        );
        return InteractD.traceBlockFace(
                placement.world,
                new Vector(placement.eyeX, placement.eyeY, placement.eyeZ),
                direction,
                x, y, z, face,
                4.75D + placement.reachAllowance,
                placement.edgeAllowance
        );
    }

    private double eyeHeight() {
        Player player = profile.getPlayer();
        if (player != null) {
            try {
                double eyeHeight = player.getEyeHeight();
                if (eyeHeight >= 1.0D && eyeHeight <= 1.7D) {
                    return eyeHeight;
                }
            } catch (Throwable ignored) {
            }
        }

        ActionData actions = profile.getActionData();
        return actions != null && actions.isSneaking() ? 1.54D : 1.62D;
    }

    private PlacementSnapshot snapshotAtPlacement(MovementData movement, RotationData rotation) {
        if (movement == null || movement.getLocation() == null || rotation == null) {
            return null;
        }

        CustomLocation location = movement.getLocation();
        ConnectionData connection = profile.getConnectionData();
        int ping = connection == null ? 0 : Math.max(connection.getPing(), connection.getTransPing());

        /*
         * Packet order is authoritative for the rotation, so compensation is
         * limited to the edge/reach uncertainty caused by the client's render
         * position.  Do not compensate by trying a future or previous look.
         */
        double reachAllowance = Math.min(0.10D, Math.max(0, ping) * 0.0004D);
        double edgeAllowance = 0.035D + Math.min(0.035D, Math.max(0, ping) * 0.00014D);

        return new PlacementSnapshot(
                location.getWorld(), location.getX(), location.getY() + eyeHeight(), location.getZ(),
                rotation.getYaw(), rotation.getPitch(), reachAllowance, edgeAllowance
        );
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

    private String faceName(int face) {
        return switch (face) {
            case 2 -> "NORTH";
            case 3 -> "SOUTH";
            case 4 -> "WEST";
            case 5 -> "EAST";
            default -> "UNKNOWN";
        };
    }

    private String faceLocation(int x, int y, int z, int face) {
        return location(x + NORMAL_X[face], y + NORMAL_Y[face], z + NORMAL_Z[face]);
    }

    private String location(int x, int y, int z) {
        return x + ", " + y + ", " + z;
    }

    private String round(double value) {
        return String.format("%.3f", value);
    }

    private void decay() {
        decreaseBufferBy(0.25D);
    }

    private static final class PlacementSnapshot {
        private final World world;
        private final double eyeX, eyeY, eyeZ, reachAllowance, edgeAllowance;
        private final float yaw, pitch;

        private PlacementSnapshot(World world, double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
                                  double reachAllowance, double edgeAllowance) {
            this.world = world;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.reachAllowance = reachAllowance;
            this.edgeAllowance = edgeAllowance;
        }

        private String location() {
            return String.format(java.util.Locale.US, "%.3f, %.3f, %.3f", eyeX, eyeY, eyeZ);
        }
    }

    @Override
    public void handle(PacketSendEvent event) {
    }
}
