package me.arrow.checks.impl.movement.speed.SpeedMath;

import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.VelocityData;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

public class SpeedUtilities {

    static double DEFAULT_WALK_SPEED_ATTRIBUTE = 0.1D;
    static double VANILLA_SPRINT_MULTIPLIER = 1.3D;
    static double SPEED_MULTIPLIER_AIR = 0.125D;
    static double SPEED_MULTIPLIER_GROUND = 0.213D;
    static double MAX_REASONABLE_MOVEMENT_ATTRIBUTE = 1024.0D;
    // Precise base effective speed (walk attribute * sprint multiplier) used for thresholds
    static final double BASE_EFFECTIVE_SPEED = 0.13001D;


    // --- Core Speed Limits ---

    public static double computeGroundLimit(Profile profile, VelocityData velocityData, double defaultBaseSpeed) {
        // Apply sprint multiplier inside movement scale (handled in getMovementScaleGround)
        double limit = defaultBaseSpeed * getMovementScaleGround(profile);
        if (velocityData.isTakingVelocity()) {
            limit += velocityData.getTotalHorizontalVelocity() * 2.0D;
        }
        return limit;
    }

    public static double computeAirLimit(Profile profile, double defaultAirBaseSpeed) {
        return defaultAirBaseSpeed * getMovementScaleAir(profile);
    }

    // --- Attributes & Sprinting ---

    public static double getMovementSpeedAttribute(Profile profile) {
        try {
            AttributeInstance attribute = profile.getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);
            if (attribute == null) return DEFAULT_WALK_SPEED_ATTRIBUTE;
            // Use the final attribute value, which includes all modifiers (potions, beacons, status effects)
            double val = attribute.getValue();
            if (Double.isNaN(val) || Double.isInfinite(val) || val <= DEFAULT_WALK_SPEED_ATTRIBUTE) {
                return DEFAULT_WALK_SPEED_ATTRIBUTE;
            }
            // Clamp to a reasonable maximum to avoid absurd values from mods or bugs
            return Math.min(MAX_REASONABLE_MOVEMENT_ATTRIBUTE, val);
        } catch (Throwable ignored) {
            return DEFAULT_WALK_SPEED_ATTRIBUTE;
        }
    }

    public static double getSprintingMultiplier(Profile profile) {
        if (profile.getActionData().isSprinting()) {
            return VANILLA_SPRINT_MULTIPLIER;
        }
        return 1.0D;
    }

    public static double getSprintingAttributeSpeed(Profile profile) {
        return getMovementSpeedAttribute(profile) * getSprintingMultiplier(profile);
    }

    // --- Potions & Enchants ---

    public static int getSpeedPotionLevel(Profile profile) {
        if (!profile.getPotionData().isHasSpeed()) {
            if (profile.getMovementData().getSinceSpeedPotionEffectTicks() <= 20) {
                return Math.max(0, profile.getPotionData().getLastSpeedAmplifier());
            }
            return 0;
        }
        return Math.max(0, profile.getPotionData().getSpeedAmplifier());
    }

    public static int getJumpBoostPotionLevel(Profile profile) {
        if (!profile.getPotionData().isHasJump()) {
            if (profile.getMovementData().getSinceJumpBoostEffectTicks() <= 10) {
                return Math.max(0, profile.getPotionData().getLastJumpAmplifier());
            }
            return 0;
        }
        return Math.max(0, profile.getPotionData().getJumpAmplifier());
    }

    public static int getJumpBoostPotionLevelInstant(Profile profile) {
        return Math.max(0, profile.getPotionData().getJumpAmplifier());
    }

    public static double getPotionSpeedAirMultiplier(Profile profile) {
        return 1.0D + (SPEED_MULTIPLIER_AIR * getSpeedPotionLevel(profile));
    }

    public static double getPotionSpeedGroundMultiplier(Profile profile) {
        return 1.0D + (SPEED_MULTIPLIER_GROUND * getSpeedPotionLevel(profile));
    }

    public static int getSoulSpeedLevel(Profile profile) {
        try {
            ItemStack boots = profile.getPlayer().getInventory().getBoots();
            if (boots == null || boots.getType() == Material.AIR) return 0;
            Enchantment ss = Enchantment.getByName("SOUL_SPEED");
            return ss != null ? Math.min(3, Math.max(0, boots.getEnchantmentLevel(ss))) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int getDepthStriderLevel(Profile profile) {
        try {
            ItemStack boots = profile.getPlayer().getInventory().getBoots();
            if (boots == null || !boots.hasItemMeta()) return 0;
            Enchantment ds = Enchantment.getByName("DEPTH_STRIDER");
            return ds != null ? boots.getEnchantmentLevel(ds) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static double getDepthStriderBoost(Profile profile) {
        return getDepthStriderLevel(profile) * (2.75D / 20.0D);
    }

    // --- Movement Scale (Attribute Ratio * Potion Multiplier) ---

    public static double getMovementScaleAir(Profile profile) {
        double effectiveSpeed = getMovementSpeedAttribute(profile);
        double attrRatio = effectiveSpeed / BASE_EFFECTIVE_SPEED;
        if (attrRatio < 1.0D) attrRatio = 1.0D; // do not reduce limit for low attributes
        return Math.max(0.0D, attrRatio * getPotionSpeedAirMultiplier(profile));
    }

    public static double getMovementScaleGround(Profile profile) {
        double effectiveSpeed = getMovementSpeedAttribute(profile);
        double baseEffective = BASE_EFFECTIVE_SPEED;
        double attrRatio = effectiveSpeed / baseEffective;
        if (attrRatio < 1.0D) attrRatio = 1.0D; // clamp low attributes
        // Preserve original tweak for high attribute values (adjusted for effective speed)
        if (effectiveSpeed > baseEffective) attrRatio += 0.0375;
        return Math.max(0.0D, attrRatio * getPotionSpeedGroundMultiplier(profile));
    }

    public static double getMinMovementSpeedAir(Profile profile) {
        return getMovementSpeedAttribute(profile) * getSprintingMultiplier(profile) * getPotionSpeedAirMultiplier(profile);
    }

    public static double getMinMovementSpeedGround(Profile profile) {
        return getMovementSpeedAttribute(profile) * getSprintingMultiplier(profile) * getPotionSpeedGroundMultiplier(profile);
    }

    // --- Check Threshold Bonuses ---

    // Base limits for attribute contribution
    private static final double GROUND_BASE_LIMIT = 0.28063D;
    private static final double AIR_BASE_LIMIT = 0.35301212D;

    public static double getGroundAttributeBonus(Profile profile) {
        // Effective speed includes sprint multiplier for ground calculations
        double effectiveSpeed = getMovementSpeedAttribute(profile);
        double delta = effectiveSpeed - BASE_EFFECTIVE_SPEED;
        return Math.max(delta, 0.0D);
        // Return the raw delta as the ground attribute bonus
    }

    public static double getAirAttributeBonus(Profile profile) {
        // Air does not use sprint multiplier, but still should ignore attributes below the base effective speed (0.13).
        double effectiveSpeed = getMovementSpeedAttribute(profile);
        double delta = effectiveSpeed - BASE_EFFECTIVE_SPEED;
        if (delta <= 0.0D) {
            return 0.0D;
        }
        return delta * (AIR_BASE_LIMIT / BASE_EFFECTIVE_SPEED);
    }

    // Helper to fetch Traveler enchant level from boots (custom enchant name "TRAVELER")
    private static int getTravelerEnchantLevel(Profile profile) {
        try {
            ItemStack boots = profile.getPlayer().getInventory().getBoots();
            if (boots == null || boots.getType() == Material.AIR) return 0;
            // Enchantment may be a custom one, attempt by name
            Enchantment traveler = Enchantment.getByName("TRAVELER");
            if (traveler == null) return 0;
            return boots.getEnchantmentLevel(traveler);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static double getGroundPotionBonus(Profile profile) {
        return 0.28063D * SPEED_MULTIPLIER_GROUND * getSpeedPotionLevel(profile);
    }

    public static double getAirPotionBonus(Profile profile) {
        return 0.35301212D * SPEED_MULTIPLIER_AIR * getSpeedPotionLevel(profile);
    }

    public static double getGroundSpeedLimitBonus(Profile profile) {
        return Math.max(0.0D, 0.28063D * (getMovementScaleGround(profile) - 1.0D));
    }

    public static double getAirSpeedLimitBonus(Profile profile) {
        return Math.max(0.0D, 0.35301212D * (getMovementScaleAir(profile) - 1.0D));
    }

    public static double getGroundAttributePotionBonus(Profile profile) {
        return Math.max(0.0D, getGroundSpeedLimitBonus(profile) - getGroundAttributeBonus(profile) - getGroundPotionBonus(profile));
    }

    public static double getAirAttributePotionBonus(Profile profile) {
        return Math.max(0.0D, getAirSpeedLimitBonus(profile) - getAirAttributeBonus(profile) - getAirPotionBonus(profile));
    }
    
    public static double getAfterJumpSpeed(Profile profile) {
        return 0.76D + (0.008D * getSpeedPotionLevel(profile));
    }
}