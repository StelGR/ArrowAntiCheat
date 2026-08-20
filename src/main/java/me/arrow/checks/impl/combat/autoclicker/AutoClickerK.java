package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.combat.autoclicker.autoclickerUtil.StreamUtil;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.OtherUtility;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AutoClickerK extends Check{

    public AutoClickerK(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "K", "Checks for spike type pattern (Credits: MrPlugin)");
    }

    private final List<Integer> clickPattern = new CopyOnWriteArrayList<>();
    private int movements;
    private int lastMovements;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {
            movements++;
        }
        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            if (profile.shouldCancel()
                    || profile.getPredictionData().isDigging()
                    || profile.getCombatData().getAttackedTicks() > 20) {
                return;
            }

            boolean cps = profile.getCombatData().getCurrentCps() > 9.5;
            if (movements < 8 && lastMovements < 8 && cps) {
                int delta = Math.abs(movements - lastMovements);
                clickPattern.add(delta);
                if (clickPattern.size() >= 40) {
                    double std = StreamUtil.getStandardDeviation(clickPattern);
                    double avg = StreamUtil.getAverage(clickPattern);
                    double variance = StreamUtil.getVariance(clickPattern);
                    if (std < 0.55 && avg <= 0.5 && variance < 10.0) {
                        if (increaseBuffer() > 4.0) {
                            setBuffer(3);
                            fail("Invalid click pattern","std " + MsgType.MAIN_THEME_COLOR.getMessage() + std
                                    + "\navg " + MsgType.MAIN_THEME_COLOR.getMessage() + avg
                                    + "\nvariance " + MsgType.MAIN_THEME_COLOR.getMessage() + variance);
                        }
                    } else {
                        setBuffer(getBuffer() - Math.min(getBuffer(), 0.06));
                    }

                    clickPattern.clear();
                }
            }

            lastMovements = movements;
            movements = 0;
        }
    }
}
