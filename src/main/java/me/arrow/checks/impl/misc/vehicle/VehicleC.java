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
public class VehicleC extends Check {
    private int airTicks;
    private double buffer;

    public VehicleC(Profile profile) {
        super(profile, CheckType.VEHICLE, "C", "Checks boats for invalid airtime");
    }

    @Override public void handle(PacketSendEvent event) { }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;
        Entity boat = profile.getPlayer() == null ? null : profile.getPlayer().getVehicle();
        VehicleData data = profile.getVehicleData();
        MovementData movement = profile.getMovementData();
        if (data == null || movement == null || !VehicleSupport.isBoat(boat) || data.getVehicleTicks() < 5) {
            reset();
            return;
        }

        Location location = data.getVehicleLocation();
        if (location == null) {
            reset();
            return;
        }
        boolean water = VehicleSupport.hasWaterContact(location);
        boolean bubble = VehicleSupport.hasBubbleColumn(location);
        if (movement.getSinceTeleportTicks() <= 5 || profile.getVelocityData().isTakingVelocity()
                || movement.isRiptiding() || bubble || !data.isVehicleHasGravity()) {
            reset();
            return;
        }

        if (water) {
            airTicks = 0;
            update(data.getDeltaXZ() > 1.0D, "water-speed", data);
            return;
        }
        if (data.isVehicleOnGround() || movement.isNearStepMaterial()) {
            reset();
            return;
        }

        airTicks++;
        double expectedY = (data.getLastDeltaY() - 0.08D) * 0.98D;
        boolean invalid = airTicks > 5 && data.getDeltaY() > expectedY + 0.055D;
        update(invalid, "airtime", data);
    }

    private void update(boolean invalid, String reason, VehicleData data) {
        verbose(getClass().getSimpleName(), buffer, 0, "reason " + reason + "\nairTicks " + airTicks
                + "\ndeltaY " + data.getDeltaY() + "\nexpectedY " + ((data.getLastDeltaY() - 0.08D) * 0.98D)
                + "\ndeltaXZ " + data.getDeltaXZ());
        if (invalid) {
            if (++buffer > 3.0D) {
                fail("Invalid boat airtime", "reason " + MsgType.MAIN_THEME_COLOR.getMessage() + reason
                        + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                        + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getDeltaY()
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
    }
}
