package me.arrow.utils.versionutils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.viaversion.viaversion.api.Via;
import me.arrow.utils.versionutils.impl.VelocityClientVersionBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;


//version utils for getting correct version, either from viaver, packetevents or velocity
public class VersionUtils {

    private VersionUtils() {
    }

    public static ClientVersion getClientVersion(Player player) {
        if (player == null) {
            return ClientVersion.UNKNOWN;
        }

        ClientVersion bridge = VelocityClientVersionBridge.getClientVersion(player);

        if (isKnown(bridge)) {
            return bridge;
        }

        ClientVersion via = getViaVersion(player);

        if (isKnown(via)) {
            return via;
        }

        ClientVersion packetEvents = getPacketEventsVersion(player);

        if (isKnown(packetEvents)) {
            return packetEvents;
        }

        return ClientVersion.UNKNOWN;
    }

    public static ClientVersion getFallbackClientVersion(Player player) {
        if (player == null) {
            return ClientVersion.UNKNOWN;
        }

        ClientVersion via = getViaVersion(player);

        if (isKnown(via)) {
            return via;
        }

        ClientVersion packetEvents = getPacketEventsVersion(player);

        if (isKnown(packetEvents)) {
            return packetEvents;
        }

        return ClientVersion.UNKNOWN;
    }

    private static ClientVersion getViaVersion(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
            return ClientVersion.UNKNOWN;
        }

        try {
            int protocol = Via.getAPI().getPlayerProtocolVersion(player.getUniqueId()).getVersion();

            if (protocol <= 0) {
                return ClientVersion.UNKNOWN;
            }

            return ClientVersion.getById(protocol);
        } catch (Throwable ignored) {
            return ClientVersion.UNKNOWN;
        }
    }

    private static ClientVersion getPacketEventsVersion(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PacketEvents")) {
            return ClientVersion.UNKNOWN;
        }

        try {
            return PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        } catch (Throwable ignored) {
            return ClientVersion.UNKNOWN;
        }
    }

    private static boolean isKnown(ClientVersion version) {
        return version != null && version != ClientVersion.UNKNOWN;
    }
}