package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.CombatData;
import me.arrow.utils.customutils.Math.MathUtil;

import java.util.ArrayList;
import java.util.List;


// this is from my old autoclicker checks on my 1.8 only anticheat (sonix v2)
@Experimental
public class AutoClickerH extends Check {

    private int movements;
    List<Integer> flawOneDelays = new ArrayList<>();
    List<Double> flawOneStdDelays = new ArrayList<>();
    private final List<Double> flawTwoDelays = new ArrayList<>();
    private double threshold1, threshold2, threshold3, lastAverage, lastDelta;

    public AutoClickerH(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "H", "Detects some autoclicker flaws");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {
            if (profile.shouldCancel()
                    || profile.getPredictionData().isActivelyDigging()) {
                movements = 20;
                return;
            }

            movements++;
        }

        else if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            //clickerFlawOne();

            // do not use clickerFlawOne, it seems terrible, but the rest seem fine? i mean they don't seem to be flagging any autoclicker i've tested..

            clickerFlawTwo();
            clickerFlawThree();

        }
    }

    public void clickerFlawOne() {
        if (movements < 15) {

            flawOneDelays.add(movements);

            double mean = MathUtil.getMedian(flawOneDelays);
            double std = MathUtil.getStandardDeviation(flawOneDelays);
            double kurtosis = MathUtil.getKurtosis(flawOneDelays);


            if (mean < 2.5 && flawOneDelays.size() >= 20) {
                if (flawOneStdDelays.size() > 30) {
                    double average = MathUtil.getAverage(flawOneStdDelays);
                    double delta = Math.abs(average - lastAverage);
                    double outlier = profile.getCombatData().getOutlier();

                    if (lastDelta < 0.0855) {

                        double newDelta = Math.abs(delta - lastDelta);

                        if (newDelta < (.43 % 5) && kurtosis < 1.7 && outlier < 15) {
                            threshold1++;

                            if (threshold1 > 6) {
                                fail("Clicker Flaw (1)","Median " + MsgType.MAIN_THEME_COLOR.getMessage() + mean
                                        +"\nStandard Deviation " + MsgType.MAIN_THEME_COLOR.getMessage() + std
                                        +"\nKurtosis " + MsgType.MAIN_THEME_COLOR.getMessage() + kurtosis
                                        +"\nNewDelta " + MsgType.MAIN_THEME_COLOR.getMessage() + newDelta
                                        +"\nLastDelta " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDelta
                                        +"\nAverage " + MsgType.MAIN_THEME_COLOR.getMessage() + average
                                        +"\nLastAverage " + MsgType.MAIN_THEME_COLOR.getMessage() + lastAverage);
                            }
                        } else {
                            threshold1 -= Math.min(threshold1, .1);
                        }
                    } else {
                        threshold1 -= Math.min(threshold1, 0.1);
                    }


                    lastDelta = delta;
                    lastAverage = average;
                    flawOneStdDelays.clear();
                }

                flawOneStdDelays.add(std);

                if (flawOneDelays.size() >= 100) {
                    flawOneDelays.clear();
                }
            }
        }
        movements = 0;
    }

    public void clickerFlawTwo(){
        if (profile.shouldCancel() || profile.getTick() < 60) {
            return;
        }

        CombatData combatData = profile.getCombatData();

        double currentCps = combatData.getArmAnimationCps();
        double kurtosis = combatData.getKurtosis();
        double median = combatData.getMedian();

        if (median < 2.5 && combatData.getMovements().size() >= 20) {
            if (currentCps > 11) {
                flawTwoDelays.add(kurtosis);

                if (flawTwoDelays.size() == 25) {

                    double std = MathUtil.getStandardDeviation(flawTwoDelays);

                    if (std < 0.1) {
                        if (++threshold2 > 2) {
                            fail("Clicker Flaw (2)","Median " + MsgType.MAIN_THEME_COLOR.getMessage() + median
                                    + "\nCPS " + MsgType.MAIN_THEME_COLOR.getMessage() + currentCps
                                    + "\nKurtosis " + MsgType.MAIN_THEME_COLOR.getMessage() + kurtosis
                                    + "\nStandardDeviation " + MsgType.MAIN_THEME_COLOR.getMessage() + std);
                        }
                    } else {
                        threshold2 -= Math.min(threshold2, 0.15);
                    }

                    flawTwoDelays.clear();
                }
            }
        }
    }

    public void clickerFlawThree(){
        if (profile.shouldCancel() || profile.getTick() < 60) {
            return;
        }

        CombatData combatData = profile.getCombatData();

        double skewness = combatData.getSkewness();
        double outlier = combatData.getOutlier();
        double currentCps = combatData.getCurrentCps();
        double kurtosis = combatData.getKurtosis();
        double median = combatData.getMedian();

        if (median < 2.5 && skewness < 0.1 && outlier < 3 && currentCps > 7.6 && kurtosis < 0) {

            if (++threshold3 > 4 && threshold3 < 50) {
                fail("Clicker Flaw (3)","Median " + MsgType.MAIN_THEME_COLOR.getMessage() + median
                        + "\nSkewness " + MsgType.MAIN_THEME_COLOR.getMessage() + skewness
                        + "\nOutlier " + MsgType.MAIN_THEME_COLOR.getMessage() + outlier
                        + "\nCPS " + MsgType.MAIN_THEME_COLOR.getMessage() + currentCps
                        + "\nKurtosis " + MsgType.MAIN_THEME_COLOR.getMessage() + kurtosis);
            }

        } else {
            threshold3 -= Math.min(threshold3, 1.25);
        }
    }
}
