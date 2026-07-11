package me.arrow.utils.custom.materials;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.material.MaterialData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-safe block-state and collision helper.

 * PacketEvents is used for protocol/state mapping. For a real block on a modern
 * server, Bukkit's actual voxel collision shape is used. That means unusual
 * partial colliders such as lanterns, chains, piston heads, heavy cores and new
 * future blocks do not need a hard-coded dimensions table.
 */
public class PEMaterials {

    private static final Method BLOCK_GET_BLOCK_DATA =
            findNoArgMethod(Block.class, "getBlockData");
    private static final Method BLOCK_GET_COLLISION_SHAPE =
            findNoArgMethod(Block.class, "getCollisionShape");
    private static final Method BLOCK_GET_BOUNDING_BOX =
            findNoArgMethod(Block.class, "getBoundingBox");
    private static final Method BLOCK_IS_PASSABLE =
            findNoArgMethod(Block.class, "isPassable");
    private static final Method VOXEL_GET_BOUNDING_BOXES =
            findNoArgMethod("org.bukkit.util.VoxelShape", "getBoundingBoxes");
    private static final Method MATERIAL_IS_OCCLUDING =
            findNoArgMethod(Material.class, "isOccluding");

    private static volatile Method convertBukkitBlockData;
    private static volatile Method shapeBoundingBoxes;
    private static volatile Class<?> boundingBoxClass;
    private static volatile Method boxMinX;
    private static volatile Method boxMinY;
    private static volatile Method boxMinZ;
    private static volatile Method boxMaxX;
    private static volatile Method boxMaxY;
    private static volatile Method boxMaxZ;

    private static final Map<Material, WrappedBlockState> SERVER_STATE_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<String, WrappedBlockState> CLIENT_STATE_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<Material, Boolean> POTENTIAL_COLLISION_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<Material, Boolean> NON_FULL_CACHE =
            new ConcurrentHashMap<>();

    /*
     * Local voxel boxes are cached by complete block-state string. Connection,
     * axis, facing, slab type, piston facing, hanging state, etc. are part of
     * that state string, so the cache still remains state-aware.
     */
    private static final Map<String, List<LocalCollisionBounds>> LOCAL_SHAPE_CACHE =
            new ConcurrentHashMap<>();

    private PEMaterials() {
    }

    public static WrappedBlockState fromBukkitBlock(Block block) {
        if (block == null) {
            return null;
        }

        /*
         * Reflection keeps this class loadable on 1.8-1.12 where BlockData and
         * Block#getBlockData do not exist at all.
         */
        if (BLOCK_GET_BLOCK_DATA != null) {
            try {
                Object blockData = BLOCK_GET_BLOCK_DATA.invoke(block);

                if (blockData != null) {
                    Method converter = convertBukkitBlockData;

                    if (converter == null || !converter.getParameterTypes()[0].isInstance(blockData)) {
                        for (Method method : SpigotConversionUtil.class.getMethods()) {
                            if (method.getName().equals("fromBukkitBlockData")
                                    && method.getParameterCount() == 1
                                    && method.getParameterTypes()[0].isInstance(blockData)) {
                                converter = method;
                                convertBukkitBlockData = method;
                                break;
                            }
                        }
                    }

                    if (converter != null) {
                        Object converted = converter.invoke(null, blockData);

                        if (converted instanceof WrappedBlockState) {
                            return (WrappedBlockState) converted;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            return SpigotConversionUtil.fromBukkitMaterialData(block.getState().getData());
        } catch (Throwable ignored) {
        }

        return fromBukkitMaterial(block.getType());
    }

    /**
     * Converts an authoritative server block to the recipient's own protocol
     * mapping. PacketEvents handles every Java client version; Geyser receives
     * the mapped Java block update and performs its Bedrock translation.
     */
    public static WrappedBlockState fromBukkitBlock(Block block, ClientVersion clientVersion) {
        return mapToClient(fromBukkitBlock(block), clientVersion);
    }

    @SuppressWarnings("deprecation")
    public static WrappedBlockState fromBukkitMaterial(Material material) {
        if (material == null || material == Material.AIR) {
            return null;
        }

        WrappedBlockState cached = SERVER_STATE_CACHE.get(material);

        if (cached != null) {
            return cached.clone();
        }

        WrappedBlockState state = null;

        try {
            state = SpigotConversionUtil.fromBukkitMaterialData(new MaterialData(material));
        } catch (Throwable ignored) {
        }

        if (state == null) {
            try {
                state = WrappedBlockState.getByString(toMinecraftKey(material));
            } catch (Throwable ignored) {
            }
        }

        if (state != null) {
            SERVER_STATE_CACHE.put(material, state.clone());
            return state;
        }

        return null;
    }

    public static WrappedBlockState fromBukkitMaterial(Material material, ClientVersion clientVersion) {
        if (material == null || material == Material.AIR) {
            return null;
        }

        if (clientVersion == null) {
            return fromBukkitMaterial(material);
        }

        String cacheKey = clientVersion.name() + '|' + material.name();
        WrappedBlockState cached = CLIENT_STATE_CACHE.get(cacheKey);

        if (cached != null) {
            return cached.clone();
        }

        WrappedBlockState mapped = mapToClient(fromBukkitMaterial(material), clientVersion);

        if (mapped == null) {
            try {
                mapped = WrappedBlockState.getByString(clientVersion, toMinecraftKey(material));
            } catch (Throwable ignored) {
            }
        }

        if (mapped != null) {
            CLIENT_STATE_CACHE.put(cacheKey, mapped.clone());
            return mapped;
        }

        return null;
    }

    public static WrappedBlockState mapToClient(WrappedBlockState source, ClientVersion clientVersion) {
        if (source == null) {
            return null;
        }

        if (clientVersion == null) {
            return source.clone();
        }

        try {
            WrappedBlockState mapped = WrappedBlockState.getByString(clientVersion, source.toString());

            if (!mapped.getType().isAir() || source.getType().isAir()) {
                return mapped;
            }
        } catch (Throwable ignored) {
        }

        try {
            WrappedBlockState mapped = source.getType().createBlockState(clientVersion);

            if (!mapped.getType().isAir() || source.getType().isAir()) {
                return mapped;
            }
        } catch (Throwable ignored) {
        }

        return source.clone();
    }

    /**
     * Exact for real blocks on modern servers. On legacy servers, where Bukkit
     * exposes no voxel shape, this intentionally uses a conservative potential-
     * collision fallback so a partial block is not treated as air.
     */
    public static boolean hasCollision(Block block) {
        if (block == null) {
            return false;
        }

        List<CollisionBounds> exact = getModernCollisionBounds(block);

        if (exact != null) {
            return !exact.isEmpty();
        }

        Boolean passable = isPassable(block);

        if (passable != null) {
            return !passable;
        }

        return hasPotentialCollision(block.getType());
    }

    public static boolean hasCollision(Material material) {
        return hasPotentialCollision(material);
    }

    public static boolean hasCollision(Material material, ClientVersion clientVersion) {
        if (material == null || material == Material.AIR || !material.isBlock()) {
            return false;
        }

        WrappedBlockState mapped = fromBukkitMaterial(material, clientVersion);

        if (hasCollision(mapped)) {
            return true;
        }

        /*
         * StateType#isSolid/isBlocking does not describe every small collision
         * shape. Lanterns, chains, piston heads, heavy cores and future partial
         * colliders may all report false here, so use the conservative material
         * fallback rather than incorrectly classifying them as air.
         */
        return hasPotentialCollision(material);
    }

    public static boolean hasPotentialCollision(Material material) {
        if (material == null || material == Material.AIR || !material.isBlock()) {
            return false;
        }

        Boolean cached = POTENTIAL_COLLISION_CACHE.get(material);

        if (cached != null) {
            return cached;
        }

        String name = normalize(material.name());
        boolean result;

        if (isAirLike(name) || isFluidLike(name) || isDefinitelyNoCollisionName(name)) {
            result = false;
        } else {
            WrappedBlockState state = fromBukkitMaterial(material);
            result = hasCollision(state);

            if (!result) {
                try {
                    result = material.isSolid();
                } catch (Throwable ignored) {
                    result = false;
                }
            }

            /*
             * Last resort: a placeable block that is not a known pass-through
             * family may have a small state-dependent collision box that basic
             * material flags do not expose.
             */
            if (!result) {
                result = true;
            }
        }

        POTENTIAL_COLLISION_CACHE.put(material, result);
        return result;
    }

    public static boolean hasCollision(WrappedBlockState state) {
        if (state == null) {
            return false;
        }

        StateType type = state.getType();
        String name = stateName(type);

        if (type.isAir() || state.isFluid() || isAirLike(name) || isFluidLike(name)) {
            return false;
        }

        if (type.isBlocking() || type.isSolid() || type.exceedsCube()) {
            return true;
        }

        /* PacketEvents type flags are not collision-shape flags. */
        return !isDefinitelyNoCollisionName(name) && isObviousPartialCollisionName(name);
    }

    public static boolean isReplaceable(Material material, ClientVersion clientVersion) {
        if (material == null || material == Material.AIR || !material.isBlock()) {
            return true;
        }

        WrappedBlockState state = fromBukkitMaterial(material, clientVersion);

        if (state != null) {
            try {
                return state.getType().isReplaceable();
            } catch (Throwable ignored) {
            }
        }

        return isDefinitelyNoCollisionName(normalize(material.name()));
    }

    public static boolean isHalfBlock(Block block) {
        return isNonFullShape(block);
    }

    public static boolean isNonFullShape(Block block) {
        if (block == null) {
            return false;
        }

        List<CollisionBounds> exact = getModernCollisionBounds(block);

        if (exact != null) {
            return !exact.isEmpty() && !isFullCube(block, exact);
        }

        return hasCollision(block) && isNonFullShape(block.getType());
    }

    public static boolean isNonFullCollision(Block block) {
        return isNonFullShape(block);
    }

    public static boolean isNonFullShape(Material material) {
        if (!hasPotentialCollision(material)) {
            return false;
        }

        Boolean cached = NON_FULL_CACHE.get(material);

        if (cached != null) {
            return cached;
        }

        String name = normalize(material.name());
        WrappedBlockState state = fromBukkitMaterial(material);
        boolean result = isObviousPartialCollisionName(name);

        if (!result && state != null) {
            StateType type = state.getType();
            result = type.exceedsCube() || !(type.isSolid() && type.isBlocking());
        }

        if (!result) {
            Boolean occluding = isOccluding(material);
            result = occluding != null && !occluding && !isKnownFullTransparentCube(name);
        }

        NON_FULL_CACHE.put(material, result);
        return result;
    }

    public static boolean isNonFullCollision(Material material) {
        return isNonFullShape(material);
    }

    public static boolean isNonFullShape(WrappedBlockState state, boolean includePassableVisualShapes) {
        return hasCollision(state) && !isNormalFullCube(state);
    }

    public static boolean isNormalFullCube(WrappedBlockState state) {
        if (!hasCollision(state)) {
            return false;
        }

        StateType type = state.getType();
        String name = stateName(type);

        if (type.exceedsCube() || isObviousPartialCollisionName(name)) {
            return false;
        }

        return type.isSolid() && type.isBlocking();
    }

    public static boolean isStair(WrappedBlockState state) {
        return state != null && stateName(state).endsWith("_STAIRS");
    }

    public static boolean isSlab(WrappedBlockState state) {
        if (state == null) return false;
        String name = stateName(state);
        return name.endsWith("_SLAB")
                || name.equals("STEP")
                || name.equals("DOUBLE_STEP")
                || name.equals("STONE_SLAB2")
                || name.equals("WOOD_STEP")
                || name.equals("WOODEN_SLAB");
    }

    public static boolean isFence(WrappedBlockState state) {
        if (state == null) return false;
        String name = stateName(state);
        return name.endsWith("_FENCE")
                || name.equals("FENCE")
                || name.equals("NETHER_FENCE")
                || name.equals("IRON_FENCE");
    }

    public static boolean isFenceGate(WrappedBlockState state) {
        if (state == null) return false;
        String name = stateName(state);
        return name.endsWith("_FENCE_GATE") || name.equals("FENCE_GATE");
    }

    public static boolean isWall(WrappedBlockState state) {
        if (state == null) return false;
        String name = stateName(state);
        return name.endsWith("_WALL") || name.equals("COBBLE_WALL");
    }

    public static boolean isLantern(WrappedBlockState state) {
        return state != null && (stateName(state).equals("LANTERN") || stateName(state).endsWith("_LANTERN"));
    }

    public static boolean isChain(WrappedBlockState state) {
        return state != null && (stateName(state).equals("CHAIN") || stateName(state).endsWith("_CHAIN"));
    }

    /**
     * Returns world-space collision boxes. Modern blocks use their exact live
     * voxel shape. Legacy blocks use a conservative unit box only when the
     * material can collide, preventing partial supports from becoming air.
     */
    public static List<CollisionBounds> getCollisionBounds(Block block) {
        if (block == null) {
            return Collections.emptyList();
        }

        List<CollisionBounds> modern = getModernCollisionBounds(block);

        if (modern != null) {
            return modern;
        }

        Boolean passable = isPassable(block);

        if ((passable != null && passable) || !hasPotentialCollision(block.getType())) {
            return Collections.emptyList();
        }

        return Collections.singletonList(new CollisionBounds(
                block.getX(), block.getY(), block.getZ(),
                block.getX() + 1.0D, block.getY() + 1.0D, block.getZ() + 1.0D
        ));
    }

    public static boolean intersectsCollision(Block block,
                                              double minX, double minY, double minZ,
                                              double maxX, double maxY, double maxZ) {
        for (CollisionBounds bounds : getCollisionBounds(block)) {
            if (bounds.intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }
        }

        return false;
    }

    private static List<CollisionBounds> getModernCollisionBounds(Block block) {
        if (BLOCK_GET_COLLISION_SHAPE == null) {
            return getModernSingleBoundingBox(block);
        }

        try {
            String stateKey = collisionStateKey(block);
            List<LocalCollisionBounds> cached = LOCAL_SHAPE_CACHE.get(stateKey);

            if (cached != null) {
                return toWorldBounds(block, cached);
            }

            Object shape = BLOCK_GET_COLLISION_SHAPE.invoke(block);

            if (shape == null) {
                LOCAL_SHAPE_CACHE.put(stateKey, Collections.emptyList());
                return Collections.emptyList();
            }

            Method boxesMethod = VOXEL_GET_BOUNDING_BOXES != null
                    ? VOXEL_GET_BOUNDING_BOXES
                    : shapeBoundingBoxes;

            if (boxesMethod == null || !boxesMethod.getDeclaringClass().isInstance(shape)) {
                boxesMethod = shape.getClass().getMethod("getBoundingBoxes");
                shapeBoundingBoxes = boxesMethod;
            }

            Object raw = boxesMethod.invoke(shape);

            if (!(raw instanceof Collection<?> rawBoxes)) {
                return null;
            }

            List<LocalCollisionBounds> local = new ArrayList<>(rawBoxes.size());

            for (Object rawBox : rawBoxes) {
                CollisionBounds world = readBoundingBox(block, rawBox);

                if (world != null) {
                    local.add(new LocalCollisionBounds(
                            world.minX - block.getX(),
                            world.minY - block.getY(),
                            world.minZ - block.getZ(),
                            world.maxX - block.getX(),
                            world.maxY - block.getY(),
                            world.maxZ - block.getZ()
                    ));
                }
            }

            List<LocalCollisionBounds> immutable = local.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(local);
            LOCAL_SHAPE_CACHE.put(stateKey, immutable);
            return toWorldBounds(block, immutable);
        } catch (Throwable ignored) {
            return getModernSingleBoundingBox(block);
        }
    }

    private static List<CollisionBounds> getModernSingleBoundingBox(Block block) {
        if (block == null || BLOCK_GET_BOUNDING_BOX == null) {
            return null;
        }

        try {
            Object rawBox = BLOCK_GET_BOUNDING_BOX.invoke(block);
            CollisionBounds bounds = readBoundingBox(block, rawBox);

            if (bounds == null
                    || bounds.maxX <= bounds.minX
                    || bounds.maxY <= bounds.minY
                    || bounds.maxZ <= bounds.minZ) {
                return Collections.emptyList();
            }

            return Collections.singletonList(bounds);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<CollisionBounds> toWorldBounds(Block block, List<LocalCollisionBounds> local) {
        if (local.isEmpty()) {
            return Collections.emptyList();
        }

        List<CollisionBounds> result = new ArrayList<>(local.size());

        for (LocalCollisionBounds box : local) {
            result.add(new CollisionBounds(
                    block.getX() + box.minX,
                    block.getY() + box.minY,
                    block.getZ() + box.minZ,
                    block.getX() + box.maxX,
                    block.getY() + box.maxY,
                    block.getZ() + box.maxZ
            ));
        }

        return result;
    }

    private static String collisionStateKey(Block block) {
        WrappedBlockState state = fromBukkitBlock(block);
        return state != null
                ? state.toString()
                : block.getType().name();
    }

    private static CollisionBounds readBoundingBox(Block block, Object rawBox) throws Exception {
        if (rawBox == null) {
            return null;
        }

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

        /*
         * Different Bukkit implementations have exposed these boxes as local
         * or world coordinates. Detect both. The 1.501 upper allowance also
         * handles legitimate over-cube shapes such as walls/fences.
         */
        boolean looksLocal = minX >= -0.001D && maxX <= 1.501D
                && minY >= -0.001D && maxY <= 2.001D
                && minZ >= -0.001D && maxZ <= 1.501D;
        boolean looksWorld = minX >= block.getX() - 0.001D
                && maxX <= block.getX() + 1.501D
                && minY >= block.getY() - 0.001D
                && maxY <= block.getY() + 2.001D
                && minZ >= block.getZ() - 0.001D
                && maxZ <= block.getZ() + 1.501D;

        if (looksLocal && !looksWorld) {
            minX += block.getX();
            minY += block.getY();
            minZ += block.getZ();
            maxX += block.getX();
            maxY += block.getY();
            maxZ += block.getZ();
        }

        return new CollisionBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean isFullCube(Block block, List<CollisionBounds> bounds) {
        if (bounds.size() != 1) {
            return false;
        }

        CollisionBounds box = bounds.get(0);
        double epsilon = 1.0E-6D;

        return Math.abs(box.minX - block.getX()) <= epsilon
                && Math.abs(box.minY - block.getY()) <= epsilon
                && Math.abs(box.minZ - block.getZ()) <= epsilon
                && Math.abs(box.maxX - (block.getX() + 1.0D)) <= epsilon
                && Math.abs(box.maxY - (block.getY() + 1.0D)) <= epsilon
                && Math.abs(box.maxZ - (block.getZ() + 1.0D)) <= epsilon;
    }

    private static Boolean isPassable(Block block) {
        if (block == null || BLOCK_IS_PASSABLE == null) {
            return null;
        }

        try {
            Object value = BLOCK_IS_PASSABLE.invoke(block);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean isOccluding(Material material) {
        if (material == null || MATERIAL_IS_OCCLUDING == null) {
            return null;
        }

        try {
            Object value = MATERIAL_IS_OCCLUDING.invoke(material);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isObviousPartialCollisionName(String name) {
        return name.endsWith("_STAIRS")
                || name.endsWith("_SLAB")
                || name.endsWith("_FENCE")
                || name.endsWith("_FENCE_GATE")
                || name.endsWith("_WALL")
                || name.endsWith("_PANE")
                || name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_CARPET")
                || name.equals("LANTERN")
                || name.endsWith("_LANTERN")
                || name.equals("CHAIN")
                || name.endsWith("_CHAIN")
                || name.endsWith("_CANDLE")
                || name.endsWith("_BED")
                || name.endsWith("_SKULL")
                || name.endsWith("_HEAD")
                || name.contains("PISTON_HEAD")
                || name.contains("PISTON_EXTENSION")
                || name.equals("HEAVY_CORE")
                || name.equals("IRON_BARS")
                || name.equals("IRON_FENCE")
                || name.equals("THIN_GLASS")
                || name.equals("CAULDRON")
                || name.endsWith("_CAULDRON")
                || name.equals("COMPOSTER")
                || name.equals("CHEST")
                || name.equals("ENDER_CHEST")
                || name.equals("TRAPPED_CHEST")
                || name.equals("ENCHANTING_TABLE")
                || name.equals("ENCHANTMENT_TABLE")
                || name.equals("END_PORTAL_FRAME")
                || name.equals("LECTERN")
                || name.equals("HOPPER")
                || name.equals("GRINDSTONE")
                || name.equals("STONECUTTER")
                || name.equals("BELL")
                || name.contains("ANVIL")
                || name.equals("FARMLAND")
                || name.equals("SOUL_SAND")
                || name.equals("SNOW")
                || name.equals("LILY_PAD")
                || name.equals("WATER_LILY")
                || name.equals("END_ROD")
                || name.equals("SEA_PICKLE")
                || name.equals("POINTED_DRIPSTONE")
                || name.contains("AMETHYST_BUD")
                || name.equals("AMETHYST_CLUSTER")
                || name.contains("CAMPFIRE")
                || name.equals("SCAFFOLDING")
                || name.equals("FLOWER_POT")
                || name.startsWith("POTTED_")
                || name.equals("CAKE")
                || name.equals("CAKE_BLOCK");
    }

    private static boolean isDefinitelyNoCollisionName(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }

        return isAirLike(name)
                || isFluidLike(name)
                || name.equals("FIRE")
                || name.equals("SOUL_FIRE")
                || name.equals("NETHER_PORTAL")
                || name.equals("END_PORTAL")
                || name.equals("END_GATEWAY")
                || name.equals("STRUCTURE_VOID")
                || name.equals("LIGHT")
                || name.equals("COBWEB")
                || name.equals("WEB")
                || name.equals("BUBBLE_COLUMN")
                || name.equals("REDSTONE")
                || name.equals("REDSTONE_WIRE")
                || name.equals("TRIPWIRE")
                || name.equals("TRIPWIRE_HOOK")
                || name.equals("VINE")
                || name.equals("VINES")
                || name.endsWith("_VINES")
                || name.endsWith("_VINES_PLANT")
                || name.equals("TORCH")
                || name.equals("SOUL_TORCH")
                || name.equals("REDSTONE_TORCH")
                || name.endsWith("_WALL_TORCH")
                || name.endsWith("_BUTTON")
                || name.equals("STONE_BUTTON")
                || name.equals("WOOD_BUTTON")
                || name.equals("LEVER")
                || name.equals("RAIL")
                || name.endsWith("_RAIL")
                || name.endsWith("_SIGN")
                || name.endsWith("_WALL_SIGN")
                || name.endsWith("_HANGING_SIGN")
                || name.endsWith("_WALL_HANGING_SIGN")
                || name.endsWith("_BANNER")
                || name.endsWith("_WALL_BANNER")
                || name.endsWith("_SAPLING")
                || name.equals("SAPLING")
                || name.equals("SHORT_GRASS")
                || name.equals("TALL_GRASS")
                || name.equals("LONG_GRASS")
                || name.equals("GRASS")
                || name.equals("FERN")
                || name.equals("LARGE_FERN")
                || name.equals("DEAD_BUSH")
                || name.equals("DANDELION")
                || name.equals("POPPY")
                || name.equals("BLUE_ORCHID")
                || name.equals("ALLIUM")
                || name.equals("AZURE_BLUET")
                || name.endsWith("_TULIP")
                || name.equals("OXEYE_DAISY")
                || name.equals("CORNFLOWER")
                || name.equals("LILY_OF_THE_VALLEY")
                || name.equals("WITHER_ROSE")
                || name.equals("SUNFLOWER")
                || name.equals("LILAC")
                || name.equals("ROSE_BUSH")
                || name.equals("PEONY")
                || name.equals("TORCHFLOWER")
                || name.equals("PITCHER_PLANT")
                || name.equals("PITCHER_CROP")
                || name.equals("BROWN_MUSHROOM")
                || name.equals("RED_MUSHROOM")
                || name.equals("CRIMSON_FUNGUS")
                || name.equals("WARPED_FUNGUS")
                || name.equals("CRIMSON_ROOTS")
                || name.equals("WARPED_ROOTS")
                || name.equals("HANGING_ROOTS")
                || name.equals("NETHER_SPROUTS")
                || name.equals("WHEAT")
                || name.equals("CARROTS")
                || name.equals("POTATOES")
                || name.equals("BEETROOTS")
                || name.equals("NETHER_WART")
                || name.endsWith("_STEM")
                || name.equals("SUGAR_CANE")
                || name.equals("KELP")
                || name.equals("KELP_PLANT")
                || name.equals("SEAGRASS")
                || name.equals("TALL_SEAGRASS");
    }

    private static boolean isKnownFullTransparentCube(String name) {
        return name.equals("GLASS")
                || name.endsWith("_STAINED_GLASS")
                || name.equals("ICE")
                || name.equals("PACKED_ICE")
                || name.equals("BLUE_ICE")
                || name.endsWith("_LEAVES");
    }

    private static boolean isAirLike(String name) {
        return name.equals("AIR")
                || name.equals("CAVE_AIR")
                || name.equals("VOID_AIR")
                || name.endsWith(":AIR");
    }

    private static boolean isFluidLike(String name) {
        return name.equals("WATER")
                || name.equals("LAVA")
                || name.equals("STATIONARY_WATER")
                || name.equals("STATIONARY_LAVA");
    }

    private static Material materialFromState(StateType type) {
        if (type == null) {
            return null;
        }

        String normalized = stateName(type);
        Material material = Material.matchMaterial(normalized);
        return material != null ? material : Material.matchMaterial("LEGACY_" + normalized);
    }

    private static String toMinecraftKey(Material material) {
        String name = material.name();

        if (name.startsWith("LEGACY_")) {
            name = name.substring("LEGACY_".length());
        }

        return "minecraft:" + name.toLowerCase(Locale.ROOT);
    }

    private static String stateName(WrappedBlockState state) {
        return state == null ? "" : stateName(state.getType());
    }

    private static String stateName(StateType type) {
        if (type == null) {
            return "";
        } else {
            type.getName();
        }

        return normalize(type.getName());
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        String name = raw.trim();
        int namespace = name.indexOf(':');

        if (namespace >= 0) {
            name = name.substring(namespace + 1);
        }

        return name.toUpperCase(Locale.ROOT);
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

    private static final class LocalCollisionBounds {
        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;

        private LocalCollisionBounds(double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    public static final class CollisionBounds {
        public final double minX;
        public final double minY;
        public final double minZ;
        public final double maxX;
        public final double maxY;
        public final double maxZ;

        public CollisionBounds(double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public boolean intersects(double otherMinX, double otherMinY, double otherMinZ,
                                  double otherMaxX, double otherMaxY, double otherMaxZ) {
            return this.maxX > otherMinX + 1.0E-7D
                    && this.minX < otherMaxX - 1.0E-7D
                    && this.maxY > otherMinY + 1.0E-7D
                    && this.minY < otherMaxY - 1.0E-7D
                    && this.maxZ > otherMinZ + 1.0E-7D
                    && this.minZ < otherMaxZ - 1.0E-7D;
        }
    }
}
