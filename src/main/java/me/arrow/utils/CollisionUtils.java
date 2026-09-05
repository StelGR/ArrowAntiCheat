package me.arrow.utils;

import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.nms.NmsInstance;
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

    /* Actual Java player footprint used only for support/ceiling collision. */
    private static final double PLAYER_HALF_WIDTH = .300001D;

    /*
     * Extended player footprint used only for floor support. It is independent
     * of deltaY; walls and ceilings continue using the real 0.6-wide body.
     */
    private static final double SUPPORT_PLAYER_HALF_WIDTH = 0.75D;

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
        return ChunkCache.get().isChunkLoaded(location);
    }

    public static boolean isChunkLoaded(final Location location) {
        if (location == null || location.getWorld() == null) return false;
        return ChunkCache.get().isChunkLoaded(location);
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
        } else if (!org.bukkit.Bukkit.isPrimaryThread()) {
            // Off the primary server thread on Spigot/Paper, location.getBlock() causes AsyncCatcherException
            return null;
        }

        try {
            return isChunkLoaded(location) ? location.getBlock() : null;
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
        if (async || !org.bukkit.Bukkit.isPrimaryThread()) {
            return getBlockAsync(location);
        }
        try {
            return location.getBlock();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static NearbyBlocksResult getNearbyBlocks(final CustomLocation location, final boolean async) {

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
                    result.handle(cloned, above, BlockPosition.ABOVE, nms);
                }

                under:
                {
                    cloned.setY(underY);
                    final Block under = getBlock(cloned, async);
                    result.handle(cloned, under, BlockPosition.UNDER, nms);
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
                    result.handle(cloned, middle, BlockPosition.MIDDLE, nms);
                }

                below:
                {
                    cloned.setY(locationY);
                    final Block below = getBlock(cloned, async);
                    result.handle(cloned, below, BlockPosition.BELOW, nms);
                }
            }
        }

        /*
         * Ground and ceiling state must come from the player's real 0.6-wide
         * hitbox, not from the wider nearby-material scan. Running this pass
         * for every scan prevents a solid wall beside the player from being
         * classified as ground while still supporting exact partial shapes.
         */
        result.resolveExactCollision(location, async, nms);

        return result;
    }

    private enum BlockPosition {
        ABOVE,
        MIDDLE,
        BELOW,
        UNDER
    }

    @Getter
    public static class NearbyBlocksResult {

        private final List<Material> blockTypes = new ArrayList<>();

        private boolean nearGround, exactGroundSupport, blockAbove, nearWaterLogged;

        private void handle(CustomLocation location, Block block, BlockPosition blockPosition, NmsInstance nms) {

            Material type = null;
            if (location != null) {
                type = me.arrow.playerdata.cache.ChunkCache.get().getBlock(location);
            }
            if ((type == null || type == Material.AIR) && block != null) {
                type = nms.getType(block);
            }

            if (type == null || type == Material.AIR) return;

            if (blockPosition == BlockPosition.UNDER) {
                if (type.isSolid()
//                        || PEMaterials.hasCollision(type)
//                        || PEMaterials.hasPotentialCollision(type)
                ) {
                    this.nearGround = true;
                }
            }

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

        private void resolveExactCollision(CustomLocation location, boolean async, NmsInstance nms) {
            if (location == null || location.getWorld() == null) {
                return;
            }

            double playerMinX = location.getX() - PLAYER_HALF_WIDTH;
            double playerMaxX = location.getX() + PLAYER_HALF_WIDTH;
            double playerMinZ = location.getZ() - PLAYER_HALF_WIDTH;
            double playerMaxZ = location.getZ() + PLAYER_HALF_WIDTH;
            double supportMinX = location.getX() - SUPPORT_PLAYER_HALF_WIDTH;
            double supportMaxX = location.getX() + SUPPORT_PLAYER_HALF_WIDTH;
            double supportMinZ = location.getZ() - SUPPORT_PLAYER_HALF_WIDTH;
            double supportMaxZ = location.getZ() + SUPPORT_PLAYER_HALF_WIDTH;
            double feetY = location.getY();

            /* Shape-aware vertical support band; edge grace is horizontal only. */
            double supportMinY = feetY - 0.625001D;
            double supportMaxY = feetY + 0.050001D;

            /* Player head/ceiling band. */
            double headMinY = feetY + 1.425D;
            double headMaxY = feetY + 1.950001D;

            int minX = floor(supportMinX);
            int maxX = floor(supportMaxX);
            int minZ = floor(supportMinZ);
            int maxZ = floor(supportMaxZ);
            int minY = floor(supportMinY);
            int maxY = floor(headMaxY);

            CustomLocation probe = location.clone();

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        probe.setX(x + 0.5D);
                        probe.setY(y + 0.5D);
                        probe.setZ(z + 0.5D);

                        Material material = me.arrow.playerdata.cache.ChunkCache.get().getBlock(probe);
                        Block block = getBlock(probe, async);

                        if ((material == null || material == Material.AIR) && block != null) {
                            material = nms.getType(block);
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

                        List<PEMaterials.CollisionBounds> boxes = null;
                        if (block != null) {
                            boxes = PEMaterials.getCollisionBounds(block);
                        }
                        if (boxes == null || boxes.isEmpty()) {
                            boxes = PEMaterials.getCollisionBounds(material, x, y, z);
                        }

                        if (boxes == null || boxes.isEmpty()) {
                            continue;
                        }

                        for (PEMaterials.CollisionBounds box : boxes) {
                            if (!this.exactGroundSupport
                                    && overlapsHorizontally(
                                            box,
                                            playerMinX, playerMinZ,
                                            playerMaxX, playerMaxZ
                                    )
                                    && Math.abs(box.maxY - feetY) <= 0.050001D) {
                                this.exactGroundSupport = true;
                            }

                            if (!this.nearGround
                                    && overlapsSupportFootprint(box, location)
                                    && box.maxY >= supportMinY
                                    && box.maxY <= supportMaxY) {
                                this.nearGround = true;
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

        private boolean overlapsSupportFootprint(PEMaterials.CollisionBounds box,
                                                 CustomLocation center) {
            return overlapsHorizontally(
                    box,
                    center.getX() - SUPPORT_PLAYER_HALF_WIDTH,
                    center.getZ() - SUPPORT_PLAYER_HALF_WIDTH,
                    center.getX() + SUPPORT_PLAYER_HALF_WIDTH,
                    center.getZ() + SUPPORT_PLAYER_HALF_WIDTH
            );
        }

        public boolean hasBlockAbove() {
            return blockAbove;
        }

        public boolean hasExactGroundSupport() {
            return exactGroundSupport;
        }
    }
}
