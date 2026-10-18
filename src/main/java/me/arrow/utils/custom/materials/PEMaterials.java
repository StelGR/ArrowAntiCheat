package me.arrow.utils.custom.materials;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.arrow.playerdata.cache.ChunkCache;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.material.MaterialData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    private static final Method BLOCK_DATA_GET_AS_STRING =
            findNoArgMethod("org.bukkit.block.data.BlockData", "getAsString");
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

    /* Legacy CraftBukkit/NMS collision access (1.8-1.12). */
    private static volatile Class<?> legacyWorldClass;
    private static volatile Method legacyWorldGetState;
    private static volatile Constructor<?> legacyBlockPositionConstructor;
    private static volatile Method legacyStateGetBlock;
    private static volatile Method legacyUpdateShape;
    private static volatile Method legacyGetCollisionBox;
    private static volatile Field[] legacyBoxFields;
    private static volatile boolean legacyCollisionUnavailable;

    private static final Map<Material, WrappedBlockState> SERVER_STATE_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<String, WrappedBlockState> CLIENT_STATE_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<Material, Boolean> POTENTIAL_COLLISION_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<Material, Boolean> NON_FULL_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<StateType, Material> STATE_TYPE_MATERIAL_CACHE =
            new ConcurrentHashMap<>(1024);
    private static final Map<WrappedBlockState, Boolean> WATERLOGGED_CACHE =
            new ConcurrentHashMap<>(1024);

    /*
     * Local voxel boxes are cached by complete block-state string. Connection,
     * axis, facing, slab type, piston facing, hanging state, etc. are part of
     * that state string, so the cache still remains state-aware.
     */
    private static final Map<String, List<LocalCollisionBounds>> LOCAL_SHAPE_CACHE =
            new ConcurrentHashMap<>();
    /* Exact server voxel shapes, indexed by PacketEvents' complete block state. */
    private static final Map<Integer, List<LocalCollisionBounds>> STATE_SHAPE_CACHE =
            new ConcurrentHashMap<>(2048);

    private static volatile Constructor<?> legacyAabbConstructor;
    private static volatile Class<?> legacyAabbClass;

    private static final boolean LEGACY_SERVER =
            BLOCK_GET_COLLISION_SHAPE == null;

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
                WrappedBlockState state = fromBukkitBlockData(BLOCK_GET_BLOCK_DATA.invoke(block));
                if (state != null) {
                    return state;
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

    /** Converts a modern Bukkit BlockData instance without requiring a live Block. */
    public static WrappedBlockState fromBukkitBlockData(Object blockData) {
        if (blockData == null) {
            return null;
        }

        try {
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
        } catch (Throwable ignored) {
        }

        return null;
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

            if (!MaterialType.isMaterial(mapped.getType().getName(), MaterialType.AIR) || MaterialType.isMaterial(source.getType().getName(), MaterialType.AIR)) {
                return mapped;
            }
        } catch (Throwable ignored) {
        }

        try {
            WrappedBlockState mapped = source.getType().createBlockState(clientVersion);

            if (!MaterialType.isMaterial(mapped.getType().getName(), MaterialType.AIR) || MaterialType.isMaterial(source.getType().getName(), MaterialType.AIR)) {
                return mapped;
            }
        } catch (Throwable ignored) {
        }

        return source.clone();
    }

    /**
     * Returns only a state the target protocol can represent. A null result is
     * intentional: callers should preserve the already-translated client view
     * instead of replacing it with an invalid modern block ID.
     */
    public static WrappedBlockState mapToClientStrictly(WrappedBlockState source, ClientVersion clientVersion) {
        if (source == null) {
            return null;
        }

        if (clientVersion == null) {
            return source.clone();
        }

        boolean sourceIsAir = source.getType().isAir();

        try {
            WrappedBlockState mapped = WrappedBlockState.getByString(clientVersion, source.toString());
            if (sourceIsAir || !mapped.getType().isAir()) {
                return mapped;
            }
        } catch (Throwable ignored) {
        }

        try {
            WrappedBlockState mapped = source.getType().createBlockState(clientVersion);
            if (sourceIsAir || !mapped.getType().isAir()) {
                return mapped;
            }
        } catch (Throwable ignored) {
        }

        return null;
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

        if (isAirLike(name) || isFluidLike(name)) {
            result = false;
        } else {
            /*
             * Some legacy names are ambiguous. GRASS is a solid grass block on
             * 1.8, but denotes vegetation on later mappings. Trust the live
             * server Material before applying name-family exclusions.
             */
            try {
                if (material.isSolid()) {
                    POTENTIAL_COLLISION_CACHE.put(material, true);
                    return true;
                }
            } catch (Throwable ignored) {
            }

            if (isDefinitelyNoCollisionName(name)) {
                POTENTIAL_COLLISION_CACHE.put(material, false);
                return false;
            }

            WrappedBlockState state = fromBukkitMaterial(material);
            result = hasCollision(state);

            if (!result) {
                try {
                    result = material.isSolid();
                } catch (Throwable ignored) {
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

        if (MaterialType.isMaterial(type.getName(), MaterialType.AIR) || state.isFluid() || isAirLike(name) || isFluidLike(name)) {
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

    public static boolean isStair(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_STAIRS") || name.equals("STAIRS") || name.contains("STAIR");
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

    public static boolean isPistonHead(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.equals("PISTON_HEAD") || name.equals("PISTON_EXTENSION");
    }

    public static boolean isPistonHead(WrappedBlockState state) {
        if (state == null) return false;
        String name = stateName(state);
        return name.equals("PISTON_HEAD") || name.equals("PISTON_EXTENSION")
                || name.contains("PISTON_HEAD") || name.contains("PISTON_EXTENSION");
    }

    public static boolean isPistonBase(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.equals("PISTON") || name.equals("STICKY_PISTON")
                || name.equals("PISTON_BASE") || name.equals("PISTON_STICKY_BASE");
    }

    public static boolean isPistonBase(WrappedBlockState state) {
        if (state == null) return false;
        String name = stateName(state);
        return name.equals("PISTON") || name.equals("STICKY_PISTON")
                || name.equals("PISTON_BASE") || name.equals("PISTON_STICKY_BASE")
                || (name.contains("PISTON") && !name.contains("HEAD") && !name.contains("EXTENSION") && !name.contains("MOVING"));
    }

    public static boolean isExtendedPiston(WrappedBlockState state) {
        if (state == null) return false;
        try {
            Method m = state.getClass().getMethod("isExtended");
            Object res = m.invoke(state);
            if (res instanceof Boolean) return (Boolean) res;
        } catch (Throwable ignored) {}
        try {
            return state.toString().toLowerCase(Locale.ROOT).contains("extended=true");
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isWaterlogged(WrappedBlockState state) {
        if (state == null) return false;
        Boolean cached = WATERLOGGED_CACHE.get(state);
        if (cached != null) return cached;

        boolean wl = false;
        try {
            Method m = state.getClass().getMethod("isWaterlogged");
            Object res = m.invoke(state);
            if (res instanceof Boolean) wl = (Boolean) res;
        } catch (Throwable ignored) {}

        if (!wl) {
            try {
                String str = state.toString();
                if (str.toLowerCase(Locale.ROOT).contains("waterlogged=true")) {
                    wl = true;
                }
            } catch (Throwable ignored) {}
        }

        WATERLOGGED_CACHE.put(state, wl);
        return wl;
    }

    public static List<CollisionBounds> getCollisionBounds(Material material, int x, int y, int z) {
        if (material == null || material == Material.AIR || !hasPotentialCollision(material)) {
            return Collections.emptyList();
        }

        String name = material.name();
        if (name.contains("SLAB") || name.contains("STEP")) {
            return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 0.5D, z + 1.0D));
        }
        if (name.contains("CARPET")) {
            return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 0.0625D, z + 1.0D));
        }
        if (name.equals("DIRT_PATH") || name.equals("GRASS_PATH")) {
            return lowFullBounds(x, y, z, .9375D);
        }
        if ((name.contains("FENCE") && !name.equals("IRON_FENCE")) || name.contains("WALL")) {
            return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 1.5D, z + 1.0D));
        }
        if (name.endsWith("_PANE") || name.equals("IRON_BARS") || name.equals("THIN_GLASS") || name.equals("GLASS_PANE") || name.equals("IRON_FENCE")) {
            return paneBounds(null, x, y, z);
        }
        if (isStair(material)) {
            return stairBounds(-1, x, y, z);
        }
        if (name.equals("PISTON_HEAD")
                || name.equals("PISTON_EXTENSION")) {
            return pistonHeadBounds(null, -1, null, x, y, z);
        }
        if (name.endsWith("_HEAD") || name.endsWith("_SKULL") || name.equals("HEAVY_CORE")) {
            return Collections.singletonList(new CollisionBounds(x + 0.25D, y, z + 0.25D, x + 0.75D, y + 0.5D, z + 0.75D));
        }
        if (name.equals("BIG_DRIPLEAF")) {
            return bigDripleafBounds(null, x, y, z);
        }
        if (name.contains("DAYLIGHT_DETECTOR")) {
            return lowFullBounds(x, y, z, .375D);
        }
        if (name.equals("SCULK_SHRIEKER")) {
            return lowFullBounds(x, y, z, .5D);
        }
        if (name.equals("SCULK_SENSOR") || name.equals("CALIBRATED_SCULK_SENSOR")) {
            return centeredLowBounds(x, y, z, .0625D, .5D);
        }
        if (name.equals("LIGHTNING_ROD")) {
            return lightningRodBounds(null, x, y, z);
        }
        return Collections.singletonList(new CollisionBounds(
                x, y, z,
                x + 1.0D, y + 1.0D, z + 1.0D
        ));

    }

    /**
     * True when material-only data cannot safely describe the collision shape.
     * The Material flags catch future partial blocks; the name list retains
     * legacy/stateful blocks whose shape may change while remaining solid.
     */
    public static boolean requiresStatefulCollision(Material material) {
        return material != null
                && material != Material.AIR
                && (isObviousPartialCollisionName(material.name()) || isNonFullShape(material) || isPistonHead(material) || isPistonBase(material));
    }

    /** State-aware form used by packet chunk data before it enters the global cache. */
    public static boolean requiresStatefulCollision(WrappedBlockState state) {
        if (state == null || MaterialType.isMaterial(state.getType().getName(), MaterialType.AIR) || state.isFluid()) {
            return false;
        }

        if (isPistonHead(state) || isPistonBase(state)) {
            return true;
        }

        Material material = materialFromState(state.getType());
        if (requiresStatefulCollision(material)) {
            return true;
        }

        try {
            StateType type = state.getType();
            return !type.isSolid() || !type.isBlocking() || type.exceedsCube();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * State-only collision fallback for asynchronous cache reads. Bukkit's live
     * voxel shape remains preferred when a Block is safely available.
     */
    public static List<CollisionBounds> getCollisionBounds(WrappedBlockState state, int x, int y, int z) {
        if (state == null) return null;

        List<LocalCollisionBounds> exact =
                STATE_SHAPE_CACHE.get(state.getGlobalId());

        if (exact != null) {
            return toWorldBounds(x, y, z, exact);
        }

        Material material = materialFromState(state.getType());
        if (material == null || material == Material.AIR) {
            return MaterialType.isMaterial(state.getType().getName(), MaterialType.AIR) ? Collections.emptyList() : null;
        }

        /*
         * For legacy servers, only fall back to live NMS for blocks whose collision
         * shape cannot be determined from the WrappedBlockState (e.g. tile entities).
         * Stairs, slabs, fences, gates, walls, panes, and pistons are fully resolved below.
         */
        if (LEGACY_SERVER && requiresStatefulCollision(state)) {
            if (!isStair(state) && !isStair(material) && !isSlab(state) && !isLegacySlabMaterial(material)
                    && !isFence(state) && !isFenceGate(state) && !isWall(state)
                    && !isPistonHead(state) && !isPistonHead(material)
                    && !isPistonBase(state) && !isPistonBase(material)) {
                return null;
            }
        }

        String name = material.name();
        if (isPistonHead(state) || isPistonHead(material)) {
            return pistonHeadBounds(state, -1, null, x, y, z);
        }
        if (isPistonBase(state) || isPistonBase(material)) {
            return pistonBaseBounds(state, -1, null, x, y, z);
        }
        if (name.equals("BIG_DRIPLEAF")) {
            return bigDripleafBounds(state, x, y, z);
        }
        if (name.contains("DAYLIGHT_DETECTOR")) {
            return lowFullBounds(x, y, z, .375D);
        }
        if (name.equals("SCULK_SHRIEKER")) {
            return lowFullBounds(x, y, z, .5D);
        }
        if (name.equals("SCULK_SENSOR") || name.equals("CALIBRATED_SCULK_SENSOR")) {
            return centeredLowBounds(x, y, z, .0625D, .5D);
        }
        if (name.equals("LIGHTNING_ROD")) {
            return lightningRodBounds(state, x, y, z);
        }
        if (name.endsWith("_FENCE_GATE") || name.equals("FENCE_GATE")) {
            return fenceGateBounds(state, x, y, z);
        }
        if ((name.endsWith("_FENCE") && !name.equals("IRON_FENCE")) || name.equals("FENCE")) {
            return fenceBounds(state, x, y, z);
        }
        if (name.endsWith("_WALL") || name.equals("COBBLE_WALL")) {
            return wallBounds(state, x, y, z);
        }
        if (name.endsWith("_PANE") || name.equals("IRON_BARS") || name.equals("THIN_GLASS") || name.equals("GLASS_PANE") || name.equals("IRON_FENCE")) {
            return paneBounds(state, x, y, z);
        }
        if (isSlab(state) || name.endsWith("_SLAB") || name.endsWith("_STEP")) {
            String type = String.valueOf(state.getTypeData());
            if ("TOP".equalsIgnoreCase(type)) {
                return Collections.singletonList(new CollisionBounds(x, y + .5D, z, x + 1.0D, y + 1.0D, z + 1.0D));
            }
            if ("DOUBLE".equalsIgnoreCase(type)) {
                return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D));
            }
            return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + .5D, z + 1.0D));
        }
        if (isStair(state) || isStair(material)) {
            return stairBounds(state, x, y, z);
        }

        return requiresStatefulCollision(material)
                ? null
                : getCollisionBounds(material, x, y, z);
    }

    /** Returns whether an exact server voxel shape has already been learned for this state. */
    public static boolean hasCachedCollisionShape(WrappedBlockState state) {
        if (state == null) {
            return false;
        }

        /*
         * Legacy collision geometry may depend on neighbouring blocks and the
         * legacy NMS block implementation. Never share one shape globally by ID.
         */
        if (LEGACY_SERVER) {
            return false;
        }

        return STATE_SHAPE_CACHE.containsKey(state.getGlobalId());
    }
    /**
     * Reads Bukkit's real collision shape once on the server thread and shares
     * it globally for every coordinate with this same block state.
     */
    public static boolean cacheCollisionShape(
            WrappedBlockState expectedState,
            Block block
    ) {
        if (expectedState == null || block == null) {
            return false;
        }

        /*
         * IMPORTANT:
         * Never put legacy collision data into the global state-ID cache.
         *
         * Legacy shapes can depend on neighbouring blocks and old NMS block
         * internals. resolveExactCollision() will query the actual live block
         * instead.
         */
        if (LEGACY_SERVER) {
            return false;
        }

        try {
            WrappedBlockState liveState = fromBukkitBlock(block);

            if (liveState == null
                    || liveState.getGlobalId() != expectedState.getGlobalId()) {
                return false;
            }

            List<CollisionBounds> worldBounds =
                    getModernCollisionBounds(block);

            if (worldBounds == null) {
                return false;
            }

            List<LocalCollisionBounds> local =
                    new ArrayList<>(worldBounds.size());

            for (CollisionBounds bounds : worldBounds) {
                local.add(new LocalCollisionBounds(
                        bounds.minX - block.getX(),
                        bounds.minY - block.getY(),
                        bounds.minZ - block.getZ(),
                        bounds.maxX - block.getX(),
                        bounds.maxY - block.getY(),
                        bounds.maxZ - block.getZ()
                ));
            }

            STATE_SHAPE_CACHE.put(
                    expectedState.getGlobalId(),
                    local.isEmpty()
                            ? Collections.emptyList()
                            : Collections.unmodifiableList(local)
            );

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<CollisionBounds> bigDripleafBounds(WrappedBlockState state, int x, int y, int z) {
        String tilt = state == null ? "NONE" : String.valueOf(state.getTilt());

        if ("FULL".equals(tilt)) {
            return Collections.emptyList();
        }

        double height = "PARTIAL".equals(tilt) ? .6875D : .8125D;
        return Collections.singletonList(new CollisionBounds(
                x + .0625D, y, z + .0625D,
                x + .9375D, y + height, z + .9375D
        ));
    }

    private static List<CollisionBounds> lowFullBounds(int x, int y, int z, double height) {
        return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + height, z + 1.0D));
    }

    private static List<CollisionBounds> centeredLowBounds(int x, int y, int z, double inset, double height) {
        return Collections.singletonList(new CollisionBounds(
                x + inset, y, z + inset,
                x + 1.0D - inset, y + height, z + 1.0D - inset
        ));
    }

    public static List<CollisionBounds> pistonHeadBounds(WrappedBlockState state, int legacyData, Block block, int x, int y, int z) {
        String facing = null;
        if (state != null) {
            try {
                Object f = state.getFacing();
                if (f != null) {
                    facing = String.valueOf(f).toUpperCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {}
            if (facing == null || facing.isEmpty() || "NULL".equals(facing)) {
                String str = state.toString().toUpperCase(Locale.ROOT);
                if (str.contains("FACING=UP")) facing = "UP";
                else if (str.contains("FACING=DOWN")) facing = "DOWN";
                else if (str.contains("FACING=NORTH")) facing = "NORTH";
                else if (str.contains("FACING=SOUTH")) facing = "SOUTH";
                else if (str.contains("FACING=WEST")) facing = "WEST";
                else if (str.contains("FACING=EAST")) facing = "EAST";
            }
        }
        if (facing == null && block != null) {
            try {
                Object blockData = block.getBlockData();
                if (blockData instanceof org.bukkit.block.data.Directional) {
                    facing = ((org.bukkit.block.data.Directional) blockData).getFacing().name();
                }
            } catch (Throwable ignored) {}
            if (facing == null) {
                try {
                    int data = block.getData() & 0x7;
                    facing = facingFromLegacyData(data);
                } catch (Throwable ignored) {}
            }
        }
        if (facing == null && legacyData >= 0) {
            facing = facingFromLegacyData(legacyData & 0x7);
        }
        if (facing == null) {
            facing = "UP";
        }

        List<CollisionBounds> boxes = new ArrayList<>(2);
        switch (facing) {
            case "DOWN":
                // Head platform: bottom 4 pixels [0, 0, 0] -> [1, 0.25, 1]
                boxes.add(new CollisionBounds(x, y, z, x + 1.0D, y + 0.25D, z + 1.0D));
                // Rod: 4x4 pixels centered [0.375, 0.25, 0.375] -> [0.625, 1.0, 0.625]
                boxes.add(new CollisionBounds(x + 0.375D, y + 0.25D, z + 0.375D, x + 0.625D, y + 1.0D, z + 0.625D));
                break;
            case "NORTH":
                // Head platform: north 4 pixels [0, 0, 0] -> [1, 1, 0.25]
                boxes.add(new CollisionBounds(x, y, z, x + 1.0D, y + 1.0D, z + 0.25D));
                // Rod: 4x4 pixels centered [0.375, 0.375, 0.25] -> [0.625, 0.625, 1.0] (top of rod is y + 0.625)
                boxes.add(new CollisionBounds(x + 0.375D, y + 0.375D, z + 0.25D, x + 0.625D, y + 0.625D, z + 1.0D));
                break;
            case "SOUTH":
                // Head platform: south 4 pixels [0, 0, 0.75] -> [1, 1, 1]
                boxes.add(new CollisionBounds(x, y, z + 0.75D, x + 1.0D, y + 1.0D, z + 1.0D));
                // Rod: 4x4 pixels centered [0.375, 0.375, 0] -> [0.625, 0.625, 0.75] (top of rod is y + 0.625)
                boxes.add(new CollisionBounds(x + 0.375D, y + 0.375D, z, x + 0.625D, y + 0.625D, z + 0.75D));
                break;
            case "WEST":
                // Head platform: west 4 pixels [0, 0, 0] -> [0.25, 1, 1]
                boxes.add(new CollisionBounds(x, y, z, x + 0.25D, y + 1.0D, z + 1.0D));
                // Rod: 4x4 pixels centered [0.25, 0.375, 0.375] -> [1.0, 0.625, 0.625] (top of rod is y + 0.625)
                boxes.add(new CollisionBounds(x + 0.25D, y + 0.375D, z + 0.375D, x + 1.0D, y + 0.625D, z + 0.625D));
                break;
            case "EAST":
                // Head platform: east 4 pixels [0.75, 0, 0] -> [1, 1, 1]
                boxes.add(new CollisionBounds(x + 0.75D, y, z, x + 1.0D, y + 1.0D, z + 1.0D));
                // Rod: 4x4 pixels centered [0, 0.375, 0.375] -> [0.75, 0.625, 0.625] (top of rod is y + 0.625)
                boxes.add(new CollisionBounds(x, y + 0.375D, z + 0.375D, x + 0.75D, y + 0.625D, z + 0.625D));
                break;
            case "UP":
            default:
                // Head platform: top 4 pixels [0, 0.75, 0] -> [1, 1, 1]
                boxes.add(new CollisionBounds(x, y + 0.75D, z, x + 1.0D, y + 1.0D, z + 1.0D));
                // Rod: 4x4 pixels centered [0.375, 0, 0.375] -> [0.625, 0.75, 0.625]
                boxes.add(new CollisionBounds(x + 0.375D, y, z + 0.375D, x + 0.625D, y + 0.75D, z + 0.625D));
                break;
        }
        return boxes;
    }

    public static List<CollisionBounds> pistonBaseBounds(WrappedBlockState state, int legacyData, Block block, int x, int y, int z) {
        boolean extended = false;
        String facing = null;
        if (state != null) {
            extended = isExtendedPiston(state);
            try {
                Object f = state.getFacing();
                if (f != null) {
                    facing = String.valueOf(f).toUpperCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {}
            if (facing == null || facing.isEmpty() || "NULL".equals(facing)) {
                String str = state.toString().toUpperCase(Locale.ROOT);
                if (str.contains("FACING=UP")) facing = "UP";
                else if (str.contains("FACING=DOWN")) facing = "DOWN";
                else if (str.contains("FACING=NORTH")) facing = "NORTH";
                else if (str.contains("FACING=SOUTH")) facing = "SOUTH";
                else if (str.contains("FACING=WEST")) facing = "WEST";
                else if (str.contains("FACING=EAST")) facing = "EAST";
            }
        }
        if (block != null) {
            try {
                Object blockData = block.getBlockData();
                if (blockData instanceof org.bukkit.block.data.type.Piston) {
                    extended = ((org.bukkit.block.data.type.Piston) blockData).isExtended();
                }
                if (blockData instanceof org.bukkit.block.data.Directional) {
                    facing = ((org.bukkit.block.data.Directional) blockData).getFacing().name();
                }
            } catch (Throwable ignored) {}
            if (legacyData < 0) {
                try {
                    legacyData = block.getData() & 0xFF;
                } catch (Throwable ignored) {}
            }
        }
        if (legacyData >= 0) {
            if ((legacyData & 0x8) != 0) {
                extended = true;
            }
            if (facing == null) {
                facing = facingFromLegacyData(legacyData & 0x7);
            }
        }

        if (!extended) {
            return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D));
        }

        if (facing == null) {
            facing = "UP";
        }

        switch (facing) {
            case "DOWN":
                return Collections.singletonList(new CollisionBounds(x, y + 0.25D, z, x + 1.0D, y + 1.0D, z + 1.0D));
            case "UP":
                return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 0.75D, z + 1.0D));
            case "NORTH":
                return Collections.singletonList(new CollisionBounds(x, y, z + 0.25D, x + 1.0D, y + 1.0D, z + 1.0D));
            case "SOUTH":
                return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 1.0D, z + 0.75D));
            case "WEST":
                return Collections.singletonList(new CollisionBounds(x + 0.25D, y, z, x + 1.0D, y + 1.0D, z + 1.0D));
            case "EAST":
                return Collections.singletonList(new CollisionBounds(x, y, z, x + 0.75D, y + 1.0D, z + 1.0D));
            default:
                return Collections.singletonList(new CollisionBounds(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D));
        }
    }

    private static String facingFromLegacyData(int dir) {
        switch (dir) {
            case 0: return "DOWN";
            case 1: return "UP";
            case 2: return "NORTH";
            case 3: return "SOUTH";
            case 4: return "WEST";
            case 5: return "EAST";
            default: return "UP";
        }
    }

    private static List<CollisionBounds> lightningRodBounds(WrappedBlockState state, int x, int y, int z) {
        String facing = state == null ? "UP" : String.valueOf(state.getFacing());

        if ("EAST".equals(facing) || "WEST".equals(facing)) {
            return Collections.singletonList(new CollisionBounds(x, y + .375D, z + .375D,
                    x + 1.0D, y + .625D, z + .625D));
        }
        if ("NORTH".equals(facing) || "SOUTH".equals(facing)) {
            return Collections.singletonList(new CollisionBounds(x + .375D, y + .375D, z,
                    x + .625D, y + .625D, z + 1.0D));
        }
        return Collections.singletonList(new CollisionBounds(x + .375D, y, z + .375D,
                x + .625D, y + 1.0D, z + .625D));
    }

    private static List<CollisionBounds> fenceGateBounds(WrappedBlockState state, int x, int y, int z) {
        if (state.isOpen()) return Collections.emptyList();

        String facing = String.valueOf(state.getFacing());
        if ("EAST".equals(facing) || "WEST".equals(facing)) {
            return Collections.singletonList(new CollisionBounds(x + .40625D, y, z, x + .59375D, y + 1.5D, z + 1.0D));
        }
        return Collections.singletonList(new CollisionBounds(x, y, z + .40625D, x + 1.0D, y + 1.5D, z + .59375D));
    }

    private static List<CollisionBounds> fenceBounds(WrappedBlockState state, int x, int y, int z) {
        List<CollisionBounds> boxes = new ArrayList<>(5);
        boxes.add(new CollisionBounds(x + .375D, y, z + .375D, x + .625D, y + 1.5D, z + .625D));

        if (isConnected(state.getNorth())) boxes.add(new CollisionBounds(x + .375D, y, z, x + .625D, y + 1.5D, z + .375D));
        if (isConnected(state.getSouth())) boxes.add(new CollisionBounds(x + .375D, y, z + .625D, x + .625D, y + 1.5D, z + 1.0D));
        if (isConnected(state.getWest())) boxes.add(new CollisionBounds(x, y, z + .375D, x + .375D, y + 1.5D, z + .625D));
        if (isConnected(state.getEast())) boxes.add(new CollisionBounds(x + .625D, y, z + .375D, x + 1.0D, y + 1.5D, z + .625D));
        return boxes;
    }

    private static List<CollisionBounds> wallBounds(WrappedBlockState state, int x, int y, int z) {
        boolean north = isConnected(state.getNorth());
        boolean south = isConnected(state.getSouth());
        boolean west = isConnected(state.getWest());
        boolean east = isConnected(state.getEast());
        boolean hasSide = north || south || west || east;
        List<CollisionBounds> boxes = new ArrayList<>(5);

        // Wall outlines may have LOW arms, but their collision arms are always 1.5 blocks high.
        // A state without side properties is an older standalone wall, so keep its center post.
        if (state.isUp() || !hasSide) {
            boxes.add(new CollisionBounds(x + .25D, y, z + .25D, x + .75D, y + 1.5D, z + .75D));
        }
        if (north) boxes.add(new CollisionBounds(x + .3125D, y, z, x + .6875D, y + 1.5D, z + .5D));
        if (south) boxes.add(new CollisionBounds(x + .3125D, y, z + .5D, x + .6875D, y + 1.5D, z + 1.0D));
        if (west) boxes.add(new CollisionBounds(x, y, z + .3125D, x + .5D, y + 1.5D, z + .6875D));
        if (east) boxes.add(new CollisionBounds(x + .5D, y, z + .3125D, x + 1.0D, y + 1.5D, z + .6875D));
        return boxes;
    }

    public static List<CollisionBounds> paneBounds(WrappedBlockState state, World world, int x, int y, int z) {
        boolean north = false;
        boolean south = false;
        boolean west = false;
        boolean east = false;

        if (state != null) {
            north = isConnected(state.getNorth());
            south = isConnected(state.getSouth());
            west = isConnected(state.getWest());
            east = isConnected(state.getEast());
        } else if (world != null) {
            ChunkCache cache = ChunkCache.get();
            Material nMat = cache.getBlock(world, x, y, z - 1);
            Material sMat = cache.getBlock(world, x, y, z + 1);
            Material wMat = cache.getBlock(world, x - 1, y, z);
            Material eMat = cache.getBlock(world, x + 1, y, z);

            north = isPaneConnectable(nMat);
            south = isPaneConnectable(sMat);
            west = isPaneConnectable(wMat);
            east = isPaneConnectable(eMat);
        }

        boolean hasSide = north || south || west || east;
        List<CollisionBounds> boxes = new ArrayList<>(5);

        // Center post (width 0.125, height 1.0 block)
        boxes.add(new CollisionBounds(x + .4375D, y, z + .4375D, x + .5625D, y + 1.0D, z + .5625D));

        if (north) boxes.add(new CollisionBounds(x + .4375D, y, z, x + .5625D, y + 1.0D, z + .5D));
        if (south) boxes.add(new CollisionBounds(x + .4375D, y, z + .5D, x + .5625D, y + 1.0D, z + 1.0D));
        if (west) boxes.add(new CollisionBounds(x, y, z + .4375D, x + .5D, y + 1.0D, z + .5625D));
        if (east) boxes.add(new CollisionBounds(x + .5D, y, z + .4375D, x + 1.0D, y + 1.0D, z + .5625D));

        if (!hasSide) {
            // Standalone or unparsed legacy pane: thin centered cross bounds
            boxes.add(new CollisionBounds(x + .4375D, y, z, x + .5625D, y + 1.0D, z + 1.0D));
            boxes.add(new CollisionBounds(x, y, z + .4375D, x + 1.0D, y + 1.0D, z + .5625D));
        }

        return boxes;
    }

    public static List<CollisionBounds> paneBounds(WrappedBlockState state, int x, int y, int z) {
        return paneBounds(state, null, x, y, z);
    }

    public static boolean isPaneConnectable(Material mat) {
        if (mat == null || mat == Material.AIR) return false;
        String name = mat.name();
        if (name.equals("IRON_BARS") || name.equals("IRON_FENCE")
                || name.endsWith("_PANE") || name.equals("THIN_GLASS") || name.equals("GLASS_PANE")
                || name.endsWith("_WALL") || name.equals("COBBLE_WALL")
                || name.endsWith("_FENCE") || name.equals("FENCE")
                || name.endsWith("_FENCE_GATE") || name.equals("FENCE_GATE")) {
            return true;
        }
        try {
            return mat.isSolid() && mat.isOccluding();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isConnected(Object value) {
        String name = String.valueOf(value);
        return !"NONE".equals(name) && !"FALSE".equals(name) && !"null".equals(name);
    }

    public static List<CollisionBounds> stairBounds(int legacyData, int x, int y, int z) {
        List<CollisionBounds> boxes = new ArrayList<>(4);
        boolean top = legacyData >= 0 && (legacyData & 0x4) != 0;

        // Base slab: bottom half if normal (y to y+0.5), top half if upside-down (y+0.5 to y+1.0)
        double baseMinY = top ? y + 0.5D : y;
        double baseMaxY = top ? y + 1.0D : y + 0.5D;
        boxes.add(new CollisionBounds(x, baseMinY, z, x + 1.0D, baseMaxY, z + 1.0D));

        // Step half: opposite half of the base
        double stepMinY = top ? y : y + 0.5D;
        double stepMaxY = top ? y + 0.5D : y + 1.0D;

        if (legacyData >= 0) {
            int dir = legacyData & 0x3;
            switch (dir) {
                case 0:
                    boxes.add(new CollisionBounds(x + 0.5D, stepMinY, z, x + 1.0D, stepMaxY, z + 1.0D)); // East
                    break;
                case 1:
                    boxes.add(new CollisionBounds(x, stepMinY, z, x + 0.5D, stepMaxY, z + 1.0D)); // West
                    break;
                case 2:
                    boxes.add(new CollisionBounds(x, stepMinY, z + 0.5D, x + 1.0D, stepMaxY, z + 1.0D)); // South
                    break;
                case 3:
                    boxes.add(new CollisionBounds(x, stepMinY, z, x + 1.0D, stepMaxY, z + 0.5D)); // North
                    break;
            }
        } else {
            // Unknown orientation fallback: add North step
            boxes.add(new CollisionBounds(x, stepMinY, z, x + 1.0D, stepMaxY, z + 0.5D));
        }

        return boxes;
    }

    public static List<CollisionBounds> stairBounds(WrappedBlockState state, int x, int y, int z) {
        List<CollisionBounds> boxes = new ArrayList<>(4);
        boolean top = false;
        String facing = "NORTH";

        if (state != null) {
            try {
                String half = String.valueOf(state.getHalf());
                top = "TOP".equalsIgnoreCase(half);
            } catch (Throwable ignored) {}
            try {
                facing = String.valueOf(state.getFacing()).toUpperCase(Locale.ROOT);
            } catch (Throwable ignored) {}
        }

        double baseMinY = top ? y + 0.5D : y;
        double baseMaxY = top ? y + 1.0D : y + 0.5D;
        boxes.add(new CollisionBounds(x, baseMinY, z, x + 1.0D, baseMaxY, z + 1.0D));

        double stepMinY = top ? y : y + 0.5D;
        double stepMaxY = top ? y + 0.5D : y + 1.0D;

        switch (facing) {
            case "EAST":
                boxes.add(new CollisionBounds(x + 0.5D, stepMinY, z, x + 1.0D, stepMaxY, z + 1.0D));
                break;
            case "WEST":
                boxes.add(new CollisionBounds(x, stepMinY, z, x + 0.5D, stepMaxY, z + 1.0D));
                break;
            case "SOUTH":
                boxes.add(new CollisionBounds(x, stepMinY, z + 0.5D, x + 1.0D, stepMaxY, z + 1.0D));
                break;
            default:
                boxes.add(new CollisionBounds(x, stepMinY, z, x + 1.0D, stepMaxY, z + 0.5D)); // NORTH
                break;
        }

        return boxes;
    }

    public static WrappedBlockState fromBukkitBlockStrictlyForClient(Block block, ClientVersion clientVersion) {
        return mapToClientStrictly(fromBukkitBlock(block), clientVersion);
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

        if (isPistonHead(block.getType())) {
            return pistonHeadBounds(null, -1, block, block.getX(), block.getY(), block.getZ());
        }
        if (isPistonBase(block.getType())) {
            return pistonBaseBounds(null, -1, block, block.getX(), block.getY(), block.getZ());
        }

        List<CollisionBounds> modern = getModernCollisionBounds(block);

        if (modern != null) {
            return modern;
        }

        if (!hasPotentialCollision(block.getType())) {
            return Collections.emptyList();
        }

        List<CollisionBounds> legacy =
                getLegacyNmsCollisionBounds(block);

        if (legacy != null) {
            return legacy;
        }

        Boolean passable = isPassable(block);

        if (passable != null && passable) {
            return Collections.emptyList();
        }

        /*
         * NEVER invent a full cube for a legacy block whose actual shape may be
         * partial/state-dependent.
         *
         * A false positive here is exactly the "ghost block while in air" failure.
         */
        if (LEGACY_SERVER
                && isLegacyPartialMaterial(block.getType())) {
            Material mat = block.getType();
            if (isPistonHead(mat)) {
                return pistonHeadBounds(null, block.getData(), block, block.getX(), block.getY(), block.getZ());
            }
            if (isPistonBase(mat)) {
                return pistonBaseBounds(null, block.getData(), block, block.getX(), block.getY(), block.getZ());
            }
            if (isStair(mat)) {
                return stairBounds(block.getData(), block.getX(), block.getY(), block.getZ());
            }
            if (isLegacySlabMaterial(mat)) {
                return getCollisionBounds(mat, block.getData(), block.getX(), block.getY(), block.getZ());
            }
            if (mat.name().equals("IRON_FENCE") || mat.name().equals("IRON_BARS") || mat.name().endsWith("_PANE")) {
                return paneBounds(null, block.getX(), block.getY(), block.getZ());
            }
            return Collections.emptyList();
        }

        return Collections.singletonList(new CollisionBounds(
                block.getX(),
                block.getY(),
                block.getZ(),
                block.getX() + 1.0D,
                block.getY() + 1.0D,
                block.getZ() + 1.0D
        ));
    }

    private static boolean isLegacyPartialMaterial(Material material) {
        if (material == null) {
            return false;
        }

        String name = normalize(material.name());

        return name.equals("STEP")
                || name.equals("WOOD_STEP")
                || name.equals("STONE_SLAB2")
                || name.equals("DOUBLE_STEP")
                || name.equals("FENCE")
                || name.equals("NETHER_FENCE")
                || name.equals("IRON_FENCE")
                || name.equals("FENCE_GATE")
                || name.equals("TRAP_DOOR")
                || name.equals("IRON_TRAPDOOR")
                || name.equals("THIN_GLASS")
                || name.equals("IRON_BARS")
                || name.endsWith("_STAIRS")
                || name.endsWith("_SLAB")
                || name.endsWith("_FENCE")
                || name.endsWith("_FENCE_GATE")
                || name.endsWith("_WALL")
                || name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_CARPET")
                || isObviousPartialCollisionName(name);
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

            if (!(raw instanceof Collection<?>)) {
                return null;
            }
            Collection<?> rawBoxes = (Collection<?>) raw;

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

    /**
     * Bukkit did not expose collision boxes before the voxel-shape API. Obtain
     * the live server block state's own collision AABB instead of maintaining a
     * material hitbox table. A null return means the server internals could not
     * be resolved and the conservative generic fallback should be used.
     */
    private static List<CollisionBounds> getLegacyNmsCollisionBounds(Block block) {
        if (block == null
                || BLOCK_GET_COLLISION_SHAPE != null
                || BLOCK_GET_BOUNDING_BOX != null
                || legacyCollisionUnavailable) {
            return null;
        }

        try {
            Object worldHandle =
                    block.getWorld()
                            .getClass()
                            .getMethod("getHandle")
                            .invoke(block.getWorld());

            ensureLegacyCollisionAccess(worldHandle);

            if (legacyCollisionUnavailable
                    || legacyWorldGetState == null
                    || legacyBlockPositionConstructor == null
                    || legacyStateGetBlock == null
                    || legacyGetCollisionBox == null) {
                return null;
            }

            Object position =
                    legacyBlockPositionConstructor.newInstance(
                            block.getX(),
                            block.getY(),
                            block.getZ()
                    );

            Object state =
                    legacyWorldGetState.invoke(
                            worldHandle,
                            position
                    );

            if (state == null) {
                return Collections.emptyList();
            }

            Object nmsBlock =
                    legacyStateGetBlock.invoke(state);

            if (nmsBlock == null) {
                return Collections.emptyList();
            }

            /*
             * A large probe box is required by the legacy collision methods on
             * 1.8-1.12:
             *
             *   a(IBlockAccess, BlockPosition, IBlockData, AxisAlignedBB)
             */
            Object probeBox = createLegacyProbeBox();

            Object rawBox;

            synchronized (nmsBlock) {
                if (legacyUpdateShape != null
                        && canSupplyLegacy(
                        legacyUpdateShape.getParameterTypes(),
                        worldHandle,
                        position,
                        state,
                        probeBox
                )) {
                    legacyUpdateShape.invoke(
                            nmsBlock,
                            argumentsForLegacy(
                                    legacyUpdateShape.getParameterTypes(),
                                    worldHandle,
                                    position,
                                    state,
                                    probeBox
                            )
                    );
                }

                rawBox =
                        legacyGetCollisionBox.invoke(
                                nmsBlock,
                                argumentsForLegacy(
                                        legacyGetCollisionBox.getParameterTypes(),
                                        worldHandle,
                                        position,
                                        state,
                                        probeBox
                                )
                        );
            }

            if (rawBox == null) {
                return Collections.emptyList();
            }

            CollisionBounds bounds =
                    readLegacyNmsBox(rawBox);

            return bounds == null
                    ? null
                    : Collections.singletonList(bounds);

        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isLegacySlabMaterial(Material material) {
        if (material == null) {
            return false;
        }

        String name = normalize(material.name());

        return name.equals("STEP")
                || name.equals("WOOD_STEP")
                || name.equals("STONE_SLAB2")
                || name.equals("SLAB")
                || name.endsWith("_SLAB");
    }

    public static List<CollisionBounds> getCollisionBounds(
            Material material,
            int legacyData,
            int x,
            int y,
            int z
    ) {
        if (material == null
                || material == Material.AIR
                || !hasPotentialCollision(material)) {
            return Collections.emptyList();
        }

        if (isLegacySlabMaterial(material)
                && legacyData >= 0) {

            /*
             * Legacy slab:
             *
             * 0x8 = TOP
             * 0x0 = BOTTOM
             */
            if ((legacyData & 0x8) != 0) {
                return Collections.singletonList(
                        new CollisionBounds(
                                x,
                                y + 0.5D,
                                z,
                                x + 1.0D,
                                y + 1.0D,
                                z + 1.0D
                        )
                );
            }

            return Collections.singletonList(
                    new CollisionBounds(
                            x,
                            y,
                            z,
                            x + 1.0D,
                            y + 0.5D,
                            z + 1.0D
                    )
            );
        }

        if (isPistonHead(material)) {
            return pistonHeadBounds(null, legacyData, null, x, y, z);
        }
        if (isPistonBase(material)) {
            return pistonBaseBounds(null, legacyData, null, x, y, z);
        }

        String name = normalize(material.name());
        if (isStair(material)) {
            return stairBounds(legacyData, x, y, z);
        }
        if (name.contains("DOUBLE_STEP") || name.contains("DOUBLE_SLAB")) {
            return Collections.singletonList(
                    new CollisionBounds(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D)
            );
        }

        return getCollisionBounds(
                material,
                x,
                y,
                z
        );
    }

    /**
     * Extracts the Minecraft 1.8 metadata value (0-15) from a WrappedBlockState.
     * Returns -1 if the state cannot be converted.
     */
    public static int extractLegacyData(WrappedBlockState state, Material material) {
        if (state == null) {
            return -1;
        }

        if (isPistonHead(state) || isPistonHead(material)) {
            String facing = "UP";
            try {
                facing = String.valueOf(state.getFacing()).toUpperCase(Locale.ROOT);
            } catch (Throwable ignored) {}
            int dir = 1; // UP default
            switch (facing) {
                case "DOWN": dir = 0; break;
                case "UP": dir = 1; break;
                case "NORTH": dir = 2; break;
                case "SOUTH": dir = 3; break;
                case "WEST": dir = 4; break;
                case "EAST": dir = 5; break;
            }
            return dir;
        }

        if (isPistonBase(state) || isPistonBase(material)) {
            String facing = "UP";
            try {
                facing = String.valueOf(state.getFacing()).toUpperCase(Locale.ROOT);
            } catch (Throwable ignored) {}
            int dir = 1; // UP default
            switch (facing) {
                case "DOWN": dir = 0; break;
                case "UP": dir = 1; break;
                case "NORTH": dir = 2; break;
                case "SOUTH": dir = 3; break;
                case "WEST": dir = 4; break;
                case "EAST": dir = 5; break;
            }
            boolean extended = isExtendedPiston(state);
            return (extended ? 8 : 0) | dir;
        }

        if (isStair(state) || isStair(material)) {
            boolean top = false;
            try {
                top = "TOP".equalsIgnoreCase(String.valueOf(state.getHalf()));
            } catch (Throwable ignored) {}

            String facing = "NORTH";
            try {
                facing = String.valueOf(state.getFacing()).toUpperCase(Locale.ROOT);
            } catch (Throwable ignored) {}

            int dir = 3; // North default
            switch (facing) {
                case "EAST": dir = 0; break;
                case "WEST": dir = 1; break;
                case "SOUTH": dir = 2; break;
                case "NORTH": dir = 3; break;
            }

            return (top ? 4 : 0) | dir;
        }

        if (isSlab(state) || isLegacySlabMaterial(material)) {
            try {
                String type = String.valueOf(state.getTypeData());
                if ("TOP".equalsIgnoreCase(type)) {
                    return 8; // Bit 0x8 indicates top half slab
                }
            } catch (Throwable ignored) {}

            return 0; // Bottom half slab default
        }

        return -1;
    }

    private static synchronized void ensureLegacyCollisionAccess(
            Object worldHandle
    ) {
        if (worldHandle == null
                || legacyCollisionUnavailable
                || (legacyWorldClass != null
                && legacyWorldClass == worldHandle.getClass())) {
            return;
        }

        try {
            Method stateMethod = null;

            for (Method method : allMethods(worldHandle.getClass())) {
                if (method.getParameterCount() != 1) {
                    continue;
                }

                String parameter =
                        method.getParameterTypes()[0].getSimpleName();

                String returned =
                        method.getReturnType().getSimpleName();

                if ((parameter.equals("BlockPosition")
                        || parameter.endsWith("BlockPos"))
                        && (returned.contains("IBlockData")
                        || returned.contains("BlockState"))) {

                    stateMethod = accessible(method);
                    break;
                }
            }

            if (stateMethod == null) {
                legacyCollisionUnavailable = true;
                return;
            }

            Class<?> positionClass =
                    stateMethod.getParameterTypes()[0];

            Constructor<?> positionConstructor =
                    positionClass.getDeclaredConstructor(
                            int.class,
                            int.class,
                            int.class
                    );

            positionConstructor.setAccessible(true);

            Object probePosition =
                    positionConstructor.newInstance(
                            0,
                            0,
                            0
                    );

            Object probeState =
                    stateMethod.invoke(
                            worldHandle,
                            probePosition
                    );

            if (probeState == null) {
                legacyCollisionUnavailable = true;
                return;
            }

            Method getBlock = null;

            for (Method method : allMethods(probeState.getClass())) {
                if (method.getParameterCount() == 0
                        && method.getReturnType()
                        .getSimpleName()
                        .equals("Block")) {

                    getBlock = accessible(method);
                    break;
                }
            }

            if (getBlock == null) {
                legacyCollisionUnavailable = true;
                return;
            }

            Object nmsBlock =
                    getBlock.invoke(probeState);

            if (nmsBlock == null) {
                legacyCollisionUnavailable = true;
                return;
            }

            /*
             * Find the old AxisAlignedBB type first.
             */
            Class<?> aabbClass = null;

            outer:
            for (Method method : allMethods(nmsBlock.getClass())) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (isLegacyAabbType(parameter)) {
                        aabbClass = parameter;
                        break outer;
                    }
                }
            }

            if (aabbClass != null) {
                try {
                    Constructor<?> constructor =
                            aabbClass.getDeclaredConstructor(
                                    double.class,
                                    double.class,
                                    double.class,
                                    double.class,
                                    double.class,
                                    double.class
                            );

                    constructor.setAccessible(true);

                    legacyAabbClass = aabbClass;
                    legacyAabbConstructor = constructor;

                } catch (Throwable ignored) {
                    legacyAabbClass = null;
                    legacyAabbConstructor = null;
                }
            }

            Object probeAabb = null;

            if (legacyAabbConstructor != null) {
                probeAabb =
                        legacyAabbConstructor.newInstance(
                                -2.0D, -2.0D, -2.0D,
                                18.0D, 18.0D, 18.0D
                        );
            }

            Method updateShape = null;
            Method collisionBox = null;
            int collisionScore = -1;

            for (Method method : allMethods(nmsBlock.getClass())) {
                Class<?>[] parameters =
                        method.getParameterTypes();

                if ((method.getName().equals("updateShape")
                        || method.getName().equals("updateState")
                        || method.getName().equals("a"))
                        && method.getReturnType() == void.class
                        && canSupplyLegacy(
                        parameters,
                        worldHandle,
                        probePosition,
                        probeState,
                        probeAabb
                )) {
                    /*
                     * Prefer update methods with the most context.
                     */
                    if (updateShape == null
                            || parameters.length
                            > updateShape.getParameterCount()) {
                        updateShape = accessible(method);
                    }
                }

                if (isLegacyAabbType(method.getReturnType())
                        && canSupplyLegacy(
                        parameters,
                        worldHandle,
                        probePosition,
                        probeState,
                        probeAabb
                )) {

                    int score = parameters.length;

                    if (score > collisionScore) {
                        collisionBox = accessible(method);
                        collisionScore = score;
                    }
                }
            }

            if (collisionBox == null) {
                legacyCollisionUnavailable = true;
                return;
            }

            legacyWorldClass =
                    worldHandle.getClass();

            legacyWorldGetState =
                    stateMethod;

            legacyBlockPositionConstructor =
                    positionConstructor;

            legacyStateGetBlock =
                    getBlock;

            legacyUpdateShape =
                    updateShape;

            legacyGetCollisionBox =
                    collisionBox;

            legacyBoxFields = null;

        } catch (Throwable ignored) {
            legacyCollisionUnavailable = true;
        }
    }

    private static CollisionBounds readLegacyNmsBox(Object rawBox) throws Exception {
        Field[] fields = legacyBoxFields;

        if (fields == null || fields.length != 6 || !fields[0].getDeclaringClass().isInstance(rawBox)) {
            fields = findCoordinateFields(rawBox.getClass());
            legacyBoxFields = fields;
        }

        if (fields == null || fields.length != 6) {
            return null;
        }

        double minX = fields[0].getDouble(rawBox);
        double minY = fields[1].getDouble(rawBox);
        double minZ = fields[2].getDouble(rawBox);
        double maxX = fields[3].getDouble(rawBox);
        double maxY = fields[4].getDouble(rawBox);
        double maxZ = fields[5].getDouble(rawBox);

        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return null;
        }

        return new CollisionBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Field[] findCoordinateFields(Class<?> type) {
        String[][] knownNames = {
                {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"},
                {"a", "b", "c", "d", "e", "f"}
        };

        for (String[] names : knownNames) {
            Field[] result = new Field[6];
            boolean valid = true;

            for (int i = 0; i < names.length; i++) {
                try {
                    Field field = type.getDeclaredField(names[i]);
                    field.setAccessible(true);
                    result[i] = field;
                } catch (Throwable ignored) {
                    valid = false;
                    break;
                }
            }

            if (valid) {
                return result;
            }
        }

        List<Field> doubles = new ArrayList<>(6);

        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == double.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    doubles.add(field);
                }
            }
        }

        return doubles.size() == 6 ? doubles.toArray(new Field[0]) : null;
    }

    private static boolean canSupplyLegacy(
            Class<?>[] parameters,
            Object... candidates
    ) {
        try {
            argumentsForLegacy(parameters, candidates);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Object[] argumentsForLegacy(
            Class<?>[] parameters,
            Object... candidates
    ) {
        Object[] result =
                new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter =
                    parameters[i];

            Object match = null;

            /*
             * Existing runtime objects.
             */
            for (Object candidate : candidates) {
                if (parameter.isInstance(candidate)) {
                    match = candidate;
                    break;
                }
            }

            /*
             * Legacy AxisAlignedBB parameter.
             */
            if (match == null
                    && isLegacyAabbType(parameter)) {

                match = createLegacyProbeBox(parameter);
            }

            if (match == null) {
                throw new IllegalArgumentException(
                        "Unsupported legacy collision parameter: "
                                + parameter.getName()
                );
            }

            result[i] = match;
        }

        return result;
    }

    private static boolean isLegacyAabbType(Class<?> type) {
        if (type == null) {
            return false;
        }

        String name = type.getSimpleName();

        return name.equals("AxisAlignedBB")
                || name.equals("AABB");
    }

    private static Object createLegacyProbeBox() {
        if (legacyAabbConstructor == null) {
            return null;
        }

        try {
            return legacyAabbConstructor.newInstance(
                    -2.0D,
                    -2.0D,
                    -2.0D,
                    18.0D,
                    18.0D,
                    18.0D
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object createLegacyProbeBox(Class<?> expectedType) {
        if (!isLegacyAabbType(expectedType)) {
            return null;
        }

        if (legacyAabbConstructor != null
                && legacyAabbClass != null
                && expectedType.isAssignableFrom(legacyAabbClass)) {
            return createLegacyProbeBox();
        }

        try {
            Constructor<?> constructor =
                    expectedType.getDeclaredConstructor(
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class
                    );

            constructor.setAccessible(true);

            return constructor.newInstance(
                    -2.0D,
                    -2.0D,
                    -2.0D,
                    18.0D,
                    18.0D,
                    18.0D
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> result = new ArrayList<>();

        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Collections.addAll(result, current.getDeclaredMethods());
        }

        return result;
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
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
        return toWorldBounds(block.getX(), block.getY(), block.getZ(), local);
    }

    private static List<CollisionBounds> toWorldBounds(int x, int y, int z, List<LocalCollisionBounds> local) {
        if (local.isEmpty()) {
            return Collections.emptyList();
        }

        List<CollisionBounds> result = new ArrayList<>(local.size());

        for (LocalCollisionBounds box : local) {
            result.add(new CollisionBounds(
                    x + box.minX,
                    y + box.minY,
                    z + box.minZ,
                    x + box.maxX,
                    y + box.maxY,
                    z + box.maxZ
            ));
        }

        return result;
    }

    private static String collisionStateKey(Block block) {
        if (BLOCK_GET_BLOCK_DATA != null) {
            try {
                Object blockData = BLOCK_GET_BLOCK_DATA.invoke(block);

                if (blockData != null) {
                    if (BLOCK_DATA_GET_AS_STRING != null
                            && BLOCK_DATA_GET_AS_STRING.getDeclaringClass().isInstance(blockData)) {
                        Object state = BLOCK_DATA_GET_AS_STRING.invoke(blockData);
                        if (state != null) {
                            return state.toString();
                        }
                    }

                    return blockData.toString();
                }
            } catch (Throwable ignored) {
            }
        }

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
        return name.equals("STEP")
                || name.equals("WOOD_STEP")
                || name.equals("STONE_SLAB2")
                || name.equals("DOUBLE_STEP")
                || name.equals("FENCE")
                || name.equals("NETHER_FENCE")
                || name.equals("FENCE_GATE")
                || name.equals("TRAP_DOOR")
                || name.equals("IRON_TRAPDOOR")
                || name.endsWith("_STAIRS")
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
                || name.equals("BIG_DRIPLEAF")
                || name.contains("DAYLIGHT_DETECTOR")
                || name.equals("SCULK_SENSOR")
                || name.equals("CALIBRATED_SCULK_SENSOR")
                || name.equals("SCULK_SHRIEKER")
                || name.equals("LIGHTNING_ROD")
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
                || name.endsWith("_GRASS")
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

    public static Material materialFromState(StateType type) {
        if (type == null) {
            return null;
        }
        Material cached = STATE_TYPE_MATERIAL_CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        Material material = matchMaterialCompat(type.getName());
        if (material == null) {
            material = Material.AIR;
        }
        STATE_TYPE_MATERIAL_CACHE.put(type, material);
        return material;
    }

    public static Material matchMaterialCompat(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        String name = normalize(raw);
        if (name.isEmpty()) {
            return null;
        }

        name = name.replace("MATERIAL.", "")
                .replace("LEGACY_", "")
                .replace(" ", "_")
                .replace("-", "_");

        Material direct = Material.matchMaterial(name);
        if (direct != null) {
            return direct;
        }

        direct = Material.matchMaterial("LEGACY_" + name);
        if (direct != null) {
            return direct;
        }

        // Modern <-> Legacy mappings for cross-version support (1.7 - 26.2)
        switch (name) {
            case "GRASS_BLOCK":
                Material grass = Material.matchMaterial("GRASS");
                if (grass != null) return grass;
                break;
            case "SHORT_GRASS":
                Material shortGrass = Material.matchMaterial("LONG_GRASS");
                if (shortGrass != null) return shortGrass;
                shortGrass = Material.matchMaterial("GRASS");
                if (shortGrass != null) return shortGrass;
                break;
            case "COBWEB":
                Material web = Material.matchMaterial("WEB");
                if (web != null) return web;
                break;
            case "WEB":
                Material cobweb = Material.matchMaterial("COBWEB");
                if (cobweb != null) return cobweb;
                break;
            case "STONE_BRICKS":
            case "CRACKED_STONE_BRICKS":
            case "MOSSY_STONE_BRICKS":
            case "CHISELED_STONE_BRICKS":
                Material brick = Material.matchMaterial("SMOOTH_BRICK");
                if (brick != null) return brick;
                break;
            case "SMOOTH_BRICK":
                Material stoneBricks = Material.matchMaterial("STONE_BRICKS");
                if (stoneBricks != null) return stoneBricks;
                break;
            case "WORKBENCH":
                Material ct = Material.matchMaterial("CRAFTING_TABLE");
                if (ct != null) return ct;
                break;
            case "CRAFTING_TABLE":
                Material wb = Material.matchMaterial("WORKBENCH");
                if (wb != null) return wb;
                break;
            case "SOIL":
                Material fl = Material.matchMaterial("FARMLAND");
                if (fl != null) return fl;
                break;
            case "FARMLAND":
                Material soil = Material.matchMaterial("SOIL");
                if (soil != null) return soil;
                break;
            case "MOB_SPAWNER":
                Material spawner = Material.matchMaterial("SPAWNER");
                if (spawner != null) return spawner;
                break;
            case "SPAWNER":
                Material mobSpawner = Material.matchMaterial("MOB_SPAWNER");
                if (mobSpawner != null) return mobSpawner;
                break;
            case "PORTAL":
                Material netherPortal = Material.matchMaterial("NETHER_PORTAL");
                if (netherPortal != null) return netherPortal;
                break;
            case "NETHER_PORTAL":
                Material portal = Material.matchMaterial("PORTAL");
                if (portal != null) return portal;
                break;
            case "ENDER_PORTAL":
                Material endPortal = Material.matchMaterial("END_PORTAL");
                if (endPortal != null) return endPortal;
                break;
            case "END_PORTAL":
                Material enderPortal = Material.matchMaterial("ENDER_PORTAL");
                if (enderPortal != null) return enderPortal;
                break;
            case "ENDER_PORTAL_FRAME":
                Material endPortalFrame = Material.matchMaterial("END_PORTAL_FRAME");
                if (endPortalFrame != null) return endPortalFrame;
                break;
            case "END_PORTAL_FRAME":
                Material enderPortalFrame = Material.matchMaterial("ENDER_PORTAL_FRAME");
                if (enderPortalFrame != null) return enderPortalFrame;
                break;
            case "PISTON_HEAD":
                Material pExt = Material.matchMaterial("PISTON_EXTENSION");
                if (pExt != null) return pExt;
                break;
            case "PISTON_EXTENSION":
                Material pHead = Material.matchMaterial("PISTON_HEAD");
                if (pHead != null) return pHead;
                break;
            case "MOVING_PISTON":
                Material pPiece = Material.matchMaterial("PISTON_MOVING_PIECE");
                if (pPiece != null) return pPiece;
                break;
            case "PISTON_MOVING_PIECE":
                Material mPiston = Material.matchMaterial("MOVING_PISTON");
                if (mPiston != null) return mPiston;
                break;
            case "PISTON":
                Material pBase = Material.matchMaterial("PISTON_BASE");
                if (pBase != null) return pBase;
                break;
            case "PISTON_BASE":
                Material piston = Material.matchMaterial("PISTON");
                if (piston != null) return piston;
                break;
            case "STICKY_PISTON":
                Material sBase = Material.matchMaterial("PISTON_STICKY_BASE");
                if (sBase != null) return sBase;
                break;
            case "PISTON_STICKY_BASE":
                Material sPiston = Material.matchMaterial("STICKY_PISTON");
                if (sPiston != null) return sPiston;
                break;

            case "STONE_SLAB":
            case "SANDSTONE_SLAB":
            case "COBBLESTONE_SLAB":
            case "BRICK_SLAB":
            case "STONE_BRICK_SLAB":
            case "NETHER_BRICK_SLAB":
            case "QUARTZ_SLAB":
                Material step = Material.matchMaterial("STEP");
                if (step != null) return step;
                break;

            case "OAK_SLAB":
            case "SPRUCE_SLAB":
            case "BIRCH_SLAB":
            case "JUNGLE_SLAB":
            case "ACACIA_SLAB":
            case "DARK_OAK_SLAB":
                Material woodStep = Material.matchMaterial("WOOD_STEP");
                if (woodStep != null) return woodStep;
                break;

            case "RED_SANDSTONE_SLAB":
                Material slab2 = Material.matchMaterial("STONE_SLAB2");
                if (slab2 != null) return slab2;
                break;
            case "OAK_PLANKS":
            case "SPRUCE_PLANKS":
            case "BIRCH_PLANKS":
            case "JUNGLE_PLANKS":
            case "ACACIA_PLANKS":
            case "DARK_OAK_PLANKS":
            case "PLANKS":
                Material wood = Material.matchMaterial("WOOD");
                if (wood != null) return wood;
                break;
            case "WOOD":
                Material oakPlanks = Material.matchMaterial("OAK_PLANKS");
                if (oakPlanks != null) return oakPlanks;
                break;
            case "OAK_LOG":
            case "SPRUCE_LOG":
            case "BIRCH_LOG":
            case "JUNGLE_LOG":
                Material log = Material.matchMaterial("LOG");
                if (log != null) return log;
                break;
            case "ACACIA_LOG":
            case "DARK_OAK_LOG":
                Material log2 = Material.matchMaterial("LOG_2");
                if (log2 != null) return log2;
                break;
            case "OAK_LEAVES":
            case "SPRUCE_LEAVES":
            case "BIRCH_LEAVES":
            case "JUNGLE_LEAVES":
                Material leaves = Material.matchMaterial("LEAVES");
                if (leaves != null) return leaves;
                break;
            case "ACACIA_LEAVES":
            case "DARK_OAK_LEAVES":
                Material leaves2 = Material.matchMaterial("LEAVES_2");
                if (leaves2 != null) return leaves2;
                break;
            case "TERRACOTTA":
            case "WHITE_TERRACOTTA":
                Material hardClay = Material.matchMaterial("HARD_CLAY");
                if (hardClay != null) return hardClay;
                break;
            case "HARD_CLAY":
                Material terracotta = Material.matchMaterial("TERRACOTTA");
                if (terracotta != null) return terracotta;
                break;
            case "WHITE_WOOL":
                Material wool = Material.matchMaterial("WOOL");
                if (wool != null) return wool;
                break;
            case "WHITE_BED":
                Material bed = Material.matchMaterial("BED_BLOCK");
                if (bed != null) return bed;
                bed = Material.matchMaterial("BED");
                if (bed != null) return bed;
                break;
            case "REPEATER":
                Material repeater = Material.matchMaterial("DIODE_BLOCK_OFF");
                if (repeater != null) return repeater;
                repeater = Material.matchMaterial("DIODE");
                if (repeater != null) return repeater;
                break;
            case "COMPARATOR":
                Material comparator = Material.matchMaterial("REDSTONE_COMPARATOR_OFF");
                if (comparator != null) return comparator;
                comparator = Material.matchMaterial("REDSTONE_COMPARATOR");
                if (comparator != null) return comparator;
                break;
            case "ENCHANTING_TABLE":
                Material enchTable = Material.matchMaterial("ENCHANTMENT_TABLE");
                if (enchTable != null) return enchTable;
                break;
            case "ENCHANTMENT_TABLE":
                Material enchTableMod = Material.matchMaterial("ENCHANTING_TABLE");
                if (enchTableMod != null) return enchTableMod;
                break;
            case "OAK_FENCE":
                Material fence = Material.matchMaterial("FENCE");
                if (fence != null) return fence;
                break;
            case "OAK_FENCE_GATE":
                Material fenceGate = Material.matchMaterial("FENCE_GATE");
                if (fenceGate != null) return fenceGate;
                break;
            case "WATER":
            case "FLOWING_WATER":
                Material water = Material.matchMaterial("WATER");
                if (water != null) return water;
                return Material.matchMaterial("STATIONARY_WATER");
            case "STATIONARY_WATER":
                return Material.matchMaterial("WATER");
            case "LAVA":
            case "FLOWING_LAVA":
                Material lava = Material.matchMaterial("LAVA");
                if (lava != null) return lava;
                return Material.matchMaterial("STATIONARY_LAVA");
            case "STATIONARY_LAVA":
                return Material.matchMaterial("LAVA");
            default:
                break;
        }

        return null;
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

    public static WrappedBlockState fromBukkitMaterialData(MaterialData data) {
        if (data == null) {
            return null;
        }

        try {
            return SpigotConversionUtil.fromBukkitMaterialData(data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static class LocalCollisionBounds {
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;

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

    public static class CollisionBounds {
        public double minX;
        public double minY;
        public double minZ;
        public double maxX;
        public double maxY;
        public double maxZ;

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
