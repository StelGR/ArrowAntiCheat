package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.StreamUtil;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.OtherUtility;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Experimental
public class AutoClickerJ extends Check{

    public AutoClickerJ(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "J", "Checks if clicking pattern is invalid (Credits: MrPlugin)");
    }

    private final List<Integer> clickData = new CopyOnWriteArrayList<>();
    private double lastEntropy;
    private double lastCoefficient;
    private double lastCorrelation;
    private int movements;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {
            movements++;
        }
        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            if (profile.shouldCancel() || profile.getPredictionData().isDigging()) {
                clickData.clear();
            }

            if (profile.getCombatData().getAttackedTicks() > 60) {
                return;
            }

            if (movements < 8) {
                clickData.add(movements);
                if (clickData.size() >= 40) {
                    double serialCorrelation = StreamUtil.calculateSerialCorrelation(clickData);
                    double entropy = StreamUtil.getEntropy(clickData);
                    double giniCoefficient = StreamUtil.giniCoefficient(clickData);
                    double cps = StreamUtil.getCPS(clickData);
                    double entropyOffset = Math.abs(entropy - lastEntropy);
                    double coefficientOffset = Math.abs(giniCoefficient - lastCoefficient);
                    double correlationOffset = Math.abs(serialCorrelation - lastCorrelation);
                    boolean isCps = cps >= 9.25 && profile.getCombatData().getCurrentCps() >= 9.25;
                    if (isInvalidClickData(giniCoefficient, entropy, serialCorrelation, entropyOffset, coefficientOffset, correlationOffset) && isCps) {
                        if (increaseBuffer() > 6.5) {
                            fail("Invalid click pattern",
                                    "entropy " + MsgType.MAIN_THEME_COLOR.getMessage() + entropy +
                                    "\ncorrelation " + MsgType.MAIN_THEME_COLOR.getMessage() + serialCorrelation +
                                    "\ncoefficient " + MsgType.MAIN_THEME_COLOR.getMessage() + giniCoefficient +
                                    "\nentropy-offset " + MsgType.MAIN_THEME_COLOR.getMessage() + entropyOffset +
                                    "\ncoefficient-offset " + MsgType.MAIN_THEME_COLOR.getMessage() + coefficientOffset +
                                    "\ncorrelation-offset " + MsgType.MAIN_THEME_COLOR.getMessage() + correlationOffset);
                        }
                    } else {
                        decreaseBufferBy(0.075);
                    }

                    lastCoefficient = giniCoefficient;
                    lastCorrelation = serialCorrelation;
                    lastEntropy = entropy;
                    clickData.clear();
                }
            }

            movements = 0;
        }
    }

    private boolean isInvalidClickData(
            double giniCoefficient, double entropy, double correlation, double entropyOffset, double coefficientOffset, double correlationOffset
    ) {
        return giniCoefficient < 0.028 && entropy < 0.77 && correlation < 0.5
                || coefficientOffset < 0.02 && entropyOffset < 0.02 && correlationOffset < 0.02
                || entropyOffset < 0.037 && entropy < 1.5 && correlation < 0.5
                || coefficientOffset < 0.006 && entropy < 1.1 && correlation < 0.3
                || correlationOffset < 0.01 && entropy < 1.1 && correlation < 0.25;
    }
}
