package me.arrow.checks.impl.misc.vehicle;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VehicleData;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;

// terrible, not used, fix or ignore

@Experimental
public class VehicleA extends Check {

    double lastX;
    double lastY;
    double lastZ;
    boolean lastGround;
    boolean lastGravity;
    double violationsZero;
    double lastDeltaXZ;

    double deltaX;
    double deltaY;
    double deltaZ;
    double deltaXZ;

    Entity vehicle;
    Location vehicleLocation;

    public VehicleA(Profile profile) {
        super(profile, CheckType.VEHICLE, "A", "Checks if the vehicle of player is moving properly");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            if (profile.getPlayer().isInsideVehicle() && profile.getPlayer().getVehicle() != null && profile.getTick() > 10) {
                MovementData movementData = profile.getMovementData();
                VehicleData vehicleData = profile.getVehicleData();

                vehicle = profile.getPlayer().getVehicle();
                vehicleLocation = vehicle.getLocation();

                lastDeltaXZ = deltaXZ;
                deltaX = Math.abs(vehicleLocation.getZ() - this.lastX);
                deltaY = vehicleLocation.getY() - this.lastY;
                deltaZ = Math.abs(vehicleLocation.getZ() - this.lastZ);
                deltaXZ = Math.hypot(deltaX, deltaZ);
                boolean gravity = vehicle.hasGravity();
                boolean ground = vehicle.isOnGround();
                if (gravity && this.lastGravity) {
                    if (deltaY > 1.5
                            && !movementData.isNearBubble()) {
                        fail("Invalid vehicle movement", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType().getName()
                                + "\nhasGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + gravity
                                + "\nonGround " + MsgType.MAIN_THEME_COLOR.getMessage() + ground);
                        this.setLast(ground, vehicleLocation);
                        return;
                    }

                    if (((deltaY > 0.01 && !movementData.isNearBubble()) || (movementData.isNearBubble() && deltaY > 0.5))
                            && vehicle instanceof Boat
                            && !ground && !this.lastGround
                            && !profile.isBouncingOnSlime()) {
                        fail("Invalid boat movement (1)", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType().getName()
                                + "\nhasGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + gravity
                                + "\nonGround " + MsgType.MAIN_THEME_COLOR.getMessage() + ground
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
                        this.setLast(ground, vehicleLocation);
                        return;
                    }

                    if (deltaY > 0.5 && vehicle instanceof Boat) {
                        fail("Invalid boat movement (2)", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType().getName()
                                + "\nhasGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + gravity
                                + "\nonGround " + MsgType.MAIN_THEME_COLOR.getMessage() + ground
                                + "\nvDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\npDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaY());
                        this.setLast(ground, vehicleLocation);
                        return;
                    }

                    if ((deltaXZ  - lastDeltaXZ) > 0.6
                            && !movementData.isNearBubble()
                            && !profile.isBouncingOnSlime()
                            && vehicle instanceof Boat)
                    {
                        fail("Impossible boat speed", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType().getName()
                                + "\nhasGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + gravity
                                + "\nonGround " + MsgType.MAIN_THEME_COLOR.getMessage() + ground
                                + "\ndeltaX " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaX
                                + "\ndeltaZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaZ
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\nspeed " + MsgType.MAIN_THEME_COLOR.getMessage() + (deltaXZ  - lastDeltaXZ)
                                + "\nexpectedSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + 0.6);
                        this.setLast(ground, vehicleLocation);
                    }

                    if (deltaY == 0.0 && (deltaX > 0.0 || deltaZ > 0.0)
                            && vehicle instanceof Boat
                            && !ground && !this.lastGround
                            && !movementData.isNearBubble()
                            && !profile.isBouncingOnSlime()
                            && !movementData.isNearWater()) {
                        if (++this.violationsZero > 2.0) {
                            fail("Not falling (boat)", "vehicle " + MsgType.MAIN_THEME_COLOR.getMessage() + vehicle.getType().getName()
                                    + "\nhasGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + gravity
                                    + "\nonGround " + MsgType.MAIN_THEME_COLOR.getMessage() + ground
                                    + "\ndeltaX " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaX
                                    + "\ndeltaZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaZ
                                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
                            this.setLast(ground, vehicleLocation);
                            return;
                        }
                    } else {
                        this.violationsZero = Math.max(this.violationsZero - 0.1, 0.0);
                    }

                    this.setLast(ground, vehicleLocation);
                }

                this.lastGravity = gravity;
                this.setLast(ground, vehicleLocation);

            }
        }
    }



    private void setLast(boolean ground, Location vehicleLocation) {
        this.lastX = vehicleLocation.getX();
        this.lastY = vehicleLocation.getY();
        this.lastZ = vehicleLocation.getX();
        this.lastGround = ground;
    }
}
