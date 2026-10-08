package me.arrow.checks.impl.misc.vehicle;

import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.ReflectionUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Method;

final class VehicleSupport {
    private VehicleSupport() {
    }

    static boolean isBoat(Entity entity) {
        if (entity == null) return false;
        String type = entity.getType().name();
        return type.contains("BOAT") || type.contains("RAFT");
    }

    static boolean isHorse(Entity entity) {
        return entity != null && entity.getType().name().contains("HORSE");
    }

    static boolean hasWaterContact(Location location) {
        return hasFluid(location, -1, 1, false);
    }

    static boolean isInsideWater(Location location) {
        return hasFluid(location, 0, 1, false);
    }

    static boolean hasBubbleColumn(Location location) {
        return hasFluid(location, -1, 2, true);
    }

    static boolean isIceSurface(double friction) {
        return friction >= 0.975D;
    }

    /** Per-vehicle support history; separate from the rider's movement state. */
    static final class IceState {
        private int iceTicks;
        private int sinceIceTicks = 1000;

        void update(boolean onIce) {
            if (onIce) {
                iceTicks = Math.min(1000, iceTicks + 1);
                sinceIceTicks = 0;
            } else {
                iceTicks = 0;
                sinceIceTicks = Math.min(1000, sinceIceTicks + 1);
            }
        }

        void reset() {
            iceTicks = 0;
            sinceIceTicks = 1000;
        }

        int getIceTicks() {
            return iceTicks;
        }

        int getSinceIceTicks() {
            return sinceIceTicks;
        }
    }

    /**
     * Slipperiness at the boat's immediate support layer.  Deliberately do not
     * search one block farther down: that block is visible while the boat is
     * airborne and must not turn air movement into ordinary 0.6 floor drag.
     */
    static double surfaceFriction(Location location) {
        if (location == null || location.getWorld() == null) return 0.91D;
        World world = location.getWorld();
        int baseX = location.getBlockX(), baseY = (int) Math.floor(location.getY() - 0.125D), baseZ = location.getBlockZ();
        double friction = 0.91D;
        boolean sampledSupport = false;

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Material material = ChunkCache.get().getBlock(world, baseX + x, baseY, baseZ + z);
                if (material == null || isFluidOrAir(world, baseX + x, baseY, baseZ + z, material)) continue;
                double blockFriction = CollisionUtils.getBlockSlipperiness(material);
                if (!sampledSupport) {
                    friction = blockFriction;
                    sampledSupport = true;
                } else {
                    friction = Math.max(friction, blockFriction);
                }
            }
        }
        return friction;
    }

    private static boolean hasFluid(Location location, int minYOffset, int maxYOffset, boolean bubblesOnly) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        int baseX = location.getBlockX(), baseY = location.getBlockY(), baseZ = location.getBlockZ();

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = minYOffset; y <= maxYOffset; y++) {
                    Material material = ChunkCache.get().getBlock(world, baseX + x, baseY + y, baseZ + z);
                    if (material == null) continue;
                    String name = material.name();
                    if (bubblesOnly ? name.equals("BUBBLE_COLUMN") : isWater(world, baseX + x, baseY + y, baseZ + z, name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isWater(World world, int x, int y, int z, String materialName) {
        return materialName.contains("WATER") || materialName.equals("BUBBLE_COLUMN")
                || CollisionUtils.isWaterLogged(world, x, y, z);
    }

    private static boolean isFluidOrAir(World world, int x, int y, int z, Material material) {
        String name = material.name();
        return name.equals("AIR") || name.equals("CAVE_AIR") || name.equals("VOID_AIR")
                || isWater(world, x, y, z, name) || name.contains("LAVA");
    }

    static double horseSpeed(Entity horse) {
        double base = ReflectionUtils.getEntityAttributeBaseValue(horse, "MOVEMENT_SPEED", 0.225D);
        double effective = ReflectionUtils.getEntityAttributeValue(horse, "MOVEMENT_SPEED", base);
        return Math.max(effective, base * (1.0D + effectLevel(horse, "SPEED") * 0.2D));
    }

    static double horseJumpVelocity(Entity horse) {
        double strength = ReflectionUtils.getEntityAttributeValue(horse, "JUMP_STRENGTH", Double.NaN);
        if (!Double.isFinite(strength) || strength <= 0.0D) {
            strength = legacyJumpStrength(horse);
        }
        return strength + (Math.max(effectLevel(horse, "JUMP"), effectLevel(horse, "JUMP_BOOST")) * 0.1D);
    }

    static int effectLevel(Entity entity, String effectName) {
        if (!(entity instanceof LivingEntity)) return 0;
        LivingEntity living = (LivingEntity) entity;
        try {
            for (PotionEffect effect : living.getActivePotionEffects()) {
                if (effect != null && effect.getType() != null
                        && effect.getType().getName().equalsIgnoreCase(effectName)) {
                    return effect.getAmplifier() + 1;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static double legacyJumpStrength(Entity horse) {
        try {
            Method method = horse.getClass().getMethod("getJumpStrength");
            Object value = method.invoke(horse);
            if (value instanceof Number) return ((Number) value).doubleValue();
        } catch (Throwable ignored) {
        }
        return 0.7D;
    }
}
