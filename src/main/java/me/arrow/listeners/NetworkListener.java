package me.arrow.listeners;


import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.MoveUtils;
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

        final PacketTypeCommon packet = event.getPacketType();

        final String crashAttempt = checkCrasher(packet, event);

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

        if (TaskUtils.isFoliaServer()) {
            TaskUtils.player(player, () -> {
                if (!player.isOnline()) return;
                profile.handleReceive(event);
            });
        } else {
            profile.handleReceive(event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        final Player player = event.getPlayer();
        if (player == null) return;

        final Profile profile = this.plugin.getProfileManager().getProfile(player);
        if (profile == null) return;

        if (TaskUtils.isFoliaServer()) {
            TaskUtils.player(player, () -> {
                if (!player.isOnline()) return;
                profile.handleSend(event);
            });
        } else {
            profile.handleSend(event);
        }
    }

    private String checkCrasher(PacketTypeCommon packet, PacketReceiveEvent event) {


        double x = 0D, y = 0D, z = 0D;
        float yaw = 0F, pitch = 0F;

        if (packet.equals(PacketType.Play.Client.PLAYER_POSITION)) {
            WrapperPlayClientPlayerPosition pos = new WrapperPlayClientPlayerPosition(event);

            x = Math.abs(pos.getPosition().getX());
            y = Math.abs(pos.getPosition().getY());
            z = Math.abs(pos.getPosition().getZ());
        } else if (packet.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            WrapperPlayClientPlayerPositionAndRotation posLook = new WrapperPlayClientPlayerPositionAndRotation(event);

            x = Math.abs(posLook.getPosition().getX());
            y = Math.abs(posLook.getPosition().getY());
            z = Math.abs(posLook.getPosition().getZ());
            yaw = Math.abs(posLook.getYaw());
            pitch = Math.abs(posLook.getPitch());
        } else if (packet.equals(PacketType.Play.Client.PLAYER_ROTATION)) {
            WrapperPlayClientPlayerRotation look = new WrapperPlayClientPlayerRotation(event);

            yaw = Math.abs(look.getYaw());
            pitch = Math.abs(look.getPitch());
        }

        final double invalidValue = 3.0E7D;

        //This messes our threading system and potentially causes damage to the server.
        final boolean invalid = x > invalidValue || y > invalidValue || z > invalidValue
                || yaw > 3.4028235e+35F
                || pitch > MoveUtils.MAXIMUM_PITCH;

        //It's impossible for these values to be NaN or Infinite.
        final boolean impossible = !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(yaw)
                || !Float.isFinite(pitch);

        if (invalid || impossible) {
            return "X: " + x + " Y: " + y + " Z: " + z + " Yaw: " + yaw + " Pitch: " + pitch;
        }

        return null;
    }
}