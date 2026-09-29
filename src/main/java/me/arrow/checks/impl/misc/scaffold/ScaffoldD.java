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
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

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
        FaceTraceResult trace = placement == null ? FaceTraceResult.UNKNOWN
                : traceEyeSight(placement, clickedX, clickedY, clickedZ, face);
        if (trace == FaceTraceResult.CLEAR || trace == FaceTraceResult.UNKNOWN) {
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
    public enum FaceTraceResult {
        CLEAR,
        BLOCKED,
        MISS,
        UNKNOWN
    }


    private FaceTraceResult traceEyeSight(PlacementSnapshot placement,
                                                     int x, int y, int z, int face) {
        if (placement.world == null) {
            return FaceTraceResult.UNKNOWN;
        }

        double yawRadians = Math.toRadians(placement.yaw);
        double pitchRadians = Math.toRadians(placement.pitch);
        double horizontal = Math.cos(pitchRadians);
        Vector direction = new Vector(
                -horizontal * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians)
        );
        return traceBlockFace(
                placement.world,
                new Vector(placement.eyeX, placement.eyeY, placement.eyeZ),
                direction,
                x, y, z, face,
                4.75D + placement.reachAllowance,
                placement.edgeAllowance
        );
    }
    private static final double FACE_TRACE_EPSILON = 1.0E-5D;


    public static FaceTraceResult traceBlockFace(World world,
                                                 Vector eye,
                                                 Vector direction,
                                                 int targetX,
                                                 int targetY,
                                                 int targetZ,
                                                 int face,
                                                 double maxDistance,
                                                 double tolerance) {
        if (world == null || eye == null || direction == null || face < 0 || face > 5
                || maxDistance <= 0.0D) {
            return FaceTraceResult.UNKNOWN;
        }

        Vector unitDirection = direction.clone();
        if (unitDirection.lengthSquared() <= 1.0E-12D) {
            return FaceTraceResult.MISS;
        }
        unitDirection.normalize();

        try {
            if (!world.isChunkLoaded(targetX >> 4, targetZ >> 4)) {
                return FaceTraceResult.UNKNOWN;
            }

            Block target = world.getBlockAt(targetX, targetY, targetZ);
            List<PEMaterials.CollisionBounds> targetBounds = PEMaterials.getCollisionBounds(target);
            if (targetBounds == null || targetBounds.isEmpty()) {
                return FaceTraceResult.UNKNOWN;
            }

            double faceDistance = Double.MAX_VALUE;
            boolean enteredTarget = false;
            for (PEMaterials.CollisionBounds bounds : targetBounds) {
                double distance = getFaceIntersectionDistance(
                        eye, unitDirection, bounds, face, maxDistance, tolerance
                );
                if (distance < 0.0D) continue;

                double entry = getBlockRayBoxEntryDistance(eye, unitDirection, bounds, maxDistance);
                if (entry < 0.0D || Math.abs(distance - entry) > tolerance) {
                    continue;
                }

                enteredTarget = true;
                faceDistance = Math.min(faceDistance, distance);
            }

            if (!enteredTarget || faceDistance == Double.MAX_VALUE) {
                return FaceTraceResult.MISS;
            }

            Vector end = eye.clone().add(unitDirection.clone().multiply(faceDistance));
            int minX = floorRay(Math.min(eye.getX(), end.getX()));
            int maxX = floorRay(Math.max(eye.getX(), end.getX()));
            int minY = floorRay(Math.min(eye.getY(), end.getY()));
            int maxY = floorRay(Math.max(eye.getY(), end.getY()));
            int minZ = floorRay(Math.min(eye.getZ(), end.getZ()));
            int maxZ = floorRay(Math.max(eye.getZ(), end.getZ()));

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (x == targetX && y == targetY && z == targetZ) {
                            continue;
                        }
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            return FaceTraceResult.UNKNOWN;
                        }

                        Block block = world.getBlockAt(x, y, z);
                        for (PEMaterials.CollisionBounds bounds : PEMaterials.getCollisionBounds(block)) {
                            double distance = getBlockRayBoxEntryDistance(eye, unitDirection, bounds, faceDistance);
                            if (distance > FACE_TRACE_EPSILON && distance + tolerance < faceDistance) {
                                return FaceTraceResult.BLOCKED;
                            }
                        }
                    }
                }
            }

            return FaceTraceResult.CLEAR;
        } catch (Throwable ignored) {
            return FaceTraceResult.UNKNOWN;
        }
    }

    private static double getFaceIntersectionDistance(Vector origin,
                                                      Vector direction,
                                                      PEMaterials.CollisionBounds bounds,
                                                      int face,
                                                      double maxDistance,
                                                      double tolerance) {
        double plane;
        double coordinate;
        double normalDot;

        switch (face) {
            case 0:
                plane = bounds.minY;
                coordinate = direction.getY();
                normalDot = -direction.getY();
                break;
            case 1:
                plane = bounds.maxY;
                coordinate = direction.getY();
                normalDot = direction.getY();
                break;
            case 2:
                plane = bounds.minZ;
                coordinate = direction.getZ();
                normalDot = -direction.getZ();
                break;
            case 3:
                plane = bounds.maxZ;
                coordinate = direction.getZ();
                normalDot = direction.getZ();
                break;
            case 4:
                plane = bounds.minX;
                coordinate = direction.getX();
                normalDot = -direction.getX();
                break;
            case 5:
                plane = bounds.maxX;
                coordinate = direction.getX();
                normalDot = direction.getX();
                break;
            default:
                return -1.0D;
        }

        if (Math.abs(coordinate) < FACE_TRACE_EPSILON || normalDot >= -FACE_TRACE_EPSILON) {
            return -1.0D;
        }

        double originCoordinate = face <= 1 ? origin.getY()
                : face <= 3 ? origin.getZ() : origin.getX();
        double distance = (plane - originCoordinate) / coordinate;
        if (distance <= FACE_TRACE_EPSILON || distance > maxDistance) {
            return -1.0D;
        }

        double hitX = origin.getX() + direction.getX() * distance;
        double hitY = origin.getY() + direction.getY() * distance;
        double hitZ = origin.getZ() + direction.getZ() * distance;
        return hitX >= bounds.minX - tolerance && hitX <= bounds.maxX + tolerance
                && hitY >= bounds.minY - tolerance && hitY <= bounds.maxY + tolerance
                && hitZ >= bounds.minZ - tolerance && hitZ <= bounds.maxZ + tolerance
                ? distance : -1.0D;
    }

    private static double getBlockRayBoxEntryDistance(Vector origin,
                                                      Vector direction,
                                                      PEMaterials.CollisionBounds bounds,
                                                      double maxDistance) {
        double[] range = {0.0D, maxDistance};

        if (!clipRayAxis(origin.getX(), direction.getX(), bounds.minX, bounds.maxX, range)
                || !clipRayAxis(origin.getY(), direction.getY(), bounds.minY, bounds.maxY, range)
                || !clipRayAxis(origin.getZ(), direction.getZ(), bounds.minZ, bounds.maxZ, range)) {
            return -1.0D;
        }

        return range[0] <= maxDistance ? range[0] : -1.0D;
    }

    private static boolean clipRayAxis(double origin, double direction,
                                       double minimum, double maximum, double[] range) {
        if (Math.abs(direction) < 1.0E-9D) {
            return origin >= minimum && origin <= maximum;
        }

        double first = (minimum - origin) / direction;
        double second = (maximum - origin) / direction;
        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }

        range[0] = Math.max(range[0], first);
        range[1] = Math.min(range[1], second);
        return range[0] <= range[1] && range[1] >= 0.0D;
    }

    private static int floorRay(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
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
