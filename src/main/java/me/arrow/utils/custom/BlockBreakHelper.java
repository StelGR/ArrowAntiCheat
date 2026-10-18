package me.arrow.utils.custom;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Universal block break speed and progress calculator for all Minecraft versions (1.8 - 26.3).
 * Uses Paper's native Block#getBreakSpeed(Player) if available, and falls back to the exact
 * vanilla NMS formula with tool tier, efficiency, haste, fatigue, water, and airborne penalties.
 */
public final class BlockBreakHelper {

    private static final MethodHandle BLOCK_GET_BREAK_SPEED;

    static {
        MethodHandle handle = null;
        try {
            Method m = Block.class.getMethod("getBreakSpeed", Player.class);
            handle = MethodHandles.publicLookup().unreflect(m);
        } catch (Throwable ignored) {
        }
        BLOCK_GET_BREAK_SPEED = handle;
    }

    private BlockBreakHelper() {
    }

    /**
     * Returns the destroy progress per tick (0.0 to 1.0+).
     * If >= 1.0F, the block is instantly breakable in 1 tick.
     */
    public static float getBreakProgress(Player player, Block block) {
        if (player == null || block == null) {
            return 1.0F;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            return 1.0F;
        }

        // 1. If Paper's Block#getBreakSpeed(Player) is available, use it (exact vanilla NMS formula)
        if (BLOCK_GET_BREAK_SPEED != null) {
            try {
                float speed = (float) BLOCK_GET_BREAK_SPEED.invoke(block, player);
                if (Float.isFinite(speed) && speed >= 0.0F) {
                    return speed;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Comprehensive vanilla formula fallback (for 1.8-1.12 or forks without getBreakSpeed)
        return calculateVanillaBreakProgress(player, block);
    }

    /**
     * Calculates the minimum ticks required to break a block.
     * Returns 1 for instant break blocks.
     */
    public static int getMinimumBreakTicks(Player player, Block block) {
        float progress = getBreakProgress(player, block);
        if (progress >= 1.0F) {
            return 1;
        }
        if (progress <= 0.0F) {
            return 999999;
        }
        return (int) Math.ceil(1.0F / progress);
    }

    public static boolean isInstantBreak(Player player, Block block) {
        return getBreakProgress(player, block) >= 1.0F;
    }

    private static float calculateVanillaBreakProgress(Player player, Block block) {
        Material mat = block.getType();
        float hardness = mat.getHardness();

        // Bedrock, barriers, end portal frames, etc.
        if (hardness < 0.0F) {
            return 0.0F;
        }

        // Grass, flowers, torches, redstone, tripwire, etc.
        if (hardness == 0.0F) {
            return 1.0F;
        }

        ItemStack held = getHeldItem(player);
        String heldName = held != null && held.getType() != null ? held.getType().name().toUpperCase(Locale.ROOT) : "";
        String blockName = mat.name().toUpperCase(Locale.ROOT);

        boolean isAppropriateTool = isAppropriateTool(heldName, blockName);
        boolean canHarvest = canHarvestDrops(heldName, blockName);

        float speed = 1.0F;
        if (isAppropriateTool) {
            speed = getBaseToolSpeed(heldName);

            int effLevel = getEfficiencyLevel(held);
            if (effLevel > 0) {
                speed += (effLevel * effLevel + 1);
            }
        } else if (isSwordOnWebOrBamboo(heldName, blockName)) {
            speed = 15.0F;
        } else if (isShearsOnSpecial(heldName, blockName)) {
            speed = blockName.contains("COBWEB") || blockName.equals("WEB") ? 15.0F : 5.0F;
        }

        // Potion effects: Haste / Conduit Power
        int hasteAmp = getHasteAmplifier(player);
        if (hasteAmp >= 0) {
            speed *= (1.0F + 0.2F * (hasteAmp + 1));
        }

        // Potion effects: Mining Fatigue
        int fatigueAmp = getMiningFatigueAmplifier(player);
        if (fatigueAmp >= 0) {
            speed *= (float) Math.pow(0.3D, Math.min(fatigueAmp + 1, 4));
        }

        // Water penalty (divided by 5 if in water and no Aqua Affinity)
        if (isPlayerSubmergedInWater(player) && !hasAquaAffinity(player)) {
            speed /= 5.0F;
        }

        // In-air penalty (divided by 5 if not on ground)
        if (!player.isOnGround()) {
            speed /= 5.0F;
        }

        int divisor = canHarvest ? 30 : 100;
        return speed / hardness / divisor;
    }

    private static ItemStack getHeldItem(Player player) {
        try {
            return player.getInventory().getItemInMainHand();
        } catch (Throwable ignored) {
            try {
                return player.getItemInHand();
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static float getBaseToolSpeed(String heldName) {
        if (heldName.contains("GOLDEN_") || heldName.contains("GOLD_")) {
            return 12.0F;
        }
        if (heldName.contains("NETHERITE_")) {
            return 9.0F;
        }
        if (heldName.contains("DIAMOND_")) {
            return 8.0F;
        }
        if (heldName.contains("IRON_")) {
            return 6.0F;
        }
        if (heldName.contains("STONE_")) {
            return 4.0F;
        }
        if (heldName.contains("WOODEN_") || heldName.contains("WOOD_")) {
            return 2.0F;
        }
        return 1.0F;
    }

    private static boolean isAppropriateTool(String tool, String block) {
        if (tool.isEmpty()) {
            return false;
        }
        if (tool.endsWith("_PICKAXE")) {
            return isPickaxeBlock(block);
        }
        if (tool.endsWith("_AXE")) {
            return isAxeBlock(block);
        }
        if (tool.endsWith("_SHOVEL") || tool.endsWith("_SPADE")) {
            return isShovelBlock(block);
        }
        if (tool.endsWith("_HOE")) {
            return isHoeBlock(block);
        }
        return false;
    }

    private static boolean isPickaxeBlock(String block) {
        return block.contains("STONE") || block.contains("ORE") || block.contains("COBBLE")
                || block.contains("BRICK") || block.contains("CONCRETE") || block.contains("TERRACOTTA")
                || block.contains("IRON") || block.contains("GOLD") || block.contains("DIAMOND")
                || block.contains("EMERALD") || block.contains("COPPER") || block.contains("NETHERITE")
                || block.contains("OBSIDIAN") || block.contains("ANVIL") || block.contains("HOPPER")
                || block.contains("DISPENSER") || block.contains("DROPPER") || block.contains("FURNACE")
                || block.contains("DEEPSLATE") || block.contains("BASALT") || block.contains("BLACKSTONE")
                || block.contains("NETHERRACK") || block.contains("QUARTZ") || block.contains("ICE")
                || block.contains("SANDSTONE") || block.contains("PRISMARINE") || block.contains("CAULDRON")
                || block.contains("RAIL") || block.contains("PISTON");
    }

    private static boolean isAxeBlock(String block) {
        return block.contains("WOOD") || block.contains("LOG") || block.contains("PLANK")
                || block.contains("CHEST") || block.contains("CRAFTING_TABLE") || block.contains("FENCE")
                || block.contains("DOOR") && !block.contains("IRON")
                || block.contains("SIGN") || block.contains("BARREL") || block.contains("BOOKSHELF")
                || block.contains("PUMPKIN") || block.contains("MELON") || block.contains("BEEHIVE")
                || block.contains("BAMBOO") || block.contains("STEM") || block.contains("HYPHAE");
    }

    private static boolean isShovelBlock(String block) {
        return block.contains("DIRT") || block.contains("SAND") || block.contains("GRAVEL")
                || block.contains("CLAY") || block.contains("SOUL_") || block.contains("SNOW")
                || block.contains("MUD") || block.contains("POWDER") || block.contains("PATH")
                || block.contains("FARMLAND");
    }

    private static boolean isHoeBlock(String block) {
        return block.contains("LEAVES") || block.contains("HAY") || block.contains("SPONGE")
                || block.contains("SCULK") || block.contains("TARGET") || block.contains("SHROOMLIGHT")
                || block.contains("NETHER_WART_BLOCK") || block.contains("WARPED_WART_BLOCK");
    }

    private static boolean isSwordOnWebOrBamboo(String tool, String block) {
        return tool.endsWith("_SWORD") && (block.contains("COBWEB") || block.equals("WEB") || block.contains("BAMBOO"));
    }

    private static boolean isShearsOnSpecial(String tool, String block) {
        return tool.equals("SHEARS") && (block.contains("WOOL") || block.contains("LEAVES") || block.contains("COBWEB") || block.equals("WEB"));
    }

    private static boolean canHarvestDrops(String tool, String block) {
        // Blocks that strictly require diamond/netherite pickaxe
        if (block.contains("OBSIDIAN") || block.contains("ANCIENT_DEBRIS") || block.contains("RESPAWN_ANCHOR")) {
            return tool.startsWith("DIAMOND_PICKAXE") || tool.startsWith("NETHERITE_PICKAXE");
        }
        // Blocks that require iron+ pickaxe
        if (block.contains("DIAMOND_ORE") || block.contains("EMERALD_ORE") || block.contains("GOLD_ORE")
                || block.contains("GOLD_BLOCK") || block.contains("REDSTONE_ORE")) {
            return tool.startsWith("IRON_PICKAXE") || tool.startsWith("DIAMOND_PICKAXE") || tool.startsWith("NETHERITE_PICKAXE");
        }
        // Blocks that require stone+ pickaxe
        if (block.contains("IRON_ORE") || block.contains("COPPER_ORE") || block.contains("LAPIS_ORE")
                || block.contains("IRON_BLOCK") || block.contains("COPPER_BLOCK")) {
            return !tool.startsWith("WOODEN_") && !tool.startsWith("WOOD_") && !tool.startsWith("GOLDEN_") && !tool.startsWith("GOLD_") && tool.endsWith("_PICKAXE");
        }
        // Blocks that require any pickaxe
        if (isPickaxeBlock(block)) {
            return tool.endsWith("_PICKAXE");
        }
        // Blocks that can be harvested by hand or any tool
        return true;
    }

    private static int getEfficiencyLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        try {
            for (Enchantment ench : item.getEnchantments().keySet()) {
                String name = ench.getName().toUpperCase(Locale.ROOT);
                if (name.contains("DIG_SPEED") || name.contains("EFFICIENCY")) {
                    return item.getEnchantments().get(ench);
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int getHasteAmplifier(Player player) {
        try {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                String name = effect.getType().getName().toUpperCase(Locale.ROOT);
                if (name.contains("FAST_DIGGING") || name.contains("HASTE") || name.contains("CONDUIT_POWER")) {
                    return effect.getAmplifier();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static int getMiningFatigueAmplifier(Player player) {
        try {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                String name = effect.getType().getName().toUpperCase(Locale.ROOT);
                if (name.contains("SLOW_DIGGING") || name.contains("MINING_FATIGUE") || name.contains("FATIGUE")) {
                    return effect.getAmplifier();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static boolean isPlayerSubmergedInWater(Player player) {
        try {
            Block block = player.getLocation().getBlock();
            if (block != null && (block.getType().name().contains("WATER") || block.isLiquid())) {
                return true;
            }
            Block eyeBlock = player.getEyeLocation().getBlock();
            return eyeBlock != null && (eyeBlock.getType().name().contains("WATER") || eyeBlock.isLiquid());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAquaAffinity(Player player) {
        try {
            ItemStack helmet = player.getInventory().getHelmet();
            if (helmet == null || !helmet.hasItemMeta()) {
                return false;
            }
            for (Enchantment ench : helmet.getEnchantments().keySet()) {
                String name = ench.getName().toUpperCase(Locale.ROOT);
                if (name.contains("WATER_WORKER") || name.contains("AQUA_AFFINITY")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
