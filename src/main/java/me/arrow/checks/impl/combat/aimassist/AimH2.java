package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.*;
import me.arrow.utils.customutils.OtherUtility;

// Credits: Karhu AimAssist F

@Experimental
public class AimH2 extends Check {

    public AimH2(Profile profile) {
        super(profile, CheckType.AIM, "H2", "Consistent rotations (yaw)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    private int streak;
    double violations;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            MovementData movement = profile.getMovementData();
            RotationData rotation = profile.getRotationData();
            CombatData combat = profile.getCombatData();

            double deltaYaw = rotation.getDeltaYaw();
            double deltaPitch = rotation.getDeltaPitch();
            double lastDeltaPitch = rotation.getLastDeltaPitch();
            double lastDeltaYaw = rotation.getLastDeltaYaw();

            boolean condition = Math.abs(rotation.getPitch()) <= 80
                    && Math.abs(rotation.getLastPitch()) <= 80
                    && movement.getSinceTeleportTicks() > 5;


            if (combat.getAttackedTicks() <= 5) {
                if (condition) {
                    if (deltaYaw > 0.001D && deltaYaw <= 5.0F && lastDeltaYaw <= 5.0F && Math.abs(rotation.getPitch()) <= 80) {

                        double gcdYaw = getGcd(deltaYaw, lastDeltaYaw);

                        if (gcdYaw < 0.009 && !rotation.getCinematicProcessor().isCinematic()) {
                            double gcdPitch = getGcd(deltaPitch, lastDeltaPitch);

                            if (deltaPitch > 0 && gcdPitch < 0.009) {
                                streak = 0;
                                violations = 0;
                            }

                            if (++streak > 20 && lastDeltaPitch == 0 && ++violations > 15) {
                                fail("Consistent rotations",
                                        "gcdPitch " + gcdPitch
                                                + "\ngcdYaw" + gcdYaw
                                                + "\ndeltaPitch" + deltaPitch
                                                + "\nlastDeltaPitch " + lastDeltaPitch
                                                + "\ndeltaYaw" + deltaYaw
                                                + "\nlastDeltaYaw " + lastDeltaYaw);
                                violations = 0;
                            }
                        }
                        else {
                            violations = Math.max(0, violations - 0.5);
                        }
                    }
                } else violations = Math.max(violations - 1.1, 0);
            }
        }
    }


    double getGcd(double current, double previous) {
        double temp;

        if (previous > current) {
            temp = current;
            current = previous;
            previous = temp;
        }

        while (previous > 0.001) {
            temp = current % previous;
            current = previous;
            previous = temp;
        }

        return current;
    }

}
