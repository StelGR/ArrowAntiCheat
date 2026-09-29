package me.arrow.utils;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.backend.bukkit.nms.NmsInstance;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static me.arrow.utils.customutils.Math.MathUtil.floor;

/**
 * A small utility class to use for nearby blocks and such.
 * NOTE: You may notice that things in here seem overly
 * Complicated or way different than what you're usually
 * Supposed to do, The reason for it is to avoid certain method calls
 * And focus on perfomance more than anything, Due to collisions usually being heavy.
 */
public class CollisionUtils {

    private CollisionUtils() {
    }

    /*
    The exact amount that gives us whether or not the player is serverside onground by using the modulo operator.
    The math for this is

    location.getY() % SERVER_GROUND_DIVISOR
     */
    public static final double SERVER_GROUND_DIVISOR = .015625D;

    /*
    The exact horizontal expansion we need in order to get all the blocks near the player.
     */
    private static final double EXPAND_HORIZONTAL = .75D;

    /* Slightly expanded footprint for ceiling collision. */
    private static final double PLAYER_HALF_WIDTH = .300001D;

    /* Ground requires the unexpanded 0.6-wide player body to overlap a block. */
    private static final double GROUND_PLAYER_HALF_WIDTH = .3D;
    private static final double GROUND_CONTACT_EPSILON = 1.0E-3D;
    /* Fences, walls, and closed gates have a collision top 1.5 blocks above their base. */
    private static final double MAX_SUPPORT_HEIGHT = 1.5D;

    /*
    The exact additional expansion we need in order to correctly account for blocks on top and below.
     */
    //private static final double EXPAND_ADDITIONAL = 2.000000000002E-6;
    private static final double EXPAND_ADDITIONAL = 2.000000000002E-6;

    /*
    The modulo values for full blocks in order to get if the player is at the edge of a block.
    The math for this is

    Math.abs(location.getX() % 1)
    Math.abs(location.getZ() % 1)
     */
    private static final double[] EDGE_MODULOS = {
            .7D,
            .72D,
            .28D,
            .3D
    };

    /*
    The modulo values for every single block in order to get if the player is against a wall.
    The math for this is

    Math.abs(location.getX() % 1)
    Math.abs(location.getZ() % 1)
     */
    private static final double[] WALL_MODULOS = {
            /*
            Full Blocks
             */
            .699999988079071D,
            .30000001192092896D,
            /*
            Glass Panes
             */
            .13749998807907104D,
            .862500011920929D,
            /*
            Cobblestone Walls
             */
            .050000011920928955D,
            .949999988079071D,
            .012499988079071045D,
            .987500011920929D,
            /*
            Fences
             */
            .07499998807907104D,
            .925000011920929D,
            /*
            Chests
             */
            .23750001192092896D,
            .762499988079071D,
            /*
            Heads
             */
            .19999998807907104D,
            .800000011920929D,
            /*
            Chains
             */
            .10624998807907104D,
            .893750011920929D,
            /*
            Bamboo
             */
            .9895833283662796D,
            .35624998807907104D,
            .7770833522081375D,
            .14375001192092896D,
            /*
            Anvils
             */
            .824999988079071D,
            .17500001192092896D,
            .11250001192092896D,
            .887499988079071D
    };

    public static boolean isNearWall(final CustomLocation location) {
        if (location == null) return false;

        final double x = location.getX() - Math.floor(location.getX());
        final double z = location.getZ() - Math.floor(location.getZ());

        for (double modulo : WALL_MODULOS) {
            final double moduloX = Math.abs(x - modulo);
            final double moduloZ = Math.abs(z - modulo);

            if (moduloX < 1.0E-4D || moduloZ < 1.0E-4D) return true;
        }

        return false;
    }

    /*
    Check if the player is near the edge of a block by using the fractional coordinate within the block.
    Verifies that the adjacent block below is actually empty/non-solid so flat ground is not flagged as an edge.
     */
    public static boolean isNearEdge(final CustomLocation location) {
        if (location == null) return false;

        final double x = location.getX() - Math.floor(location.getX());
        final double z = location.getZ() - Math.floor(location.getZ());

        boolean nearMinX = x > EDGE_MODULOS[2] && x < EDGE_MODULOS[3]; // [0.28, 0.30]
        boolean nearMaxX = x > EDGE_MODULOS[0] && x < EDGE_MODULOS[1]; // [0.70, 0.72]
        boolean nearMinZ = z > EDGE_MODULOS[2] && z < EDGE_MODULOS[3]; // [0.28, 0.30]
        boolean nearMaxZ = z > EDGE_MODULOS[0] && z < EDGE_MODULOS[1]; // [0.70, 0.72]

        if (!nearMinX && !nearMaxX && !nearMinZ && !nearMaxZ) {
            return false;
        }

        if (location.getWorld() != null) {
            int blockX = location.getBlockX();
            int blockY = (int) Math.floor(location.getY() - 0.5D);
            int blockZ = location.getBlockZ();

            if (nearMinX && !isSolidAt(location.getWorld(), blockX - 1, blockY, blockZ)) return true;
            if (nearMaxX && !isSolidAt(location.getWorld(), blockX + 1, blockY, blockZ)) return true;
            if (nearMinZ && !isSolidAt(location.getWorld(), blockX, blockY, blockZ - 1)) return true;
            if (nearMaxZ && !isSolidAt(location.getWorld(), blockX, blockY, blockZ + 1)) return true;

            return false;
        }

        return true;
    }

    private static boolean isSolidAt(World world, int x, int y, int z) {
        Material mat = ChunkCache.get().getBlock(world.getName(), x, y, z);
        return mat != null && mat.isSolid();
    }

    public static float getBlockSlipperiness(final Material type) {
        if (type == null) return MoveUtils.FRICTION_FACTOR;

        /*
         * This lookup sometimes samples a pass-through block beside/below the
         * player (for example a sign).  It must behave as air, not as a normal
         * 0.6 floor.  Do not use Material#isTransparent alone: glass, carpets,
         * fences and closed gates are transparent-looking but still have a
         * collision shape a player can stand on.
         */
        if (usesAirFriction(type)) {
            return MoveUtils.FRICTION;
        }

        return switch (type) {
            case SLIME_BLOCK -> .8F;
            case ICE, PACKED_ICE -> .98F;
            case BLUE_ICE -> .989F;
            default -> {
                if ("FROSTED_ICE".equals(type.name())) yield .98F;
                yield MoveUtils.FRICTION_FACTOR;
            }
        };
    }

    private static boolean usesAirFriction(final Material material) {
        String name = material.name();

        if (name.equals("AIR") || name.equals("VOID_AIR") || name.equals("CAVE_AIR")) {
            return true;
        }

        // Fluids and powder snow have dedicated movement handling.  They are
        // not a floor, but also must not be reduced to the ordinary air case.
        if (MaterialType.isMaterial(name, MaterialType.LIQUID) || name.equals("POWDER_SNOW")) {
            return false;
        }

        // This is cached by PEMaterials and recognises signs, plants, torches,
        // rails, banners, vines and other genuinely pass-through shapes while
        // preserving collision-bearing transparent materials as block support.
        return !PEMaterials.hasPotentialCollision(material);
    }

    public static boolean isServerGround(final double y) {
        /*
        You should be checking if it's zero, Otherwise falling from very high
        Distances can mess with this, I'm sorry dawson but it's true.
         */
        return Math.abs(y) % SERVER_GROUND_DIVISOR == 0D;
    }

    /*
    A smart way to check if the player has a certain block under them
    Without touching the block itself.
     */
    public static boolean hasBlockUnder(final CustomLocation location, final CustomLocation blockLocation) {
        if (location == null || blockLocation == null) return false;

        final double locationX = location.getX();
        final double locationY = location.getY();
        final double locationZ = location.getZ();

        final double blockX = blockLocation.getX();
        final double blockY = blockLocation.getY();
        final double blockZ = blockLocation.getZ();

        final double deltaX = MathUtils.getAbsoluteDelta(blockX, locationX);
        final double deltaY = blockY - locationY;
        final double deltaZ = MathUtils.getAbsoluteDelta(blockZ, locationZ);

        return deltaX <= 0.8D && deltaY < 0D && deltaY >= -2.5D && deltaZ <= 0.8D;
    }

    public static boolean hasBlockUnder2(final CustomLocation location, final CustomLocation blockLocation) {
        final double locationX = location.getX();
        final double locationZ = location.getZ();

        final double blockX = blockLocation.getX();
        final double blockZ = blockLocation.getZ();

        final double deltaX = MathUtils.getAbsoluteDelta(blockX, locationX);
        final double deltaZ = MathUtils.getAbsoluteDelta(blockZ, locationZ);

        final double maxHorizontal = 0.8D;

        return deltaX <= maxHorizontal && deltaZ <= maxHorizontal;
    }


    public static boolean isStandingOnMaterial(final CustomLocation loc,
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final MaterialType... targets) {
        if (loc == null || targets == null || targets.length == 0) return false;

        // Build predicate from MaterialType targets
        Predicate<Material> predicate = material -> {
            if (material == null) return false;
            for (MaterialType t : targets) {
                if (MaterialType.isMaterial(material.name(), t)) return true;
            }
            return false;
        };

        return isStandingOnMaterial(loc, nearby, predicate);
    }

    public static boolean isStandingOnSlime(final CustomLocation loc,
                                            final CollisionUtils.NearbyBlocksResult nearby,
                                            final MaterialType... targets) {
        if (loc == null || targets == null || targets.length == 0) return false;

        // Build predicate from MaterialType targets
        Predicate<Material> predicate = material -> {
            if (material == null) return false;
            for (MaterialType t : targets) {
                if (MaterialType.isMaterial(material.name(), t)) return true;
            }
            return false;
        };

        return isStandingOnSlime(loc, nearby, predicate);
    }


    /**
     * General check using Bukkit Material constants.
     */
    public static boolean isStandingOnMaterial(final CustomLocation loc,
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final Material... targets) {
        if (loc == null || targets == null || targets.length == 0) return false;

        Predicate<Material> predicate = material -> {
            if (material == null) return false;
            for (Material t : targets) {
                if (material == t) return true;
            }
            return false;
        };

        return isStandingOnMaterial(loc, nearby, predicate);
    }


    public static boolean hasWaterUnder(final CustomLocation location, final CustomLocation blockLocation) {
        if (location == null || blockLocation == null) return false;

        final double locationX = location.getX();
        final double locationY = location.getY();
        final double locationZ = location.getZ();

        final double blockX = blockLocation.getX();
        final double blockY = blockLocation.getY();
        final double blockZ = blockLocation.getZ();

        final double deltaX = MathUtils.getAbsoluteDelta(blockX, locationX);
        final double deltaY = blockY - locationY;
        final double deltaZ = MathUtils.getAbsoluteDelta(blockZ, locationZ);

        return deltaX < .61D && deltaY <= 0.2D && deltaY >= -1.3D && deltaZ < .61D;
    }

    public static boolean isStandingOnWater(final CustomLocation loc,
                                            final CollisionUtils.NearbyBlocksResult nearby,
                                            final MaterialType... targets) {
        if (loc == null || targets == null || targets.length == 0) return false;

        // Build predicate from MaterialType targets
        Predicate<Material> predicate = material -> {
            if (material == null) return false;
            for (MaterialType t : targets) {
                if (MaterialType.isMaterial(material.name(), t)) return true;
            }
            return false;
        };

        return isStandingOnWater(loc, nearby, predicate);
    }

    public static boolean isStandingOnWater(final CustomLocation loc,
                                            final CollisionUtils.NearbyBlocksResult nearby,
                                            final Predicate<Material> predicate) {
        if (loc == null || predicate == null) return false;

        final int baseX = loc.getBlockX();
        final int baseY = (int) Math.floor(loc.getY() - 0.01D);
        final int baseZ = loc.getBlockZ();

        boolean anyCandidateChecked = false;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                CustomLocation blockLoc = loc.clone();
                blockLoc.setX(baseX + dx + 0.5);
                blockLoc.setY(baseY);
                blockLoc.setZ(baseZ + dz + 0.5);

                Material blockMat = getMaterial(blockLoc);
                if (blockMat != null && blockMat != Material.AIR) {
                    anyCandidateChecked = true;
                    if (CollisionUtils.hasWaterUnder(loc, blockLoc) && (predicate.test(blockMat) || isWaterLogged(blockLoc))) {
                        return true;
                    }
                }
            }
        }

        if (!anyCandidateChecked && nearby != null) {
            if (nearby.isNearWaterLogged()) return true;
            if (CollisionUtils.hasBlockUnder2(loc, loc.clone().subtract(0, 1, 0))) {
                for (Material m : nearby.getBlockTypes()) {
                    if (predicate.test(m)) return true;
                }
            }
        }

        return false;
    }

    public static boolean isInsideWater(final CustomLocation location) {
        return isInsideWater(location, 0.3D, 1.8D);
    }

    public static boolean isInsideWater(final CustomLocation location, final double halfWidth, final double height) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        final World world = location.getWorld();
        final String worldName = world.getName();

        // Player bounding box deflated by 0.001D to match vanilla Minecraft fluid collision
        final double minX = location.getX() - halfWidth + 0.001D;
        final double maxX = location.getX() + halfWidth - 0.001D;
        final double minY = location.getY() + 0.001D;
        final double maxY = location.getY() + height - 0.001D;
        final double minZ = location.getZ() - halfWidth + 0.001D;
        final double maxZ = location.getZ() + halfWidth - 0.001D;

        if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
            return false;
        }

        final int minBX = (int) Math.floor(minX);
        final int maxBX = (int) Math.floor(maxX);
        final int minBY = (int) Math.floor(minY);
        final int maxBY = (int) Math.floor(maxY);
        final int minBZ = (int) Math.floor(minZ);
        final int maxBZ = (int) Math.floor(maxZ);

        final ChunkCache cache = ChunkCache.get();

        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                for (int by = minBY; by <= maxBY; by++) {
                    Material mat = cache.getBlock(worldName, bx, by, bz);
                    if (mat == null) {
                        Block b = getBlock(new CustomLocation(world, bx, by, bz), true);
                        if (b != null) {
                            mat = Arrow.getInstance().getNmsManager().getNmsInstance().getType(b);
                        }
                    }
                    if (mat == null || mat == Material.AIR) {
                        continue;
                    }

                    boolean isWater = ChunkCache.isWaterMaterial(mat);
                    boolean isWaterlogged = !isWater && (cache.isWaterLogged(worldName, bx, by, bz) || isWaterLogged(world, bx, by, bz));

                    if (!isWater && !isWaterlogged) {
                        continue;
                    }

                    // Flowing water height calculation:
                    double waterMaxY = by + getWaterHeight(world, bx, by, bz);

                    // Overlap between player's deflated AABB and the block's water volume
                    double overlapMinX = Math.max(minX, bx);
                    double overlapMaxX = Math.min(maxX, bx + 1.0D);
                    double overlapMinY = Math.max(minY, by);
                    double overlapMaxY = Math.min(maxY, waterMaxY);
                    double overlapMinZ = Math.max(minZ, bz);
                    double overlapMaxZ = Math.min(maxZ, bz + 1.0D);

                    if (overlapMinX >= overlapMaxX || overlapMinY >= overlapMaxY || overlapMinZ >= overlapMaxZ) {
                        continue;
                    }

                    // Pure water blocks have no solid collision: player is inside water!
                    if (isWater) {
                        return true;
                    }

                    // For waterlogged blocks, inspect the solid collision geometry of the block
                    WrappedBlockState state = cache.getBlockState(worldName, bx, by, bz);

                    // 1. Slabs
                    boolean isSlab = PEMaterials.isSlab(state)
                            || mat.name().contains("SLAB")
                            || mat.name().contains("STEP");

                    if (isSlab) {
                        String slabType = state != null ? String.valueOf(state.getTypeData()) : null;
                        if (slabType == null || "null".equalsIgnoreCase(slabType)) {
                            int legacyData = cache.getLegacyBlockData(worldName, bx, by, bz);
                            if (legacyData >= 0) {
                                slabType = (legacyData & 0x8) != 0 ? "TOP" : "BOTTOM";
                            }
                        }
                        if ("DOUBLE".equalsIgnoreCase(slabType)) {
                            continue; // Double slab is fully solid
                        }
                        if ("TOP".equalsIgnoreCase(slabType)) {
                            // Solid is [by + 0.5, by + 1.0], water is [by, by + 0.5]
                            if (overlapMinY < by + 0.5D) {
                                return true;
                            }
                            continue;
                        }
                        // BOTTOM slab: solid is [by, by + 0.5], water is [by + 0.5, waterMaxY]
                        if (overlapMaxY > by + 0.5D) {
                            return true;
                        }
                        continue;
                    }

                    // 2. Generic partial blocks: fetch collision bounding boxes
                    List<PEMaterials.CollisionBounds> bounds = null;
                    if (state != null) {
                        bounds = PEMaterials.getCollisionBounds(state, bx, by, bz);
                    }
                    if (bounds == null) {
                        int legacyData = cache.getLegacyBlockData(worldName, bx, by, bz);
                        if (legacyData >= 0 && PEMaterials.isLegacySlabMaterial(mat)) {
                            bounds = PEMaterials.getCollisionBounds(mat, legacyData, bx, by, bz);
                        }
                    }
                    if (bounds == null || bounds.isEmpty()) {
                        // Passable waterlogged blocks like signs, banners, ladders
                        return true;
                    }

                    // Check if the player overlap is completely inside any solid collision box
                    boolean fullyInsideSolid = false;
                    for (PEMaterials.CollisionBounds box : bounds) {
                        if (box.minX <= overlapMinX && box.maxX >= overlapMaxX
                                && box.minY <= overlapMinY && box.maxY >= overlapMaxY
                                && box.minZ <= overlapMinZ && box.maxZ >= overlapMaxZ) {
                            fullyInsideSolid = true;
                            break;
                        }
                    }

                    if (!fullyInsideSolid) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static int getWaterLevel(final World world, final int x, final int y, final int z) {
        if (world == null) return 0;
        final String worldName = world.getName();
        final ChunkCache cache = ChunkCache.get();

        // 1. Try PacketEvents cached block state
        WrappedBlockState state = cache.getBlockState(worldName, x, y, z);
        if (state != null) {
            String str = state.toString();
            int idx = str.indexOf("level=");
            if (idx != -1) {
                int end = idx + 6;
                while (end < str.length() && Character.isDigit(str.charAt(end))) {
                    end++;
                }
                try {
                    return Integer.parseInt(str.substring(idx + 6, end));
                } catch (Throwable ignored) {}
            }
        }

        // 2. Try legacy block data (1.8 - 1.12)
        int legacyData = cache.getLegacyBlockData(worldName, x, y, z);
        if (legacyData >= 0) {
            if ((legacyData & 0x8) != 0) {
                return 8; // Falling water
            }
            return legacyData & 0x7;
        }

        // 3. Fallback to Bukkit Block
        Block b = getBlock(new CustomLocation(world, x, y, z), true);
        if (b != null) {
            try {
                Object bd = b.getBlockData();
                if (bd instanceof org.bukkit.block.data.Levelled) {
                    return ((org.bukkit.block.data.Levelled) bd).getLevel();
                }
            } catch (Throwable ignored) {}
            try {
                byte data = b.getData();
                if ((data & 0x8) != 0) return 8;
                return data & 0x7;
            } catch (Throwable ignored) {}
        }

        return 0; // Default source block (level 0)
    }

    public static int getWaterLevel(final CustomLocation location) {
        if (location == null || location.getWorld() == null) return 0;
        return getWaterLevel(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static double getWaterHeight(final World world, final int x, final int y, final int z) {
        if (world == null) return 0.0D;
        final String worldName = world.getName();
        final ChunkCache cache = ChunkCache.get();

        // If water or waterlogged block is directly above, fluid fills the entire 1.0 block height
        Material matAbove = cache.getBlock(worldName, x, y + 1, z);
        if (ChunkCache.isWaterMaterial(matAbove) || cache.isWaterLogged(worldName, x, y + 1, z)) {
            return 1.0D;
        }

        int level = getWaterLevel(world, x, y, z);
        if (level >= 8) {
            return 1.0D; // Falling water
        }
        if (level <= 0) {
            return 8.0D / 9.0D; // Source block surface level (~0.8888889D)
        }
        // Flowing water stages 1 to 7:
        return (8 - level) / 9.0D;
    }

    public static double getWaterHeight(final CustomLocation location) {
        if (location == null || location.getWorld() == null) return 0.0D;
        return getWaterHeight(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static boolean isFlowingWater(final World world, final int x, final int y, final int z) {
        int level = getWaterLevel(world, x, y, z);
        return level > 0 && level < 8;
    }

    public static boolean isWaterSource(final World world, final int x, final int y, final int z) {
        return getWaterLevel(world, x, y, z) == 0;
    }

    public static boolean isStandingOnMaterial(final CustomLocation loc,
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final Predicate<Material> predicate) {
        if (loc == null || predicate == null) return false;

        final int baseX = loc.getBlockX();
        final int baseZ = loc.getBlockZ();

        final int feetBlockY = (int) Math.floor(loc.getY() - 0.001D);

        boolean anyCandidateChecked = false;

        for (int dy = 0; dy >= -1; dy--) {
            final int y = feetBlockY + dy;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    CustomLocation blockLoc = loc.clone();
                    blockLoc.setX(baseX + dx + 0.5);
                    blockLoc.setY(y);
                    blockLoc.setZ(baseZ + dz + 0.5);

                    Material blockMat = getMaterial(blockLoc);
                    if (blockMat != null && blockMat != Material.AIR) {
                        anyCandidateChecked = true;
                        if (hasBlockUnder2(loc, blockLoc) && predicate.test(blockMat)) {
                            return true;
                        }
                    }
                }
            }
        }

        if (!anyCandidateChecked && nearby != null) {
            for (Material m : nearby.getBlockTypes()) {
                if (predicate.test(m)) return true;
            }
        }

        return false;
    }

    public static boolean isStandingOnSlime(final CustomLocation loc,
                                            final NearbyBlocksResult nearby,
                                            final Predicate<Material> predicate) {
        if (loc == null || predicate == null) return false;

        final int baseX = loc.getBlockX();
        final int baseZ = loc.getBlockZ();

        final int feetBlockY = (int) Math.floor(loc.getY() - 0.001D);

        boolean anyCandidateChecked = false;

        for (int dy = 0; dy >= -1; dy--) {
            final int y = feetBlockY + dy;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    CustomLocation blockLoc = loc.clone();
                    blockLoc.setX(baseX + dx + 0.5);
                    blockLoc.setY(y);
                    blockLoc.setZ(baseZ + dz + 0.5);

                    Material blockMat = getMaterial(blockLoc);
                    if (blockMat != null && blockMat != Material.AIR) {
                        anyCandidateChecked = true;
                        if (hasBlockUnder(loc, blockLoc) && predicate.test(blockMat)) {
                            return true;
                        }
                    }
                }
            }
        }

        if (!anyCandidateChecked && nearby != null) {
            for (Material m : nearby.getBlockTypes()) {
                if (predicate.test(m)) return true;
            }
        }

        return false;
    }

    public static Material getMaterial(final CustomLocation location) {
        if (location == null || location.getWorld() == null) {
            return Material.AIR;
        }
        Material cached = me.arrow.playerdata.cache.ChunkCache.get().getBlock(location);
        if (cached != null && cached != Material.AIR) {
            return cached;
        }
        Block block = getBlock(location, true);
        if (block != null) {
            Material type = Arrow.getInstance().getNmsManager().getNmsInstance().getType(block);
            if (type != null && type != Material.AIR) {
                ChunkCache.get().setBlock(location, type);
                return type;
            }
        }
        return cached != null ? cached : Material.AIR;
    }

    public static boolean isChunkLoaded(final CustomLocation location) {
        if (location == null || location.getWorld() == null) return false;
        String worldName = location.getWorld().getName();
        double x = location.getX();
        double z = location.getZ();
        int cx = (int) Math.floor(x) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        if (!ChunkCache.get().isChunkLoaded(location.getWorld(), cx, cz)) {
            ChunkCache.get().queueMissingChunk(worldName, cx, cz);
            return false;
        }
        int minCx = (int) Math.floor(x - 0.3) >> 4;
        int maxCx = (int) Math.floor(x + 0.3) >> 4;
        int minCz = (int) Math.floor(z - 0.3) >> 4;
        int maxCz = (int) Math.floor(z + 0.3) >> 4;
        if (minCx != cx && !ChunkCache.get().isChunkLoaded(location.getWorld(), minCx, cz)) {
            ChunkCache.get().queueMissingChunk(worldName, minCx, cz);
            return false;
        }
        if (maxCx != cx && !ChunkCache.get().isChunkLoaded(location.getWorld(), maxCx, cz)) {
            ChunkCache.get().queueMissingChunk(worldName, maxCx, cz);
            return false;
        }
        if (minCz != cz && !ChunkCache.get().isChunkLoaded(location.getWorld(), cx, minCz)) {
            ChunkCache.get().queueMissingChunk(worldName, cx, minCz);
            return false;
        }
        if (maxCz != cz && !ChunkCache.get().isChunkLoaded(location.getWorld(), cx, maxCz)) {
            ChunkCache.get().queueMissingChunk(worldName, cx, maxCz);
            return false;
        }
        return true;
    }

    public static boolean isChunkLoaded(final Location location) {
        if (location == null || location.getWorld() == null) return false;
        String worldName = location.getWorld().getName();
        double x = location.getX();
        double z = location.getZ();
        int cx = (int) Math.floor(x) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        if (!ChunkCache.get().isChunkLoaded(location.getWorld(), cx, cz)) {
            ChunkCache.get().queueMissingChunk(worldName, cx, cz);
            return false;
        }
        int minCx = (int) Math.floor(x - 0.3) >> 4;
        int maxCx = (int) Math.floor(x + 0.3) >> 4;
        int minCz = (int) Math.floor(z - 0.3) >> 4;
        int maxCz = (int) Math.floor(z + 0.3) >> 4;
        if (minCx != cx && !ChunkCache.get().isChunkLoaded(location.getWorld(), minCx, cz)) {
            ChunkCache.get().queueMissingChunk(worldName, minCx, cz);
            return false;
        }
        if (maxCx != cx && !ChunkCache.get().isChunkLoaded(location.getWorld(), maxCx, cz)) {
            ChunkCache.get().queueMissingChunk(worldName, maxCx, cz);
            return false;
        }
        if (minCz != cz && !ChunkCache.get().isChunkLoaded(location.getWorld(), cx, minCz)) {
            ChunkCache.get().queueMissingChunk(worldName, cx, minCz);
            return false;
        }
        if (maxCz != cz && !ChunkCache.get().isChunkLoaded(location.getWorld(), cx, maxCz)) {
            ChunkCache.get().queueMissingChunk(worldName, cx, maxCz);
            return false;
        }
        return true;
    }

    public static boolean isWaterLogged(final World world, final int x, final int y, final int z) {
        if (world == null) return false;
        if (me.arrow.playerdata.cache.ChunkCache.get().isWaterLogged(world, x, y, z)) return true;
        Block block = getBlock(new CustomLocation(world, x, y, z), true);
        if (block != null) {
            boolean wl = Arrow.getInstance().getNmsManager().getNmsInstance().isWaterLogged(block);
            if (wl) {
                me.arrow.playerdata.cache.ChunkCache.get().setWaterLogged(world, x, y, z, true);
            }
            return wl;
        }
        return false;
    }

    public static boolean isWaterLogged(final CustomLocation location) {
        if (location == null || location.getWorld() == null) return false;
        return isWaterLogged(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static Block getBlockAsync(final CustomLocation location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        if (TaskUtils.isFoliaServer()) {
            if (!TaskUtils.isOwnedByCurrentRegion(location)) {
                return null;
            }
        }

        try {
            return location.getBlock();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Block getBlock(final CustomLocation location, boolean async) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(location)) {
            return null;
        }
        if (async) {
            return getBlockAsync(location);
        }
        try {
            return location.getBlock();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static NearbyBlocksResult getNearbyBlocks(final CustomLocation location, final boolean async) {
        return getNearbyBlocks(location, null, async);
    }

    public static NearbyBlocksResult getNearbyBlocks(final CustomLocation location,
                                                     final CustomLocation previousLocation,
                                                     final boolean async) {

        NearbyBlocksResult result = new NearbyBlocksResult();

        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();

        /*
        A list that we'll be using in order to detect duplicate blocks.
         */
        final double locationX = location.getX();
        final double locationY = location.getY();
        final double locationZ = location.getZ();

        final double aboveY = locationY + 1.9D;
        final double middleY = locationY + 1D;
        /*
         * The original -0.5 probe skips bottom slabs because their top is at
         * Y + 0.5. Sampling immediately below the feet works for every support
         * height while PEMaterials still decides whether the block can collide.
         */
        final double underY = locationY - 1.0E-6D;

        CustomLocation cloned = location.clone();

        for (double x = -EXPAND_HORIZONTAL; x <= EXPAND_HORIZONTAL; x += EXPAND_HORIZONTAL) {

            for (double z = -EXPAND_HORIZONTAL; z <= EXPAND_HORIZONTAL; z += EXPAND_HORIZONTAL) {

                /*
                Get the additional expansion amount.
                 */
                final double additionalX = x > 0D ? -EXPAND_ADDITIONAL : EXPAND_ADDITIONAL;
                final double additionalZ = z > 0D ? -EXPAND_ADDITIONAL : EXPAND_ADDITIONAL;

                /*
                Get the horizontal expansion amount.
                 */
                final double expandX = locationX + x;
                final double expandZ = locationZ + z;

                /*
                Expand additionally since we're going to get the blocks above and under first.
                 */
                cloned.setX(expandX + additionalX);
                cloned.setZ(expandZ + additionalZ);

                above:
                {
                    cloned.setY(aboveY);
                    final Block above = getBlock(cloned, async);
                    result.handle(cloned, above, nms);
                }

                under:
                {
                    cloned.setY(underY);
                    final Block under = getBlock(cloned, async);
                    result.handle(cloned, under, nms);
                }

                /*
                Expand properly.
                 */
                cloned.setX(expandX);
                cloned.setZ(expandZ);

                middle:
                {
                    cloned.setY(middleY);
                    final Block middle = getBlock(cloned, async);
                    result.handle(cloned, middle, nms);
                }

                below:
                {
                    cloned.setY(locationY);
                    final Block below = getBlock(cloned, async);
                    result.handle(cloned, below, nms);
                }
            }
        }

        /* Ground and ceiling state come from collision shapes, not the wider nearby-material scan. */
        result.resolveExactCollision(location, previousLocation, async, nms);

        return result;
    }

    @Getter
    public static class NearbyBlocksResult {

        private final List<Material> blockTypes = new ArrayList<>();

        private boolean exactGroundSupport, landingGroundSupport, blockAbove, nearWaterLogged;
        private boolean unresolvedCollisionShape;

        private void handle(CustomLocation location, Block block, NmsInstance nms) {

            Material type = null;
            if (location != null) {
                type = me.arrow.playerdata.cache.ChunkCache.get().getBlock(location);
            }
            if ((type == null || type == Material.AIR) && block != null) {
                type = nms.getType(block);
            }

            if (type == null || type == Material.AIR) return;

            if (!this.nearWaterLogged) {
                if (type.name().contains("WATER")
                        || (location != null && ChunkCache.get().isWaterLogged(location))
                        || (block != null && nms.isWaterLogged(block))) {
                    this.nearWaterLogged = true;
                }
            }

            if (this.blockTypes.contains(type)) return;

            this.blockTypes.add(type);
        }

        private void resolveExactCollision(CustomLocation location, CustomLocation previousLocation,
                                           boolean async, NmsInstance nms) {
            if (location == null || location.getWorld() == null) {
                return;
            }

            double playerMinX = location.getX() - PLAYER_HALF_WIDTH;
            double playerMaxX = location.getX() + PLAYER_HALF_WIDTH;
            double playerMinZ = location.getZ() - PLAYER_HALF_WIDTH;
            double playerMaxZ = location.getZ() + PLAYER_HALF_WIDTH;
            double feetY = location.getY();

            boolean descending = previousLocation != null
                    && previousLocation.getWorld() == location.getWorld()
                    && feetY < previousLocation.getY() - GROUND_CONTACT_EPSILON;
            double sweepMinX = descending
                    ? Math.min(location.getX(), previousLocation.getX()) - GROUND_PLAYER_HALF_WIDTH
                    : location.getX() - GROUND_PLAYER_HALF_WIDTH;
            double sweepMaxX = descending
                    ? Math.max(location.getX(), previousLocation.getX()) + GROUND_PLAYER_HALF_WIDTH
                    : location.getX() + GROUND_PLAYER_HALF_WIDTH;
            double sweepMinZ = descending
                    ? Math.min(location.getZ(), previousLocation.getZ()) - GROUND_PLAYER_HALF_WIDTH
                    : location.getZ() - GROUND_PLAYER_HALF_WIDTH;
            double sweepMaxZ = descending
                    ? Math.max(location.getZ(), previousLocation.getZ()) + GROUND_PLAYER_HALF_WIDTH
                    : location.getZ() + GROUND_PLAYER_HALF_WIDTH;

            /* Player head/ceiling band. */
            double headMinY = feetY + 1.425D;
            double headMaxY = feetY + 1.950001D;

            int minX = floor(Math.min(playerMinX, sweepMinX));
            int maxX = floor(Math.max(playerMaxX, sweepMaxX));
            int minZ = floor(Math.min(playerMinZ, sweepMinZ));
            int maxZ = floor(Math.max(playerMaxZ, sweepMaxZ));
            /*
             * A fence's top can be below the feet block (for example, its base
             * is Y=64 while its collision top and the player's feet are 65.5).
             * Include every possible support base, then let the exact top and
             * footprint tests below decide whether it is actually supporting.
             */
            int minY = floor(feetY - MAX_SUPPORT_HEIGHT);
            int maxY = floor(headMaxY);

            CustomLocation probe = location.clone();

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        probe.setX(x + 0.5D);
                        probe.setY(y + 0.5D);
                        probe.setZ(z + 0.5D);

                        Material material = ChunkCache.get().getBlock(probe);
                        int legacyData =
                                ChunkCache.get().getLegacyBlockData(
                                        location.getWorld().getName(),
                                        x,
                                        y,
                                        z
                                );
                        WrappedBlockState cachedState = ChunkCache.get().getBlockState(probe);
                        boolean needsExactStateShape = cachedState != null
                                && PEMaterials.requiresStatefulCollision(cachedState)
                                && !PEMaterials.hasCachedCollisionShape(cachedState);

                        if (needsExactStateShape) {
                            ChunkCache.get().requestCollisionShape(location.getWorld(), x, y, z, cachedState);
                        }

                        List<PEMaterials.CollisionBounds> boxes = cachedState != null
                                ? PEMaterials.getCollisionBounds(cachedState, x, y, z)
                                : null;

                        if (legacyData >= 0
                                && PEMaterials.isLegacySlabMaterial(material)) {

                            boxes = PEMaterials.getCollisionBounds(
                                    material,
                                    legacyData,
                                    x,
                                    y,
                                    z
                            );

                        } else if (cachedState != null) {

                            boxes = PEMaterials.getCollisionBounds(
                                    cachedState,
                                    x,
                                    y,
                                    z
                            );
                        }
                        Block block = null;

                        if (material == null) {
                            block = getBlock(probe, async);
                            if (block != null) {
                                material = nms.getType(block);
                            }
                        }

                        if (material == null || material == Material.AIR) {
                            continue;
                        }

                        if (!this.blockTypes.contains(material)) {
                            this.blockTypes.add(material);
                        }

                        if (!this.nearWaterLogged) {
                            if (material.name().contains("WATER")
                                    || me.arrow.playerdata.cache.ChunkCache.get().isWaterLogged(probe)
                                    || (block != null && nms.isWaterLogged(block))) {
                                this.nearWaterLogged = true;
                            }
                        }

                        if (boxes == null && PEMaterials.requiresStatefulCollision(material)) {
                            if (block == null) {
                                block = getBlock(probe, async);
                            }
                        }
                        if (boxes == null && block != null) {
                            if (cachedState != null && PEMaterials.cacheCollisionShape(cachedState, block)) {
                                boxes = PEMaterials.getCollisionBounds(cachedState, x, y, z);
                            } else {
                                boxes = PEMaterials.getCollisionBounds(block);
                            }
                        }
                        if (boxes == null) {
                            boxes = PEMaterials.getCollisionBounds(material, x, y, z);
                        }

                        if (needsExactStateShape && !PEMaterials.hasCachedCollisionShape(cachedState)) {
                            this.unresolvedCollisionShape = true;
                        }

                        if (boxes == null || boxes.isEmpty()) {
                            continue;
                        }

                        for (PEMaterials.CollisionBounds box : boxes) {
                            if (!this.exactGroundSupport
                                    && overlapsHorizontally(
                                            box,
                                            location.getX() - GROUND_PLAYER_HALF_WIDTH,
                                            location.getZ() - GROUND_PLAYER_HALF_WIDTH,
                                            location.getX() + GROUND_PLAYER_HALF_WIDTH,
                                            location.getZ() + GROUND_PLAYER_HALF_WIDTH
                                    )
                                    && Math.abs(box.maxY - feetY) <= GROUND_CONTACT_EPSILON) {
                                this.exactGroundSupport = true;
                            }

                            if (!this.landingGroundSupport
                                    && descending
                                    && overlapsHorizontally(
                                            box,
                                            sweepMinX, sweepMinZ,
                                            sweepMaxX, sweepMaxZ
                                    )
                                    && Math.abs(box.maxY - feetY) <= GROUND_CONTACT_EPSILON) {
                                this.landingGroundSupport = true;
                            }

                            if (!this.blockAbove && box.intersects(
                                    playerMinX, headMinY, playerMinZ,
                                    playerMaxX, headMaxY, playerMaxZ)) {
                                this.blockAbove = true;
                            }
                        }
                    }
                }
            }
        }

        private boolean overlapsHorizontally(PEMaterials.CollisionBounds box,
                                             double minX, double minZ,
                                             double maxX, double maxZ) {
            return box.maxX > minX + 1.0E-7D
                    && box.minX < maxX - 1.0E-7D
                    && box.maxZ > minZ + 1.0E-7D
                    && box.minZ < maxZ - 1.0E-7D;
        }

        public boolean hasBlockAbove() {
            return blockAbove;
        }

        public boolean hasExactGroundSupport() {
            return exactGroundSupport;
        }

        public boolean hasLandingGroundSupport() {
            return landingGroundSupport;
        }

        /** One nearby partial state is still waiting for its first server-shape read. */
        public boolean hasUnresolvedCollisionShape() {
            return unresolvedCollisionShape;
        }
    }
}
