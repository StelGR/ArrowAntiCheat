package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

// connection data to process confirmed transaction info

@Getter
@Setter
public class ConnectionData implements Data {
    private int ping, transPing, lastTransPing, dropTransTime;
    private int clientTick, flyingTick, clientTickTrans;
    private int lastFlyingReceived;
    private boolean isLagging = false;
    private int keepDropTick, transDropTick, averageTransactionPing;
    private short transactionId = Short.MAX_VALUE;
    private final Profile profile;
    private final List<Integer> pingList = new ArrayList<>();
    private long transactionStamp;

    public ConnectionData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        Profile profileT = Arrow.getInstance().getProfileManager().getProfile(player);
        if (profileT == null) return;

        boolean modernTransaction = PacketEvents.getAPI()
                .getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_17);

        if (modernTransaction) {
            if (event.getPacketType().equals(PacketType.Play.Client.PONG)) {
                WrapperPlayClientPong transactionPacket = new WrapperPlayClientPong(event);
                int actionId = transactionPacket.getId();
                processTransaction(actionId, (short) actionId, player);
            }

            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Client.WINDOW_CONFIRMATION)) {
            WrapperPlayClientWindowConfirmation transactionPacket = new WrapperPlayClientWindowConfirmation(event);
            short actionId = transactionPacket.getActionId();
            processTransaction(actionId, actionId, player);
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
    }

    private void processTransaction(int pingID, short actionID, Player user) {
        Profile profileT = Arrow.getInstance().getProfileManager().getProfile(user);
        if (profileT == null) return;

        Long sentTime = null;

        if (profileT.getISentTransactions().containsKey(pingID)) {
            sentTime = profileT.getISentTransactions().remove(pingID);
        } else if (profileT.getSSentTransactions().containsKey(actionID)) {
            sentTime = profileT.getSSentTransactions().remove(actionID);
        }

        if (sentTime == null) {
            return;
        }

        if (!profileT.isHasReceivedTransaction()) {
            transactionStamp = sentTime;
        }

        profileT.setHasReceivedTransaction(true);

        lastTransPing = transPing;
        transPing = (int) (System.currentTimeMillis() - sentTime);

        flyingTick = 0;
        transDropTick = 0;
        lastFlyingReceived++;
        dropTransTime = Math.abs(transPing - lastTransPing);
        clientTickTrans = (int) Math.ceil(transPing / 50.0);

        pingList.add(transPing);

        if (pingList.size() > 250) {
            averageTransactionPing = (int) MathUtil.getAverage(pingList);
            pingList.clear();
        }
    }
}
