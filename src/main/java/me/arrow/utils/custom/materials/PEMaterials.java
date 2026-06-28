package me.arrow.utils.custom.materials;


import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.Locale;

public final class PEMaterials {

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

        String name = normalize(material.name());

        if (isAirLike(name) || isFluidLike(name)) return false;

        return isKnownCollisionNonFull(name);
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
                || name.equals("CHEST")
                || name.equals("ENDER_CHEST")
                || name.equals("TRAPPED_CHEST")
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