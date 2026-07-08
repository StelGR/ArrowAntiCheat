package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;

public class AimG extends Check {

    public AimG(Profile profile) {
        super(profile, CheckType.AIM, "G", "Checks if the player is moving impossibly low in one axis while moving insanely fast on another.");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }


    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            if (profile.getCombatData().getAttackedTicks() < 20
                    && profile.getMovementData().isMoving()
                    && profile.getRotationData().getCinematicProcessor().isCinematic()) {
                invalidPitch();
                invalidYaw();
            }
        }
    }

    double pitchBuffer;
    public void invalidPitch() {
        RotationData rotationData = profile.getRotationData();

        float deltaYaw = rotationData.getDeltaYaw();
        float deltaPitch = rotationData.getDeltaPitch();

        boolean invalid = deltaPitch < .0001 && deltaPitch > 0 && deltaYaw > .5F;

        if (invalid) {
            if (++pitchBuffer > 6.0) {
                fail("Impossible Pitch Change", "deltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaPitch +
                        "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getPitch() +
                        "\nlastPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getLastPitch() +
                        "\ndeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYaw +
                        "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getYaw() +
                        "\nlastPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getLastYaw());
            }
        } else {
            pitchBuffer -= Math.min(pitchBuffer, 0.25);
        }
    }


    double yawBuffer;
    public void invalidYaw() {
        RotationData rotationData = profile.getRotationData();

        float deltaYaw = rotationData.getDeltaYaw();
        float deltaPitch = rotationData.getDeltaPitch();

        boolean invalid = deltaYaw < .0001 && deltaYaw > 0 && deltaPitch > .5F;

        if (invalid) {
            if (yawBuffer > 6.0) {
                fail("Impossible Yaw Change", "deltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaPitch +
                        "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getPitch() +
                        "\nlastPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getLastPitch() +
                        "\ndeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYaw +
                        "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getYaw() +
                        "\nlastPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getLastYaw());
            }
        } else {
            yawBuffer -= Math.min(yawBuffer, 0.25);
        }
    }
}

