package me.arrow.utils;

import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.nms.NmsInstance;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.MaterialType;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A small utility class to use for nearby blocks and such.
 * NOTE: You may notice that things in here seem overly
 * Complicated or way different than what you're usually
 * Supposed to do, The reason for it is to avoid certain method calls
 * And focus on perfomance more than anything, Due to collisions usually being heavy.
 */
public final class CollisionUtils {

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

    /*
    The exact additional expansion we need in order to correctly account for blocks on top and below.
     */
    //private static final double EXPAND_ADDITIONAL = 2.000000000002E-6;
    private static final double EXPAND_ADDITIONAL = 0;

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

        final double x = Math.abs(location.getX() % 1);
        final double z = Math.abs(location.getZ() % 1);

        for (double modulo : WALL_MODULOS) {

            final double moduloX = Math.abs(x - modulo);
            final double moduloZ = Math.abs(z - modulo);

            /*
            This is the correct amount we need to check for
            Since this accounts for all the collision changes.
             */
            if (moduloX < 1.706E-13 || moduloZ < 1.706E-13) return true;
        }

        return false;
    }

    /*
    Check if the player is near the edge of a block by using the modulo operator
    NOTE: The reason we're not using the deltaX and Z is due to this being more accurate and
    Not affected if you're only moving in one direction.
     */
    public static boolean isNearEdge(final CustomLocation location) {

        final double x = Math.abs(location.getX() % 1);
        final double z = Math.abs(location.getZ() % 1);

        return (x > EDGE_MODULOS[0] && x < EDGE_MODULOS[1])
                || (x > EDGE_MODULOS[2] && x < EDGE_MODULOS[3])
                || (z > EDGE_MODULOS[0] && z < EDGE_MODULOS[1])
                || (z > EDGE_MODULOS[2] && z < EDGE_MODULOS[3]);
    }

    public static float getBlockSlipperiness(final Material type) {

        return switch (type) {
            case SLIME_BLOCK -> .8F;
            case ICE, PACKED_ICE -> .98F;
            case BLUE_ICE -> .989F;
            default -> MoveUtils.FRICTION_FACTOR;
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

        final double locationX = location.getX();
        final double locationY = location.getY();
        final double locationZ = location.getZ();

        final double blockX = blockLocation.getX();
        final double blockY = blockLocation.getY();
        final double blockZ = blockLocation.getZ();

        final double deltaX = MathUtils.getAbsoluteDelta(blockX, locationX);
        final double deltaY = blockY - locationY;
        final double deltaZ = MathUtils.getAbsoluteDelta(blockZ, locationZ);

        return deltaX < 1.3D && deltaY < 0D && deltaZ < 1.3D;
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
                                               final boolean async,
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

        return isStandingOnMaterial(loc, nearby, async, predicate);
    }

    public static boolean isStandingOnSlime(final CustomLocation loc,
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final boolean async,
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

        return isStandingOnSlime(loc, nearby, async, predicate);
    }


    /**
     * General check using Bukkit Material constants.
     */
    public static boolean isStandingOnMaterial(final CustomLocation loc,
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final boolean async,
                                               final Material... targets) {
        if (loc == null || targets == null || targets.length == 0) return false;

        Predicate<Material> predicate = material -> {
            if (material == null) return false;
            for (Material t : targets) {
                if (material == t) return true;
            }
            return false;
        };

        return isStandingOnMaterial(loc, nearby, async, predicate);
    }


    public static boolean hasWaterUnder(final CustomLocation location, final CustomLocation blockLocation) {

        final double locationX = location.getX();
        final double locationY = location.getY();
        final double locationZ = location.getZ();

        final double blockX = blockLocation.getX();
        final double blockY = blockLocation.getY();
        final double blockZ = blockLocation.getZ();

        final double deltaX = MathUtils.getAbsoluteDelta(blockX, locationX);
        final double deltaY = blockY - locationY;
        final double deltaZ = MathUtils.getAbsoluteDelta(blockZ, locationZ);

        return deltaX < .61D && deltaY < -0.7D && deltaZ < .61D;
    }

    public static boolean isStandingOnWater(final CustomLocation loc,
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final boolean async,
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

        return isStandingOnWater(loc, nearby, async, predicate);
    }

    public static boolean isStandingOnWater(final CustomLocation loc,
                                            final CollisionUtils.NearbyBlocksResult nearby,
                                            final boolean async,
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

                Block block = CollisionUtils.getBlock(blockLoc, async);
                if (block == null) continue;

                anyCandidateChecked = true;

                if (CollisionUtils.hasWaterUnder(loc, blockLoc) && predicate.test(block.getType())) {
                    return true;
                }
            }
        }

        if (!anyCandidateChecked && nearby != null) {
            if (CollisionUtils.hasBlockUnder2(loc, loc.clone().subtract(0, 1, 0))) {
                for (Material m : nearby.getBlockTypes()) {
                    if (predicate.test(m)) return true;
                }
            }
        }

        return false;
    }


    /**
     * Core implementation: checks a 3x3 candidate grid below the provided location (keeps your hasBlockUnder semantics).
     * Falls back to nearby.getBlockTypes() check if candidate blocks are null (chunk not loaded).
     * - loc: the player location to check (tests blocks at y - 1).
     * - nearby: the precomputed NearbyBlocksResult for that location (can be null).
     * - async: pass true if you want getBlock(..., true) behavior (matches your usage).
     * - predicate: a predicate to test a Block's Material.
     */
    public static boolean isStandingOnMaterial(final CustomLocation loc,
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final boolean async,
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

                    Block block = CollisionUtils.getBlock(blockLoc, async);
                    if (block == null) continue;

                    anyCandidateChecked = true;

                    if (CollisionUtils.hasBlockUnder2(loc, blockLoc) && predicate.test(block.getType())) {
                        return true;
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
                                               final CollisionUtils.NearbyBlocksResult nearby,
                                               final boolean async,
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

                    Block block = CollisionUtils.getBlock(blockLoc, async);
                    if (block == null) continue;

                    anyCandidateChecked = true;

                    if (CollisionUtils.hasBlockUnder(loc, blockLoc) && predicate.test(block.getType())) {
                        return true;
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

    public static boolean isChunkLoaded(final CustomLocation location) {
        return Arrow.getInstance().getNmsManager().getNmsInstance().isChunkLoaded(
                location.getWorld(), location.getBlockX(), location.getBlockZ()
        );
    }

    private static Block getBlockAsync(final CustomLocation location) {
        return isChunkLoaded(location) ? location.getBlock() : null;
    }

    public static Block getBlock(final CustomLocation location, boolean async) {
        return async ? getBlockAsync(location) : location.getBlock();
    }

    /*
    Get the nearby blocks around the player
    The reason this is overly complicated and checks for duplicates
    Is to avoid unnecessary looping within our processors
    And to avoid certain method calls that are heavy especially in 1.9+
     */

    public static List<Block> getNearbyBlockObjects(final CustomLocation location, final boolean async) {
        List<Block> blocks = new ArrayList<>();
        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();

        final double locationX = location.getX();
        final double locationY = location.getY();
        final double locationZ = location.getZ();

        final double aboveY = locationY + 1.9D;
        final double middleY = locationY + 1D;
        final double underY = locationY - .5000001D;

        CustomLocation cloned = location.clone();

        for (double x = -EXPAND_HORIZONTAL; x <= EXPAND_HORIZONTAL; x += EXPAND_HORIZONTAL) {
            for (double z = -EXPAND_HORIZONTAL; z <= EXPAND_HORIZONTAL; z += EXPAND_HORIZONTAL) {
                final double additionalX = x > 0D ? -EXPAND_ADDITIONAL : EXPAND_ADDITIONAL;
                final double additionalZ = z > 0D ? -EXPAND_ADDITIONAL : EXPAND_ADDITIONAL;
                final double expandX = locationX + x;
                final double expandZ = locationZ + z;

                // Check above
                cloned.setX(expandX + additionalX);
                cloned.setZ(expandZ + additionalZ);
                cloned.setY(aboveY);
                Block above = getBlock(cloned, async);
                if (above != null && !blocks.contains(above)) {
                    blocks.add(above);
                }

                // Check under
                cloned.setY(underY);
                Block under = getBlock(cloned, async);
                if (under != null && !blocks.contains(under)) {
                    blocks.add(under);
                }

                // Check middle
                cloned.setX(expandX);
                cloned.setZ(expandZ);
                cloned.setY(middleY);
                Block middle = getBlock(cloned, async);
                if (middle != null && !blocks.contains(middle)) {
                    blocks.add(middle);
                }

                // Check below
                cloned.setY(locationY);
                Block below = getBlock(cloned, async);
                if (below != null && !blocks.contains(below)) {
                    blocks.add(below);
                }
            }
        }

        return blocks;
    }

    public static NearbyBlocksResult getNearbyBlocks(final CustomLocation location, final boolean async) {

        NearbyBlocksResult result = new NearbyBlocksResult();

        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();

        /*
        A list that we'll be using in order to detect duplicate blocks.
         */
        final List<Block> blockPositions = new ArrayList<>();

        final double locationX = location.getX();
        final double locationY = location.getY();
        final double locationZ = location.getZ();

        final double aboveY = locationY + 1.9D;
        final double middleY = locationY + 1D;
        final double underY = locationY - .5000001D;

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

                    if (above == null) break above;

                    if (!blockPositions.contains(above)) {

                        result.handle(above, BlockPosition.ABOVE, nms);

                        blockPositions.add(above);
                    }
                }

                under:
                {

                    cloned.setY(underY);

                    final Block under = getBlock(cloned, async);

                    if (under == null) break under;

                    if (!blockPositions.contains(under)) {

                        result.handle(under, BlockPosition.UNDER, nms);

                        blockPositions.add(under);
                    }
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

                    if (middle == null) break middle;

                    if (!blockPositions.contains(middle)) {

                        result.handle(middle, BlockPosition.MIDDLE, nms);

                        blockPositions.add(middle);
                    }
                }

                below:
                {

                    cloned.setY(locationY);

                    final Block below = getBlock(cloned, async);

                    if (below == null) break below;

                    if (!blockPositions.contains(below)) {

                        result.handle(below, BlockPosition.BELOW, nms);

                        blockPositions.add(below);
                    }
                }
            }
        }

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

        private boolean nearGround, blockAbove, nearWaterLogged;

        private void handle(Block block, BlockPosition blockPosition, NmsInstance nms) {

            /*
            Get the material type.
             */
            Material type = nms.getType(block);

            /*
            Invalid.
             */
            if (type == null) return;

            /*
            Handle statuses if the block is solid.
             */
            if (type.isSolid()
                    || !isTransparent(type)
                    || isCarpet(type)
                    || isWeirdBlock(type)) {

                switch (blockPosition) {

                    case ABOVE:

                        if (!this.blockAbove) this.blockAbove = true;

                        break;

                    case UNDER:

                        if (!this.nearGround) this.nearGround = true;

                        break;
                }
            }

            /*
            Handle waterlogged.
             */
            if (!this.nearWaterLogged) this.nearWaterLogged = nms.isWaterLogged(block);

            /*
            Duplicate.
             */
            if (this.blockTypes.contains(type)) return;

            /*
            Add the block type.
             */
            this.blockTypes.add(type);
        }

        public boolean hasBlockAbove() {
            return blockAbove;
        }

        public boolean isWeirdBlock(Material material) {
            if (material == null) return false;

            return switch (material) {
                case DAYLIGHT_DETECTOR,
                     BIG_DRIPLEAF_STEM,
                     BIG_DRIPLEAF,
                     SMALL_DRIPLEAF,
                     BREWING_STAND,
                     LILY_PAD,
                     COMPARATOR,
                     REPEATER,
                     COCOA,
                     POWDER_SNOW,
                     LECTERN,
                     SCULK_SENSOR,
                     SCULK_SHRIEKER,
                     CALIBRATED_SCULK_SENSOR,
                     // All shulker box variants
                     SHULKER_BOX, WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX,
                     LIGHT_BLUE_SHULKER_BOX, YELLOW_SHULKER_BOX, LIME_SHULKER_BOX, PINK_SHULKER_BOX,
                     GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX, CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX,
                     BLUE_SHULKER_BOX, BROWN_SHULKER_BOX, GREEN_SHULKER_BOX, RED_SHULKER_BOX,
                     BLACK_SHULKER_BOX -> true;
                default -> false;
            };
        }

        public boolean isCarpet(Material material) {
            if (material == null) return false;

            return switch (material) {
                case WHITE_CARPET, ORANGE_CARPET, MAGENTA_CARPET, LIGHT_BLUE_CARPET,
                     YELLOW_CARPET, LIME_CARPET, PINK_CARPET, GRAY_CARPET,
                     LIGHT_GRAY_CARPET, CYAN_CARPET, PURPLE_CARPET, BLUE_CARPET,
                     BROWN_CARPET, GREEN_CARPET, RED_CARPET, BLACK_CARPET -> true;
                default -> false;
            };
        }

        public boolean isTransparent(Material material) {
            if (!material.isBlock()) {
                return false;
            } else {
                return switch (material) {
                    case AIR, OAK_SAPLING, SPRUCE_SAPLING, BIRCH_SAPLING, JUNGLE_SAPLING, ACACIA_SAPLING, DARK_OAK_SAPLING,
                         BAMBOO_SAPLING, POWERED_RAIL, DETECTOR_RAIL, SHORT_GRASS, FERN, DEAD_BUSH, DANDELION, POPPY,
                         BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP, OXEYE_DAISY,
                         CORNFLOWER, LILY_OF_THE_VALLEY, WITHER_ROSE, SUNFLOWER, LILAC, ROSE_BUSH, PEONY, BROWN_MUSHROOM,
                         RED_MUSHROOM, TORCH, SOUL_TORCH, FIRE, SOUL_FIRE, REDSTONE, WHEAT, RAIL, LEVER, REDSTONE_TORCH,
                         STONE_BUTTON, OAK_BUTTON, SPRUCE_BUTTON, BIRCH_BUTTON, JUNGLE_BUTTON, ACACIA_BUTTON,
                         DARK_OAK_BUTTON, MANGROVE_BUTTON, BAMBOO_BUTTON, CRIMSON_BUTTON, WARPED_BUTTON, SUGAR_CANE,
                         PUMPKIN_STEM, MELON_STEM, VINE, NETHER_WART, NETHER_PORTAL, TRIPWIRE_HOOK,
                         TRIPWIRE, CARROTS, POTATOES, ACTIVATOR_RAIL, TALL_GRASS, LARGE_FERN, WATER, LAVA, SCULK_VEIN -> true;
                    default -> false;
                };
            }
        }
    }
}