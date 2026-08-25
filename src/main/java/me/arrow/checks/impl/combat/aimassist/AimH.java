package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.*;
import me.arrow.utils.customutils.OtherUtility;

// Credits: Karhu AimAssist E

@Experimental
public class AimH extends Check {

    public AimH(Profile profile) {
        super(profile, CheckType.AIM, "H", "Consistent rotations (pitch)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    private double lastGCD;
    double violations;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            MovementData movement = profile.getMovementData();
            RotationData rotation = profile.getRotationData();
            CombatData combat = profile.getCombatData();
            ActionData action = profile.getActionData();

            double deltaYaw = rotation.getDeltaYaw();
            double deltaPitch = rotation.getDeltaPitch();
            double lastDeltaPitch = rotation.getLastDeltaPitch();
            double lastDeltaYaw = rotation.getLastDeltaYaw();

            boolean condition = !rotation.getCinematicProcessor().isCinematic()
                    && rotation.getPitch() < 90
                    && rotation.getLastPitch() < 90
                    && deltaPitch <= 5f
                    && movement.getSinceTeleportTicks() > 5;

            double addition = lastGCD < 0.003 ? 0.5 : 0;

            if (combat.getAttackedTicks() <= 4 || action.getLastConfirmedUnderPlaceTicks() <= 4) {
                if (condition) {
                    double gcdPitch = getGcd(deltaPitch, lastDeltaPitch);
                    double gcdYaw = getGcd(deltaYaw, lastDeltaYaw);

                    if (deltaPitch > 0.2 && Math.abs(deltaPitch - lastDeltaPitch) > 0.2 && gcdPitch < 0.008) {

                        violations = Math.min(30, violations + 0.5 + addition); //Prevent buffer overflow

                        if (violations > 17.5) {
                            fail("Consistent rotations",
                                    "gcdPitch " + gcdPitch
                                            + "\ngcdYaw" + gcdYaw
                                            + "\ndeltaPitch" + deltaPitch
                                            + "\nlastDeltaPitch " + lastDeltaPitch
                                            + "\ndeltaYaw" + deltaYaw
                                            + "\nlastDeltaYaw " + lastDeltaYaw);
                        }
                    } else {
                        violations = Math.max(violations - 0.65, 0);
                    }
                    lastGCD = gcdPitch;
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
