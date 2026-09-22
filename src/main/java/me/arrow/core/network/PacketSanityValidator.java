package me.arrow.core.network;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;

/** Shared packet-boundary validation for every platform backend. */
public final class PacketSanityValidator {

    private static final float MAXIMUM_PITCH = 90.0F;

    private PacketSanityValidator() {
    }

    /** @return the invalid-value details, or {@code null} when the packet is safe. */
    public static String check(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        double x = 0D, y = 0D, z = 0D;
        float yaw = 0F, pitch = 0F;

        if (type.equals(PacketType.Play.Client.PLAYER_POSITION)) {
            WrapperPlayClientPlayerPosition packet = new WrapperPlayClientPlayerPosition(event);
            x = Math.abs(packet.getPosition().getX());
            y = Math.abs(packet.getPosition().getY());
            z = Math.abs(packet.getPosition().getZ());
        } else if (type.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            WrapperPlayClientPlayerPositionAndRotation packet = new WrapperPlayClientPlayerPositionAndRotation(event);
            x = Math.abs(packet.getPosition().getX());
            y = Math.abs(packet.getPosition().getY());
            z = Math.abs(packet.getPosition().getZ());
            yaw = Math.abs(packet.getYaw());
            pitch = Math.abs(packet.getPitch());
        } else if (type.equals(PacketType.Play.Client.PLAYER_ROTATION)) {
            WrapperPlayClientPlayerRotation packet = new WrapperPlayClientPlayerRotation(event);
            yaw = Math.abs(packet.getYaw());
            pitch = Math.abs(packet.getPitch());
        }

        if (x > 3.0E7D || y > 3.0E7D || z > 3.0E7D
                || yaw > 3.4028235e+35F || pitch > MAXIMUM_PITCH
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return "X: " + x + " Y: " + y + " Z: " + z
                    + " Yaw: " + yaw + " Pitch: " + pitch;
        }
        return null;
    }
}
