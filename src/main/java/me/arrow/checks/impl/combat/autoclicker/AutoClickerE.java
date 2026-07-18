package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.Stats;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.CombatData;
import me.arrow.utils.custom.SampleList;

public class AutoClickerE extends Check {
    SampleList<Double> clickSamples = new SampleList<>(300);

    public AutoClickerE(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "E", "Sample based autoclicker check (Credits: MrPlugin | MTR)");
    }
    @Override
    public void handle(PacketSendEvent event) {

    }

    double cps;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {

            CombatData combatData = profile.getCombatData();

            cps = combatData.getCurrentCps();
        }

        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            if (profile.getPredictionData().isDigging() || cps < 11) return;

            clickSamples.add(cps);

            if (!clickSamples.isCollected()) return;

            double kur = Stats.kurtosis(clickSamples);
            double skew = Stats.skewness(clickSamples);
            double st = Stats.stdDev(clickSamples);
            double mean = Stats.mean(clickSamples);
            double variance = Stats.variance(clickSamples);
            double entropy = Stats.entropy(clickSamples);
            double range = Stats.range(clickSamples);

            long outliers = clickSamples.stream()
                    .filter(i -> i >= 4)
                    .count();

            long zeros = clickSamples.stream()
                    .filter(i -> i == 0)
                    .count();

            if (kur <= 0 && cps > 13) {
                fail("Invalid Kurtosis", "Kurtosis " + MsgType.MAIN_THEME_COLOR.getMessage() + kur
                        + "\nCPS "+ MsgType.MAIN_THEME_COLOR.getMessage() + cps);
                return;
            }

            boolean invalidSt = st < 0.5 || st > 1 && cps > 11;
            boolean invalidSkew = skew > 1.2 && cps > 11;
            boolean invalidKur = kur > 3.3 && cps > 11;
            boolean invalidMean = mean < 2 && cps > 11;
            boolean invalidVar = variance > 1 && cps > 11;
            boolean invalidEntropy = entropy > 1.8 && cps > 11;
            boolean invalidZeros = zeros > 3 && cps > 11;
            boolean invalidRange = range > 6 && cps > 11;
            boolean invalidOutliers = outliers > 20 && cps > 11;

            if (invalidSt
                    || invalidSkew
                    || invalidKur
                    || invalidMean
                    || invalidVar
                    || invalidEntropy
                    || invalidZeros
                    || invalidRange
                    || invalidOutliers) {

                fail("Suspicious clicking", "cps " + MsgType.MAIN_THEME_COLOR.getMessage() + cps
                        + "\nstd " + MsgType.MAIN_THEME_COLOR.getMessage() + st + " | " + invalidSt
                        + "\nskewness " + MsgType.MAIN_THEME_COLOR.getMessage() + skew + " | " + invalidSkew
                        + "\nkurtosis " + MsgType.MAIN_THEME_COLOR.getMessage() + kur + " | " + invalidKur
                        + "\nmean " + MsgType.MAIN_THEME_COLOR.getMessage() + mean + " | " + invalidMean
                        + "\nvariance " + MsgType.MAIN_THEME_COLOR.getMessage() + variance + " | " + invalidVar
                        + "\nentropy " + MsgType.MAIN_THEME_COLOR.getMessage() + entropy + " | " + invalidEntropy
                        + "\nzeros " + MsgType.MAIN_THEME_COLOR.getMessage() + zeros + " | " + invalidZeros
                        + "\nrange " + MsgType.MAIN_THEME_COLOR.getMessage() + range + " | " + invalidRange
                        + "\noutliers " + MsgType.MAIN_THEME_COLOR.getMessage() + outliers + " | " + invalidOutliers);

            }

            clickSamples.clear();
        }
    }
}
