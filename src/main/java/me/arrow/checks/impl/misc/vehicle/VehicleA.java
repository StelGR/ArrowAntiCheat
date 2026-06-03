package me.arrow.checks.impl.misc.vehicle;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VehicleData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.Locale;

@Experimental
public class VehicleA extends Check {

    private Location lastVehicleLocation;
    private double lastDeltaX;
    private double lastDeltaY;
    private double lastDeltaZ;
    private double lastDeltaXZ;

    private double violations;

    public VehicleA(Profile profile) {
        super(profile, CheckType.VEHICLE, "A", "Predicts vehicle movement and validates impossible motion");
    }

    @Override
    public void handle(PacketSendEvent event) {
        // not needed
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isMovementPacket(event.getPacketType())) {
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
        if (current == null || current.getWorld() == null) {
            syncState();
            return;
        }

        if (lastVehicleLocation == null || lastVehicleLocation.getWorld() == null) {
            lastVehicleLocation = current.clone();
            lastDeltaX = 0.0D;
            lastDeltaY = 0.0D;
            lastDeltaZ = 0.0D;
            lastDeltaXZ = 0.0D;
            return;
        }

        if (!lastVehicleLocation.getWorld().equals(current.getWorld())) {
            lastVehicleLocation = current.clone();
            lastDeltaX = 0.0D;
            lastDeltaY = 0.0D;
            lastDeltaZ = 0.0D;
            lastDeltaXZ = 0.0D;
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

        final boolean onIce = movementData.isOnIce() || movementData.getSinceMovingOnIceTicks() < 6;
        final boolean onSlime = movementData.isOnSlime() || movementData.isOnExtendedHitboxSlime() || movementData.getSinceMovingOnSlimeTicks() < 6;
        final boolean nearBubble = movementData.isNearBubble();

        final boolean teleportRecent = movementData.getSinceTeleportTicks() <= 5;
        final boolean riptide = movementData.isRiptiding() || movementData.getSinceRiptidingTicks() <= 5;
        final boolean velocity = profile.getVelocityData() != null && profile.getVelocityData().isTakingVelocity();
        final boolean slimeBounce = profile.isBouncingOnSlime();
        final boolean nearClimbable = movementData.isNearClimbable();

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
                            0.0D, 0.0D, 0.0D, 0.0D, "skip"));
            syncState(current, deltaX, deltaY, deltaZ, deltaXZ);
            return;
        }

        final boolean jumpWindow = vehicleData.isLastVehicleOnGround() && !vehicleData.isVehicleOnGround();

        final double horizontalCap = getHorizontalCap(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface, onIce, onSlime);
        final double accelCap = getAccelerationCap(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface, onIce, onSlime);
        final double verticalCap = getVerticalCap(vehicle, boat, livingMount, minecart, jumpWindow, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface);

        final double predictedXZ = predictHorizontal(lastDeltaXZ, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface, onIce, onSlime);
        final double tolerance = getTolerance(boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface);

        boolean invalid = false;
        String reason = null;

        if (deltaXZ > Math.max(horizontalCap, predictedXZ) + tolerance) {
            invalid = true;
            reason = "horizontal-too-fast";
        } else if (Math.abs(deltaXZ - lastDeltaXZ) > accelCap) {
            invalid = true;
            reason = "horizontal-acceleration";
        }

        if (!invalid && deltaY > verticalCap) {
            invalid = true;
            reason = "vertical-rise";
        }

        if (!invalid && boat && !vehicleNearWater) {
            if (deltaY > 0.02D || deltaXZ > 0.16D) {
                invalid = true;
                reason = "boat-dry-land-motion";
            }
        }

        if (!invalid && boat && !vehicleNearWater && deltaXZ > 0.06D && Math.abs(deltaXZ - lastDeltaXZ) > 0.04D) {
            invalid = true;
            reason = "boat-dry-land-accel";
        }

        if (!invalid && livingMount) {
            if (!jumpWindow && deltaY > 0.08D) {
                invalid = true;
                reason = "mount-unexpected-rise";
            }
            if (jumpWindow && deltaY > 0.58D) {
                invalid = true;
                reason = "mount-impossible-jump";
            }
        }

        verbose(getClass().getSimpleName(), violations, 0,
                debug(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface,
                        onIce, onSlime, deltaX, deltaY, deltaZ, deltaXZ,
                        horizontalCap, accelCap, verticalCap, predictedXZ, reason));

        if (invalid) {
            if (++violations > 2.0D) {
                fail("Invalid vehicle movement",
                        debug(vehicle, boat, livingMount, minecart, vehicleInWater, vehicleNearWater, vehicleOnWaterSurface,
                                onIce, onSlime, deltaX, deltaY, deltaZ, deltaXZ,
                                horizontalCap, accelCap, verticalCap, predictedXZ, reason));
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
    }

    private boolean isMovementPacket(PacketTypeCommon packetType) {
        return packetType.equals(PacketType.Play.Client.PLAYER_FLYING)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
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
                    if (isWaterLike(world.getBlockAt(baseX + x, baseY + y, baseZ + z))) {
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
                    if (isWaterLike(world.getBlockAt(baseX + x, baseY + y, baseZ + z))) {
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

        return isWaterLike(world.getBlockAt(baseX, baseY - 1, baseZ))
                || isWaterLike(world.getBlockAt(baseX + 1, baseY - 1, baseZ))
                || isWaterLike(world.getBlockAt(baseX - 1, baseY - 1, baseZ))
                || isWaterLike(world.getBlockAt(baseX, baseY - 1, baseZ + 1))
                || isWaterLike(world.getBlockAt(baseX, baseY - 1, baseZ - 1));
    }

    private boolean isWaterLike(Block block) {
        if (block == null || block.getType() == null) {
            return false;
        }

        final String name = block.getType().name();

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

        return isWaterlogged(block);
    }

    private boolean isWaterlogged(Block block) {
        try {
            Object blockData = block.getClass().getMethod("getBlockData").invoke(block);
            if (blockData == null) {
                return false;
            }

            Method method = blockData.getClass().getMethod("isWaterlogged");
            Object result = method.invoke(blockData);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private double predictHorizontal(double lastDeltaXZ,
                                     boolean boat,
                                     boolean livingMount,
                                     boolean minecart,
                                     boolean vehicleInWater,
                                     boolean vehicleNearWater,
                                     boolean vehicleOnWaterSurface,
                                     boolean onIce,
                                     boolean onSlime) {
        if (boat) {
            double drag = vehicleInWater || vehicleOnWaterSurface ? 0.96D : 0.72D;
            double impulse = vehicleInWater || vehicleOnWaterSurface ? 0.08D : 0.03D;

            if (onIce) {
                impulse += 0.08D;
            }

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
                cap = 0.38D + (getHorseJumpStrength(vehicle) * 0.03D);
            } else if (type.contains("DONKEY") || type.contains("MULE") || type.contains("LLAMA")) {
                cap = 0.30D;
            } else if (type.contains("PIG") || type.contains("STRIDER") || type.contains("CAMEL")) {
                cap = 0.28D;
            } else {
                cap = 0.26D;
            }

            if (profile.getPotionData().isHasSpeed()) {
                cap += 0.03D;
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
            double cap = 0.14D;

            if (profile.getPotionData().isHasSpeed()) {
                cap += 0.02D;
            }

            return cap;
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
            double cap = jumpWindow ? 0.58D : 0.08D;

            if (profile.getPotionData().isHasLevitation()) {
                cap += 0.12D;
            }

            if (profile.getPotionData().isHasSpeed()) {
                cap += 0.02D;
            }

            return cap;
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

    private double getHorseJumpStrength(Entity vehicle) {
        try {
            Method method = vehicle.getClass().getMethod("getJumpStrength");
            Object value = method.invoke(vehicle);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Throwable ignored) {
        }

        return 0.5D;
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
                + "\nonSlime " + MsgType.MAIN_THEME_COLOR.getMessage() + onSlime
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
            return;
        }

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