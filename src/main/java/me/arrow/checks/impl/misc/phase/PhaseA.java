package me.arrow.checks.impl.misc.phase;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.MaterialType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Experimental
public class PhaseA extends Check {

    private static final double PLAYER_HALF_WIDTH = 0.2985D;
    private static final double PLAYER_HEIGHT = 1.798D;
    private static final double COLLISION_EPSILON = 0.0015D;

    private static final double MAX_GROUND_PHASE_SPEED = 0.17D;
    private static final double MAX_AIR_PHASE_SPEED = 0.36D;
    private static final double MAX_VERTICAL_PHASE_SPEED = 8.0D;

    private static final double TRACE_STEP = 0.025D;
    private static final double MIN_HORIZONTAL_MOVE = 0.001D;
    private static final double MIN_VERTICAL_MOVE = 0.003D;

    private double threshold;

    public PhaseA(Profile profile) {
        super(profile, CheckType.PHASE, "A", "Checks if the player is clipping through a block");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isMovement(event)) return;

        if (profile == null || profile.getPlayer() == null || profile.getMovementData() == null) {
            return;
        }

        Player player = profile.getPlayer();

        if (!player.isOnline()) {
            return;
        }

        if (profile.getTick() < 120 || profile.shouldCancel()) {
            decay();
            return;
        }

        try {
            if (profile.isExempt().isTeleports()) {
                decay();
                return;
            }
        } catch (Throwable ignored) {
        }

        if (player.isInsideVehicle() || player.getAllowFlight()) {
            decay();
            return;
        }

        CustomLocation to = profile.getMovementData().getLocation();
        CustomLocation rawFrom = profile.getMovementData().getLastLocation();

        if (rawFrom == null || to == null || rawFrom.getWorld() == null || to.getWorld() == null) {
            decay();
            return;
        }

        if (!rawFrom.getWorld().equals(to.getWorld())) {
            decay();
            return;
        }

        CustomLocation from = getTraceStart(rawFrom, to);

        double deltaX = to.getX() - from.getX();
        double deltaY = to.getY() - from.getY();
        double deltaZ = to.getZ() - from.getZ();
        double deltaXZ = Math.hypot(deltaX, deltaZ);

        if (deltaXZ < MIN_HORIZONTAL_MOVE && Math.abs(deltaY) < MIN_VERTICAL_MOVE) {
            decay();
            return;
        }

        boolean ground = profile.getMovementData().isOnGround() || profile.getMovementData().isServerGround();

        if (ground && deltaXZ > MAX_GROUND_PHASE_SPEED) {
            decay();
            return;
        }

        if (!ground && deltaXZ > MAX_AIR_PHASE_SPEED) {
            decay();
            return;
        }

        if (Math.abs(deltaY) > MAX_VERTICAL_PHASE_SPEED) {
            decay();
            return;
        }

        DesyncResult desync = getClientWorldDesync();

        if (desync.shouldExempt()) {
            syncDesync(desync);

            if (desync.shouldSetback()) {
                profile.getMovementData().getSetbackProcessor().causeSetBack(this.getClass().getSimpleName());
            }

            decay();
            return;
        }

        if (isGhostBlockExempt()) {
            decay();
            return;
        }

        PhaseHit hit = null;

        if (deltaXZ >= MIN_HORIZONTAL_MOVE) {
            hit = traceHorizontalPhase(from, to);
        }

        if (hit == null && Math.abs(deltaY) >= MIN_VERTICAL_MOVE) {
            hit = traceVerticalPhase(from, to);
        }

        if (hit == null) {
            decay();
            return;
        }

        if (isRecentlyDesyncedNear(hit.x, hit.y, hit.z)) {
            decay();
            return;
        }

        if (++threshold > 2.0D) {
            fail("Clipping through block",
                    "block " + MsgType.MAIN_THEME_COLOR.getMessage() + hit.material.name()
                            + "\nxyz " + MsgType.MAIN_THEME_COLOR.getMessage() + hit.x + ", " + hit.y + ", " + hit.z
                            + "\ntype " + MsgType.MAIN_THEME_COLOR.getMessage() + hit.type
                            + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                            + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + ground
                            + "\nstep " + MsgType.MAIN_THEME_COLOR.getMessage() + hit.step);

            profile.getMovementData().getSetbackProcessor().causeSetBack(this.getClass().getSimpleName());
        }

        verbose(this.getClass().getSimpleName(), threshold, 2.0D,
                "block " + hit.material.name()
                        + "\nxyz " + hit.x + ", " + hit.y + ", " + hit.z
                        + "\ntype " + hit.type
                        + "\ndeltaXZ " + deltaXZ
                        + "\ndeltaY " + deltaY);
    }

    private PhaseHit traceHorizontalPhase(CustomLocation from, CustomLocation to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        double distance = Math.hypot(dx, dz);
        int steps = Math.max(1, Math.min(32, (int) Math.ceil(distance / TRACE_STEP)));

        World world = to.getWorld();
        if (world == null) return null;

        Box startBox = playerBox(from.getX(), from.getY(), from.getZ());

        for (int i = 1; i <= steps; i++) {
            double progress = i / (double) steps;

            double x = from.getX() + (dx * progress);
            double y = from.getY() + (dy * progress);
            double z = from.getZ() + (dz * progress);

            Box playerBox = playerBox(x, y, z);

            int minX = floor(playerBox.minX) - 1;
            int maxX = floor(playerBox.maxX) + 1;
            int minY = floor(playerBox.minY);
            int maxY = floor(playerBox.maxY);
            int minZ = floor(playerBox.minZ) - 1;
            int maxZ = floor(playerBox.maxZ) + 1;

            for (int bx = minX; bx <= maxX; bx++) {
                for (int by = minY; by <= maxY; by++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        Material material = getServerMaterial(world, bx, by, bz);

                        if (!isPhaseCollidable(material)) {
                            continue;
                        }

                        Block block = world.getBlockAt(bx, by, bz);
                        List<Box> blockBoxes = getBlockBoxes(block);

                        for (Box blockBox : blockBoxes) {
                            if (!playerBox.intersects(blockBox)) {
                                continue;
                            }

                            if (startBox.intersects(blockBox)) {
                                continue;
                            }

                            if (!isMovingIntoBlockHorizontally(startBox, playerBox, blockBox, dx, dz)) {
                                continue;
                            }

                            return new PhaseHit(bx, by, bz, material, i, "HORIZONTAL");
                        }
                    }
                }
            }
        }

        return null;
    }

    private PhaseHit traceVerticalPhase(CustomLocation from, CustomLocation to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        World world = to.getWorld();
        if (world == null) return null;

        double distance = Math.abs(dy);
        int steps = Math.max(1, Math.min(32, (int) Math.ceil(distance / TRACE_STEP)));

        Box startBox = playerBox(from.getX(), from.getY(), from.getZ());

        for (int i = 1; i <= steps; i++) {
            double progress = i / (double) steps;

            double x = from.getX() + (dx * progress);
            double y = from.getY() + (dy * progress);
            double z = from.getZ() + (dz * progress);

            Box playerBox = playerBox(x, y, z);

            int minX = floor(playerBox.minX);
            int maxX = floor(playerBox.maxX);
            int minY = floor(playerBox.minY) - 1;
            int maxY = floor(playerBox.maxY) + 1;
            int minZ = floor(playerBox.minZ);
            int maxZ = floor(playerBox.maxZ);

            for (int bx = minX; bx <= maxX; bx++) {
                for (int by = minY; by <= maxY; by++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        Material material = getServerMaterial(world, bx, by, bz);

                        if (!isPhaseCollidable(material)) {
                            continue;
                        }

                        Block block = world.getBlockAt(bx, by, bz);
                        List<Box> blockBoxes = getBlockBoxes(block);

                        for (Box blockBox : blockBoxes) {
                            if (!playerBox.intersects(blockBox)) {
                                continue;
                            }

                            if (startBox.intersects(blockBox)) {
                                continue;
                            }

                            if (!horizontallyOverlaps(playerBox, blockBox)) {
                                continue;
                            }

                            if (!isMovingIntoBlockVertically(startBox, playerBox, blockBox, dy)) {
                                continue;
                            }

                            return new PhaseHit(bx, by, bz, material, i, dy > 0.0D ? "VERTICAL_UP" : "VERTICAL_DOWN");
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isMovingIntoBlockHorizontally(Box from, Box to, Box block, double dx, double dz) {
        boolean verticalOverlap = to.maxY > block.minY + COLLISION_EPSILON
                && to.minY < block.maxY - COLLISION_EPSILON;

        if (!verticalOverlap) {
            return false;
        }

        boolean crossingXPositive = dx > 0.0D
                && from.maxX <= block.minX + COLLISION_EPSILON
                && to.maxX > block.minX + COLLISION_EPSILON;

        boolean crossingXNegative = dx < 0.0D
                && from.minX >= block.maxX - COLLISION_EPSILON
                && to.minX < block.maxX - COLLISION_EPSILON;

        boolean crossingZPositive = dz > 0.0D
                && from.maxZ <= block.minZ + COLLISION_EPSILON
                && to.maxZ > block.minZ + COLLISION_EPSILON;

        boolean crossingZNegative = dz < 0.0D
                && from.minZ >= block.maxZ - COLLISION_EPSILON
                && to.minZ < block.maxZ - COLLISION_EPSILON;

        return crossingXPositive || crossingXNegative || crossingZPositive || crossingZNegative;
    }

    private boolean isMovingIntoBlockVertically(Box from, Box to, Box block, double dy) {
        if (dy > 0.0D) {
            return from.maxY <= block.minY + COLLISION_EPSILON
                    && to.maxY > block.minY + COLLISION_EPSILON;
        }

        if (dy < 0.0D) {
            return from.minY >= block.maxY - COLLISION_EPSILON
                    && to.minY < block.maxY - COLLISION_EPSILON;
        }

        return false;
    }

    private boolean horizontallyOverlaps(Box playerBox, Box blockBox) {
        return playerBox.maxX > blockBox.minX + COLLISION_EPSILON
                && playerBox.minX < blockBox.maxX - COLLISION_EPSILON
                && playerBox.maxZ > blockBox.minZ + COLLISION_EPSILON
                && playerBox.minZ < blockBox.maxZ - COLLISION_EPSILON;
    }

    private Box playerBox(double x, double y, double z) {
        return new Box(
                x - PLAYER_HALF_WIDTH,
                y + 0.001D,
                z - PLAYER_HALF_WIDTH,
                x + PLAYER_HALF_WIDTH,
                y + PLAYER_HEIGHT,
                z + PLAYER_HALF_WIDTH
        );
    }

    private List<Box> getBlockBoxes(Block block) {
        if (block == null) {
            return Collections.emptyList();
        }

        Material material = block.getType();

        if (!isPhaseCollidable(material)) {
            return Collections.emptyList();
        }

        if (isThinPane(material)) {
            return getThinPaneBoxes(block);
        }

        if (!isFullCubeCandidate(material)) {
            return Collections.emptyList();
        }

        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        return Collections.singletonList(new Box(
                x,
                y,
                z,
                x + 1.0D,
                y + 1.0D,
                z + 1.0D
        ));
    }

    private List<Box> getThinPaneBoxes(Block block) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        double min = 0.4375D;
        double max = 0.5625D;

        List<Box> boxes = new ArrayList<>(2);

        boxes.add(new Box(
                x + min,
                y,
                z,
                x + max,
                y + 1.0D,
                z + 1.0D
        ));

        boxes.add(new Box(
                x,
                y,
                z + min,
                x + 1.0D,
                y + 1.0D,
                z + max
        ));

        return boxes;
    }

    private boolean isPhaseCollidable(Material material) {
        return isThinPane(material) || isFullCubeCandidate(material);
    }

    private boolean isThinPane(Material material) {
        if (material == null || isAirLike(material)) {
            return false;
        }

        String name = material.name();

        return name.contains("PANE")
                || name.contains("IRON_BARS")
                || name.contains("THIN_GLASS");
    }

    private boolean isFullCubeCandidate(Material material) {
        if (material == null || isAirLike(material)) {
            return false;
        }

        String name = material.name();

        if (MaterialType.isMaterial(name, MaterialType.LIQUID)) return false;
        if (MaterialType.isMaterial(name, MaterialType.WEB)) return false;
        if (MaterialType.isMaterial(name, MaterialType.BUBBLE)) return false;
        if (MaterialType.isMaterial(name, MaterialType.CLIMBABLE)) return false;

        if (name.contains("WATER")) return false;
        if (name.contains("LAVA")) return false;
        if (name.contains("WEB")) return false;
        if (name.contains("COBWEB")) return false;
        if (name.contains("RAIL")) return false;
        if (name.contains("CARPET")) return false;
        if (name.contains("SNOW")) return false;
        if (name.contains("TORCH")) return false;
        if (name.contains("BUTTON")) return false;
        if (name.contains("PRESSURE_PLATE")) return false;
        if (name.contains("REDSTONE")) return false;
        if (name.contains("SIGN")) return false;
        if (name.contains("BANNER")) return false;
        if (name.contains("SAPLING")) return false;
        if (name.contains("FLOWER")) return false;
        if (name.contains("GRASS")) return false;
        if (name.contains("MUSHROOM")) return false;
        if (name.contains("STAIRS")) return false;
        if (name.contains("SLAB")) return false;
        if (name.contains("STEP")) return false;
        if (name.contains("FENCE")) return false;
        if (name.contains("WALL")) return false;
        if (name.contains("DOOR")) return false;
        if (name.contains("TRAPDOOR")) return false;
        if (name.contains("GATE")) return false;
        if (name.contains("LADDER")) return false;
        if (name.contains("VINE")) return false;
        if (name.contains("CHEST")) return false;
        if (name.contains("BED")) return false;
        if (name.contains("CACTUS")) return false;
        if (name.contains("CAKE")) return false;
        if (name.contains("ANVIL")) return false;
        if (name.contains("MOVING_PISTON")) return false;
        if (name.contains("PISTON_HEAD")) return false;
        if (name.contains("PISTON_EXTENSION")) return false;

        try {
            return material.isSolid();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private CustomLocation getTraceStart(CustomLocation fallback, CustomLocation to) {
        try {
            List<CustomLocation> locations = profile.getMovementData().getPastLocations();

            if (locations == null || locations.size() < 2) {
                return fallback;
            }

            int checked = 0;

            for (int i = locations.size() - 2; i >= 0 && checked < 3; i--, checked++) {
                CustomLocation past = locations.get(i);

                if (past == null || past.getWorld() == null || !past.getWorld().equals(to.getWorld())) {
                    continue;
                }

                double dx = to.getX() - past.getX();
                double dy = to.getY() - past.getY();
                double dz = to.getZ() - past.getZ();

                if (Math.abs(dy) > MAX_VERTICAL_PHASE_SPEED) {
                    continue;
                }

                if (Math.hypot(dx, dz) > 0.55D) {
                    continue;
                }

                if (Math.hypot(dx, dz) > MIN_HORIZONTAL_MOVE || Math.abs(dy) > MIN_VERTICAL_MOVE) {
                    return past;
                }
            }
        } catch (Throwable ignored) {
        }

        return fallback;
    }

    private Material getServerMaterial(World world, int x, int y, int z) {
        try {
            return Arrow.getInstance().getNmsManager().getNmsInstance2().getType(world, x, y, z);
        } catch (Throwable ignored) {
            try {
                return world.getBlockAt(x, y, z).getType();
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private boolean isGhostBlockExempt() {
        Object blockProcessor = invoke(profile, "getBlockProcessor", "getBlockData", "getWorldProcessor");

        if (blockProcessor == null) {
            return false;
        }

        return readBoolean(blockProcessor, "isNearGhostBlock")
                || readBoolean(blockProcessor, "isOnGhostBlock")
                || readBoolean(blockProcessor, "isInsideGhostBlock")
                || readBoolean(blockProcessor, "isUnderGhostBlock")
                || readBoolean(blockProcessor, "isInteractingGhostBlock");
    }

    private DesyncResult getClientWorldDesync() {
        Object tracker = invoke(profile, "getClientWorldTracker");

        if (tracker == null) {
            return DesyncResult.EMPTY;
        }

        Object result = invoke(tracker, "scanPlayerCollision");

        if (result == null) {
            return DesyncResult.EMPTY;
        }

        DesyncResult desync = new DesyncResult();

        desync.nearGhostBlock = readBoolean(result, "nearGhostBlock");
        desync.onGhostBlock = readBoolean(result, "onGhostBlock");
        desync.insideGhostBlock = readBoolean(result, "insideGhostBlock");
        desync.underGhostBlock = readBoolean(result, "underGhostBlock");
        desync.interactingGhostBlock = readBoolean(result, "interactingGhostBlock");

        desync.clientOnlyBlock = readBoolean(result, "clientOnlyBlock");
        desync.serverOnlyBlock = readBoolean(result, "serverOnlyBlock");
        desync.insideServerOnlyBlock = readBoolean(result, "insideServerOnlyBlock");
        desync.physicsMismatch = readBoolean(result, "physicsMismatch");
        desync.pendingLagCompensated = readBoolean(result, "pendingLagCompensated");
        desync.unknownClientChunk = readBoolean(result, "unknownClientChunk");

        desync.rawResult = result;
        desync.tracker = tracker;

        return desync;
    }

    private void syncDesync(DesyncResult desync) {
        if (desync == null || desync.tracker == null || desync.rawResult == null) {
            return;
        }

        try {
            Method method = desync.tracker.getClass().getMethod("syncCollisionArea", desync.rawResult.getClass());
            method.invoke(desync.tracker, desync.rawResult);
        } catch (Throwable ignored) {
        }
    }

    private boolean isRecentlyDesyncedNear(int x, int y, int z) {
        Object tracker = invoke(profile, "getClientWorldTracker");

        if (tracker == null || profile.getPlayer() == null) {
            return false;
        }

        try {
            Location location = new Location(profile.getPlayer().getWorld(), x + 0.5D, y + 0.5D, z + 0.5D);

            for (Method method : tracker.getClass().getMethods()) {
                if (!method.getName().equals("hasRecentDesyncNear")) {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();

                if (params.length == 3
                        && params[0].isAssignableFrom(Location.class)
                        && (params[1] == double.class || params[1] == Double.class)
                        && (params[2] == double.class || params[2] == Double.class)) {
                    Object result = method.invoke(tracker, location, 1.5D, 2.0D);

                    if (result instanceof Boolean) {
                        return (Boolean) result;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean isAirLike(Material material) {
        if (material == null) {
            return true;
        }

        if (material == Material.AIR) {
            return true;
        }

        String name = material.name();

        return name.equals("CAVE_AIR") || name.equals("VOID_AIR");
    }

    private void decay() {
        threshold = Math.max(0.0D, threshold - 0.05D);
    }

    private Object invoke(Object object, String... methodNames) {
        if (object == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            try {
                Method method = object.getClass().getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(object);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private boolean readBoolean(Object object, String fieldOrGetter) {
        if (object == null || fieldOrGetter == null) {
            return false;
        }

        try {
            Method method = object.getClass().getMethod(fieldOrGetter);
            Object value = method.invoke(object);

            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Field field = object.getClass().getDeclaredField(fieldOrGetter);
            field.setAccessible(true);
            Object value = field.get(object);

            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static final class PhaseHit {
        private final int x;
        private final int y;
        private final int z;
        private final Material material;
        private final int step;
        private final String type;

        private PhaseHit(int x, int y, int z, Material material, int step, String type) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
            this.step = step;
            this.type = type;
        }
    }

    private static final class DesyncResult {
        private static final DesyncResult EMPTY = new DesyncResult();

        private boolean nearGhostBlock;
        private boolean onGhostBlock;
        private boolean insideGhostBlock;
        private boolean underGhostBlock;
        private boolean interactingGhostBlock;

        private boolean clientOnlyBlock;
        private boolean serverOnlyBlock;
        private boolean insideServerOnlyBlock;
        private boolean physicsMismatch;
        private boolean pendingLagCompensated;
        private boolean unknownClientChunk;

        private Object tracker;
        private Object rawResult;

        private boolean shouldExempt() {
            return nearGhostBlock
                    || onGhostBlock
                    || insideGhostBlock
                    || underGhostBlock
                    || interactingGhostBlock
                    || clientOnlyBlock
                    || serverOnlyBlock
                    || physicsMismatch
                    || pendingLagCompensated
                    || unknownClientChunk;
        }

        private boolean shouldSetback() {
            return onGhostBlock || insideServerOnlyBlock;
        }
    }

    private static final class Box {
        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;

        private Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
        }

        private boolean intersects(Box other) {
            if (other == null) {
                return false;
            }

            return this.maxX > other.minX + COLLISION_EPSILON
                    && this.minX < other.maxX - COLLISION_EPSILON
                    && this.maxY > other.minY + COLLISION_EPSILON
                    && this.minY < other.maxY - COLLISION_EPSILON
                    && this.maxZ > other.minZ + COLLISION_EPSILON
                    && this.minZ < other.maxZ - COLLISION_EPSILON;
        }
    }
}