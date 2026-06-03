package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.EntityUtil;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.lang.reflect.Method;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.STEER_VEHICLE;

@Getter
public class VehicleData implements Data {

    @Setter
    private int VehicleTicks, sinceVehicleTicks, sinceNearVehicleTicks;

    @Getter
    @Setter
    private Location vehicleLocation, lastVehicleLocation;

    @Getter
    @Setter
    private double deltaX, deltaY, deltaZ, deltaXZ,
            lastDeltaX, lastDeltaY, lastDeltaZ, lastDeltaXZ;

    @Getter
    @Setter
    private boolean vehicleOnGround, lastVehicleOnGround;

    @Getter
    @Setter
    private boolean vehicleHasGravity, lastVehicleHasGravity;

    @Getter
    @Setter
    private String vehicleType, lastVehicleType;

    @Getter
    @Setter
    private int vehicleMoveTicks;

    private final Profile profile;

    public VehicleData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(STEER_VEHICLE)) {
            WrapperPlayClientSteerVehicle wrapperPlayClientSteerVehicle = new WrapperPlayClientSteerVehicle(event);

            if (wrapperPlayClientSteerVehicle.isUnmount()) {
                profile.getVehicleTicks().reset();
            }
        }

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            if (profile.getPlayer().isInsideVehicle()) {
                sinceVehicleTicks = 0;

                if (profile.getPlayer().getVehicle() != null) {
                    updateSnapshot(profile.getPlayer().getVehicle());
                    vehicleMoveTicks++;
                }

                if (VehicleTicks < 20) {
                    VehicleTicks++;
                }
            } else {
                sinceVehicleTicks++;

                if (VehicleTicks > 0) {
                    VehicleTicks--;
                }

                if (vehicleLocation != null) {
                    lastVehicleLocation = vehicleLocation.clone();
                    lastDeltaX = deltaX;
                    lastDeltaY = deltaY;
                    lastDeltaZ = deltaZ;
                    lastDeltaXZ = deltaXZ;
                    lastVehicleOnGround = vehicleOnGround;
                    lastVehicleHasGravity = vehicleHasGravity;
                    lastVehicleType = vehicleType;
                }
            }

            if (EntityUtil.isNearBoat(profile)) {
                sinceNearVehicleTicks = 0;
            } else {
                sinceNearVehicleTicks++;
            }
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
    }

    private void updateSnapshot(Entity vehicle) {
        if (vehicle == null) {
            return;
        }

        if (vehicleLocation != null) {
            lastVehicleLocation = vehicleLocation.clone();
            lastDeltaX = deltaX;
            lastDeltaY = deltaY;
            lastDeltaZ = deltaZ;
            lastDeltaXZ = deltaXZ;
            lastVehicleOnGround = vehicleOnGround;
            lastVehicleHasGravity = vehicleHasGravity;
            lastVehicleType = vehicleType;
        }

        vehicleLocation = vehicle.getLocation().clone();
        vehicleOnGround = vehicle.isOnGround();
        vehicleType = vehicle.getType().name();
        vehicleHasGravity = readHasGravity(vehicle);

        if (lastVehicleLocation != null) {
            deltaX = vehicleLocation.getX() - lastVehicleLocation.getX();
            deltaY = vehicleLocation.getY() - lastVehicleLocation.getY();
            deltaZ = vehicleLocation.getZ() - lastVehicleLocation.getZ();
            deltaXZ = Math.hypot(deltaX, deltaZ);
        } else {
            deltaX = 0.0D;
            deltaY = 0.0D;
            deltaZ = 0.0D;
            deltaXZ = 0.0D;
        }
    }

    private boolean readHasGravity(Entity entity) {
        try {
            Method method = entity.getClass().getMethod("hasGravity");
            Object value = method.invoke(entity);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }
}