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
public class VehicleC extends Check {
    private int airTicks;
    private double buffer;
    private UUID trackedVehicleId;

    public VehicleC(Profile profile) {
        super(profile, CheckType.VEHICLE, "C", "Checks airborne movement for boats and ground-bound mounts");
    }

    @Override public void handle(PacketSendEvent event) { }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;

        Entity vehicle = profile.getPlayer() == null ? null : profile.getPlayer().getVehicle();
        VehicleData data = profile.getVehicleData();
        MovementData movement = profile.getMovementData();
        boolean boat = VehicleSupport.isBoat(vehicle);
        boolean groundBoundMount = VehicleSupport.isGroundBoundMount(vehicle);
        if (data == null || movement == null || (!boat && !groundBoundMount) || data.getVehicleTicks() < 5) {
            reset();
            return;
        }

        UUID vehicleId = vehicle.getUniqueId();
        if (trackedVehicleId != null && !trackedVehicleId.equals(vehicleId)) reset();
        trackedVehicleId = vehicleId;

        Location location = data.getVehicleLocation();
        if (location == null) {
            reset();
            return;
        }

        if (movement.getSinceTeleportTicks() <= 5
                || (profile.getVelocityData() != null && profile.getVelocityData().isTakingVelocity())
                || movement.isRiptiding()
                || (!boat && (movement.isInsideLiquid() || movement.isNearWater() || movement.isNearLava()))
                || movement.getSinceNearPistonTicks() <= 5
                || movement.getSinceNearSlimeTicks() <= 5
                || profile.isBouncingOnSlime()
                || !data.isVehicleHasGravity()) {
            reset();
            return;
        }

        if (boat) {
            handleBoat(data, movement, location);
        } else {
            handleGroundBoundMount(vehicle, data, movement);
        }
    }

    private void handleBoat(VehicleData data, MovementData movement, Location location) {
        boolean water = VehicleSupport.hasWaterContact(location);
        boolean bubble = VehicleSupport.hasBubbleColumn(location);
        if (bubble) {
            reset();
            return;
        }

        if (water) {
            airTicks = 0;
            update(data.getDeltaXZ() > 1.0D, "water-speed", data,
                    expectedVerticalDelta(data.getLastDeltaY()));
            return;
        }
        if (data.isVehicleOnGround() || movement.isNearStepMaterial()) {
            reset();
            return;
        }

        airTicks++;
        double expectedY = expectedVerticalDelta(data.getLastDeltaY());
        boolean invalid = airTicks > 5 && data.getDeltaY() > expectedY + 0.055D;
        update(invalid, "boat-airtime", data, expectedY);
    }

    private void handleGroundBoundMount(Entity mount, VehicleData data, MovementData movement) {
        if (data.isVehicleOnGround() || movement.isNearStepMaterial()) {
            reset();
            return;
        }

        airTicks++;
        double expectedY = expectedVerticalDelta(data.getLastDeltaY());
        double jumpLimit = VehicleSupport.mountJumpVelocity(mount) + 0.12D;

        boolean jumpTakeoff = data.getDeltaY() > 0.0D
                && data.getDeltaY() <= jumpLimit
                && ((data.isLastVehicleOnGround() && !data.isVehicleOnGround())
                || (data.isSteerJump() && data.getSteerAgeTicks() <= 2 && data.getLastDeltaY() <= 0.08D));

        boolean impossibleRise = data.getDeltaY() > jumpLimit;
        boolean floating = airTicks > 5 && !jumpTakeoff && data.getDeltaY() > expectedY + 0.055D;
        update(impossibleRise || floating, impossibleRise ? "mount-jump-limit" : "mount-airtime", data, expectedY);
    }

    private double expectedVerticalDelta(double previousDeltaY) {
        return (previousDeltaY - 0.08D) * 0.98D;
    }

    private void update(boolean invalid, String reason, VehicleData data, double expectedY) {
        verbose(getClass().getSimpleName(), buffer, 0, "reason " + reason + "\nairTicks " + airTicks
                + "\nvehicle " + data.getVehicleType()
                + "\nonGround " + data.isVehicleOnGround()
                + "\ndeltaY " + data.getDeltaY()
                + "\nexpectedY " + expectedY
                + "\nlastDeltaY " + data.getLastDeltaY()
                + "\ndeltaXZ " + data.getDeltaXZ());
        if (invalid) {
            if (++buffer > 3.0D) {
                fail("Invalid vehicle airtime", "reason " + MsgType.MAIN_THEME_COLOR.getMessage() + reason
                        + "\nvehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getVehicleType()
                        + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                        + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getDeltaY()
                        + "\nexpectedY " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedY
                        + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getDeltaXZ());
                buffer = 2.0D;
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.20D);
        }
    }

    private void reset() {
        airTicks = 0;
        buffer = 0.0D;
        trackedVehicleId = null;
    }
}
