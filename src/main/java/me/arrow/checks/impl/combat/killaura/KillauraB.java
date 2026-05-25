package me.arrow.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

// useless above 1.9, so it's disabled

public class KillauraB extends Check {

    public KillauraB(Profile profile) {
        super(profile, CheckType.KILLAURA, "B", "Checks for multi aura");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    private int lastTarget;
    private int attacks;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {

            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK && PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_9)) {
                final int target = wrapper.getEntityId();

                if (target != this.lastTarget && ++this.attacks > 1) {
                    fail("MultiAura",
                            "targetID "+ MsgType.MAIN_THEME_COLOR.getMessage() +target
                            +"\nlastTargetID "+ MsgType.MAIN_THEME_COLOR.getMessage()+lastTarget);
                    this.attacks = 0;
                }

                this.lastTarget = target;
            }
        } else if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)) {
            attacks = 0;
        }
    }
}
