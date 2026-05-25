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


public class AutoClickerF extends Check {

    public AutoClickerF(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "F", "Checks if the player is clicking pattern is consistent (Credits: MrPlugin)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    List<Double> clickData = new ArrayList<>();
    double buffer, lastSTD;


    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            if (profile.getPredictionData().isDigging()) return;

            clickData.add(profile.getCombatData().getCurrentCps());

            if (clickData.size() >= 150) {
                double std = MathUtil.getStandardDeviation(clickData);
                double difference = Math.abs(std - this.lastSTD);

                if (difference <= 0.01 && profile.getCombatData().getCurrentCps() > 11) {
                    if (buffer++ > 3) {
                        fail( "Consistent clicking pattern",
                                "STD " + MsgType.MAIN_THEME_COLOR.getMessage() + std
                                        + "\nlastSTD " + MsgType.MAIN_THEME_COLOR.getMessage() + lastSTD
                                        + "\ndifference " + MsgType.MAIN_THEME_COLOR.getMessage() + difference);
                    }
                } else {
                    buffer -= Math.min(buffer, 0.5);
                }
                this.lastSTD = std;
                clickData.clear();
            }
        }
    }
}

