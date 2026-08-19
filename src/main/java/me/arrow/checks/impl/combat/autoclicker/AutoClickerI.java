package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.StreamUtil;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AutoClickerI extends Check {

    private final List<Integer> clickData = new CopyOnWriteArrayList<>();
    private double lastDev;
    private double lastEntropy;
    private double lastVariance;
    private double variance;
    private double globalCheatPercent;
    private int movements;

    public AutoClickerI(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "I", "Detect autoclicking patern (Credit: MrPlugin)");
    }

    @Override
    public void handle(PacketSendEvent event) {}

    @Override
    public void handle(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {
            movements++;
        }

        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            
            if (profile.shouldCancel() || profile.getPredictionData().isDigging()) {
                clickData.clear();
                return;
            }
            
            if (movements < 10) {
                clickData.add(movements);
                if (clickData.size() >= 100) {
                    double dev = StreamUtil.getDeviation(clickData);
                    double cps = StreamUtil.getCPS(clickData);
                    double entropy = StreamUtil.getEntropy(clickData);
                    double skewness = StreamUtil.getSkewness(clickData);
                    double variance = StreamUtil.getVariance(clickData);
                    int distinct = StreamUtil.getDistinct(clickData);
                    this.handleInvalidClickData(dev, entropy, distinct, skewness, cps, variance);
                    this.lastVariance = this.variance;
                    this.variance = variance;
                    this.lastEntropy = entropy;
                    this.lastDev = dev;
                    clickData.clear();
                }
            }

            movements = 0;
        }
    }

    private void handleInvalidClickData(double dev, double entropy, int distinct, double skewness, double cps, double variance) {
        double cheatingPercent = 0.0;
        double deviationOffset = Math.abs(dev - lastDev);
        double entropyOffset = Math.abs(entropy - lastEntropy);
        double varOffset = Math.abs(variance - lastVariance);
        if (deviationOffset <= 0.1) {
            cheatingPercent += 5.0;
        }

        if (dev < 0.7) {
            cheatingPercent += 5.0;
        }

        if (entropyOffset <= 0.15) {
            cheatingPercent += 5.0;
        }

        if (skewness > 0.0 && skewness < 0.2) {
            cheatingPercent += 5.0;
        }

        if (distinct < 4) {
            cheatingPercent += 5.0;
        }

        if (varOffset < 0.3) {
            cheatingPercent += 5.0;
        }

        if (cps > 13.0) {
            cheatingPercent += 10.0;
        } else {
            cheatingPercent *= 0.5;
        }

        globalCheatPercent += cheatingPercent;
        if (globalCheatPercent >= 70.0) {
            fail("Suspicious click pattern",
                    "var " + MsgType.MAIN_THEME_COLOR.getMessage() + variance +
                    "\nvarOffset " + MsgType.MAIN_THEME_COLOR.getMessage() + varOffset +
                    "\ncps " + MsgType.MAIN_THEME_COLOR.getMessage() + cps +
                    "\ndistinct " + MsgType.MAIN_THEME_COLOR.getMessage() + distinct +
                    "\ndev " + MsgType.MAIN_THEME_COLOR.getMessage() + dev +
                    "\ndevOffset " + MsgType.MAIN_THEME_COLOR.getMessage() + deviationOffset +
                    "\nentropy " + MsgType.MAIN_THEME_COLOR.getMessage() + entropy +
                    "\nentropyOffset " + MsgType.MAIN_THEME_COLOR.getMessage() + entropyOffset +
                    "\nskewness " + MsgType.MAIN_THEME_COLOR.getMessage() + skewness +
                    "\npercent " + MsgType.MAIN_THEME_COLOR.getMessage() + globalCheatPercent);
        }

        if (globalCheatPercent >= 100.0) {
            globalCheatPercent = 100.0;
        }

        globalCheatPercent = globalCheatPercent - Math.min(globalCheatPercent, 20.0);
    }
}
