package me.arrow.checks.impl.movement.speed.SpeedMath;

import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.custom.PotionType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

// our speed utilities, this is where Speed A (Ground) is computed.

public class SpeedUtilities {

    public static final double DEFAULT_WALK_SPEED_ATTRIBUTE = 0.1D;
    public static final double VANILLA_SPRINT_MULTIPLIER = 1.3D;
    public static final double SPEED_POTION_MULTIPLIER_PER_LEVEL = 0.2D;

    // Vanilla's generic.movement_speed max is extremely high on modern versions.
    // Keep this high enough to support real attribute values, but clamp bad/plugin-corrupt values.
    public static final double MAX_REASONABLE_MOVEMENT_ATTRIBUTE = 1024.0D;
    private static final double MIN_FRICTION = 1.0E-4D;

    public static double computeGroundLimit(Profile profile,
                                            VelocityData velocityData,
                                            double blockFriction,
                                            double iceBoost,
                                            double sprintBase,
                                            double noSprintBase,
                                            double defaultBaseSpeed) {
        double groundLimit = defaultBaseSpeed * getEffectiveMovementScale(profile);

        groundLimit += iceBoost;

        if (velocityData != null) {
            groundLimit += Math.max(0.0D, velocityData.getTotalHorizontalVelocity());
        }

        return groundLimit;
    }

    public static double computeAirLimit(Profile profile, double defaultAirBaseSpeed) {
        return defaultAirBaseSpeed * getEffectiveMovementScale(profile);
    }

    public static double friction(double blockFriction) {
        if (Double.isNaN(blockFriction) || Double.isInfinite(blockFriction)) {
            return MIN_FRICTION;
        }

        return Math.max(MIN_FRICTION, blockFriction);
    }

    public static int getSoulSpeedLevel(Profile profile) {
        try {
            org.bukkit.inventory.ItemStack boots = profile.getPlayer().getInventory().getBoots();

            if (boots == null || boots.getType() == org.bukkit.Material.AIR) {
                return 0;
            }

            org.bukkit.enchantments.Enchantment soulSpeed = org.bukkit.enchantments.Enchantment.getByName("SOUL_SPEED");

            if (soulSpeed == null) {
                return 0;
            }

            return Math.min(3, Math.max(0, boots.getEnchantmentLevel(soulSpeed)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static double getMovementSpeedAttribute(Profile profile) {
        try {
            if (profile == null || profile.getPlayer() == null) {
                return DEFAULT_WALK_SPEED_ATTRIBUTE;
            }

            AttributeInstance attributeInstance = profile.getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);

            if (attributeInstance != null) {
                return clampMovementAttribute(attributeInstance.getBaseValue());
            }
        } catch (Throwable ignored) {
        }

        return DEFAULT_WALK_SPEED_ATTRIBUTE;
    }

    public static double getMovementSpeedAttributeValue(Profile profile) {
        try {
            if (profile == null || profile.getPlayer() == null) {
                return DEFAULT_WALK_SPEED_ATTRIBUTE;
            }

            AttributeInstance attributeInstance = profile.getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);

            if (attributeInstance != null) {
                return clampMovementAttribute(attributeInstance.getValue());
            }
        } catch (Throwable ignored) {
        }

        return getMovementSpeedAttribute(profile);
    }

    private static double clampMovementAttribute(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return DEFAULT_WALK_SPEED_ATTRIBUTE;
        }

        return Math.min(MAX_REASONABLE_MOVEMENT_ATTRIBUTE, Math.max(0.0D, value));
    }

    private static boolean isSprinting(Profile profile) {
        try {
            return profile != null
                    && profile.getActionData() != null
                    && profile.getActionData().isSprinting();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static double getSprintingMultiplier(Profile profile) {
        return isSprinting(profile) ? VANILLA_SPRINT_MULTIPLIER : 1.0D;
    }

    public static double getManualEffectiveMovementSpeed(Profile profile) {
        return clampMovementAttribute(getMovementSpeedAttribute(profile)
                * getSprintingMultiplier(profile)
                * getPotionSpeedMultiplier(profile));
    }

    public static double getEffectiveMovementSpeed(Profile profile) {
        /*
         * getBaseValue() misses AttributeModifiers, while getValue() can include them.
         * Use the larger of:
         *   - manual base * sprint * Speed potion
         *   - Bukkit's final attribute value
         * This supports both /attribute base changes and plugins that add modifiers.
         */
        return Math.max(getManualEffectiveMovementSpeed(profile), getMovementSpeedAttributeValue(profile));
    }

    public static double getVanillaReferenceMovementSpeed(Profile profile) {
        return DEFAULT_WALK_SPEED_ATTRIBUTE * getSprintingMultiplier(profile);
    }

    public static double getEffectiveMovementScale(Profile profile) {
        double reference = getVanillaReferenceMovementSpeed(profile);

        if (reference <= 0.0D) {
            return 1.0D;
        }

        return Math.max(0.0D, getEffectiveMovementSpeed(profile) / reference);
    }

    public static double getAttributeBonus(Profile profile, double coefficient, double maxBonus) {
        double extraScale = Math.max(0.0D, getEffectiveMovementScale(profile) - getPotionSpeedMultiplier(profile));
        return Math.min(maxBonus, extraScale * coefficient);
    }

    public static double getGroundAttributeBonus(Profile profile) {
        return getAttributeBonus(profile, 0.2778085125D, MAX_REASONABLE_MOVEMENT_ATTRIBUTE);
    }

    public static double getAirAttributeBonus(Profile profile) {
        return getAttributeBonus(profile, 0.35301212D, MAX_REASONABLE_MOVEMENT_ATTRIBUTE);
    }

    public static double getGroundPotionBonus(Profile profile) {
        double potionScale = getPotionSpeedMultiplier(profile) - 1.0D;

        if (potionScale <= 0.0D) {
            return 0.0D;
        }

        return 0.2778085125D * potionScale;
    }

    public static double getAirPotionBonus(Profile profile) {
        double potionScale = getPotionSpeedMultiplier(profile) - 1.0D;

        if (potionScale <= 0.0D) {
            return 0.0D;
        }

        return 0.35301212D * potionScale;
    }

    public static int getSpeedPotionLevel(Profile profile) {
        if (profile == null || profile.getPotionData() == null) {
            return 0;
        }

        if (!profile.getPotionData().isHasSpeed()) {
            try {
                if (profile.getMovementData() != null
                        && profile.getMovementData().getSinceSpeedPotionEffectTicks() <= 20) {
                    return Math.max(0, profile.getPotionData().getPotionEffectLevel(PotionType.SPEED));
                }
            } catch (Throwable ignored) {
            }

            return 0;
        }

        return Math.max(0, profile.getPotionData().getPotionEffectLevel(PotionType.SPEED));
    }

    public static double getPotionSpeedMultiplier(Profile profile) {
        int level = getSpeedPotionLevel(profile);

        if (level <= 0) {
            return 1.0D;
        }

        return 1.0D + (SPEED_POTION_MULTIPLIER_PER_LEVEL * level);
    }

    public static double getAttributeScale(Profile profile) {
        double attribute = getMovementSpeedAttribute(profile);

        if (attribute <= 0.0D) {
            return 1.0D;
        }

        return Math.max(0.0D, attribute / DEFAULT_WALK_SPEED_ATTRIBUTE);
    }

    public static double getSprintingAttributeSpeed(Profile profile) {
        return getMovementSpeedAttribute(profile) * getSprintingMultiplier(profile);
    }

    public static double getAttributeAndPotionScale(Profile profile) {
        return getEffectiveMovementScale(profile);
    }

    public static double getIceSpeedBoost(double increment, double movingIceTicks, double limit) {
        return movingIceTicks > 0.0D ? Math.min(increment * movingIceTicks, limit) : 0.0D;
    }

    public static double getAfterJumpSpeed(Profile profile) {
        int speedLevel = getSpeedPotionLevel(profile);
        return 0.9175D + (0.008D * speedLevel);
    }

    public static int getPotionEffectLevel(Profile user, PotionEffectType potionEffectType) {
        if (user == null || potionEffectType == null) {
            return 0;
        }

        if (user.getPlayer() == null || !user.getPlayer().isOnline()) {
            return 0;
        }

        try {
            for (PotionEffect effect : user.getPlayer().getActivePotionEffects()) {
                if (effect == null || effect.getType() == null) {
                    continue;
                }

                if (effect.getType().equals(potionEffectType)) {
                    return effect.getAmplifier() + 1;
                }
            }
        } catch (Throwable ignored) {
            return 0;
        }

        return 0;
    }

    public static int getDepthStriderLevel(Profile profile) {
        try {
            if (profile == null || profile.getPlayer() == null) {
                return 0;
            }

            ItemStack boots = profile.getPlayer().getInventory().getBoots();

            if (boots != null && boots.hasItemMeta()) {
                return boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    public static double getDepthStriderBoost(Profile profile) {
        int depthStriderLevel = getDepthStriderLevel(profile);
        return depthStriderLevel * (2.5 / 20.0D);
    }

    public static double getAttributeExtra(Profile profile) {
        return Math.max(0.0D, getMovementSpeedAttribute(profile) - DEFAULT_WALK_SPEED_ATTRIBUTE);
    }

    public static double getAirAttributePotionBonus(Profile profile) {
        double combined = getAirSpeedLimitBonus(profile) - getAirAttributeBonus(profile) - getAirPotionBonus(profile);
        return Math.max(0.0D, combined);
    }

    public static double getGroundAttributePotionBonus(Profile profile) {
        double combined = getGroundSpeedLimitBonus(profile) - getGroundAttributeBonus(profile) - getGroundPotionBonus(profile);
        return Math.max(0.0D, combined);
    }

    public static double getAirSpeedLimitBonus(Profile profile) {
        return Math.max(0.0D, computeAirLimit(profile, 0.35301212D) - 0.35301212D);
    }

    public static double getGroundSpeedLimitBonus(Profile profile) {
        return Math.max(0.0D, (0.2778085125D * getEffectiveMovementScale(profile)) - 0.2778085125D);
    }
}
