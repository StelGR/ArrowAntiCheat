package me.arrow.checks.impl.misc.vehicle;

import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.ReflectionUtils;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
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
        if (entity == null) return false;
        String type = entity.getType().name().toUpperCase(java.util.Locale.ROOT);
        return type.contains("HORSE") || type.equals("DONKEY") || type.equals("MULE");
    }

    static boolean isMinecart(Entity entity) {
        return entity != null && entity.getType().name().toUpperCase(java.util.Locale.ROOT).contains("MINECART");
    }

    static boolean isControlledMount(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        String type = entity.getType().name().toUpperCase(java.util.Locale.ROOT);
        return type.contains("HORSE") || type.equals("DONKEY") || type.equals("MULE")
                || type.equals("PIG") || type.equals("STRIDER") || type.contains("CAMEL")
                || type.contains("HAPPY_GHAST") || type.contains("NAUTILUS");
    }

    static boolean hasFreeVerticalControl(Entity entity) {
        if (entity == null) return false;
        String type = entity.getType().name().toUpperCase(java.util.Locale.ROOT);
        return type.contains("HAPPY_GHAST") || type.contains("NAUTILUS");
    }

    static boolean isGroundBoundMount(Entity entity) {
        if (!isControlledMount(entity)) return false;
        String type = entity.getType().name().toUpperCase(java.util.Locale.ROOT);
        // The nautilus gets vertical control in water, which VehicleC exempts;
        // it still should not be able to hover through open air.
        return !type.contains("HAPPY_GHAST");
    }

    static double mountSpeed(Entity mount) {
        String type = mount == null ? "" : mount.getType().name().toUpperCase(java.util.Locale.ROOT);
        double fallback = type.equals("PIG") ? 0.25D
                : type.equals("STRIDER") ? 0.175D
                : type.contains("CAMEL") ? 0.09D
                : type.contains("GHAST") ? 0.1D
                : type.contains("NAUTILUS") ? 0.1D
                : 0.225D;
        double base = ReflectionUtils.getEntityAttributeBaseValue(mount, "MOVEMENT_SPEED", fallback);
        double effective = ReflectionUtils.getEntityAttributeValue(mount, "MOVEMENT_SPEED", base);
        return Math.max(effective, base * (1.0D + effectLevel(mount, "SPEED") * 0.2D));
    }

    static double minecartMaxSpeed(Entity minecart) {
        if (minecart != null) {
            try {
                Object value = minecart.getClass().getMethod("getMaxSpeed").invoke(minecart);
                if (value instanceof Number) {
                    double speed = ((Number) value).doubleValue();
                    if (Double.isFinite(speed) && speed > 0.0D) return speed;
                }
            } catch (Throwable ignored) {
                // Older Bukkit APIs do not expose the Paper max-speed method.
            }
        }
        return 0.4D;
    }

    static RailState railState(Location location) {
        if (location == null || location.getWorld() == null) return RailState.UNKNOWN;
        World world = location.getWorld();
        int x = floor(location.getX()), z = floor(location.getZ());
        int y = location.getBlockY();
        boolean sampledBlock = false;
        for (int offset : new int[]{0, -1, -2}) {
            Material material = ChunkCache.get().getBlock(world, x, y + offset, z);
            if (material == null) continue;
            sampledBlock = true;
            if (!material.name().toUpperCase(java.util.Locale.ROOT).contains("RAIL")) continue;

            WrappedBlockState state = ChunkCache.get().getBlockState(world, x, y + offset, z);
            boolean poweredRail = material.name().toUpperCase(java.util.Locale.ROOT).contains("POWERED_RAIL");
            if (state == null) {
                // If legacy/cache data cannot tell whether the rail is powered,
                // treat it as powered so acceleration checks do not punish it.
                return new RailState(true, poweredRail, false, false);
            }

            String shape = "";
            try {
                Object railShape = state.getShape();
                if (railShape != null) shape = railShape.toString().toUpperCase(java.util.Locale.ROOT);
            } catch (Throwable ignored) {
            }
            boolean powered = poweredRail && state.isPowered();
            return new RailState(true, powered, shape.startsWith("ASCENDING_"), true);
        }
        return sampledBlock ? RailState.NONE : RailState.UNKNOWN;
    }

    static final class RailState {
        static final RailState UNKNOWN = new RailState(false, false, false, false);
        static final RailState NONE = new RailState(false, false, false, true);
        final boolean onRail;
        final boolean powered;
        final boolean ascending;
        final boolean known;

        RailState(boolean onRail, boolean powered, boolean ascending, boolean known) {
            this.onRail = onRail;
            this.powered = powered;
            this.ascending = ascending;
            this.known = known;
        }
    }

    static boolean hasWaterContact(Location location) {
        return hasFluid(location, -1, 1, false);
    }

    static boolean isInsideWater(Location location) {
        return hasFluid(location, 0, 1, false);
    }

    static boolean isOnWaterSurface(Location location) {
        return hasFluid(location, -1, -1, false);
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
        int minX = floor(location.getX() - 0.65D);
        int maxX = floor(location.getX() + 0.65D);
        int minY = (int) Math.floor(location.getY() - 0.125D);
        int minZ = floor(location.getZ() - 0.65D);
        int maxZ = floor(location.getZ() + 0.65D);
        double frictionSum = 0.0D;
        int supportSamples = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Material material = ChunkCache.get().getBlock(world, x, minY, z);
                if (material == null || isFluidOrAir(world, x, minY, z, material)) continue;
                frictionSum += CollisionUtils.getBlockSlipperiness(material);
                supportSamples++;
            }
        }
        return supportSamples == 0 ? 0.91D : frictionSum / supportSamples;
    }

    private static boolean hasFluid(Location location, int minYOffset, int maxYOffset, boolean bubblesOnly) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        int minX = floor(location.getX() - 0.65D), maxX = floor(location.getX() + 0.65D);
        int baseY = location.getBlockY();
        int minZ = floor(location.getZ() - 0.65D), maxZ = floor(location.getZ() + 0.65D);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minYOffset; y <= maxYOffset; y++) {
                    Material material = ChunkCache.get().getBlock(world, x, baseY + y, z);
                    if (material == null) continue;
                    String name = material.name();
                    if (bubblesOnly ? name.equals("BUBBLE_COLUMN") : isWater(world, x, baseY + y, z, name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
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
        return mountSpeed(horse);
    }

    static double horseHorizontalSpeedPerTick(Entity horse) {
        // Horse movement-speed attributes are not block-per-tick values. Vanilla
        // horse travel is just under 42.16 blocks/second per attribute unit.
        return horseSpeed(horse) * (42.16D / 20.0D);
    }

    static double horseJumpVelocity(Entity horse) {
        double strength = ReflectionUtils.getEntityAttributeValue(horse, "JUMP_STRENGTH", Double.NaN);
        if (!Double.isFinite(strength) || strength <= 0.0D) {
            strength = legacyJumpStrength(horse);
        }
        return strength + (Math.max(effectLevel(horse, "JUMP"), effectLevel(horse, "JUMP_BOOST")) * 0.1D);
    }

    static double mountJumpVelocity(Entity mount) {
        if (isHorse(mount)) return horseJumpVelocity(mount);

        double strength = ReflectionUtils.getEntityAttributeValue(mount, "JUMP_STRENGTH", Double.NaN);
        if (Double.isFinite(strength) && strength > 0.0D) {
            return strength + (Math.max(effectLevel(mount, "JUMP"), effectLevel(mount, "JUMP_BOOST")) * 0.1D);
        }
        return mount != null && mount.getType().name().toUpperCase(java.util.Locale.ROOT).contains("CAMEL")
                ? 0.9D : 0.7D;
    }

    static int effectLevel(Entity entity, String effectName) {
        if (!(entity instanceof LivingEntity)) return 0;
        LivingEntity living = (LivingEntity) entity;
        try {
            for (PotionEffect effect : living.getActivePotionEffects()) {
                if (effect != null && effect.getType().getName().equalsIgnoreCase(effectName)) {
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
