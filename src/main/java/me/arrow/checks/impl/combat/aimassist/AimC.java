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
import me.arrow.utils.customutils.Math.MathUtil;

import java.util.ArrayList;
import java.util.List;

@Experimental
public class AimC extends Check {


    private final List<Double> pitchChanges = new ArrayList<>();
    private final List<Double> yawChanges = new ArrayList<>();
    private final List<Long> timestamps = new ArrayList<>();


    public AimC(Profile profile) {
            super(profile, CheckType.AIM, "C", "Smooth Aim (2)");
        }

        @Override
        public void handle(PacketSendEvent event) {


        }

        @Override
        public void handle(PacketReceiveEvent event) {
            if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                    || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

                RotationData rotationData = profile.getRotationData();
                if (profile.getCombatData().getAttackedTicks() < 50 && !rotationData.getCinematicProcessor().isCinematic()) {
                    long currentTime = System.currentTimeMillis();
                    double deltaYaw = rotationData.getDeltaYaw();
                    double deltaPitch = rotationData.getDeltaPitch();

                    pitchChanges.add(deltaPitch);
                    yawChanges.add(deltaYaw);
                    timestamps.add(currentTime);

                    if (calculateAim()) {
                        int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

                        if (increaseBuffer() > requiredBuffer) {
                            if (profile.getTrustFactor().getTrust() >= 80) {
                                profile.getTrustFactor().decreaseTrustBy(2.75);
                            } else {
                                fail("Smooth Aim", "deltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYaw
                                + "\ndeltaPitch " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaPitch);
                                profile.getTrustFactor().decreaseTrustBy(2);
                            }
                        }
                    }
                    else {
                        decreaseBufferBy(0.05);
                        if (profile.getTick() % 20 == 0 && profile.getTick() != 0) {
                            profile.getTrustFactor().increaseTrustBy(0.0025);
                        }
                    }
                }

            }
        }
    private boolean calculateAim() {
        // listo no fillo returno falso.
        if (pitchChanges.size() < 20 || yawChanges.size() < 20) {
            return false;
        }

        // calculate mean of yaw, pitch changes.
        double avgPitchChange = MathUtil.getAverage(pitchChanges);
        double avgYawChange = MathUtil.getAverage(yawChanges);
        // grab the deviation.
        double stdDevPitch = MathUtil.getStandardDeviation(pitchChanges);
        double stdDevYaw = MathUtil.getStandardDeviation(yawChanges);
        // check time between aim changes.
        double timeBetweenChanges = getAverageTimeBetweenChanges(timestamps);

        float smoothnessThreshold = 0.3f;

        float stdDevThreshold = 0.15f;

        float timeThreshold = 80.0f;

        verbose(this.getClass().getSimpleName(), 0, 0, "Smoothness (" + timeBetweenChanges / 1000 + ") Y|P (" + avgYawChange + " | " + avgPitchChange
                + ") SDY|SDP (" + stdDevYaw + " | " + stdDevPitch);

        pitchChanges.clear();
        yawChanges.clear();

        return (avgPitchChange < smoothnessThreshold && avgYawChange < smoothnessThreshold &&
                stdDevPitch < stdDevThreshold && stdDevYaw < stdDevThreshold &&
                timeBetweenChanges < timeThreshold);
    }


    private float getAverageTimeBetweenChanges(List<Long> timestamps) {
        if (timestamps.size() < 2) {
            return Float.MAX_VALUE;
        }
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) {
            intervals.add(timestamps.get(i) - timestamps.get(i - 1));
        }
        timestamps.clear();
        return (float) intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
}
