package me.arrow.checks.impl.combat.aimassist.aimassistUtil;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import me.arrow.playerdata.data.impl.RotationData;

/** A signed packet-time rotation delta for general aim analysis. */
public class RotationFrame {

    public final double yaw;
    public final double pitch;

    private RotationFrame(double yaw, double pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static RotationFrame capture(RotationData rotation) {
        return new RotationFrame(
                wrap(rotation.getYaw() - rotation.getLastYaw()),
                rotation.getPitch() - rotation.getLastPitch()
        );
    }

    public static boolean isRotationPacket(PacketTypeCommon type) {
        return type.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || type.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    public double length() {
        return Math.abs(yaw) + Math.abs(pitch);
    }

    public static double wrap(double angle) {
        angle %= 360.0D;
        if (angle >= 180.0D) {
            angle -= 360.0D;
        } else if (angle < -180.0D) {
            angle += 360.0D;
        }
        return angle;
    }
}
