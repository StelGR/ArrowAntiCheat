package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.combat.aimassist.aimassistUtil.LinearRegression;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.CombatData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.customutils.EvictingList;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.utils.customutils.OtherUtility;

import java.text.DecimalFormat;

@Experimental
public class AimI extends Check {
    EvictingList<Double> yawData = new EvictingList<>(250), pitchData = new EvictingList<>(250);

    public AimI(Profile profile) {
        super(profile, CheckType.AIM, "I", "Smooth aim (4)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }


    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {
            CombatData combatData = profile.getCombatData();
            RotationData rotationData = profile.getRotationData();

            boolean attacking = combatData.getAttackedTicks() <= 4;

            handle:
            {
                if (!attacking || rotationData.getCinematicProcessor().isCinematic()) break handle;

                double deltaYaw = rotationData.getDeltaYaw();
                double deltaPitch = rotationData.getPitch();

                yawData.add(deltaYaw);
                pitchData.add(deltaPitch);

                if (yawData.isFull()) {

                    Double[] regressionX = new Double[yawData.size()];
                    Double[] regressionY = new Double[pitchData.size()];

                    regressionX = yawData.toArray(regressionX);
                    regressionY = pitchData.toArray(regressionY);

                    final LinearRegression regression = new LinearRegression(regressionX, regressionY);

                    double standardDeviationYaw = MathUtil.getStandardDeviation(yawData);
                    double standardDeviationPitch = MathUtil.getStandardDeviation(pitchData);
                    double error = regression.interceptStdErr();
                    double prediction = regression.predict(1.75);

                    String information = "standardDeviationYaw " + format(standardDeviationYaw) + "\nstandardDeviationPitch " + format(standardDeviationPitch) + "\nerror " + format(error);

                    verbose("AimI", standardDeviationYaw , standardDeviationPitch, "Verbose\n" + information);
                    if (standardDeviationYaw > 10 && standardDeviationPitch < 4.5 && error < 0.5 && prediction > 5) {
                        fail("Smooth Aim", information);
                    }
                    yawData.clear();
                    pitchData.clear();
                }
            }
        }
    }
    public String format(double input) {
        return new DecimalFormat("###.###").format(input);
    }
}
