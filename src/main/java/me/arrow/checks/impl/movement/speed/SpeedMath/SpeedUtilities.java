package me.arrow.checks.impl.movement.speed.SpeedMath;

import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.VelocityData;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

public class SpeedUtilities {
    public static final double DEFAULT_WALK_SPEED_ATTRIBUTE = 0.1D;
    public static final double VANILLA_SPRINT_MULTIPLIER = 1.3D;
    public static final double SPEED_MULTIPLIER_AIR = 0.125D;
    public static final double SPEED_MULTIPLIER_GROUND = 0.2141D;
    public static final double MAX_REASONABLE_MOVEMENT_ATTRIBUTE = 1024.0D;
    private static final double MIN_FRICTION = 1.0E-4D;

    public static double computeGroundLimit(Profile profile, VelocityData velocityData, double defaultBaseSpeed) {
        double limit = defaultBaseSpeed * getEffectiveMovementScaleGround(profile);
        if (velocityData.isTakingVelocity()) limit += velocityData.getTotalHorizontalVelocity() * 2;
        return limit;
    }

    public static double computeAirLimit(Profile profile, double defaultAirBaseSpeed) {
        return defaultAirBaseSpeed * getEffectiveMovementScaleAir(profile);
    }

    public static double friction(double blockFriction) {
        return Math.max(MIN_FRICTION, (Double.isNaN(blockFriction) || Double.isInfinite(blockFriction)) ? MIN_FRICTION : blockFriction);
    }

    public static int getSoulSpeedLevel(Profile profile) {
        try {
            ItemStack boots = profile.getPlayer().getInventory().getBoots();
            if (boots == null || boots.getType() == org.bukkit.Material.AIR) return 0;
            Enchantment ss = Enchantment.getByName("SOUL_SPEED");
            return ss == null ? 0 : Math.min(3, Math.max(0, boots.getEnchantmentLevel(ss)));
        } catch (Throwable ignored) { return 0; }
    }

    public static double getMovementSpeedAttribute(Profile profile) {
        try {
            if (profile == null || profile.getPlayer() == null) return DEFAULT_WALK_SPEED_ATTRIBUTE;
            AttributeInstance ai = profile.getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);
            return ai == null ? DEFAULT_WALK_SPEED_ATTRIBUTE : clamp(ai.getBaseValue());
        } catch (Throwable ignored) { return DEFAULT_WALK_SPEED_ATTRIBUTE; }
    }

    public static double getMovementSpeedAttributeValue(Profile profile) {
        try {
            if (profile == null || profile.getPlayer() == null) return DEFAULT_WALK_SPEED_ATTRIBUTE;
            AttributeInstance ai = profile.getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);
            return ai == null ? getMovementSpeedAttribute(profile) : clamp(ai.getValue());
        } catch (Throwable ignored) { return getMovementSpeedAttribute(profile); }
    }

    private static double clamp(double value) {
        return (Double.isNaN(value) || Double.isInfinite(value)) ? DEFAULT_WALK_SPEED_ATTRIBUTE : Math.min(MAX_REASONABLE_MOVEMENT_ATTRIBUTE, Math.max(0, value));
    }

    private static boolean isSprinting(Profile profile) {
        try { return profile != null && profile.getActionData() != null && profile.getActionData().isSprinting(); } catch (Throwable ignored) { return false; }
    }

    public static double getSprintingMultiplier(Profile profile) {
        return isSprinting(profile) ? VANILLA_SPRINT_MULTIPLIER : 1.0D;
    }

    public static double getManualEffectiveMovementSpeedAir(Profile profile) {
        return clamp(getMovementSpeedAttribute(profile) * getSprintingMultiplier(profile) * getPotionSpeedAirMultiplier(profile));
    }

    public static double getManualEffectiveMovementSpeedGround(Profile profile) {
        return clamp(getMovementSpeedAttribute(profile) * getSprintingMultiplier(profile) * getPotionSpeedGroundMultiplier(profile));
    }

    public static double getEffectiveMovementSpeedAir(Profile profile) {
        return Math.max(getManualEffectiveMovementSpeedAir(profile), getMovementSpeedAttributeValue(profile));
    }

    public static double getEffectiveMovementSpeedGround(Profile profile) {
        return Math.max(getManualEffectiveMovementSpeedGround(profile), getMovementSpeedAttributeValue(profile));
    }

    public static double getVanillaReferenceMovementSpeed(Profile profile) {
        return DEFAULT_WALK_SPEED_ATTRIBUTE * getSprintingMultiplier(profile);
    }

    public static double getEffectiveMovementScaleAir(Profile profile) {
        double ref = getVanillaReferenceMovementSpeed(profile);
        return ref <= 0 ? 1 : Math.max(0, getEffectiveMovementSpeedAir(profile) / ref);
    }

    public static double getEffectiveMovementScaleGround(Profile profile) {
        double ref = getVanillaReferenceMovementSpeed(profile);
        return ref <= 0 ? 1 : Math.max(0, getEffectiveMovementSpeedGround(profile) / ref);
    }

    public static double getAttributeBonusAir(Profile profile, double coefficient, double maxBonus) {
        return Math.min(maxBonus, Math.max(0, getEffectiveMovementScaleAir(profile) - getPotionSpeedAirMultiplier(profile)) * coefficient);
    }

    public static double getAttributeBonusGround(Profile profile, double coefficient, double maxBonus) {
        return Math.min(maxBonus, Math.max(0, getEffectiveMovementScaleGround(profile) - getPotionSpeedGroundMultiplier(profile)) * coefficient);
    }

    public static double getGroundAttributeBonus(Profile profile) {
        return getAttributeBonusGround(profile, 0.27397D, MAX_REASONABLE_MOVEMENT_ATTRIBUTE);
    }

    public static double getAirAttributeBonus(Profile profile) {
        return getAttributeBonusAir(profile, 0.35301212D, MAX_REASONABLE_MOVEMENT_ATTRIBUTE);
    }

    public static double getGroundPotionBonus(Profile profile) {
        double scale = getPotionSpeedGroundMultiplier(profile) - 1;
        return scale <= 0 ? 0 : 0.2778085125D * scale;
    }

    public static double getAirPotionBonus(Profile profile) {
        double scale = getPotionSpeedAirMultiplier(profile) - 1;
        return scale <= 0 ? 0 : 0.35301212D * scale;
    }

    public static int getSpeedPotionLevel(Profile profile) {
        if (profile == null || profile.getPotionData() == null) return 0;
        if (!profile.getPotionData().isHasSpeed()) {
            try {
                if (profile.getMovementData() != null && profile.getMovementData().getSinceSpeedPotionEffectTicks() <= 20)
                    return Math.max(0, profile.getPotionData().getLastSpeedAmplifier());
            } catch (Throwable ignored) {}
            return 0;
        }
        return Math.max(0, profile.getPotionData().getSpeedAmplifier());
    }

    public static int getJumpBoostPotionLevel(Profile profile) {
        if (profile == null || profile.getPotionData() == null) return 0;
        if (!profile.getPotionData().isHasJump()) {
            try {
                if (profile.getMovementData() != null && profile.getMovementData().getSinceJumpBoostEffectTicks() <= 10)
                    return Math.max(0, profile.getPotionData().getLastJumpAmplifier());
            } catch (Throwable ignored) {}
            return 0;
        }
        return Math.max(0, profile.getPotionData().getJumpAmplifier());
    }

    public static int getJumpBoostPotionLevelInstant(Profile profile) {
        return (profile == null || profile.getPotionData() == null) ? 0 : Math.max(0, profile.getPotionData().getJumpAmplifier());
    }

    public static double getPotionSpeedAirMultiplier(Profile profile) {
        int lvl = getSpeedPotionLevel(profile);
        return lvl <= 0 ? 1 : 1 + SPEED_MULTIPLIER_AIR * lvl;
    }

    public static double getPotionSpeedGroundMultiplier(Profile profile) {
        int lvl = getSpeedPotionLevel(profile);
        return lvl <= 0 ? 1 : 1 + SPEED_MULTIPLIER_GROUND * lvl;
    }


    public static double getSprintingAttributeSpeed(Profile profile) {
        return getMovementSpeedAttribute(profile) * getSprintingMultiplier(profile);
    }

    public static double getIceSpeedBoost(double increment, double movingIceTicks, double limit) {
        return movingIceTicks > 0 ? Math.min(increment * movingIceTicks, limit) : 0;
    }

    public static double getAfterJumpSpeed(Profile profile) {
        return 0.72D + 0.008D * getSpeedPotionLevel(profile);
    }

    public static int getDepthStriderLevel(Profile profile) {
        try {
            if (profile == null || profile.getPlayer() == null) return 0;
            ItemStack boots = profile.getPlayer().getInventory().getBoots();
            return (boots != null && boots.hasItemMeta()) ? boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER) : 0;
        } catch (Throwable ignored) { return 0; }
    }

    public static double getDepthStriderBoost(Profile profile) {
        return getDepthStriderLevel(profile) * (2.75 / 20.0D);
    }

    public static double getAirAttributePotionBonus(Profile profile) {
        return Math.max(0, getAirSpeedLimitBonus(profile) - getAirAttributeBonus(profile) - getAirPotionBonus(profile));
    }

    public static double getGroundAttributePotionBonus(Profile profile) {
        return Math.max(0, getGroundSpeedLimitBonus(profile) - getGroundAttributeBonus(profile) - getGroundPotionBonus(profile));
    }

    public static double getAirSpeedLimitBonus(Profile profile) {
        return Math.max(0, computeAirLimit(profile, 0.35301212D) - 0.35301212D);
    }

    public static double getGroundSpeedLimitBonus(Profile profile) {
        return Math.max(0, 0.2778085125D * getEffectiveMovementScaleGround(profile) - 0.2778085125D);
    }
}