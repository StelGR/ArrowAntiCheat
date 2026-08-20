package me.arrow.checks.impl.misc.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.utils.customutils.OtherUtility;

import java.util.ArrayDeque;
import java.util.Deque;

// eagle scaffold Standard div

@Experimental
public class ScaffoldA extends Check {

    public ScaffoldA(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "A", "Checks for eagle scaffold");
    }

    Deque<Integer> interactions = new ArrayDeque<>();

    int flying;

    boolean placed = false;

    double buffer;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            placed = true;
        }
        else if (OtherUtility.isFlying(event.getPacketType())) {
            if (profile.getMovementData().getDeltaXZ() != 0
                    || profile.getMovementData().getDeltaY() != 0
                    || profile.getRotationData().getDeltaYaw() != 0
                    || profile.getRotationData().getDeltaPitch() != 0) {
                this.flying++;
            }
            placed = false;
        }
        else if (event.getPacketType().equals(PacketType.Play.Client.ENTITY_ACTION)) {
            WrapperPlayClientEntityAction entityAction = new WrapperPlayClientEntityAction(event);


            if (entityAction.getAction() != WrapperPlayClientEntityAction.Action.START_SNEAKING) {
                return;
            }

            if (profile.getActionData().hasRecentConfirmedUnderPlace(5 + (profile.getConnectionData().getClientTickTrans() * 2))
                    && profile.isAirBridging(profile.getPlayer().getLocation())) {
                if (interactions.add(flying) && interactions.size() >= 15) {
                    double std = MathUtil.getStandardDeviation(MathUtil.dequeTranslator(interactions));
                    if (std < 0.325) {
                        fail("Very Suspicious crouching", "std " + MsgType.MAIN_THEME_COLOR.getMessage() + std);
                    } else if (std < 0.65) {
                        if (++buffer > 3.0D) {
                            fail("Suspicious crouching", "std " + MsgType.MAIN_THEME_COLOR.getMessage() + std);
                        }
                    } else {
                        buffer = Math.max(buffer - 0.75, 0.0);
                    }
                    interactions.clear();
                }
                flying = 0;
            }
        }
    }

    @Override
    public void handle(PacketSendEvent event) {

    }
}
