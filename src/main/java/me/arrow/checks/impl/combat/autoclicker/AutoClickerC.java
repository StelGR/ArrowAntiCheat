package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.ArrayList;
import java.util.List;

@Experimental
public class AutoClickerC extends Check {

    public AutoClickerC(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "C", "Checks for click outliers");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    private double cps;
    private int movements;


    List<Integer> delays = new ArrayList<>();
    double threshold;

    @Override
    public void handle(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {
            if (profile.getPredictionData().isDigging()) {
                movements = 20;
                return;
            }

            if (profile.shouldCancel()) {
                movements = 20;
                return;
            }

            cps = profile.getCombatData().getCurrentCps();

            movements++;
        }
        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            cps++;

            if (movements < 10) {
                delays.add(movements);

                if (delays.size() == 1000) {

                    int outliers = (int) delays.stream()
                            .filter(delay -> delay > 3)
                            .count();

                    if (outliers < 7) {
                        if (++threshold > 1 && cps > 8) {
                            fail("Outliers",
                                    "outliers "+ MsgType.MAIN_THEME_COLOR.getMessage() + outliers
                                            +"\ndelays "+ MsgType.MAIN_THEME_COLOR.getMessage() + delays.size()
                                            +"\nmovements "+ MsgType.MAIN_THEME_COLOR.getMessage() + movements);
                        }
                    } else {
                        threshold -= Math.min(threshold, 1.5);
                    }

                    delays.clear();
                }
            }
        }
    }
}
