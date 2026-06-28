package me.arrow.utils.custom.materials;

import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//This is my material class, that adds the material names for all mc versions, please save me

public enum MaterialType {

    AIR("AIR", "CAVE_AIR", "VOID_AIR"),

    PANE("GLASS_PANE", "WHITE_STAINED_GLASS_PANE", "ORANGE_STAINED_GLASS_PANE",
            "MAGENTA_STAINED_GLASS_PANE", "LIGHT_BLUE_STAINED_GLASS_PANE", "YELLOW_STAINED_GLASS_PANE",
            "LIME_STAINED_GLASS_PANE", "PINK_STAINED_GLASS_PANE", "GRAY_STAINED_GLASS_PANE",
            "LIGHT_GRAY_STAINED_GLASS_PANE", "CYAN_STAINED_GLASS_PANE", "PURPLE_STAINED_GLASS_PANE",
            "BLUE_STAINED_GLASS_PANE", "BROWN_STAINED_GLASS_PANE", "GREEN_STAINED_GLASS_PANE",
            "RED_STAINED_GLASS_PANE", "BLACK_STAINED_GLASS_PANE", "IRON_BARS"),
    BREWING_STAND("BREWING_STAND"),

    STRUCTURE_VOID("STRUCTURE_VOID"),

    TRIDENT("TRIDENT"),

    FENCE("OAK_FENCE", "SPRUCE_FENCE", "BIRCH_FENCE", "JUNGLE_FENCE", "ACACIA_FENCE",
            "DARK_OAK_FENCE", "CRIMSON_FENCE", "WARPED_FENCE", "NETHER_BRICK_FENCE", "OAK_FENCE_GATE",
            "SPRUCE_FENCE_GATE", "BIRCH_FENCE_GATE", "JUNGLE_FENCE_GATE", "ACACIA_FENCE_GATE",
            "DARK_OAK_FENCE_GATE", "CRIMSON_FENCE_GATE", "WARPED_FENCE_GATE", "FENCE", "IRON_FENCE",
            "FENCE_GATE", "NETHER_FENCE", "CHERRY_FENCE", "CHERRY_FENCE_GATE", "PALE_OAK_FENCE", "BAMBOO_FENCE", "BAMBOO_FENCE_GATE", "PALE_OAK_FENCE_GATE", "MANGROVE_FENCE", "MANGROVE_FENCE_GATE"),
    WALL("COBBLE_WALL", "COBBLESTONE_WALL", "MOSSY_COBBLESTONE_WALL",
            "BRICK_WALL", "PRISMARINE_WALL", "RED_SANDSTONE_WALL", "MOSSY_STONE_BRICK_WALL", "GRANITE_WALL",
            "STONE_BRICK_WALL", "NETHER_BRICK_WALL", "ANDESITE_WALL", "RED_NETHER_BRICK_WALL", "SANDSTONE_WALL",
            "END_STONE_BRICK_WALL", "DIORITE_WALL", "BLACKSTONE_WALL", "POLISHED_BLACKSTONE_WALL",
            "POLISHED_BLACKSTONE_BRICK_WALL", "COBBLED_DEEPSLATE_WALL", "POLISHED_DEEPSLATE_WALL", "DEEPSLATE_TILE_WALL", "RESIN_BRICK_WALL", "DEEPSLATE_BRICK_WALL"),

    SNOW("SNOW"),

    SHULKER("SHULKER_BOX", "WHITE_SHULKER_BOX", "ORANGE_SHULKER_BOX", "MAGENTA_SHULKER_BOX",
            "LIGHT_BLUE_SHULKER_BOX", "YELLOW_SHULKER_BOX", "LIME_SHULKER_BOX", "PINK_SHULKER_BOX",
            "GRAY_SHULKER_BOX", "LIGHT_GRAY_SHULKER_BOX", "CYAN_SHULKER_BOX", "PURPLE_SHULKER_BOX",
            "BLUE_SHULKER_BOX", "BROWN_SHULKER_BOX", "GREEN_SHULKER_BOX", "RED_SHULKER_BOX",
            "BLACK_SHULKER_BOX", "SILVER_SHULKER_BOX"),

    BED("WHITE_BED", "ORANGE_BED", "MAGENTA_BED", "LIGHT_BLUE_BED", "YELLOW_BED", "LIME_BED",
            "PINK_BED", "GRAY_BED", "LIGHT_GRAY_BED", "CYAN_BED", "PURPLE_BED", "BLUE_BED", "BROWN_BED", "GREEN_BED",
            "RED_BED", "BLACK_BED", "BED_BLOCK", "BED"),

    PISTON("PISTON", "STICKY_PISTON", "PISTON_HEAD", "MOVING_PISTON", "PISTON_STICKY_BASE", "PISTON_BASE",
            "PISTON_EXTENSION", "PISTON_MOVING_PIECE"),

    TRAPDOOR("IRON_TRAPDOOR", "OAK_TRAPDOOR", "SPRUCE_TRAPDOOR", "BIRCH_TRAPDOOR", "JUNGLE_TRAPDOOR",
            "ACACIA_TRAPDOOR", "DARK_OAK_TRAPDOOR", "CRIMSON_TRAPDOOR", "WARPED_TRAPDOOR", "TRAP_DOOR"),

    STAIRS("CUT_COPPER_STAIRS", "EXPOSED_CUT_COPPER_STAIRS", "WEATHERED_CUT_COPPER_STAIRS",
            "OXIDIZED_CUT_COPPER_STAIRS", "WAXED_CUT_COPPER_STAIRS", "WAXED_EXPOSED_CUT_COPPER_STAIRS",
            "WAXED_WEATHERED_CUT_COPPER_STAIRS", "WAXED_OXIDIZED_CUT_COPPER_STAIRS", "PURPUR_STAIRS",
            "OAK_STAIRS", "COBBLESTONE_STAIRS", "BRICK_STAIRS", "STONE_BRICK_STAIRS", "NETHER_BRICK_STAIRS",
            "SANDSTONE_STAIRS", "SPRUCE_STAIRS", "BIRCH_STAIRS", "JUNGLE_STAIRS", "CRIMSON_STAIRS", "WARPED_STAIRS",
            "QUARTZ_STAIRS", "ACACIA_STAIRS", "DARK_OAK_STAIRS", "PRISMARINE_STAIRS", "PRISMARINE_BRICK_STAIRS",
            "DARK_PRISMARINE_STAIRS", "RED_SANDSTONE_STAIRS", "POLISHED_GRANITE_STAIRS", "SMOOTH_RED_SANDSTONE_STAIRS",
            "MOSSY_STONE_BRICK_STAIRS", "POLISHED_DIORITE_STAIRS", "MOSSY_COBBLESTONE_STAIRS", "END_STONE_BRICK_STAIRS",
            "STONE_STAIRS", "SMOOTH_SANDSTONE_STAIRS", "SMOOTH_QUARTZ_STAIRS", "GRANITE_STAIRS", "ANDESITE_STAIRS",
            "RED_NETHER_BRICK_STAIRS", "POLISHED_ANDESITE_STAIRS", "DIORITE_STAIRS", "COBBLED_DEEPSLATE_STAIRS",
            "POLISHED_DEEPSLATE_STAIRS", "DEEPSLATE_BRICK_STAIRS", "DEEPSLATE_TILE_STAIRS", "BLACKSTONE_STAIRS",
            "POLISHED_BLACKSTONE_STAIRS", "POLISHED_BLACKSTONE_BRICK_STAIRS", "WOOD_STAIRS", "SMOOTH_STAIRS",
            "SPRUCE_WOOD_STAIRS", "BIRCH_WOOD_STAIRS", "JUNGLE_WOOD_STAIRS", "RESIN_BRICK_STAIRS", "PALE_OAK_STAIRS", "POLISHED_TUFF_STAIRS", "TUFF_BRICK_STAIRS", "TUFF_STAIRS", "CHERRY_STAIRS", "BAMBOO_STAIRS"),

    ICE("ICE", "FROSTED_ICE", "PACKED_ICE", "BLUE_ICE"),

    SLIME("SLIME_BLOCK"),

    WEB("WEB", "COBWEB"),

    POWDER_SNOW("POWDER_SNOW"),

    CLIMBABLE("WEEPING_VINES", "TWISTING_VINES", "LADDER", "VINE", "WEEPING_VINES_PLANT",
            "TWISTING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT", "VINES"),

    SOUL_BLOCK("SOUL_SAND", "SOUL_SOIL"),

    SOUL_SAND("SOUL_SAND"),
    SOUL_SOIL("SOUL_SOIL"),

    HONEY("HONEY_BLOCK"),

    BERRIES("SWEET_BERRY_BUSH"),

    SCAFFOLDING("SCAFFOLDING"),

    BUBBLE("BUBBLE_COLUMN"),

    LIQUID("WATER", "LAVA", "STATIONARY_WATER", "STATIONARY_LAVA", "BUBBLE_COLUMN"),

    WATER("WATER", "STATIONARY_WATER", "BUBBLE_COLUMN"),

    LAVA("LAVA", "STATIONARY_LAVA"),

    HALF_BLOCK("CHORUS_PLANT", "CHORUS_FLOWER", "POPPED_CHORUS_FRUIT", "CHORUS_FRUIT_POPPED",
            "MOSS_CARPET", "WHITE_CARPET", "ORANGE_CARPET", "MAGENTA_CARPET", "LIGHT_BLUE_CARPET", "YELLOW_CARPET",
            "LIME_CARPET", "PINK_CARPET", "GRAY_CARPET", "LIGHT_GRAY_CARPET", "CYAN_CARPET", "PURPLE_CARPET",
            "BLUE_CARPET", "BROWN_CARPET", "GREEN_CARPET", "RED_CARPET", "BLACK_CARPET", "CARPET", "AZALEA_LEAVES",
            "FLOWERING_AZALEA_LEAVES", "AZALEA", "FLOWERING_AZALEA", "POTTED_AZALEA_BUSH",
            "POTTED_FLOWERING_AZALEA_BUSH", "BIG_DRIPLEAF", "SMALL_DRIPLEAF", "BIG_DRIPLEAF_STEM",
            "LILY_OF_THE_VALLEY", "LILY_PAD", "POTTED_LILY_OF_THE_VALLEY", "WATER_LILY", "END_ROD", "PLAYER_HEAD",
            "ZOMBIE_HEAD", "CREEPER_HEAD", "DRAGON_HEAD", "PISTON_HEAD", "ZOMBIE_WALL_HEAD", "PLAYER_WALL_HEAD",
            "CREEPER_WALL_HEAD", "DRAGON_WALL_HEAD", "SKELETON_SKULL", "WITHER_SKELETON_SKULL",
             "SKELETON_WALL_SKULL", "WITHER_SKELETON_WALL_SKULL", "SKULL",
            "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "CUT_COPPER_SLAB",
            "EXPOSED_CUT_COPPER_SLAB", "WEATHERED_CUT_COPPER_SLAB", "OXIDIZED_CUT_COPPER_SLAB", "WAXED_CUT_COPPER_SLAB",
            "WAXED_EXPOSED_CUT_COPPER_SLAB", "WAXED_WEATHERED_CUT_COPPER_SLAB", "WAXED_OXIDIZED_CUT_COPPER_SLAB", "OAK_SLAB",
            "SPRUCE_SLAB", "BIRCH_SLAB", "JUNGLE_SLAB", "ACACIA_SLAB",
            "DARK_OAK_SLAB", "MANGROVE_SLAB", "CHERRY_SLAB", "PALE_OAK_SLAB",
            "BAMBOO_SLAB", "BAMBOO_MOSAIC_SLAB", "CRIMSON_SLAB", "WARPED_SLAB",
            "STONE_SLAB", "SMOOTH_STONE_SLAB", "GRANITE_SLAB", "POLISHED_GRANITE_SLAB",
            "DIORITE_SLAB", "POLISHED_DIORITE_SLAB", "ANDESITE_SLAB", "POLISHED_ANDESITE_SLAB",
            "COBBLESTONE_SLAB", "MOSSY_COBBLESTONE_SLAB", "STONE_BRICK_SLAB", "MOSSY_STONE_BRICK_SLAB",
            "BRICK_SLAB", "END_STONE_BRICK_SLAB", "NETHER_BRICK_SLAB", "RED_NETHER_BRICK_SLAB",
            "SANDSTONE_SLAB", "CUT_SANDSTONE_SLAB", "SMOOTH_SANDSTONE_SLAB", "RED_SANDSTONE_SLAB",
            "CUT_RED_SANDSTONE_SLAB", "SMOOTH_RED_SANDSTONE_SLAB", "QUARTZ_SLAB", "SMOOTH_QUARTZ_SLAB",
            "PURPUR_SLAB", "PRISMARINE_SLAB", "PRISMARINE_BRICK_SLAB", "DARK_PRISMARINE_SLAB",
            "BLACKSTONE_SLAB", "POLISHED_BLACKSTONE_SLAB", "POLISHED_BLACKSTONE_BRICK_SLAB", "CUT_SANDSTONE_SLAB",
            "PETRIFIED_OAK_SLAB", "COBBLED_DEEPSLATE_SLAB", "POLISHED_DEEPSLATE_SLAB", "DEEPSLATE_BRICK_SLAB",
            "DEEPSLATE_TILE_SLAB", "MUD_BRICK_SLAB", "POLISHED_GRANITE_SLAB", "POLISHED_DIORITE_SLAB",
            "POLISHED_ANDESITE_SLAB", "SMOOTH_QUARTZ_SLAB",
            /* legacy / Bukkit names (1.8 / 1.9 era) */
            "STONE_SLAB2", "DOUBLE_STONE_SLAB", "DOUBLE_STONE_SLAB2", "STEP",
            "DOUBLE_STEP", "WOOD_DOUBLE_STEP", "WOODEN_SLAB",
            "ENDER_PORTAL_FRAME", "END_PORTAL_FRAME", "ENCHANTMENT_TABLE", "ENCHANTING_TABLE", "CAULDRON",
            "WATER_CAULDRON", "LAVA_CAULDRON", "POWDER_SNOW_CAULDRON", "COMPOSTER",
            "CHEST", "ENDER_CHEST", "TRAPPED_CHEST", "FARMLAND", "FLOWER_POT", "POTTED_OAK_SAPLING",
            "POTTED_SPRUCE_SAPLING", "POTTED_BIRCH_SAPLING", "POTTED_JUNGLE_SAPLING", "POTTED_ACACIA_SAPLING",
            "POTTED_DARK_OAK_SAPLING", "POTTED_FERN", "POTTED_DANDELION", "POTTED_POPPY", "POTTED_BLUE_ORCHID",
            "POTTED_ALLIUM", "POTTED_AZURE_BLUET", "POTTED_RED_TULIP", "POTTED_ORANGE_TULIP", "POTTED_WHITE_TULIP",
            "POTTED_PINK_TULIP", "POTTED_OXEYE_DAISY", "POTTED_CORNFLOWER", "POTTED_WITHER_ROSE", "POTTED_RED_MUSHROOM",
            "POTTED_BROWN_MUSHROOM", "POTTED_DEAD_BUSH", "POTTED_CACTUS", "POTTED_BAMBOO", "POTTED_CRIMSON_FUNGUS",
            "POTTED_WARPED_FUNGUS", "POTTED_CRIMSON_ROOTS", "POTTED_WARPED_ROOTS", "GRINDSTONE", "STONECUTTER",
            "POINTED_DRIPSTONE", "SEA_PICKLE", "SMALL_AMETHYST_BUD", "MEDIUM_AMETHYST_BUD", "LARGE_AMETHYST_BUD",
            "AMETHYST_CLUSTER", "CAMPFIRE", "SOUL_CAMPFIRE", "LANTERN", "COPPER_LANTERN", "EXPOSED_COPPER_LANTERN",
            "WEATHERED_COPPER_LANTERN", "OXIDIZED_COPPER_LANTERN", "WAXED_COPPER_LANTERN", "WAXED_EXPOSED_COPPER_LANTERN",
            "WAXED_WEATHERED_COPPER_LANTERN", "WAXED_OXIDIZED_COPPER_LANTERN", "SOUL_LANTERN", "CANDLE", "WHITE_CANDLE",
            "CHAIN", "IRON_CHAIN", "COPPER_CHAIN", "EXPOSED_COPPER_CHAIN",
            "WEATHERED_COPPER_CHAIN", "OXIDIZED_COPPER_CHAIN", "WAXED_COPPER_CHAIN", "WAXED_EXPOSED_COPPER_CHAIN",
            "WAXED_WEATHERED_COPPER_CHAIN", "WAXED_OXIDIZED_COPPER_CHAIN",
            "ORANGE_CANDLE", "MAGENTA_CANDLE", "LIGHT_BLUE_CANDLE", "YELLOW_CANDLE", "LIME_CANDLE", "PINK_CANDLE",
            "GRAY_CANDLE", "LIGHT_GRAY_CANDLE", "CYAN_CANDLE", "PURPLE_CANDLE", "BLUE_CANDLE", "BROWN_CANDLE",
            "GREEN_CANDLE", "RED_CANDLE", "BLACK_CANDLE", "CANDLE_CAKE", "WHITE_CANDLE_CAKE", "ORANGE_CANDLE_CAKE",
            "MAGENTA_CANDLE_CAKE", "LIGHT_BLUE_CANDLE_CAKE", "YELLOW_CANDLE_CAKE", "LIME_CANDLE_CAKE",
            "PINK_CANDLE_CAKE", "GRAY_CANDLE_CAKE", "LIGHT_GRAY_CANDLE_CAKE", "CYAN_CANDLE_CAKE", "PURPLE_CANDLE_CAKE",
            "BLUE_CANDLE_CAKE", "BROWN_CANDLE_CAKE", "GREEN_CANDLE_CAKE", "RED_CANDLE_CAKE", "BLACK_CANDLE_CAKE",
            "CAKE_BLOCK", "CAKE", "REPEATER", "COMPARATOR", "REDSTONE_COMPARATOR_OFF", "REDSTONE_COMPARATOR_ON",
            "REDSTONE_COMPARATOR", "DAYLIGHT_DETECTOR", "DAYLIGHT_DETECTOR_INVERTED", "LECTERN", "HOPPER", "DRIED_GHAST"),

    WHITE_STAINED_GLASS_PANE("WHITE_STAINED_GLASS_PANE"),
    ORANGE_STAINED_GLASS_PANE("ORANGE_STAINED_GLASS_PANE"),
    MAGENTA_STAINED_GLASS_PANE("MAGENTA_STAINED_GLASS_PANE"),
    LIGHT_BLUE_STAINED_GLASS_PANE("LIGHT_BLUE_STAINED_GLASS_PANE"),
    YELLOW_STAINED_GLASS_PANE("YELLOW_STAINED_GLASS_PANE"),
    LIME_STAINED_GLASS_PANE("LIME_STAINED_GLASS_PANE"),
    PINK_STAINED_GLASS_PANE("PINK_STAINED_GLASS_PANE"),
    GRAY_STAINED_GLASS_PANE("GRAY_STAINED_GLASS_PANE"),
    LIGHT_GRAY_STAINED_GLASS_PANE("LIGHT_GRAY_STAINED_GLASS_PANE"),
    CYAN_STAINED_GLASS_PANE("CYAN_STAINED_GLASS_PANE"),
    PURPLE_STAINED_GLASS_PANE("PURPLE_STAINED_GLASS_PANE"),
    BLUE_STAINED_GLASS_PANE("BLUE_STAINED_GLASS_PANE"),
    BROWN_STAINED_GLASS_PANE("BROWN_STAINED_GLASS_PANE"),
    GREEN_STAINED_GLASS_PANE("GREEN_STAINED_GLASS_PANE"),
    RED_STAINED_GLASS_PANE("RED_STAINED_GLASS_PANE"),
    BLACK_STAINED_GLASS_PANE("BLACK_STAINED_GLASS_PANE"),

    GLASS_PANE("GLASS_PANE"),
    STAINED_GLASS_PANE("STAINED_GLASS_PANE"),// the old pre-1.13 base one
    IRON_BARS("IRON_BARS"),

    SKELETON_SKULL("SKELETON_SKULL"),
    WITHER_SKELETON_SKULL("WITHER_SKELETON_SKULL"),
    SKELETON_WALL_SKULL("SKELETON_WALL_SKULL"),
    WITHER_SKELETON_WALL_SKULL("WITHER_SKELETON_WALL_SKULL"),

    SKULL_BANNER_PATTERN("SKULL_BANNER_PATTERN"), // not a block, but an item
    SKULL("SKULL"),

    SLAB(
            "CUT_COPPER_SLAB",
            "EXPOSED_CUT_COPPER_SLAB",
            "WEATHERED_CUT_COPPER_SLAB",
            "OXIDIZED_CUT_COPPER_SLAB",
            "WAXED_CUT_COPPER_SLAB",
            "WAXED_EXPOSED_CUT_COPPER_SLAB",
            "WAXED_WEATHERED_CUT_COPPER_SLAB",
            "WAXED_OXIDIZED_CUT_COPPER_SLAB",
            "OAK_SLAB",
            "SPRUCE_SLAB",
            "BIRCH_SLAB",
            "JUNGLE_SLAB",
            "ACACIA_SLAB",
            "DARK_OAK_SLAB",
            "MANGROVE_SLAB",
            "CHERRY_SLAB",
            "PALE_OAK_SLAB",
            "BAMBOO_SLAB",
            "BAMBOO_MOSAIC_SLAB",
            "CRIMSON_SLAB",
            "WARPED_SLAB",
            "STONE_SLAB",
            "SMOOTH_STONE_SLAB",
            "GRANITE_SLAB",
            "POLISHED_GRANITE_SLAB",
            "DIORITE_SLAB",
            "POLISHED_DIORITE_SLAB",
            "ANDESITE_SLAB",
            "POLISHED_ANDESITE_SLAB",
            "COBBLESTONE_SLAB",
            "MOSSY_COBBLESTONE_SLAB",
            "STONE_BRICK_SLAB",
            "MOSSY_STONE_BRICK_SLAB",
            "BRICK_SLAB",
            "END_STONE_BRICK_SLAB",
            "NETHER_BRICK_SLAB",
            "RED_NETHER_BRICK_SLAB",
            "SANDSTONE_SLAB",
            "CUT_SANDSTONE_SLAB",
            "SMOOTH_SANDSTONE_SLAB",
            "RED_SANDSTONE_SLAB",
            "CUT_RED_SANDSTONE_SLAB",
            "SMOOTH_RED_SANDSTONE_SLAB",
            "QUARTZ_SLAB",
            "SMOOTH_QUARTZ_SLAB",
            "PURPUR_SLAB",
            "PRISMARINE_SLAB",
            "PRISMARINE_BRICK_SLAB",
            "DARK_PRISMARINE_SLAB",
            "BLACKSTONE_SLAB",
            "POLISHED_BLACKSTONE_SLAB",
            "POLISHED_BLACKSTONE_BRICK_SLAB",
            "CUT_SANDSTONE_SLAB",
            "PETRIFIED_OAK_SLAB",
            "COBBLED_DEEPSLATE_SLAB",
            "POLISHED_DEEPSLATE_SLAB",
            "DEEPSLATE_BRICK_SLAB",
            "DEEPSLATE_TILE_SLAB",
            "MUD_BRICK_SLAB",
            "POLISHED_GRANITE_SLAB",
            "POLISHED_DIORITE_SLAB",
            "POLISHED_ANDESITE_SLAB",
            "SMOOTH_QUARTZ_SLAB",
            /* legacy / Bukkit names (1.8 / 1.9 era) */
            "STONE_SLAB2",
            "DOUBLE_STONE_SLAB",
            "DOUBLE_STONE_SLAB2",
            "STEP",
            "DOUBLE_STEP",
            "WOOD_DOUBLE_STEP",
            "WOODEN_SLAB"
    ),

    DRIP_LEAF("BIG_DRIPLEAF", "SMALL_DRIPLEAF", "BIG_DRIPLEAF_STEM"),

    LEGACY_WOOL("WOOL"),

    TORCHES("TORCH", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH",
            "SOUL_TORCH", "SOUL_WALL_TORCH"),

    TORCH("TORCH"),
    REDSTONE_TORCH_OFF("REDSTONE_TORCH_OFF"),
    REDSTONE_TORCH_ON("REDSTONE_TORCH_ON"),
    REDSTONE_TORCH("REDSTONE_TORCH"),
    REDSTONE_WALL_TORCH("REDSTONE_WALL_TORCH"), // technically appeared in 1.13, legacy redstone torches were just one id

    // 1.13+ additions
    SOUL_TORCH("SOUL_TORCH"),
    SOUL_WALL_TORCH("SOUL_WALL_TORCH"),
    WATER_PLANT("KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS"),
    SHIELD("SHIELD"),
    BOW("BOW", "CROSSBOW"),
    DOOR(
            // 1.8 (legacy)
    "WOODEN_DOOR",   // old name for OAK_DOOR
            "IRON_DOOR_BLOCK",

            // 1.9–1.12 additions
            "OAK_DOOR",
            "SPRUCE_DOOR",
            "BIRCH_DOOR",
            "JUNGLE_DOOR",
            "ACACIA_DOOR",
            "DARK_OAK_DOOR",
            "IRON_DOOR",     // renamed from IRON_DOOR_BLOCK

            // 1.16 Nether update
            "CRIMSON_DOOR",
            "WARPED_DOOR",

            // 1.19 Wild update
            "MANGROVE_DOOR",

            // 1.20 Trails & Tales
            "CHERRY_DOOR",
            "BAMBOO_DOOR",

            // 1.21 Tricky Trials
            "COPPER_DOOR",
            "EXPOSED_COPPER_DOOR",
            "WEATHERED_COPPER_DOOR",
            "OXIDIZED_COPPER_DOOR",
            "WAXED_COPPER_DOOR",
            "WAXED_EXPOSED_COPPER_DOOR",
            "WAXED_WEATHERED_COPPER_DOOR",
            "WAXED_OXIDIZED_COPPER_DOOR"
    ),
    CACTUS("CACTUS"),
    LEVER("LEVER"),


    BUGGY_BLOCK("CHEST", "ENDER_CHEST", "TRAPPED_CHEST", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL"),

    LIGHT("LIGHT"),

    WATER_BUCKET("WATER_BUCKET"),
    LAVA_BUCKET("LAVA_BUCKET"),

    TRANSPARENT("AIR","OAK_SAPLING","SPRUCE_SAPLING","BIRCH_SAPLING","JUNGLE_SAPLING","ACACIA_SAPLING","DARK_OAK_SAPLING","BAMBOO_SAPLING","POWERED_RAIL","DETECTOR_RAIL","SHORT_GRASS","FERN","DEAD_BUSH","DANDELION","POPPY","BLUE_ORCHID","ALLIUM","AZURE_BLUET","RED_TULIP","ORANGE_TULIP","WHITE_TULIP","PINK_TULIP","OXEYE_DAISY","CORNFLOWER","LILY_OF_THE_VALLEY","WITHER_ROSE","SUNFLOWER","LILAC","ROSE_BUSH","PEONY","BROWN_MUSHROOM","RED_MUSHROOM","TORCH","SOUL_TORCH","FIRE","SOUL_FIRE","REDSTONE","WHEAT","RAIL","LEVER","REDSTONE_TORCH","STONE_BUTTON","OAK_BUTTON","SPRUCE_BUTTON","BIRCH_BUTTON","JUNGLE_BUTTON","ACACIA_BUTTON","DARK_OAK_BUTTON","MANGROVE_BUTTON","BAMBOO_BUTTON","CRIMSON_BUTTON","WARPED_BUTTON","SUGAR_CANE","PUMPKIN_STEM","MELON_STEM","VINE","NETHER_WART","NETHER_PORTAL","TRIPWIRE_HOOK","TRIPWIRE","CARROTS","POTATOES","ACTIVATOR_RAIL","TALL_GRASS","LARGE_FERN","WATER","LAVA", "SCULK_VEIN", "LIGHT", "LONG_GRASS", "DOUBLE_PLANT", "YELLOW_FLOWER", "RED_ROSE", "STATIONARY_LAVA", "STATIONARY_WATER"),

    ONE_POINT_21_WEIRD(
            "SHORT_DRY_GRASS",
            "TALL_DRY_GRASS",
            "WILDFLOWERS",
            "CACTUS_FLOWER",
            "PALE_HANGING_MOSS",
            "EYEBLOSSOM"
    )
            ;

    @Getter
    public final String[] values;

    MaterialType(String... values) {
        this.values = values;
    }

    private static final Object NO_TAG = new Object();

    private static final Map<String, Object> TAG_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> TAG_RESULT_CACHE = new ConcurrentHashMap<>();

    public static boolean isStair(Block block) {
        return block != null && isStair(block.getType());
    }

    public static boolean isStair(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "STAIRS")) return true;

        String name = material.name();

        // Future-proof:
        // OAK_STAIRS, TUFF_STAIRS, RESIN_BRICK_STAIRS, WHITE_WOOL_STAIRS, etc.
        return name.endsWith("_STAIRS")
                || isMaterial(name, MaterialType.STAIRS);
    }

    public static boolean isFence(Block block) {
        return block != null && isFence(block.getType());
    }

    public static boolean isFence(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "FENCES", "WOODEN_FENCES")) return true;

        String name = material.name();

        // Future-proof:
        // OAK_FENCE, CHERRY_FENCE, WHITE_WOOL_FENCE, etc.
        // Does NOT match OAK_FENCE_GATE because that ends with _FENCE_GATE.
        return name.endsWith("_FENCE")
                || name.equals("FENCE")
                || name.equals("NETHER_FENCE")
                || name.equals("IRON_FENCE")
                || isMaterial(name, MaterialType.FENCE);
    }

    public static boolean isFenceGate(Block block) {
        return block != null && isFenceGate(block.getType());
    }

    public static boolean isFenceGate(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "FENCE_GATES")) return true;

        String name = material.name();

        return name.endsWith("_FENCE_GATE")
                || name.equals("FENCE_GATE");
    }

    public static boolean isWall(Block block) {
        return block != null && isWall(block.getType());
    }

    public static boolean isWall(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "WALLS")) return true;

        String name = material.name();

        // Future-proof:
        // COBBLESTONE_WALL, TUFF_WALL, WHITE_WOOL_WALL, etc.
        return name.endsWith("_WALL")
                || name.equals("COBBLE_WALL")
                || isMaterial(name, MaterialType.WALL);
    }

    public static boolean isSlab(Block block) {
        return block != null && isSlab(block.getType());
    }

    public static boolean isSlab(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "SLABS", "WOODEN_SLABS")) return true;

        String name = material.name();

        return name.endsWith("_SLAB")
                || name.equals("STEP")
                || name.equals("WOODEN_SLAB")
                || name.equals("STONE_SLAB2")
                || isMaterial(name, MaterialType.SLAB);
    }

    public static boolean isDoor(Block block) {
        return block != null && isDoor(block.getType());
    }

    public static boolean isDoor(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "DOORS", "WOODEN_DOORS")) return true;

        String name = material.name();

        return name.endsWith("_DOOR")
                || name.equals("WOODEN_DOOR")
                || name.equals("IRON_DOOR_BLOCK")
                || isMaterial(name, MaterialType.DOOR);
    }

    public static boolean isTrapdoor(Block block) {
        return block != null && isTrapdoor(block.getType());
    }

    public static boolean isTrapdoor(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "TRAPDOORS", "WOODEN_TRAPDOORS")) return true;

        String name = material.name();

        return name.endsWith("_TRAPDOOR")
                || name.equals("TRAP_DOOR")
                || isMaterial(name, MaterialType.TRAPDOOR);
    }

    public static boolean isPane(Block block) {
        return block != null && isPane(block.getType());
    }

    public static boolean isPane(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "GLASS_PANES")) return true;

        String name = material.name();

        return name.endsWith("_PANE")
                || name.equals("GLASS_PANE")
                || name.equals("STAINED_GLASS_PANE")
                || name.equals("IRON_BARS")
                || name.equals("IRON_FENCE")
                || isMaterial(name, MaterialType.PANE);
    }

    public static boolean isButton(Block block) {
        return block != null && isButton(block.getType());
    }

    public static boolean isButton(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "BUTTONS", "WOODEN_BUTTONS")) return true;

        return material.name().endsWith("_BUTTON")
                || material.name().equals("STONE_BUTTON")
                || material.name().equals("WOOD_BUTTON");
    }

    public static boolean isPressurePlate(Block block) {
        return block != null && isPressurePlate(block.getType());
    }

    public static boolean isPressurePlate(Material material) {
        if (material == null) return false;

        if (hasAnyBukkitTag(material, "PRESSURE_PLATES", "WOODEN_PRESSURE_PLATES", "STONE_PRESSURE_PLATES")) return true;

        String name = material.name();

        return name.endsWith("_PRESSURE_PLATE")
                || name.equals("STONE_PLATE")
                || name.equals("WOOD_PLATE")
                || name.equals("GOLD_PLATE")
                || name.equals("IRON_PLATE");
    }

    public static boolean isStair(String materialName) {
        String name = normalizeMaterialName(materialName);
        return name != null && (name.endsWith("_STAIRS") || isMaterial(name, MaterialType.STAIRS));
    }

    public static boolean isFence(String materialName) {
        String name = normalizeMaterialName(materialName);
        return name != null && (
                name.endsWith("_FENCE")
                        || name.equals("FENCE")
                        || name.equals("NETHER_FENCE")
                        || name.equals("IRON_FENCE")
                        || isMaterial(name, MaterialType.FENCE)
        );
    }

    public static boolean isFenceGate(String materialName) {
        String name = normalizeMaterialName(materialName);
        return name != null && (name.endsWith("_FENCE_GATE") || name.equals("FENCE_GATE"));
    }

    public static boolean isWall(String materialName) {
        String name = normalizeMaterialName(materialName);
        return name != null && (
                name.endsWith("_WALL")
                        || name.equals("COBBLE_WALL")
                        || isMaterial(name, MaterialType.WALL)
        );
    }

    private static String normalizeMaterialName(String materialName) {
        if (materialName == null) return null;

        String name = materialName.trim();

        if (name.isEmpty()) return null;

        int namespaceIndex = name.indexOf(':');
        if (namespaceIndex != -1) {
            name = name.substring(namespaceIndex + 1);
        }

        return name.toUpperCase(Locale.ROOT);
    }

    private static boolean hasAnyBukkitTag(Material material, String... tagNames) {
        if (material == null || tagNames == null) return false;

        for (String tagName : tagNames) {
            if (hasBukkitTag(material, tagName)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasBukkitTag(Material material, String tagName) {
        if (material == null || tagName == null) return false;

        String resultKey = tagName + ":" + material.name();
        Boolean cachedResult = TAG_RESULT_CACHE.get(resultKey);

        if (cachedResult != null) {
            return cachedResult;
        }

        Object tag = getBukkitTag(tagName);

        if (tag == NO_TAG) {
            TAG_RESULT_CACHE.put(resultKey, false);
            return false;
        }

        try {
            Class<?> tagClass = Class.forName("org.bukkit.Tag");
            Method isTagged = tagClass.getMethod("isTagged", Object.class);

            boolean result = Boolean.TRUE.equals(isTagged.invoke(tag, material));

            TAG_RESULT_CACHE.put(resultKey, result);
            return result;
        } catch (Throwable ignored) {
            TAG_RESULT_CACHE.put(resultKey, false);
            return false;
        }
    }

    private static Object getBukkitTag(String tagName) {
        Object cached = TAG_CACHE.get(tagName);

        if (cached != null) {
            return cached;
        }

        try {
            Class<?> tagClass = Class.forName("org.bukkit.Tag");
            Field field = tagClass.getField(tagName);
            Object tag = field.get(null);

            TAG_CACHE.put(tagName, tag);
            return tag;
        } catch (Throwable ignored) {
            TAG_CACHE.put(tagName, NO_TAG);
            return NO_TAG;
        }
    }

    public static boolean isMaterial(String value, MaterialType type) {
        /*
        Null checking since we're going to be using the "==" operator which is not null safe.
         */
        if (value == null) return false;

        /*
        Compare using "=="
        NOTE: This does improve perfomance by a L O T
        However this can ONLY be used in cases such as this (Materials)
        Where we're comparing string objects that never change no matter what (Applies to constant strings aswell).
         */
        for (String t : type.values) if (value == t) return true;

        return false;
    }
}