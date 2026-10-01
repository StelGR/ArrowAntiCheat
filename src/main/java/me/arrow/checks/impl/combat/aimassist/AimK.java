package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.impl.combat.aimassist.aimassistUtil.RotationFrame;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;

import java.util.ArrayList;
import java.util.List;

//credits: neuralense
@Experimental
public class AimK extends Check {

    public AimK(Profile profile) {
        super(profile, CheckType.AIM, "K", "Detects invalid 180 yaw values");
    }

    private float suspiciousYaw;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!RotationFrame.isRotationPacket(event.getPacketType())) {
            return;
        }

        RotationData rotationData = profile.getRotationData();

        if (profile.shouldCancel() || profile.isExempt().vehicle()) {
            return;
        }

        if (yawCoarse()) {
            this.suspiciousYaw = 0.0f;
            return;
        }

        final float diff = Math.abs(rotationData.getYaw() - rotationData.getLastYaw()) % 180.0f;
        if (diff > 1.0f && Math.round(diff) == diff) {
            if (diff == this.suspiciousYaw) {
                fail("Invalid Yaw Difference","predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + suspiciousYaw
                        + " diff " + MsgType.MAIN_THEME_COLOR.getMessage() + diff
                        + " deltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.abs(rotationData.getYaw() - rotationData.getLastYaw()));
            }
            this.suspiciousYaw = (float)Math.round(diff);
        }
        else {
            this.suspiciousYaw = 0.0f;
        }
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    public boolean yawCoarse() {
        return yawStep() >= 1.0F / 32;
    }

    public float yawStep() {
        return Math.ulp(Math.max(Math.abs(profile.getRotationData().getLastYaw()), Math.abs(profile.getRotationData().getYaw())));
    }
}
