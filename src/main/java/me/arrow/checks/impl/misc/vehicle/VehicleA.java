package me.arrow.checks.impl.misc.vehicle;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VehicleData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;

@Experimental
public class VehicleA extends Check {

    Location lastVehicleLocation;
    double lastDeltaX;
    double lastDeltaY;
    double lastDeltaZ;
    double lastDeltaXZ;
    double lastSurfaceFriction = 0.6D;
    double violations;
    private final VehicleSupport.IceState iceState = new VehicleSupport.IceState();

    public VehicleA(Profile profile) {
        super(profile, CheckType.VEHICLE, "A", "Predicts vehicle movement and validates impossible motion");
    }

    @Override
    public void handle(PacketSendEvent event) {
        // not needed
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) {
            return;
        }

        if (profile.getPlayer() == null || !profile.getPlayer().isInsideVehicle()) {
            syncState();
            return;
        }

        final Entity vehicle = profile.getPlayer().getVehicle();
        if (vehicle == null) {
            syncState();
            return;
        }

        final MovementData movementData = profile.getMovementData();
        final VehicleData vehicleData = profile.getVehicleData();

        if (movementData == null || vehicleData == null) {
            syncState();
            return;
        }

        if (isLegacyServer()) {
            syncState();
            return;
        }

        /*
         * Do not judge immediately after mount or while the vehicle state is still settling.
         * SinceVehicleTicks is not used here because it resets while mounted.
         */
        if (profile.getTick() <= 10 || vehicleData.getVehicleTicks() < 4) {
            syncState();
            return;
        }

        Location current = vehicle.getLocation();
        if (current.getWorld() == null) {
            syncState();
            return;
        }

        if (lastVehicleLocation == null || lastVehicleLocation.getWorld() == null) {
            lastVehicleLocation = current.clone();
            lastDeltaX = 0.0D;
            lastDeltaY = 0.0D;
            lastDeltaZ = 0.0D;
            lastDeltaXZ = 0.0D;
            lastSurfaceFriction = isBoat(vehicle) ? VehicleSupport.surfaceFriction(current) : 0.6D;
            iceState.update(isBoat(vehicle) && VehicleSupport.isIceSurface(lastSurfaceFriction));
            return;
        }

        if (!lastVehicleLocation.getWorld().equals(current.getWorld())) {
            lastVehicleLocation = current.clone();
            lastDeltaX = 0.0D;
            lastDeltaY = 0.0D;
            lastDeltaZ = 0.0D;
            lastDeltaXZ = 0.0D;
            lastSurfaceFriction = isBoat(vehicle) ? VehicleSupport.surfaceFriction(current) : 0.6D;
            iceState.update(isBoat(vehicle) && VehicleSupport.isIceSurface(lastSurfaceFriction));
            return;
        }

        final double deltaX = current.getX() - lastVehicleLocation.getX();
        final double deltaY = current.getY() - lastVehicleLocation.getY();
        final double deltaZ = current.getZ() - lastVehicleLocation.getZ();
        final double deltaXZ = Math.hypot(deltaX, deltaZ);

        final boolean boat = isBoat(vehicle);
        final boolean livingMount = !boat && vehicle instanceof LivingEntity;
        final boolean minecart = isMinecart(vehicle);

        final boolean vehicleInWater = isVehicleInWater(current);
        final boolean vehicleNearWater = vehicleInWater || isVehicleNearWater(current);
        final boolean vehicleOnWaterSurface = isVehicleOnWaterSurface(current);
        final double surfaceFriction = boat ? VehicleSupport.surfaceFriction(current) : 0.6D;
        final double carryFriction = boat ? lastSurfaceFriction : 0.6D;
        final double effectiveSurfaceFriction = Math.max(surfaceFriction, carryFriction);
        final boolean vehicleOnIce = boat && VehicleSupport.isIceSurface(surfaceFriction);
        iceState.update(vehicleOnIce);

        final boolean onIce = boat ? vehicleOnIce : movementData.isOnIce() || movementData.getSinceMovingOnIceTicks() < 6;
        final boolean onSlime = movementData.isOnSlime() || movementData.isOnExtendedHitboxSlime() || movementData.getSinceMovingOnSlimeTicks() < 6;

        final boolean teleportRecent = movementData.getSinceTeleportTicks() <= 5;
        final boolean riptide = movementData.isRiptiding() || movementData.getSinceRiptidingTicks() <= 5;
        final boolean velocity = profile.getVelocityData() != null && profile.getVelocityData().isTakingVelocity();
        final boolean slimeBounce = profile.isBouncingOnSlime();
        final boolean nearClimbable = movementData.isNearClimbable();
        final boolean nearStepMaterial = movementData.isNearStepMaterial();
        if (exempt("nearStepMaterial", nearStepMaterial)) {
            lastSurfaceFriction = surfaceFriction;
            syncState(current, deltaX, deltaY, deltaZ, deltaXZ);
            return;
        }

        final boolean skip = teleportRecent
                || riptide
                || velocity
                || slimeBounce
                || nearClimbable
                || (boat && (movementData.getSinceNearWaterTicks() <= 1 || movementData.isInsideWater() || movementData.isOnTopOfWater()));

        if (skip) {
            verbose(getClass().getSimpleName(), violations, 0,
                    debug(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface,
                            onIce, onSlime, deltaX, deltaY, deltaZ, deltaXZ,
                            0.0D, 0.0D, 0.0D, 0.0D, surfaceFriction, carryFriction, "skip"));
            lastSurfaceFriction = surfaceFriction;
            syncState(current, deltaX, deltaY, deltaZ, deltaXZ);
            return;
        }

        final boolean jumpWindow = vehicleData.isLastVehicleOnGround() && !vehicleData.isVehicleOnGround();
        final boolean horse = livingMount && VehicleSupport.isHorse(vehicle);
        // Bukkit's ground state often remains one movement packet behind a horse jump.
        // Treat a positive vertical sequence as a jump, but validate its real jump-strength below.
        final boolean horseJumpPhase = horse && (jumpWindow || deltaY > 0.08D || lastDeltaY > 0.08D);

        double horizontalCap = getHorizontalCap(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface, onIce, onSlime);
        double accelCap = getAccelerationCap(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface, onIce, onSlime);
        if (boat && !vehicleNearWater) {
            horizontalCap = Math.max(horizontalCap, 0.14D + Math.max(0.0D, effectiveSurfaceFriction - 0.6D) * 1.4D);
            accelCap = Math.max(accelCap, 0.06D + Math.max(0.0D, effectiveSurfaceFriction - 0.6D) * 0.45D);
        }
        if (horseJumpPhase) {
            double horseSpeed = VehicleSupport.horseSpeed(vehicle);
            horizontalCap = Math.max(horizontalCap, horseSpeed + 0.25D);
            accelCap = Math.max(accelCap, horseSpeed + 0.18D);
        }
        final double verticalCap = getVerticalCap(vehicle, boat, livingMount, minecart, horseJumpPhase || jumpWindow, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface);

        final double predictedXZ = predictHorizontal(lastDeltaXZ, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface, onIce, onSlime, carryFriction);
        final double tolerance = getTolerance(boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface);

        boolean invalid = false;
        String reason = null;

        if (deltaXZ > Math.max(horizontalCap, predictedXZ) + tolerance) {
            invalid = true;
            reason = "horizontal-too-fast";
        } else if (deltaXZ - lastDeltaXZ > accelCap) {
            invalid = true;
            reason = "horizontal-acceleration";
        }

        if (!invalid && deltaY > verticalCap) {
            invalid = true;
            reason = "vertical-rise";
        }

        if (!invalid && boat && !vehicleNearWater && effectiveSurfaceFriction <= 0.65D) {
            double dryLandLimit = Math.max(0.16D, predictedXZ + tolerance);
            if (deltaY > 0.02D || deltaXZ > dryLandLimit) {
                invalid = true;
                reason = "boat-dry-land-motion";
            }
        }

        if (!invalid && boat && !vehicleNearWater && effectiveSurfaceFriction <= 0.65D
                && deltaXZ > 0.06D && deltaXZ - lastDeltaXZ > Math.max(0.04D, accelCap)) {
            invalid = true;
            reason = "boat-dry-land-accel";
        }

        if (!invalid && livingMount) {
            if (horse) {
                final double maxJumpVelocity = VehicleSupport.horseJumpVelocity(vehicle) + 0.08D;
                final double expectedVertical = (lastDeltaY - 0.08D) * 0.98D;
                if (deltaY > maxJumpVelocity) {
                    invalid = true;
                    reason = "horse-impossible-jump";
                } else if (lastDeltaY > 0.08D && deltaY > expectedVertical + 0.08D) {
                    invalid = true;
                    reason = "horse-invalid-jump-physics";
                }
            } else if (!jumpWindow && deltaY > 0.08D) {
                invalid = true;
                reason = "mount-unexpected-rise";
            } else if (jumpWindow && deltaY > 0.58D) {
                invalid = true;
                reason = "mount-impossible-jump";
            }
        }

        verbose(getClass().getSimpleName(), violations, 0,
                debug(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface,
                        onIce, onSlime, deltaX, deltaY, deltaZ, deltaXZ,
                        horizontalCap, accelCap, verticalCap, predictedXZ, surfaceFriction, carryFriction, reason));

        if (invalid) {
            if (++violations > 2.0D) {
                fail("Invalid vehicle movement",
                        debug(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface,
                                onIce, onSlime, deltaX, deltaY, deltaZ, deltaXZ,
                                horizontalCap, accelCap, verticalCap, predictedXZ, surfaceFriction, carryFriction, reason));
                violations = 0.0D;
            }
        } else {
            violations = Math.max(0.0D, violations - 0.20D);
        }

        lastVehicleLocation = current.clone();
        lastDeltaX = deltaX;
        lastDeltaY = deltaY;
        lastDeltaZ = deltaZ;
        lastDeltaXZ = deltaXZ;
        lastSurfaceFriction = surfaceFriction;
    }

    private boolean isLegacyServer() {
        return PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8);
    }

    private boolean isBoat(Entity entity) {
        if (entity == null) {
            return false;
        }

        final String type = entity.getType().name().toUpperCase(Locale.ROOT);
        return entity instanceof Boat || type.contains("BOAT") || type.contains("RAFT");
    }

    private boolean isMinecart(Entity entity) {
        if (entity == null) {
            return false;
        }

        return entity.getType().name().toUpperCase(Locale.ROOT).contains("MINECART");
    }

    private boolean isVehicleInWater(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        World world = location.getWorld();
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();

        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (isWaterLike(world, baseX + x, baseY + y, baseZ + z)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isVehicleNearWater(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        World world = location.getWorld();
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();

        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (isWaterLike(world, baseX + x, baseY + y, baseZ + z)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isVehicleOnWaterSurface(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        World world = location.getWorld();
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();

        return isWaterLike(world, baseX, baseY - 1, baseZ)
                || isWaterLike(world, baseX + 1, baseY - 1, baseZ)
                || isWaterLike(world, baseX - 1, baseY - 1, baseZ)
                || isWaterLike(world, baseX, baseY - 1, baseZ + 1)
                || isWaterLike(world, baseX, baseY - 1, baseZ - 1);
    }

    private boolean isWaterLike(World world, int x, int y, int z) {
        if (world == null) return false;

        Material material = ChunkCache.get().getBlock(world, x, y, z);
        if (material != null && material != Material.AIR) {
            final String name = material.name();
            if (name.contains("WATER")
                    || name.equals("BUBBLE_COLUMN")
                    || name.equals("KELP")
                    || name.equals("KELP_PLANT")
                    || name.equals("SEAGRASS")
                    || name.equals("TALL_SEAGRASS")
                    || name.equals("WATER_CAULDRON")
                    || name.equals("LEGACY_WATER")
                    || name.equals("LEGACY_STATIONARY_WATER")) {
                return true;
            }
        }

        return CollisionUtils.isWaterLogged(world, x, y, z);
    }

    private double predictHorizontal(double lastDeltaXZ,
                                     boolean boat,
                                     boolean livingMount,
                                     boolean minecart,
                                     boolean vehicleInWater,
                                     boolean vehicleNearWater,
                                     boolean vehicleOnWaterSurface,
                                     boolean onIce,
                                     boolean onSlime,
                                     double surfaceFriction) {
        if (boat) {
            double drag = vehicleInWater || vehicleOnWaterSurface ? 0.96D
                    : surfaceFriction;
            double impulse = vehicleInWater || vehicleOnWaterSurface ? 0.08D
                    : 0.04D;

            if (onSlime) {
                impulse += 0.05D;
            }

            return (lastDeltaXZ * drag) + impulse;
        }

        if (minecart) {
            return (lastDeltaXZ * 0.92D) + 0.08D;
        }

        if (livingMount) {
            return (lastDeltaXZ * 0.90D) + 0.07D;
        }

        return (lastDeltaXZ * 0.90D) + 0.06D;
    }

    private double getHorizontalCap(Entity vehicle,
                                    boolean boat,
                                    boolean livingMount,
                                    boolean minecart,
                                    boolean vehicleInWater,
                                    boolean vehicleNearWater,
                                    boolean vehicleOnWaterSurface,
                                    boolean onIce,
                                    boolean onSlime) {
        if (boat) {
            double cap = vehicleInWater || vehicleOnWaterSurface ? 0.42D : 0.14D;

            if (vehicleNearWater && !vehicleInWater && !vehicleOnWaterSurface) {
                cap += 0.03D;
            }

            if (onIce) {
                cap += 0.12D;
            }

            if (onSlime) {
                cap += 0.05D;
            }

            return cap;
        }

        if (minecart) {
            return 0.82D;
        }

        if (livingMount) {
            final String type = vehicle.getType().name().toUpperCase(Locale.ROOT);

            double cap;
            if (type.contains("HORSE")) {
                cap = VehicleSupport.horseSpeed(vehicle) + 0.15D;
            } else if (type.contains("DONKEY") || type.contains("MULE") || type.contains("LLAMA")) {
                cap = 0.30D;
            } else if (type.contains("PIG") || type.contains("STRIDER") || type.contains("CAMEL")) {
                cap = 0.28D;
            } else {
                cap = 0.26D;
            }

            return cap;
        }

        return 0.30D;
    }

    private double getAccelerationCap(Entity vehicle,
                                      boolean boat,
                                      boolean livingMount,
                                      boolean minecart,
                                      boolean vehicleInWater,
                                      boolean vehicleNearWater,
                                      boolean vehicleOnWaterSurface,
                                      boolean onIce,
                                      boolean onSlime) {
        if (boat) {
            double cap = vehicleInWater || vehicleOnWaterSurface ? 0.12D : 0.06D;

            if (vehicleNearWater && !vehicleInWater && !vehicleOnWaterSurface) {
                cap += 0.02D;
            }

            if (onIce) {
                cap += 0.05D;
            }

            if (onSlime) {
                cap += 0.03D;
            }

            return cap;
        }

        if (minecart) {
            return 0.20D;
        }

        if (livingMount) {
            return VehicleSupport.isHorse(vehicle)
                    ? Math.max(0.14D, VehicleSupport.horseSpeed(vehicle) * 0.60D)
                    : 0.14D;
        }

        return 0.12D;
    }

    private double getVerticalCap(Entity vehicle,
                                  boolean boat,
                                  boolean livingMount,
                                  boolean minecart,
                                  boolean jumpWindow,
                                  boolean vehicleInWater,
                                  boolean vehicleNearWater,
                                  boolean vehicleOnWaterSurface) {
        if (boat) {
            if (vehicleInWater || vehicleOnWaterSurface) {
                return 0.10D;
            }

            if (vehicleNearWater) {
                return 0.05D;
            }

            return 0.02D;
        }

        if (minecart) {
            return 0.06D;
        }

        if (livingMount) {
            if (VehicleSupport.isHorse(vehicle)) {
                return jumpWindow ? VehicleSupport.horseJumpVelocity(vehicle) + 0.08D : 0.08D;
            }
            return jumpWindow ? 0.58D : 0.08D;
        }

        return 0.08D;
    }

    private double getTolerance(boolean boat,
                                boolean livingMount,
                                boolean minecart,
                                boolean vehicleInWater,
                                boolean vehicleNearWater,
                                boolean vehicleOnWaterSurface) {
        if (boat) {
            return vehicleInWater || vehicleOnWaterSurface ? 0.04D : 0.02D;
        }

        if (minecart) {
            return 0.04D;
        }

        if (livingMount) {
            return 0.03D;
        }

        return 0.03D;
    }

    private String debug(Entity vehicle,
                         boolean boat,
                         boolean livingMount,
                         boolean minecart,
                         boolean vehicleInWater,
                         boolean vehicleNearWater,
                         boolean vehicleOnWaterSurface,
                         boolean onIce,
                         boolean onSlime,
                         double deltaX,
                         double deltaY,
                         double deltaZ,
                         double deltaXZ,
                         double horizontalCap,
                         double accelCap,
                         double verticalCap,
                         double predictedXZ,
                         double surfaceFriction,
                         double carryFriction,
                         String reason) {
        return "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType().name()
                + "\nreason " + MsgType.MAIN_THEME_COLOR.getMessage() + reason
                + "\nboat " + MsgType.MAIN_THEME_COLOR.getMessage() + boat
                + "\nlivingMount " + MsgType.MAIN_THEME_COLOR.getMessage() + livingMount
                + "\nminecart " + MsgType.MAIN_THEME_COLOR.getMessage() + minecart
                + "\nvehicleInWater " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicleInWater
                + "\nvehicleNearWater " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicleNearWater
                + "\nvehicleOnWaterSurface " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicleOnWaterSurface
                + "\nonIce " + MsgType.MAIN_THEME_COLOR.getMessage() + onIce
                + "\niceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + iceState.getIceTicks()
                + "\nsinceIceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + iceState.getSinceIceTicks()
                + "\nonSlime " + MsgType.MAIN_THEME_COLOR.getMessage() + onSlime
                + "\nsurfaceFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + surfaceFriction
                + "\ncarryFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + carryFriction
                + "\ndeltaX " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaX
                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                + "\ndeltaZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaZ
                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                + "\npredictedXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedXZ
                + "\nhorizontalCap " + MsgType.MAIN_THEME_COLOR.getMessage() + horizontalCap
                + "\naccelCap " + MsgType.MAIN_THEME_COLOR.getMessage() + accelCap
                + "\nverticalCap " + MsgType.MAIN_THEME_COLOR.getMessage() + verticalCap
                + "\nvehicleTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVehicleData().getVehicleTicks()
                + "\nsinceNearVehicleTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVehicleData().getSinceNearVehicleTicks();
    }

    private void syncState() {
        final Entity vehicle = profile.getPlayer() != null ? profile.getPlayer().getVehicle() : null;
        if (vehicle == null) {
            lastVehicleLocation = null;
            lastDeltaX = 0.0D;
            lastDeltaY = 0.0D;
            lastDeltaZ = 0.0D;
            lastDeltaXZ = 0.0D;
            lastSurfaceFriction = 0.6D;
            iceState.reset();
            return;
        }

        lastSurfaceFriction = isBoat(vehicle) ? VehicleSupport.surfaceFriction(vehicle.getLocation()) : 0.6D;
        iceState.update(isBoat(vehicle) && VehicleSupport.isIceSurface(lastSurfaceFriction));
        syncState(vehicle.getLocation(), 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private void syncState(Location current, double deltaX, double deltaY, double deltaZ, double deltaXZ) {
        if (current == null) {
            return;
        }

        lastVehicleLocation = current.clone();
        lastDeltaX = deltaX;
        lastDeltaY = deltaY;
        lastDeltaZ = deltaZ;
        lastDeltaXZ = deltaXZ;
    }
}
