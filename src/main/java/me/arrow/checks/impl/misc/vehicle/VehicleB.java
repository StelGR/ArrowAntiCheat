package me.arrow.checks.impl.misc.vehicle;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VehicleData;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

@Experimental
public class VehicleB extends Check {
    private static final float[][] INPUTS = {{0, 0}, {0, 1}, {1, 1}, {-1, 1}, {1, 0}, {-1, 0}, {0, -1}, {1, -1}, {-1, -1}};
    private double buffer;
    private double lastSurfaceFriction = 0.6D;

    public VehicleB(Profile profile) {
        super(profile, CheckType.VEHICLE, "B", "Predicts boat and horse acceleration");
    }

    @Override public void handle(PacketSendEvent event) { }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;
        Entity vehicle = profile.getPlayer() == null ? null : profile.getPlayer().getVehicle();
        VehicleData vehicleData = profile.getVehicleData();
        MovementData movementData = profile.getMovementData();
        if (vehicle == null || vehicleData == null || movementData == null || vehicleData.getVehicleTicks() < 5
                || (!VehicleSupport.isBoat(vehicle) && !VehicleSupport.isHorse(vehicle))) {
            reset();
            return;
        }

        Location location = vehicleData.getVehicleLocation();
        if (location == null) {
            reset();
            return;
        }

        boolean boat = VehicleSupport.isBoat(vehicle);
        boolean water = VehicleSupport.hasWaterContact(location);
        boolean bubble = VehicleSupport.hasBubbleColumn(location);
        double surfaceFriction = boat ? VehicleSupport.surfaceFriction(location) : 0.6D;
        double carryFriction = boat ? lastSurfaceFriction : 0.6D;
        double effectiveFriction = Math.max(surfaceFriction, carryFriction);
        if (movementData.getSinceTeleportTicks() <= 5 || profile.getVelocityData().isTakingVelocity()
                || movementData.isRiptiding() || bubble || (!boat && (water || movementData.isNearStepMaterial()))) {
            reset();
            return;
        }

        double force = boat ? (water ? 0.08D : 0.04D) : VehicleSupport.horseSpeed(vehicle);
        double drag = boat ? (water ? 0.90D : carryFriction) : 0.91D;
        double lowest = Double.MAX_VALUE;
        double yaw = Math.toRadians(location.getYaw());
        for (float[] input : INPUTS) {
            double x = vehicleData.getLastDeltaX() * drag;
            double z = vehicleData.getLastDeltaZ() * drag;
            double length = Math.hypot(input[0], input[1]);
            if (length > 0.0D) {
                double strafe = input[0] / length * force;
                double forward = input[1] / length * force;
                x += strafe * Math.cos(yaw) - forward * Math.sin(yaw);
                z += forward * Math.cos(yaw) + strafe * Math.sin(yaw);
            }
            lowest = Math.min(lowest, Math.hypot(vehicleData.getDeltaX() - x, vehicleData.getDeltaZ() - z));
        }

        double tolerance = boat ? (water ? 0.075D : 0.055D + Math.max(0.0D, effectiveFriction - 0.6D) * 0.20D) : Math.max(0.09D, force * 0.35D);

//        boolean sinceIce = movementData.getSinceMovingOnIceTicks() < 60;
//
//        if (exempt("sinceIce", sinceIce)) {
//            tolerance += 0.56D;
//        }

        boolean invalid = vehicleData.getDeltaXZ() > 0.08D && lowest > tolerance;
        verbose(getClass().getSimpleName(), buffer, 0, "lowest " + lowest + "\ntolerance " + tolerance
                + "\nforce " + force + "\ndrag " + drag + "\nsurfaceFriction " + surfaceFriction + "\ncarryFriction " + carryFriction + "\ndeltaXZ " + vehicleData.getDeltaXZ()
                + "\nwater " + water + "\nvehicle " + vehicle.getType());

        if (invalid) {
            buffer += Math.min(2.0D, lowest * 12.0D);
            if (buffer > 6.0D) {
                fail("Invalid vehicle acceleration", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType()
                        + "\nlowest " + MsgType.MAIN_THEME_COLOR.getMessage() + lowest
                        + "\niceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSinceMovingOnIceTicks()
                        + "\ntolerance " + MsgType.MAIN_THEME_COLOR.getMessage() + tolerance
                        + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicleData.getDeltaXZ());
                buffer = 4.0D;
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.20D);
        }
        lastSurfaceFriction = surfaceFriction;
    }

    private void reset() {
        buffer = 0.0D;
        lastSurfaceFriction = 0.6D;
    }
}
