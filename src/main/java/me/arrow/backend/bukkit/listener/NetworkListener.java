package me.arrow.backend.bukkit.listener;


import com.github.retrooper.packetevents.event.*;
import me.arrow.Arrow;
import me.arrow.core.network.PacketSanityValidator;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.TaskUtils;
import org.bukkit.entity.Player;

//niks network listener converted to PacketEvents from ProtocolLib, i prefer packetevents.

public class NetworkListener extends PacketListenerAbstract implements PacketListener {

    private final Arrow plugin;

    public NetworkListener(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        final Player player = event.getPlayer();
        if (player == null) return;

        final Profile profile = this.plugin.getProfileManager().getProfile(player);
        if (profile == null) return;

        final String crashAttempt = PacketSanityValidator.check(event);

        if (crashAttempt != null) {
            event.setCancelled(true);

            ChatUtils.log("Kicking " + player.getName()
                    + " for sending an invalid position packet, Information: " + crashAttempt);

            TaskUtils.player(player, () -> {
                if (player.isOnline()) {
                    player.kickPlayer("Invalid Packet");
                }
            });

            return;
        }

        profile.handleReceive(event);
        //OtherUtility.log(player.getName()+" sent: "+event.getPacketType().getName());
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        final Player player = event.getPlayer();
        if (player == null) return;

        final Profile profile = this.plugin.getProfileManager().getProfile(player);
        if (profile == null) return;

        // Note: ChunkCache is updated globally by ChunkCacheListener (registered once),
        // so we do NOT call processServerPacket() here — doing so would write to the
        // cache once per online player instead of once per packet.

        profile.handleSend(event);


        //OtherUtility.log("Server sent: "+event.getPacketType().getName() + " to "+player.getName());
    }

}
