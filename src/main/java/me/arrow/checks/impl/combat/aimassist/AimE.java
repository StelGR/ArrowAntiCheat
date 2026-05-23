package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.aim.RotationHeuristics;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

// Liquid bounce killaura detecter, really easy to false though, so again trust factor based
// made that way so that if you do trigger it alot, something is sus with ya
// but once in a while flags, are probably falses

@Experimental
public class AimE extends Check {

    public AimE(Profile profile) {
        super(profile, CheckType.AIM, "E", "Aim Analysis (Yaw)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    private final RotationHeuristics heuristics = new RotationHeuristics(200, 1.25F, 7.5F);
    private final List<Double> rotationHistory = new ArrayList<>();

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {



            RotationData data = profile.getRotationData();

            if (!profile.getMovementData().isMoving()
                    || profile.getCombatData().getAttackedTicks() > 20
                    || data.getCinematicProcessor().isCinematic()) {
                return;
            }

            Player target = getPlayerByEntityId(profile.getCombatData().getTarget());

            if (target == null || !target.isOnline() || target == profile.getPlayer()) {
                return;
            }

            Profile targetProfile = Arrow.getInstance().getProfileManager().getProfile(target);

            if (targetProfile == null || targetProfile.getMovementData() == null) {
                return;
            }

            float deltaYaw = data.getDeltaYaw();
            heuristics.process(deltaYaw);
            rotationHistory.add((double) deltaYaw);

            if (!heuristics.isFinished()) return;

            var result = heuristics.getResult();
            double avg = result.getAverage();
            double min = result.getMin();
            double max = result.getMax();
            int lowCount = result.getLowCount();
            int highCount = result.getHighCount();
            int duplicates = result.getDuplicates();
            int roundedCount = result.getRoundedCount();

            String reasons = "";

            double stdDev = MathUtil.getStandardDeviation(rotationHistory);
            double skew = MathUtil.getSkewness(rotationHistory);
            var outliers = MathUtil.getOutliers(rotationHistory);
            double outlierRatio = (outliers.one.size() + outliers.two.size()) / (double) rotationHistory.size();

            double uniqueRatio = (rotationHistory.size() - duplicates) / (double) rotationHistory.size();

            int snapThreshold = 20;
            int snapCount = 0;
            for (int i = 1; i < rotationHistory.size(); i++) {
                double cur = Math.abs(rotationHistory.get(i));
                double prev = Math.abs(rotationHistory.get(i - 1));
                if (cur > snapThreshold && prev < 1.0) snapCount++;
            }

            int longestIdenticalStreak = computeLongestIdenticalStreak(rotationHistory, 1e-6);

            double kurt = computeKurtosis(rotationHistory, avg, stdDev);
            double ent = computeEntropy(rotationHistory);

            double suspicion = 0.0;

            if (max > 45.0 || snapCount >= 6) {
                suspicion += 8.0;
                reasons += "Weird Snap; ";
            }
            if (snapCount >= 3 && max > 30.0) {
                suspicion += 5.0;
                reasons += "Multiple Snaps; ";
            }
            if (longestIdenticalStreak >= 6) {
                suspicion += 4.0;
                reasons += "Long Identical Streak; ";
            }
            if (uniqueRatio < 0.2 && stdDev < 0.35) {
                suspicion += 6.0;
                reasons += "Extremely Low Diversity; ";
            }
            if (stdDev < 0.4 && lowCount > (rotationHistory.size() * 0.5)) {
                suspicion += 3.5;
                reasons += "Unnaturally Consistent; ";
            }
            if (ent < 1.2) {
                suspicion += 2.5;
                reasons += "Low Entropy; ";
            }
            if (outlierRatio > 0.12 || kurt > 4.0) {
                suspicion += 2.5;
                reasons += "Heavy Spikes / Kurtosis; ";
            }
            if (duplicates > rotationHistory.size() * 0.3 && roundedCount > 20) {
                suspicion += 3.0;
                reasons += "High Rounding & Duplicates; ";
            }
            if (Math.abs(skew) > 1.2) {
                suspicion += 1.5;
                reasons += "Strong Directional Bias; ";
            }
            if (avg < 1.0 && lowCount > rotationHistory.size() * 0.6) {
                suspicion += 2.0;
                reasons += "Extremely Smooth; ";
            }

            if (suspicion >= 11) {
                int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

                if (increaseBuffer() > requiredBuffer) {
                    if (profile.getTrustFactor().getTrust() >= 80) {
                        profile.getTrustFactor().decreaseTrustBy(1.25);
                    } else {
                        fail("Aim Analysis (Yaw)", "avg " + MsgType.MAIN_THEME_COLOR.getMessage() + avg
                                + "\nmin " + MsgType.MAIN_THEME_COLOR.getMessage() + min
                                + "\nmax " + MsgType.MAIN_THEME_COLOR.getMessage() + max
                                + "\nlowCount " + MsgType.MAIN_THEME_COLOR.getMessage() + lowCount
                                + "\nhighCount " + MsgType.MAIN_THEME_COLOR.getMessage() + highCount
                                + "\nduplicates " + MsgType.MAIN_THEME_COLOR.getMessage() + duplicates
                                + "\nroundedCount " + MsgType.MAIN_THEME_COLOR.getMessage() + roundedCount
                                + "\nstddev " + MsgType.MAIN_THEME_COLOR.getMessage() + stdDev
                                + "\nskewness " + MsgType.MAIN_THEME_COLOR.getMessage() + skew
                                + "\noutlierRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + outlierRatio
                                + "\nSuspicion " + MsgType.MAIN_THEME_COLOR.getMessage() + suspicion
                                + "\nReasons " + MsgType.MAIN_THEME_COLOR.getMessage() + reasons.trim());
                        heuristics.reset();
                        rotationHistory.clear();
                        profile.getTrustFactor().decreaseTrustBy(2);
                    }
                }
            }
            else {
                decreaseBufferBy(0.05);
                if (profile.getTick() % 20 == 0 && profile.getTick() != 0) {
                    profile.getTrustFactor().increaseTrustBy(0.0125);
                }
            }
        }
    }

    private Player getPlayerByEntityId(int entityId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getEntityId() == entityId) {
                return player;
            }
        }

        return null;
    }

    private static int computeLongestIdenticalStreak(List<Double> data, double eps) {
        if (data == null || data.isEmpty()) return 0;
        int best = 1;
        int cur = 1;
        for (int i = 1; i < data.size(); i++) {
            if (Math.abs(data.get(i) - data.get(i - 1)) <= eps) {
                cur++;
            } else {
                if (cur > best) best = cur;
                cur = 1;
            }
        }
        if (cur > best) best = cur;
        return best;
    }

    private static double computeKurtosis(List<Double> data, double mean, double stdDev) {
        int n = data.size();
        if (n < 4 || stdDev == 0.0) return 0.0;
        double m4 = 0.0;
        for (double v : data) {
            double z = (v - mean) / stdDev;
            m4 += z * z * z * z;
        }
        double term1 = (n * (n + 1.0)) / ((n - 1.0) * (n - 2.0) * (n - 3.0));
        double term2 = m4;
        double term3 = 3.0 * ((n - 1.0) * (n - 1.0)) / ((n - 2.0) * (n - 3.0));
        return term1 * term2 - term3;
    }

    private static double computeEntropy(List<Double> data) {
        if (data == null || data.isEmpty()) return 0.0;
        double binSize = 0.5;
        java.util.HashMap<Integer, Integer> counts = new java.util.HashMap<>();
        for (double v : data) {
            int bin = (int) Math.floor(v / binSize);
            counts.put(bin, counts.getOrDefault(bin, 0) + 1);
        }
        double entropy = 0.0;
        int n = data.size();
        for (int freq : counts.values()) {
            double p = freq / (double) n;
            entropy -= p * (Math.log(p) / Math.log(2.0));
        }
        return entropy;
    }

}
