package me.arrow.playerdata.processors.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.nms.NmsInstance;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.TaskUtils;
import org.bukkit.entity.Player;

// just used to update our GUI info

public class NMSProcessor implements Data {

    Profile profile;

    public NMSProcessor(Profile profile) {
        this.profile = profile;
    }


    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {

            Player player = profile.getPlayer();
            if (player == null) return;

            Runnable update = () -> {
                Player current = profile.getPlayer();

                if (current == null || !current.isOnline()) {
                    return;
                }

                NmsInstance nmsInstance = Arrow.getInstance().getNmsManager().getNmsInstance();

                profile.setCrawling(nmsInstance.isCrawling(current));
                profile.setSneaking(nmsInstance.isSneaking(current));
                profile.setSwimming(nmsInstance.isSwimming(current));
                profile.setAttackCooldown(nmsInstance.getAttackCooldown(current));
                profile.setSleeping(nmsInstance.isSleeping(current));
            };

            if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(player)) {
                TaskUtils.player(player, update);
            } else {
                update.run();
            }
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {

    }
}
