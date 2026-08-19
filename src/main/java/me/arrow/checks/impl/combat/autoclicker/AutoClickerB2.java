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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Experimental
public class AutoClickerB2 extends Check{

    public AutoClickerB2(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "B2", "Checks if clicking pattern is too consistent (Credits: MrPlugin)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    private final List<Integer> clickData = new CopyOnWriteArrayList<>();
    private int movements;

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
            }

            if (profile.getCombatData().getAttackedTicks() > 60) {
                return;
            }

            if (movements < 8) {
                clickData.add(movements);
                if (clickData.size() >= 100) {
                    double std = StreamUtil.getStandardDeviation(clickData);

                    if (std <= 0.45) {
                        if (increaseBuffer() > 3) {
                            fail("Consistent clicks (2)",
                                    "std " + MsgType.MAIN_THEME_COLOR.getMessage() + std);
                        }
                    } else {
                        decreaseBufferBy(0.5);
                    }

                    clickData.clear();
                }
            }

            movements = 0;
        }
    }
}
