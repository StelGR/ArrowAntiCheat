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

import java.util.UUID;

@Experimental
public class VehicleB extends Check {
    private double buffer;
    private double lastSurfaceFriction = 0.6D;
    private UUID trackedVehicleId;

    public VehicleB(Profile profile) {
        super(profile, CheckType.VEHICLE, "B", "Checks acceleration against vehicle input and rail state");
    }

    @Override public void handle(PacketSendEvent event) { }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;

        Entity vehicle = profile.getPlayer() == null ? null : profile.getPlayer().getVehicle();
        VehicleData vehicleData = profile.getVehicleData();
        MovementData movementData = profile.getMovementData();
        if (vehicle == null || vehicleData == null || movementData == null || vehicleData.getVehicleTicks() < 5) {
            reset();
            trackedVehicleId = null;
            return;
        }

        UUID currentVehicleId = vehicle.getUniqueId();
        if (trackedVehicleId != null && !trackedVehicleId.equals(currentVehicleId)) reset();
        trackedVehicleId = currentVehicleId;

        boolean boat = VehicleSupport.isBoat(vehicle);
        boolean minecart = VehicleSupport.isMinecart(vehicle);
        boolean controlledMount = VehicleSupport.isControlledMount(vehicle);
        if (!boat && !minecart && !controlledMount) {
            reset();
            return;
        }
        if (!minecart && vehicleData.getSteerAgeTicks() > 3) {
            reset();
            return;
        }

        Location location = vehicleData.getVehicleLocation();
        if (location == null) {
            reset();
            return;
        }

        if (movementData.getSinceTeleportTicks() <= 5
                || (profile.getVelocityData() != null && profile.getVelocityData().isTakingVelocity())
                || movementData.isRiptiding()) {
            reset();
            return;
        }

        if (minecart) {
            checkMinecartAcceleration(vehicle, vehicleData, location);
            return;
        }

        boolean water = VehicleSupport.hasWaterContact(location);
        boolean bubble = VehicleSupport.hasBubbleColumn(location);
        double surfaceFriction = boat ? VehicleSupport.surfaceFriction(location) : 0.6D;
        double carryFriction = boat ? lastSurfaceFriction : 0.6D;
        double effectiveFriction = Math.max(surfaceFriction, carryFriction);
        if (bubble || (!boat && (water || movementData.isNearStepMaterial()))
                || (VehicleSupport.isHorse(vehicle) && vehicleData.isSteerJump())) {
            reset();
            return;
        }

        double force = boat ? (water ? 0.08D : 0.04D) : VehicleSupport.mountSpeed(vehicle);
        double drag = boat ? (water ? 0.90D : carryFriction) : 0.91D;
        double forwardInput = vehicleData.getSteerForward();
        double sidewaysInput = vehicleData.getSteerSideways();
        double inputLength = Math.hypot(forwardInput, sidewaysInput);
        if (inputLength > 1.0D) {
            forwardInput /= inputLength;
            sidewaysInput /= inputLength;
        }

        // Vehicle steering is relative to the rider's view, not the vehicle's
        // previous motion. Use the actual STEER_VEHICLE input instead of trying
        // all nine possible directions and accepting whichever fits best.
        double yaw = Math.toRadians(profile.getRotationData().getYaw());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double predictedX = vehicleData.getLastDeltaX() * drag
                + (forwardX * forwardInput + rightX * sidewaysInput) * force;
        double predictedZ = vehicleData.getLastDeltaZ() * drag
                + (forwardZ * forwardInput + rightZ * sidewaysInput) * force;
        double error = Math.hypot(vehicleData.getDeltaX() - predictedX, vehicleData.getDeltaZ() - predictedZ);

        double tolerance = boat
                ? (water ? 0.075D : 0.055D + Math.max(0.0D, effectiveFriction - 0.6D) * 0.20D)
                : Math.max(0.075D, force * 0.35D);

        boolean invalid = vehicleData.getDeltaXZ() > 0.08D && error > tolerance;
        verbose(getClass().getSimpleName(), buffer, 0, "error " + error + "\ntolerance " + tolerance
                + "\nforce " + force + "\ndrag " + drag + "\nsurfaceFriction " + surfaceFriction + "\ncarryFriction " + carryFriction + "\ndeltaXZ " + vehicleData.getDeltaXZ()
                + "\ninputForward " + forwardInput + "\ninputSideways " + sidewaysInput
                + "\nsteerAge " + vehicleData.getSteerAgeTicks() + "\nwater " + water + "\nvehicle " + vehicle.getType());

        if (invalid) {
            buffer += Math.min(2.0D, error * 12.0D);
            if (buffer > 6.0D) {
                fail("Invalid vehicle acceleration", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType()
                        + "\nerror " + MsgType.MAIN_THEME_COLOR.getMessage() + error
                        + "\niceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSinceMovingOnIceTicks()
                        + "\ntolerance " + MsgType.MAIN_THEME_COLOR.getMessage() + tolerance
                        + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicleData.getDeltaXZ()
                        + "\ninputForward " + MsgType.MAIN_THEME_COLOR.getMessage() + forwardInput
                        + "\ninputSideways " + MsgType.MAIN_THEME_COLOR.getMessage() + sidewaysInput);
                buffer = 4.0D;
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.20D);
        }
        lastSurfaceFriction = surfaceFriction;
    }

    private void checkMinecartAcceleration(Entity minecart, VehicleData data, Location location) {
        VehicleSupport.RailState rail = VehicleSupport.railState(location);
        if (!rail.known || !rail.onRail || rail.powered || rail.ascending) {
            // Powered rails and slopes contribute external acceleration. Vehicle A
            // independently enforces the cart's configured maximum speed there.
            reset();
            return;
        }

        double currentSpeed = Math.sqrt(data.getDeltaXZ() * data.getDeltaXZ() + data.getDeltaY() * data.getDeltaY());
        double previousSpeed = Math.sqrt(data.getLastDeltaXZ() * data.getLastDeltaXZ()
                + data.getLastDeltaY() * data.getLastDeltaY());
        double drag = 0.96D;
        double unexpectedAcceleration = currentSpeed - (previousSpeed * drag);
        double tolerance = 0.035D;
        boolean invalid = currentSpeed > 0.08D && unexpectedAcceleration > tolerance;

        verbose(getClass().getSimpleName(), buffer, 0, "railAcceleration " + unexpectedAcceleration
                + "\ntolerance " + tolerance + "\ncurrentSpeed " + currentSpeed
                + "\npreviousSpeed " + previousSpeed + "\nrailPowered " + rail.powered
                + "\nrailAscending " + rail.ascending + "\nvehicle " + minecart.getType());

        if (invalid) {
            buffer += Math.min(2.0D, unexpectedAcceleration * 12.0D);
            if (buffer > 6.0D) {
                fail("Invalid minecart acceleration", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + minecart.getType()
                        + "\nrailAcceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + unexpectedAcceleration
                        + "\ncurrentSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + currentSpeed
                        + "\nmaxSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + VehicleSupport.minecartMaxSpeed(minecart));
                buffer = 4.0D;
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.20D);
        }
    }

    private void reset() {
        buffer = 0.0D;
        lastSurfaceFriction = 0.6D;
    }
}
