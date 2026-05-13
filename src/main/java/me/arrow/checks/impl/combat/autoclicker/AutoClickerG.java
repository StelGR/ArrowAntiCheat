package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;


public class AutoClickerG extends Check {

    public AutoClickerG(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "G", "Checks if the player is clicking without outliers (Credits: MrPlugin)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    List<Integer> clickData = new CopyOnWriteArrayList<>();
    double buffer, lastSTD;

    private int movements;
    private boolean digging;
    private long lastDiggingUpdate;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) {
            WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);

            if (wrapper.getAction() == DiggingAction.START_DIGGING) {
                digging = true;
                movements = 0;
                lastDiggingUpdate = System.currentTimeMillis();
            }
            else if (wrapper.getAction() == DiggingAction.FINISHED_DIGGING
                    || wrapper.getAction() == DiggingAction.CANCELLED_DIGGING) {
                digging = false;
                lastDiggingUpdate = System.currentTimeMillis();
            }
            return;
        }
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {
            if (digging || System.currentTimeMillis() - lastDiggingUpdate < 250L) {
                movements = 0;
                return;
            }

            if (profile.shouldCancel()
                    || profile.getLastBlockPlaceTimer().hasNotPassed(20)
                    || profile.getLastBlockPlaceCancelTimer().hasNotPassed(20)) {
                movements = 20;
                return;
            }

            this.movements++;
        }
        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {

            if (digging || System.currentTimeMillis() - lastDiggingUpdate < 250L) {
                movements = 0;
                buffer = 0;
                return;
            }

            if (movements < 10) {
                clickData.add(movements);

                if (clickData.size() >= 1000) {
                    int outliers = (int) clickData.stream().filter(delay -> delay > 3).count();

                    if (outliers < 7) {
                        if (buffer++ > 2) {
                            fail( "No outliers",
                                    "outliers " + MsgType.MAIN_THEME_COLOR.getMessage() + outliers
                                            + "\nmaxOutliers " + MsgType.MAIN_THEME_COLOR.getMessage() + "6-e7"
                                            + "\ncps " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getCombatData().getCurrentCps());
                        }
                    } else {
                        buffer -= Math.min(buffer, 1.2);
                    }
                    clickData.clear();
                }
            }
        }
        movements = 0;
    }
}


