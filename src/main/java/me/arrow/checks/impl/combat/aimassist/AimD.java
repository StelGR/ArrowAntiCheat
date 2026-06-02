package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.playerdata.processors.impl.SensitivityProcessor;
import me.arrow.utils.customutils.Math.MathUtil;

public class AimD extends Check {
    public AimD(Profile profile) {
        super(profile, CheckType.AIM, "D", "GCD Flaw");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            if (profile.getCombatData().getAttackedTicks() < 40) {
                RotationData rotationData = profile.getRotationData();
                SensitivityProcessor sensitivityProcessor = rotationData.getSensitivityProcessor();
                boolean validSensitivity = sensitivityProcessor.getSensitivityPercent() > 0 && sensitivityProcessor.getSensitivityPercent() < 200;

                if (validSensitivity) {
                    double mcpSensitivity = sensitivityProcessor.getMcpSensitivity();

                    float f = (float) mcpSensitivity * 0.6f + 0.2f;

                    float gcd = f * f * f * 1.2f;
                    float yaw = rotationData.getYaw();
                    float pitch = rotationData.getPitch();

                    float adjustedYaw = yaw - yaw % gcd;
                    float adjustedPitch = pitch - pitch % gcd;

                    float yawDifference = Math.abs(yaw - adjustedYaw);
                    float pitchDifference = Math.abs(pitch - adjustedPitch);

                    float deltaYaw = rotationData.getDeltaYaw();
                    float deltaPitch = rotationData.getDeltaPitch();

                    float combined = deltaYaw + deltaPitch;

                    if (MathUtil.isScientificNotation(yawDifference) && pitchDifference == 0.0f) {
                        int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

                        if (increaseBuffer() > requiredBuffer) {
                            if (profile.getTrustFactor().getTrust() >= 80) {
                                profile.getTrustFactor().decreaseTrustBy(5);
                            } else {
                                fail("GCD Flaw", "gcd " + MsgType.MAIN_THEME_COLOR.getMessage() + gcd
                                        + "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + yaw
                                        + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch
                                        + "\nadjustedYaw (yaw - yaw % gcd)" + MsgType.MAIN_THEME_COLOR.getMessage() + adjustedYaw
                                        + "\nadjustedPitch (pitch - pitch % gcd)" + MsgType.MAIN_THEME_COLOR.getMessage() + adjustedYaw
                                        + "\nyawDifference (Math.abs(yaw - adjustedYaw))" + MsgType.MAIN_THEME_COLOR.getMessage() + yawDifference
                                        + "\npitchDifference (Math.abs(pitch - adjustedPitch))" + MsgType.MAIN_THEME_COLOR.getMessage() + pitchDifference
                                        + "\ndeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYaw
                                        + "\ndeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaPitch
                                        + "\ncombined (dY + dP)" + MsgType.MAIN_THEME_COLOR.getMessage() + combined);
                                profile.getTrustFactor().decreaseTrustBy(2);
                            }
                        }
                    } else {
                        decreaseBufferBy(.2);
                        if (profile.getTick() % 20 == 0 && profile.getTick() != 0) {
                            profile.getTrustFactor().increaseTrustBy(0.0025);
                        }
                    }

                }
            }
        }
    }
}
