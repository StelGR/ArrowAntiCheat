package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.CombatData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.customutils.EvictingList;
import me.arrow.utils.customutils.OtherUtility;


@Experimental
public class AimF extends Check {

    EvictingList<Double> yawSamples = new EvictingList<>(50), pitchSamples = new EvictingList<>(50);

    public AimF(Profile profile) {
        super(profile, CheckType.AIM, "F", "Smooth aim (3)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            RotationData rotData = profile.getRotationData();
            CombatData attackData = profile.getCombatData();

            if (rotData.getCinematicProcessor().getCinematicTicks() >= 2 || attackData.getAttackedTicks() > 20)
                return;
            double yawDelta = rotData.getDeltaYaw();
            double pitchDelta = rotData.getDeltaPitch();

            yawSamples.add(yawDelta);
            pitchSamples.add(pitchDelta);

            if (pitchSamples.isFull() && yawSamples.isFull()) {

                int distinctYaw = (int) yawSamples.stream().distinct().count();
                int distinctPitch = (int) pitchSamples.stream().distinct().count();

                if (distinctYaw > 40 && distinctPitch <= distinctYaw / 2 && distinctPitch > 10) {
                    if (increaseBuffer() > 3) {
                        fail("Smooth Aim", "yawCount " + distinctYaw + MsgType.MAIN_THEME_COLOR.getMessage()
                                + "\npitchCount " + MsgType.MAIN_THEME_COLOR.getMessage() + distinctPitch);
                    }
                } else {
                    decreaseBufferBy(0.75);
                }
                pitchSamples.clear();
                yawSamples.clear();
            }
        }
    }
}
