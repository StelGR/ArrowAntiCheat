package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.impl.combat.aimassist.aimassistUtil.RotationFrame;
import me.arrow.checks.impl.combat.aimassist.aimassistUtil.Vector3dm;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.customutils.EvictingList;


//credits: neuralense
@Experimental
public class AimL extends Check {

    final private EvictingList<Vector3dm> rotation = new EvictingList<>(200);
    double buffer = 0;

    public AimL(Profile profile) {
        super(profile, CheckType.AIM, "L", "Disabled");
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!RotationFrame.isRotationPacket(event.getPacketType())) {
            return;
        }

        if (profile.isExempt().isTeleports()) return;
        if (profile.isExempt().isVehicle()) return;
        if (profile.getMovementData().getMovingTicks() < 5) return;

        RotationData rotationData = profile.getRotationData();

        float deltaPitch = rotationData.getDeltaPitch();
        float deltaYaw = rotationData.getDeltaYaw();
        float lastDeltaPitch = rotationData.getLastDeltaPitch();
        float lastDeltaYaw = rotationData.getLastDeltaYaw();

//        if(rotationData.getCinematicProcessor().isCinematic()) {
//            rotation.clear();
//        }

        if (deltaPitch != lastDeltaPitch && deltaYaw != lastDeltaYaw) {
            if (!rotation.isEmpty() && Math.hypot(deltaYaw, deltaPitch) > 1 && deltaYaw > 0 && deltaPitch > 0) {
                if (rotation.contains(new Vector3dm(deltaYaw, deltaPitch, 0))) {
                    if (buffer++ > 4) {
                        fail("Invalid Rotation Heuristics", "deltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYaw
                                + "\ndeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaPitch
                                + "\npreviousYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaYaw
                                + "\npreviousPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaPitch
                                + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage() + rotation.size()
                                + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + buffer);
                        buffer = 3.5;
                    }
                } else {
                    buffer = Math.max(buffer - 0.1, 0);
                }
            }
            rotation.add(new Vector3dm(deltaYaw, deltaPitch, 0));
        }
    }

    @Override
    public void handle(PacketSendEvent event) {
    }
}
