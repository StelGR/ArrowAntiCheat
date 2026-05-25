package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.Math.MathUtil;

import java.util.ArrayList;
import java.util.List;


public class AutoClickerB extends Check {

    public AutoClickerB(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "B", "Checks if the player is clicking too consistently");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    List<Integer> delays = new ArrayList<>();
    double buffer;

    private double cps;
    private int movements;

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

            if (movements < 10) {
                delays.add(movements);

                if (delays.size() >= 150) {
                    double std = MathUtil.getStandardDeviation(delays);

                    if (std <= 0.45) {
                        if (buffer++ > 2 && cps > 7) {
                            fail( "Consistent clicks",
                                    "std " + MsgType.MAIN_THEME_COLOR.getMessage() + std);
                        }
                    } else {
                        buffer -= Math.min(buffer, 0.125);
                    }

                    delays.clear();
                }
            }
        }
    }
}
