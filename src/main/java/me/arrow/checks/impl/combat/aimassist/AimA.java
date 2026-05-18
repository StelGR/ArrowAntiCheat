package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;

public class AimA extends Check {

    public AimA(Profile profile) {
        super(profile, CheckType.AIM, "A", "Checks if the player is rounding rotations");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    private double buffer;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            if (profile.getCombatData().getAttackedTicks() < 40) {
                RotationData rotationData = profile.getRotationData();

                double pitch = Math.abs(rotationData.getPitch() - rotationData.getLastPitch());

                if (pitch % 0.5 == 0.0 && pitch % 1.5f != 0.0) {
                    int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

                    if (buffer++ > requiredBuffer) {
                        if (profile.getTrustFactor().getTrust() >= 80) {
                            profile.getTrustFactor().decreaseTrust();
                        } else {
                            fail("Rounding Rotations", "pitchDifference " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch +
                                    "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getPitch() +
                                    "\nlastPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + rotationData.getLastPitch());
                            profile.getTrustFactor().decreaseTrustBy(2);
                        }
                    }
                } else {
                    buffer -= Math.min(buffer, 0.125);
                    if (profile.getTick() % 20 == 0 && profile.getTick() != 0) {
                        profile.getTrustFactor().increaseTrustBy(0.0025);
                    }
                }
            }
        }
    }
}
