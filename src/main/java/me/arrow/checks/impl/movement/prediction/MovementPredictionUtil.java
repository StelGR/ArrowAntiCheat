package me.arrow.checks.impl.movement.prediction;

import lombok.Getter;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.MaterialType;
import org.bukkit.Material;
import org.bukkit.World;

public class MovementPredictionUtil {

    private static final int RADIUS = 2;
    private static final int DEPTH = 2;
    private static final double STEP_THRESHOLD = 0.35D;
    private static final double MOVE_EPS = 1.0E-4D;
    private static final double SIDE_EPS = 0.35D;

    private MovementPredictionUtil() {
    }

    public enum VerticalMove {
        UP,
        DOWN,
        HORIZONTAL
    }

    public enum RelativeMove {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT,
        STATIONARY
    }

    public enum MovementSector {
        FORWARD,
        FORWARD_LEFT,
        FORWARD_RIGHT,
        LEFT,
        RIGHT,
        BACKWARD_LEFT,
        BACKWARD_RIGHT,
        BACKWARD,
        STATIONARY
    }

    @Getter
    public static final class DirectionalMovement {

        private final double deltaX;
        private final double deltaZ;
        private final double deltaXZ;

        private final float yaw;

        private final double moveX;
        private final double moveZ;

        private final double forwardX;
        private final double forwardZ;

        /**
         *  1.0 = fully forward
         *  0.0 = pure sideways
         * -1.0 = fully backwards
         */
        private final double dot;

        /**
         * Negative = left
         * Positive = right
         */
        private final double cross;

        /**
         * Signed movement angle relative to yaw.
         *
         * 0      = forward
         * 90     = right
         * -90    = left
         * +/-180 = backwards
         */
        private final double signedAngle;

        /**
         * Absolute angle away from forward.
         *
         * 0   = forward
         * 45  = vanilla W+A / W+D
         * 90  = pure A / D
         * 180 = pure S
         */
        private final double absoluteAngle;

        private final MovementSector sector;

        private DirectionalMovement(double deltaX,
                                    double deltaZ,
                                    double deltaXZ,
                                    float yaw,
                                    double moveX,
                                    double moveZ,
                                    double forwardX,
                                    double forwardZ,
                                    double dot,
                                    double cross,
                                    double signedAngle,
                                    double absoluteAngle,
                                    MovementSector sector) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
            this.deltaXZ = deltaXZ;
            this.yaw = yaw;
            this.moveX = moveX;
            this.moveZ = moveZ;
            this.forwardX = forwardX;
            this.forwardZ = forwardZ;
            this.dot = dot;
            this.cross = cross;
            this.signedAngle = signedAngle;
            this.absoluteAngle = absoluteAngle;
            this.sector = sector;
        }

        public boolean isMoving() {
            return deltaXZ > MOVE_EPS;
        }

        public boolean isForward() {
            return sector == MovementSector.FORWARD
                    || sector == MovementSector.FORWARD_LEFT
                    || sector == MovementSector.FORWARD_RIGHT;
        }

        public boolean isSideways() {
            return sector == MovementSector.LEFT
                    || sector == MovementSector.RIGHT;
        }

        public boolean isBackwards() {
            return sector == MovementSector.BACKWARD
                    || sector == MovementSector.BACKWARD_LEFT
                    || sector == MovementSector.BACKWARD_RIGHT;
        }

        public boolean isLeft() {
            return cross < 0.0D;
        }

        public boolean isRight() {
            return cross > 0.0D;
        }

        public RelativeMove toSimpleRelativeMove() {
            if (!isMoving()) {
                return RelativeMove.STATIONARY;
            }

            if (absoluteAngle <= 67.5D) {
                return RelativeMove.FORWARD;
            }

            if (absoluteAngle >= 112.5D) {
                return RelativeMove.BACKWARD;
            }

            return cross > 0.0D ? RelativeMove.RIGHT : RelativeMove.LEFT;
        }
    }

    @Getter
    public static final class BlockSample {

        private final int x;
        private final int y;
        private final int z;
        private final Material material;

        public BlockSample(int x, int y, int z, Material material) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
        }
    }

    public static BlockSample[] scanAroundFeet(World world, double x, double y, double z) {
        int baseX = floor(x);
        int baseY = floor(y);
        int baseZ = floor(z);

        BlockSample[] samples = new BlockSample[(RADIUS * 2 + 1) * (RADIUS * 2 + 1) * (DEPTH + 1)];
        int index = 0;

        for (int dy = 0; dy >= -DEPTH; dy--) {
            int py = baseY + dy;

            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int px = baseX + dx;

                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    int pz = baseZ + dz;
                    Material material = world.getBlockAt(px, py, pz).getType();
                    samples[index++] = new BlockSample(px, py, pz, material);
                }
            }
        }

        return samples;
    }

    public static VerticalMove predictVerticalMove(World world, double x, double y, double z, double deltaX, double deltaZ) {
        if (Math.abs(deltaX) < MOVE_EPS && Math.abs(deltaZ) < MOVE_EPS) {
            return VerticalMove.HORIZONTAL;
        }

        double currentSurface = surfaceScore(world, x, y, z);
        double predictedSurface = surfaceScore(world, x + deltaX, y, z + deltaZ);
        double diff = predictedSurface - currentSurface;

        if (diff >= STEP_THRESHOLD) {
            return VerticalMove.UP;
        }

        if (diff <= -STEP_THRESHOLD) {
            return VerticalMove.DOWN;
        }

        return VerticalMove.HORIZONTAL;
    }

    public static DirectionalMovement predictDirectionalMovement(double deltaX, double deltaZ, float yaw) {
        double deltaXZ = Math.hypot(deltaX, deltaZ);

        if (deltaXZ < MOVE_EPS) {
            return new DirectionalMovement(
                    deltaX,
                    deltaZ,
                    deltaXZ,
                    yaw,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D,
                    MovementSector.STATIONARY
            );
        }

        double moveX = deltaX / deltaXZ;
        double moveZ = deltaZ / deltaXZ;

        double rad = Math.toRadians(yaw);

        /*
         * Minecraft yaw:
         *
         * yaw 0    = +Z
         * yaw 90   = -X
         * yaw -90  = +X
         * yaw 180  = -Z
         */
        double forwardX = -Math.sin(rad);
        double forwardZ = Math.cos(rad);

        double dot = clamp((forwardX * moveX) + (forwardZ * moveZ), -1.0D, 1.0D);

        /*
         * Positive cross = right
         * Negative cross = left
         */
        double cross = clamp((forwardX * moveZ) - (forwardZ * moveX), -1.0D, 1.0D);

        double signedAngle = Math.toDegrees(Math.atan2(cross, dot));
        double absoluteAngle = Math.abs(signedAngle);

        MovementSector sector = classifySector(signedAngle, absoluteAngle);

        return new DirectionalMovement(
                deltaX,
                deltaZ,
                deltaXZ,
                yaw,
                moveX,
                moveZ,
                forwardX,
                forwardZ,
                dot,
                cross,
                signedAngle,
                absoluteAngle,
                sector
        );
    }

    public static DirectionalMovement predictDirectionalMovement(MovementData movementData, float yaw) {
        return predictDirectionalMovement(
                movementData.getDeltaX(),
                movementData.getDeltaZ(),
                yaw
        );
    }

    public static DirectionalMovement predictDirectionalMovement(MovementData movementData, RotationData rotationData) {
        return predictDirectionalMovement(
                movementData.getDeltaX(),
                movementData.getDeltaZ(),
                rotationData.getTrustedYaw()
        );
    }

    public static RelativeMove predictRelativeMove(double deltaX, double deltaZ, float yaw) {
        return predictDirectionalMovement(deltaX, deltaZ, yaw).toSimpleRelativeMove();
    }

    public static VerticalMove predictVerticalMove(MovementData md) {
        return predictVerticalMove(
                md.getLocation().getWorld(),
                md.getLocation().getX(),
                md.getLocation().getY(),
                md.getLocation().getZ(),
                md.getDeltaX(),
                md.getDeltaZ()
        );
    }

    public static RelativeMove predictRelativeMove(MovementData md, RotationData rd) {
        return predictRelativeMove(md.getDeltaX(), md.getDeltaZ(), rd.getTrustedYaw());
    }

    public static float wrapDegrees(float value) {
        value %= 360.0F;

        if (value >= 180.0F) {
            value -= 360.0F;
        }

        if (value < -180.0F) {
            value += 360.0F;
        }

        return value;
    }

    public static float yawDifference(float first, float second) {
        return Math.abs(wrapDegrees(first - second));
    }

    public static double movementYawFromDelta(double deltaX, double deltaZ) {
        if (Math.hypot(deltaX, deltaZ) < MOVE_EPS) {
            return 0.0D;
        }

        /*
         * Inverse of:
         * forwardX = -sin(yaw)
         * forwardZ = cos(yaw)
         */
        return wrapDegrees((float) Math.toDegrees(Math.atan2(-deltaX, deltaZ)));
    }

    private static MovementSector classifySector(double signedAngle, double absoluteAngle) {
        if (absoluteAngle < 22.5D) {
            return MovementSector.FORWARD;
        }

        if (absoluteAngle < 67.5D) {
            return signedAngle > 0.0D ? MovementSector.FORWARD_RIGHT : MovementSector.FORWARD_LEFT;
        }

        if (absoluteAngle < 112.5D) {
            return signedAngle > 0.0D ? MovementSector.RIGHT : MovementSector.LEFT;
        }

        if (absoluteAngle < 157.5D) {
            return signedAngle > 0.0D ? MovementSector.BACKWARD_RIGHT : MovementSector.BACKWARD_LEFT;
        }

        return MovementSector.BACKWARD;
    }

    private static double surfaceScore(World world, double x, double y, double z) {
        int bx = floor(x);
        int by = floor(y);
        int bz = floor(z);

        for (int dy = 0; dy >= -DEPTH; dy--) {
            int py = by + dy;

            if (isBlocking(world, bx, py, bz)) {
                return py + 1.0D;
            }
        }

        return by - DEPTH;
    }

    private static boolean isBlocking(World world, int x, int y, int z) {
        String type = world.getBlockAt(x, y, z).getType().name();

        if (MaterialType.isMaterial(type, MaterialType.AIR)) return false;
        if (MaterialType.isMaterial(type, MaterialType.LIQUID)) return false;
        if (MaterialType.isMaterial(type, MaterialType.TRANSPARENT)) return false;

        if (MaterialType.isMaterial(type, MaterialType.HALF_BLOCK)) return true;
        if (MaterialType.isMaterial(type, MaterialType.PANE)) return true;
        if (MaterialType.isMaterial(type, MaterialType.FENCE)) return true;
        if (MaterialType.isMaterial(type, MaterialType.WALL)) return true;
        if (MaterialType.isMaterial(type, MaterialType.DOOR)) return true;
        if (MaterialType.isMaterial(type, MaterialType.TRAPDOOR)) return true;

        return true;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}