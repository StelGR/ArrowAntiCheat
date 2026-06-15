package me.arrow.playerdata.processors.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.nms.NmsInstance;
import me.arrow.playerdata.data.Data;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.entity.Player;

// just used to update our GUIs

public class NMSProcessor implements Data {

    Profile profile;

    public NMSProcessor(Profile profile) {
        this.profile = profile;
    }

    /**
     * @param event
     */
    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {

            NmsInstance nmsInstance = Arrow.getInstance().getNmsManager().getNmsInstance();
            MovementData movementData = profile.getMovementData();
            CustomLocation customLocation = movementData.getLocation();
            Player player = profile.getPlayer();


            profile.setCrawling(nmsInstance.isCrawling(player));
            profile.setSneaking(nmsInstance.isSneaking(player));
            profile.setSwimming(nmsInstance.isSwimming(player));
            profile.setAttackCooldown(nmsInstance.getAttackCooldown(player));
            profile.setSleeping(nmsInstance.isSleeping(player));

        }
    }

    /**
     * @param event
     */
    @Override
    public void processSend(PacketSendEvent event) {

    }
}
