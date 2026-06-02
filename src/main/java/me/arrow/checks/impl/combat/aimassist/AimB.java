package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.MathUtils;

@Experimental
public class AimB extends Check {
    public AimB(Profile profile) {
        super(profile, CheckType.AIM, "B", "Smooth Aim (1)");
    }

    private double lastDeltaYaw, lastLastDeltaYaw, lastDeltaPitch, lastLastDeltaPitch;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            if (profile.getCombatData().getAttackedTicks() < 40) {
                RotationData rotationData = profile.getRotationData();

                double deltaYawClamped = MathUtils.clamp180(rotationData.getDeltaYaw());
                double deltaPitch = rotationData.getDeltaPitch();

                if (deltaPitch < 0.05f && deltaPitch > 0.009f && lastDeltaPitch < 0.05f && lastDeltaPitch > 0.009f
                        && deltaYawClamped > 6.2f && lastDeltaYaw > 0.4f) {
                    int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

                    if (increaseBuffer() > requiredBuffer) {
                        if (profile.getTrustFactor().getTrust() >= 80) {
                            profile.getTrustFactor().decreaseTrustBy(3);
                        } else {
                            fail("Smooth Aim",
                                    "deltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYawClamped
                                            + "\ndeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaPitch
                                            + "\nlastDeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaYaw
                                            + "\nlastDeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaPitch
                                            + "\nlastLastDeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + lastLastDeltaYaw
                                            + "\nlastLastDeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + lastLastDeltaPitch);
                            profile.getTrustFactor().decreaseTrustBy(2);
                        }
                    }
                } else {
                    decreaseBufferBy(0.075);
                    if (profile.getTick() % 20 == 0 && profile.getTick() != 0) {
                        profile.getTrustFactor().increaseTrustBy(0.0025);
                    }
                }

                lastLastDeltaPitch = lastDeltaPitch;
                lastLastDeltaYaw = lastDeltaPitch;
                lastDeltaPitch = deltaPitch;
                lastDeltaYaw = deltaYawClamped;
            }
        }
    }
}
