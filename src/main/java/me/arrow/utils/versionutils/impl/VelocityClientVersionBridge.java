package me.arrow.utils.versionutils.impl;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VelocityClientVersionBridge implements PluginMessageListener {

    private static final String MODERN_CHANNEL = "arrow:version";
    private static final String LEGACY_CHANNEL = "ArrowVersion";

    private static final Map<UUID, ClientVersion> CLIENT_VERSIONS = new ConcurrentHashMap<>();

    public static void register(Plugin plugin) {
        VelocityClientVersionBridge listener = new VelocityClientVersionBridge();

        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, MODERN_CHANNEL, listener);
            Bukkit.getLogger().info("[ArrowVersionBridge] Registered incoming channel " + MODERN_CHANNEL);
        } catch (Throwable throwable) {
            Bukkit.getLogger().warning("[ArrowVersionBridge] Failed to register " + MODERN_CHANNEL + ": " + throwable.getMessage());
        }

        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, LEGACY_CHANNEL, listener);
            Bukkit.getLogger().info("[ArrowVersionBridge] Registered incoming channel " + LEGACY_CHANNEL);
        } catch (Throwable throwable) {
            Bukkit.getLogger().warning("[ArrowVersionBridge] Failed to register " + LEGACY_CHANNEL + ": " + throwable.getMessage());
        }
    }

    public static void unregister(Plugin plugin) {
        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, MODERN_CHANNEL);
        } catch (Throwable ignored) {
        }

        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, LEGACY_CHANNEL);
        } catch (Throwable ignored) {
        }

        CLIENT_VERSIONS.clear();
    }

    public static void remove(Player player) {
        if (player != null) {
            CLIENT_VERSIONS.remove(player.getUniqueId());
        }
    }

    public static boolean hasVersion(Player player) {
        return player != null && CLIENT_VERSIONS.containsKey(player.getUniqueId());
    }

    public static ClientVersion getClientVersion(Player player) {
        if (player == null) {
            return ClientVersion.UNKNOWN;
        }

        return CLIENT_VERSIONS.getOrDefault(player.getUniqueId(), ClientVersion.UNKNOWN);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (message == null) {
            return;
        }

        if (!channel.equalsIgnoreCase(MODERN_CHANNEL) && !channel.equalsIgnoreCase(LEGACY_CHANNEL)) {
            return;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            UUID uuid = UUID.fromString(in.readUTF());
            int protocol = in.readInt();

            if (!uuid.equals(player.getUniqueId()) || protocol <= 0) {
                return;
            }

            ClientVersion version = ClientVersion.getById(protocol);

            CLIENT_VERSIONS.put(uuid, version);

            Bukkit.getLogger().info("[ArrowVersionBridge] Received protocol " + protocol + " -> "
                    + version.getReleaseName() + " for " + player.getName() + " through " + channel);
        } catch (Throwable throwable) {
            Bukkit.getLogger().warning("[ArrowVersionBridge] Failed to read version message: " + throwable.getMessage());
        }
    }
}