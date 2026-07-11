package me.arrow.utils.custom.materials;


import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PEMaterials {

    private static final Method BLOCK_COLLISION_SHAPE = findNoArgMethod(Block.class, "getCollisionShape");
    private static final Method VOXEL_BOUNDING_BOXES = findNoArgMethod("org.bukkit.util.VoxelShape", "getBoundingBoxes");
    private static volatile Method shapeBoundingBoxes;
    private static volatile Class<?> boundingBoxClass;
    private static volatile Method boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;
    private static final Map<Material, Boolean> NON_FULL_COLLISION_CACHE = new ConcurrentHashMap<>();

    private PEMaterials() {
    }

    public static WrappedBlockState fromBukkitBlock(Block block) {
        if (block == null) return null;

        /*
         * 1.13+ path.
         * Safe in try/catch so older runtimes do not kill the server.
         */
        try {
            return SpigotConversionUtil.fromBukkitBlockData(block.getBlockData());
        } catch (Throwable ignored) {
        }

        /*
         * 1.8 - 1.12 path.
         */
        try {
            return SpigotConversionUtil.fromBukkitMaterialData(block.getState().getData());
        } catch (Throwable ignored) {
        }

        return null;
    }

    public static boolean isHalfBlock(Block block) {
        return isNonFullShape(block);
    }

    public static boolean isNonFullShape(Block block) {
        if (block == null) return false;

        WrappedBlockState state = fromBukkitBlock(block);

        if (state != null) {
            return isNonFullShape(state, true);
        }

        return isNonFullShape(block.getType());
    }

    public static boolean isNonFullCollision(Block block) {
        if (block == null) return false;

        WrappedBlockState state = fromBukkitBlock(block);

        if (state != null) {
            return isNonFullShape(state, false);
        }

        return isNonFullCollision(block.getType());
    }

    public static boolean isNonFullShape(Material material) {
        if (material == null || !material.isBlock()) return false;

        String name = normalize(material.name());

        if (isAirLike(name) || isFluidLike(name)) return false;

        return isKnownCollisionNonFull(name)
                || isVisiblePassableShape(name);
    }

    public static boolean isNonFullCollision(Material material) {
        if (material == null || !material.isBlock()) return false;

        Boolean cached = NON_FULL_COLLISION_CACHE.get(material);

        if (cached != null) return cached;

        String name = normalize(material.name());

        if (isAirLike(name) || isFluidLike(name)) return false;

        boolean result = isKnownCollisionNonFull(name);
        NON_FULL_COLLISION_CACHE.put(material, result);
        return result;
    }

    public static boolean isNonFullShape(WrappedBlockState state, boolean includePassableVisualShapes) {
        if (state == null) return false;

        StateType type = state.getType();
        String name = normalize(type.getName());

        if (type.isAir() || state.isFluid() || isAirLike(name) || isFluidLike(name)) {
            return false;
        }

        /*
         * Fences/walls can exceed the normal cube shape.
         */
        if (type.exceedsCube()) {
            return true;
        }

        /*
         * Real collision partials.
         */
        if (isKnownCollisionNonFull(name)) {
            return true;
        }

        /*
         * Visual / outline shape blocks.
         * These may not always collide, but they are still weird nearby blocks
         * for movement exemptions, ray checks, ghost-block checks, etc.
         */
        return includePassableVisualShapes && isVisiblePassableShape(name);
    }

    public static boolean isNormalFullCube(WrappedBlockState state) {
        if (state == null) return false;

        StateType type = state.getType();
        String name = normalize(type.getName());

        if (type.isAir() || state.isFluid() || isAirLike(name) || isFluidLike(name)) {
            return false;
        }

        if (type.exceedsCube()) return false;
        if (isKnownCollisionNonFull(name)) return false;
        if (isVisiblePassableShape(name)) return false;

        return type.isSolid() && type.isBlocking();
    }

    public static boolean isStair(WrappedBlockState state) {
        return state != null && isStairName(normalize(state.getType().getName()));
    }

    public static boolean isSlab(WrappedBlockState state) {
        return state != null && isSlabName(normalize(state.getType().getName()));
    }

    public static boolean isFence(WrappedBlockState state) {
        return state != null && isFenceName(normalize(state.getType().getName()));
    }

    public static boolean isFenceGate(WrappedBlockState state) {
        return state != null && isFenceGateName(normalize(state.getType().getName()));
    }

    public static boolean isWall(WrappedBlockState state) {
        return state != null && isWallName(normalize(state.getType().getName()));
    }

    public static boolean isLantern(WrappedBlockState state) {
        return state != null && normalize(state.getType().getName()).endsWith("_LANTERN");
    }

    public static boolean isChain(WrappedBlockState state) {
        return state != null && normalize(state.getType().getName()).endsWith("_CHAIN");
    }

    /**
     * Returns world-space collision boxes. Modern servers use Bukkit's exact
     * voxel shape; legacy servers use state-aware approximations for the common
     * non-full blocks that affect movement support.
     */
    public static List<CollisionBounds> getCollisionBounds(Block block) {
        if (block == null) return Collections.emptyList();

        List<CollisionBounds> modern = getModernCollisionBounds(block);

        if (modern != null) {
            return modern;
        }

        return getLegacyCollisionBounds(block);
    }

    private static List<CollisionBounds> getModernCollisionBounds(Block block) {
        if (BLOCK_COLLISION_SHAPE == null) return null;

        try {
            Object shape = BLOCK_COLLISION_SHAPE.invoke(block);

            if (shape == null) return Collections.emptyList();

            Method boxesMethod = VOXEL_BOUNDING_BOXES != null ? VOXEL_BOUNDING_BOXES : shapeBoundingBoxes;

            if (boxesMethod == null || !boxesMethod.getDeclaringClass().isInstance(shape)) {
                boxesMethod = shape.getClass().getMethod("getBoundingBoxes");
                shapeBoundingBoxes = boxesMethod;
            }

            Object rawBoxes = boxesMethod.invoke(shape);

            if (!(rawBoxes instanceof Collection<?> boxes) || boxes.isEmpty()) {
                return Collections.emptyList();
            }

            List<CollisionBounds> result = new ArrayList<>(boxes.size());

            for (Object rawBox : boxes) {
                CollisionBounds bounds = readBoundingBox(block, rawBox);

                if (bounds != null) {
                    result.add(bounds);
                }
            }

            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CollisionBounds readBoundingBox(Block block, Object rawBox) throws Exception {
        if (rawBox == null) return null;

        Class<?> type = rawBox.getClass();

        if (boundingBoxClass != type) {
            boxMinX = type.getMethod("getMinX");
            boxMinY = type.getMethod("getMinY");
            boxMinZ = type.getMethod("getMinZ");
            boxMaxX = type.getMethod("getMaxX");
            boxMaxY = type.getMethod("getMaxY");
            boxMaxZ = type.getMethod("getMaxZ");
            boundingBoxClass = type;
        }

        double minX = ((Number) boxMinX.invoke(rawBox)).doubleValue();
        double minY = ((Number) boxMinY.invoke(rawBox)).doubleValue();
        double minZ = ((Number) boxMinZ.invoke(rawBox)).doubleValue();
        double maxX = ((Number) boxMaxX.invoke(rawBox)).doubleValue();
        double maxY = ((Number) boxMaxY.invoke(rawBox)).doubleValue();
        double maxZ = ((Number) boxMaxZ.invoke(rawBox)).doubleValue();

        boolean localCoordinates = minX >= -1.0E-6D && maxX <= 1.000001D
                && minZ >= -1.0E-6D && maxZ <= 1.000001D;

        if (localCoordinates) {
            minX += block.getX();
            maxX += block.getX();
            minY += block.getY();
            maxY += block.getY();
            minZ += block.getZ();
            maxZ += block.getZ();
        }

        return new CollisionBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static List<CollisionBounds> getLegacyCollisionBounds(Block block) {
        Material material = block.getType();
        String name = normalize(material.name());
        int data = getLegacyData(block);

        if (isAirLike(name) || isFluidLike(name)) return Collections.emptyList();

        if (isSlabName(name)) {
            if (name.contains("DOUBLE")) return single(block, 0, 0, 0, 1, 1, 1);
            boolean top = (data & 0x8) != 0;
            return single(block, 0, top ? 0.5D : 0, 0, 1, top ? 1 : 0.5D, 1);
        }

        if (isStairName(name)) {
            boolean upsideDown = (data & 0x4) != 0;
            int direction = data & 0x3;
            List<CollisionBounds> boxes = new ArrayList<>(2);

            if (upsideDown) {
                boxes.add(bounds(block, 0, 0.5D, 0, 1, 1, 1));
            } else {
                boxes.add(bounds(block, 0, 0, 0, 1, 0.5D, 1));
            }

            double minY = upsideDown ? 0 : 0.5D;
            double maxY = upsideDown ? 0.5D : 1;

            switch (direction) {
                case 0 -> boxes.add(bounds(block, 0.5D, minY, 0, 1, maxY, 1));
                case 1 -> boxes.add(bounds(block, 0, minY, 0, 0.5D, maxY, 1));
                case 2 -> boxes.add(bounds(block, 0, minY, 0.5D, 1, maxY, 1));
                default -> boxes.add(bounds(block, 0, minY, 0, 1, maxY, 0.5D));
            }

            return boxes;
        }

        if (name.endsWith("_PANE") || name.contains("BARS") || name.equals("THIN_GLASS") || name.equals("IRON_FENCE")) {
            return getLegacyPaneBounds(block);
        }

        if (isFenceName(name)) {
            return cross(block, 0.375D, 0.625D, 1.5D);
        }

        if (isWallName(name)) {
            return cross(block, 0.25D, 0.75D, 1.5D);
        }

        if (isFenceGateName(name)) {
            if ((data & 0x4) != 0) return Collections.emptyList();
            return (data & 0x1) == 0
                    ? single(block, 0, 0, 0.375D, 1, 1.5D, 0.625D)
                    : single(block, 0.375D, 0, 0, 0.625D, 1.5D, 1);
        }

        if (name.equals("TRAP_DOOR") || name.endsWith("_TRAPDOOR")) {
            boolean open = (data & 0x4) != 0;

            if (!open) {
                boolean top = (data & 0x8) != 0;
                return single(block, 0, top ? 0.8125D : 0, 0, 1, top ? 1 : 0.1875D, 1);
            }

            return switch (data & 0x3) {
                case 0 -> single(block, 0, 0, 0.8125D, 1, 1, 1);
                case 1 -> single(block, 0, 0, 0, 1, 1, 0.1875D);
                case 2 -> single(block, 0.8125D, 0, 0, 1, 1, 1);
                default -> single(block, 0, 0, 0, 0.1875D, 1, 1);
            };
        }

        if (name.endsWith("_CARPET") || name.equals("CARPET")) {
            return single(block, 0, 0, 0, 1, 0.0625D, 1);
        }

        if (name.endsWith("_PRESSURE_PLATE") || name.endsWith("_PLATE")) {
            return single(block, 0.0625D, 0, 0.0625D, 0.9375D, 0.0625D, 0.9375D);
        }

        if (name.equals("SNOW") || name.equals("SNOW_LAYER")) {
            return single(block, 0, 0, 0, 1, Math.max(0.125D, ((data & 0x7) + 1) / 8.0D), 1);
        }

        if (name.equals("CAULDRON") || name.endsWith("_CAULDRON")) {
            List<CollisionBounds> boxes = new ArrayList<>(5);
            boxes.add(bounds(block, 0, 0, 0, 1, 0.3125D, 1));
            boxes.add(bounds(block, 0, 0.3125D, 0, 0.125D, 1, 1));
            boxes.add(bounds(block, 0.875D, 0.3125D, 0, 1, 1, 1));
            boxes.add(bounds(block, 0.125D, 0.3125D, 0, 0.875D, 1, 0.125D));
            boxes.add(bounds(block, 0.125D, 0.3125D, 0.875D, 0.875D, 1, 1));
            return boxes;
        }

        if (name.contains("CHEST")) return single(block, 0.0625D, 0, 0.0625D, 0.9375D, 0.875D, 0.9375D);
        if (name.equals("MUD")) return single(block, 0, 0, 0, 1, 0.875D, 1);
        if (name.equals("HONEY_BLOCK")) return single(block, 0, 0, 0, 1, 0.9375D, 1);
        if (name.equals("DRIED_GHAST")) return single(block, 0.0625D, 0, 0.0625D, 0.9375D, 0.5D, 0.9375D);
        if (name.equals("HEAVY_CORE")) return single(block, 0.25D, 0, 0.25D, 0.75D, 0.5D, 0.75D);
        if (name.contains("ENCHANT")) return single(block, 0, 0, 0, 1, 0.75D, 1);
        if (name.contains("PORTAL_FRAME")) return single(block, 0, 0, 0, 1, 0.8125D, 1);
        if (name.contains("DAYLIGHT")) return single(block, 0, 0, 0, 1, 0.375D, 1);
        if (name.equals("FARMLAND") || name.equals("SOIL")) return single(block, 0, 0, 0, 1, 0.9375D, 1);
        if (name.equals("SOUL_SAND")) return single(block, 0, 0, 0, 1, 0.875D, 1);
        if (name.equals("CAKE") || name.equals("CAKE_BLOCK")) return single(block, 0.0625D, 0, 0.0625D, 0.9375D, 0.5D, 0.9375D);
        if (name.equals("BED") || name.endsWith("_BED")) return single(block, 0, 0, 0, 1, 0.5625D, 1);
        if (name.contains("FLOWER_POT") || name.startsWith("POTTED_")) return single(block, 0.3125D, 0, 0.3125D, 0.6875D, 0.375D, 0.6875D);
        if (name.equals("CACTUS")) return single(block, 0.0625D, 0, 0.0625D, 0.9375D, 0.9375D, 0.9375D);

        if (name.equals("BIG_DRIPLEAF")) {
            List<CollisionBounds> boxes = new ArrayList<>(2);
            boxes.add(bounds(block, 0, 0.6875D, 0, 1, 0.9375D, 1));
            boxes.add(bounds(block, 0.375D, 0, 0.375D, 0.625D, 0.6875D, 0.625D));
            return boxes;
        }

        if (name.contains("ANVIL")) {
            return (data & 0x1) == 0
                    ? single(block, 0.125D, 0, 0, 0.875D, 1, 1)
                    : single(block, 0, 0, 0.125D, 1, 1, 0.875D);
        }

        if (!material.isSolid() && !isKnownCollisionNonFull(name)) {
            return Collections.emptyList();
        }

        return single(block, 0, 0, 0, 1, 1, 1);
    }

    private static List<CollisionBounds> cross(Block block, double min, double max, double height) {
        List<CollisionBounds> boxes = new ArrayList<>(2);
        boxes.add(bounds(block, 0, 0, min, 1, height, max));
        boxes.add(bounds(block, min, 0, 0, max, height, 1));
        return boxes;
    }

    private static List<CollisionBounds> getLegacyPaneBounds(Block block) {
        final double min = 0.4375D;
        final double max = 0.5625D;

        boolean west = connectsPane(block.getRelative(-1, 0, 0));
        boolean east = connectsPane(block.getRelative(1, 0, 0));
        boolean north = connectsPane(block.getRelative(0, 0, -1));
        boolean south = connectsPane(block.getRelative(0, 0, 1));

        List<CollisionBounds> boxes = new ArrayList<>(3);

        if (west || east) {
            boxes.add(bounds(block, west ? 0.0D : min, 0, min, east ? 1.0D : max, 1, max));
        }

        if (north || south) {
            boxes.add(bounds(block, min, 0, north ? 0.0D : min, max, 1, south ? 1.0D : max));
        }

        if (boxes.isEmpty()) {
            boxes.add(bounds(block, min, 0, min, max, 1, max));
        }

        return boxes;
    }

    private static boolean connectsPane(Block neighbor) {
        if (neighbor == null || neighbor.getType() == null) return false;

        Material material = neighbor.getType();
        String name = normalize(material.name());

        return material.isSolid()
                || name.endsWith("_PANE")
                || name.contains("BARS")
                || name.equals("THIN_GLASS")
                || name.equals("IRON_FENCE");
    }

    private static List<CollisionBounds> single(Block block,
                                                double minX, double minY, double minZ,
                                                double maxX, double maxY, double maxZ) {
        return Collections.singletonList(bounds(block, minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static CollisionBounds bounds(Block block,
                                          double minX, double minY, double minZ,
                                          double maxX, double maxY, double maxZ) {
        return new CollisionBounds(
                block.getX() + minX,
                block.getY() + minY,
                block.getZ() + minZ,
                block.getX() + maxX,
                block.getY() + maxY,
                block.getZ() + maxZ
        );
    }

    private static int getLegacyData(Block block) {
        try {
            Method method = block.getClass().getMethod("getData");
            Object value = method.invoke(block);
            return value instanceof Number ? ((Number) value).intValue() & 0xFF : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findNoArgMethod(String className, String name) {
        try {
            return findNoArgMethod(Class.forName(className), name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static final class CollisionBounds {
        public final double minX, minY, minZ, maxX, maxY, maxZ;

        public CollisionBounds(double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    private static boolean isKnownCollisionNonFull(String name) {
        if (name == null) return false;

        return isStairName(name)
                || isSlabName(name)
                || isFenceName(name)
                || isFenceGateName(name)
                || isWallName(name)
                || name.endsWith("_PANE")
                || name.endsWith("_BARS")
                || name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_CARPET")
                || name.endsWith("_LANTERN")
                || name.endsWith("_CHAIN")
                || name.endsWith("_CANDLE")
                || name.endsWith("_CANDLE_CAKE")
                || name.endsWith("_SIGN")
                || name.endsWith("_WALL_SIGN")
                || name.endsWith("_HANGING_SIGN")
                || name.endsWith("_WALL_HANGING_SIGN")
                || name.endsWith("_SKULL")
                || name.endsWith("_WALL_SKULL")
                || name.endsWith("_HEAD")
                || name.endsWith("_WALL_HEAD")
                || name.equals("IRON_BARS")
                || name.equals("IRON_FENCE")
                || name.equals("THIN_GLASS")
                || name.equals("STAINED_GLASS_PANE")
                || name.equals("CAULDRON")
                || name.endsWith("_CAULDRON")
                || name.equals("COMPOSTER")
                || name.contains("CHEST")
                || name.equals("MUD")
                || name.equals("HONEY_BLOCK")
                || name.equals("DRIED_GHAST")
                || name.equals("HEAVY_CORE")
                || name.equals("ENCHANTING_TABLE")
                || name.equals("ENCHANTMENT_TABLE")
                || name.equals("END_PORTAL_FRAME")
                || name.equals("ENDER_PORTAL_FRAME")
                || name.equals("DAYLIGHT_DETECTOR")
                || name.equals("DAYLIGHT_DETECTOR_INVERTED")
                || name.equals("LECTERN")
                || name.equals("HOPPER")
                || name.equals("GRINDSTONE")
                || name.equals("STONECUTTER")
                || name.equals("BELL")
                || name.equals("ANVIL")
                || name.equals("CHIPPED_ANVIL")
                || name.equals("DAMAGED_ANVIL")
                || name.equals("FARMLAND")
                || name.equals("SOUL_SAND")
                || name.equals("SNOW")
                || name.equals("LILY_PAD")
                || name.equals("WATER_LILY")
                || name.equals("END_ROD")
                || name.equals("SEA_PICKLE")
                || name.equals("POINTED_DRIPSTONE")
                || name.equals("AMETHYST_CLUSTER")
                || name.equals("SMALL_AMETHYST_BUD")
                || name.equals("MEDIUM_AMETHYST_BUD")
                || name.equals("LARGE_AMETHYST_BUD")
                || name.equals("CAMPFIRE")
                || name.equals("SOUL_CAMPFIRE")
                || name.equals("SCAFFOLDING")
                || name.equals("FLOWER_POT")
                || name.startsWith("POTTED_")
                || name.equals("CAKE")
                || name.equals("CAKE_BLOCK")
                || name.equals("CACTUS")
                || name.equals("BIG_DRIPLEAF")
                || name.equals("BED")
                || name.endsWith("_BED");
    }

    private static boolean isVisiblePassableShape(String name) {
        if (name == null) return false;

        return name.equals("NETHER_PORTAL")
                || name.equals("END_PORTAL")
                || name.equals("END_GATEWAY")
                || name.equals("LIGHT")
                || name.equals("STRUCTURE_VOID")
                || name.equals("TORCH")
                || name.endsWith("_TORCH")
                || name.endsWith("_WALL_TORCH")
                || name.equals("FIRE")
                || name.equals("SOUL_FIRE")
                || name.equals("REDSTONE")
                || name.equals("REDSTONE_WIRE")
                || name.equals("LEVER")
                || name.endsWith("_BUTTON")
                || name.equals("STONE_BUTTON")
                || name.equals("WOOD_BUTTON")
                || name.equals("RAIL")
                || name.endsWith("_RAIL")
                || name.equals("TRIPWIRE")
                || name.equals("TRIPWIRE_HOOK")
                || name.equals("VINE")
                || name.equals("VINES")
                || name.endsWith("_VINES")
                || name.endsWith("_VINES_PLANT");
    }

    private static boolean isStairName(String name) {
        return name != null
                && (name.endsWith("_STAIRS")
                || name.equals("WOOD_STAIRS")
                || name.equals("SMOOTH_STAIRS"));
    }

    private static boolean isSlabName(String name) {
        return name != null
                && (name.endsWith("_SLAB")
                || name.equals("STEP")
                || name.equals("DOUBLE_STEP")
                || name.equals("STONE_SLAB2")
                || name.equals("DOUBLE_STONE_SLAB")
                || name.equals("DOUBLE_STONE_SLAB2")
                || name.equals("WOOD_STEP")
                || name.equals("WOODEN_SLAB")
                || name.equals("WOOD_DOUBLE_STEP"));
    }

    private static boolean isFenceName(String name) {
        return name != null
                && (name.endsWith("_FENCE")
                || name.equals("FENCE")
                || name.equals("NETHER_FENCE")
                || name.equals("IRON_FENCE"));
    }

    private static boolean isFenceGateName(String name) {
        return name != null
                && (name.endsWith("_FENCE_GATE")
                || name.equals("FENCE_GATE"));
    }

    private static boolean isWallName(String name) {
        return name != null
                && (name.endsWith("_WALL")
                || name.equals("COBBLE_WALL"));
    }

    private static boolean isAirLike(String name) {
        return name == null
                || name.equals("AIR")
                || name.equals("CAVE_AIR")
                || name.equals("VOID_AIR")
                || name.equals("LEGACY_AIR");
    }

    private static boolean isFluidLike(String name) {
        return name != null && (
                name.equals("WATER")
                        || name.equals("LAVA")
                        || name.equals("STATIONARY_WATER")
                        || name.equals("STATIONARY_LAVA")
                        || name.equals("BUBBLE_COLUMN")
        );
    }

    private static String normalize(String raw) {
        if (raw == null) return null;

        String name = raw.trim();

        if (name.isEmpty()) return null;

        int namespace = name.indexOf(':');

        if (namespace != -1) {
            name = name.substring(namespace + 1);
        }

        return name.toUpperCase(Locale.ROOT);
    }
}
