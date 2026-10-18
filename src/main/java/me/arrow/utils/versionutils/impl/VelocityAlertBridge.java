package me.arrow.utils.versionutils.impl;

import com.google.common.collect.Iterables;
import me.arrow.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.UUID;

public class VelocityAlertBridge implements PluginMessageListener {

    private static final String MODERN_CHANNEL = "arrow:alerts";
    private static final String LEGACY_CHANNEL = "ArrowAlerts";

    private static Plugin pluginInstance;
    private static VelocityAlertBridge listenerInstance;

    public static void register(Plugin plugin) {
        pluginInstance = plugin;
        listenerInstance = new VelocityAlertBridge();

        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, MODERN_CHANNEL, listenerInstance);
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, MODERN_CHANNEL);
        } catch (Throwable ignored) {
        }

        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, LEGACY_CHANNEL, listenerInstance);
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, LEGACY_CHANNEL);
        } catch (Throwable ignored) {
        }
    }

    public static void unregister(Plugin plugin) {
        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, MODERN_CHANNEL);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, MODERN_CHANNEL);
        } catch (Throwable ignored) {
        }

        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, LEGACY_CHANNEL);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, LEGACY_CHANNEL);
        } catch (Throwable ignored) {
        }

        pluginInstance = null;
        listenerInstance = null;
    }

    /**
     * Sends an alert to the Velocity proxy to be broadcast across other servers.
     */
    public static void sendAlert(String serverName,
                                 UUID playerUuid,
                                 String playerName,
                                 String checkName,
                                 String checkType,
                                 int vl,
                                 int maxVl,
                                 String ping,
                                 String tps,
                                 String mainColor,
                                 String secondColor) {
        if (pluginInstance == null) {
            return;
        }

        Player messenger = playerUuid != null ? Bukkit.getPlayer(playerUuid) : null;
        if (messenger == null || !messenger.isOnline()) {
            messenger = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
        }

        if (messenger == null) {
            return;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);

            out.writeUTF("ALERT");
            out.writeUTF(serverName != null ? serverName : "Server");
            out.writeUTF(playerUuid != null ? playerUuid.toString() : "");
            out.writeUTF(playerName != null ? playerName : "Unknown");
            out.writeUTF(checkName != null ? checkName : "Check");
            out.writeUTF(checkType != null ? checkType : "");
            out.writeInt(vl);
            out.writeInt(maxVl);
            out.writeUTF(ping != null ? ping : "0");
            out.writeUTF(tps != null ? tps : "20.0");
            out.writeUTF(mainColor != null ? mainColor : "&6");
            out.writeUTF(secondColor != null ? secondColor : "&7");
            out.flush();

            byte[] data = bytes.toByteArray();
            try {
                messenger.sendPluginMessage(pluginInstance, MODERN_CHANNEL, data);
            } catch (Throwable ignored) {
            }
            try {
                messenger.sendPluginMessage(pluginInstance, LEGACY_CHANNEL, data);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
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
            String subChannel = in.readUTF();

            if (!"FORWARD_ALERT".equals(subChannel)) {
                return;
            }

            String serverDisplay = in.readUTF();
            String playerUuidStr = in.readUTF();
            String playerName = in.readUTF();
            String checkName = in.readUTF();
            String checkType = in.readUTF();
            int vl = in.readInt();
            int maxVl = in.readInt();
            String ping = in.readUTF();
            String tps = in.readUTF();
            String mainColor = in.readUTF();
            String secondColor = in.readUTF();
            String formattedMessage = in.readUTF();
            boolean deliveredToProxyPlayers = in.readBoolean();

            String colored = ChatUtils.format(formattedMessage);

            // 1. Print alert to this server's console
            Bukkit.getConsoleSender().sendMessage(colored);

            // 2. Broadcast to operators or players with arrow.alerts on this server
            for (Player online : Bukkit.getOnlinePlayers()) {
                boolean isOp = online.isOp();
                boolean hasPerm = online.hasPermission("arrow.alerts");

                // If Velocity already delivered to players with arrow.alerts via proxy chat,
                // only deliver here to local OPs who do NOT have proxy arrow.alerts permission
                // to prevent double messages.
                if (deliveredToProxyPlayers) {
                    if (isOp && !hasPerm) {
                        online.sendMessage(colored);
                    }
                } else {
                    if (isOp || hasPerm) {
                        online.sendMessage(colored);
                    }
                }
            }

        } catch (Throwable throwable) {
            Bukkit.getLogger().warning("[ArrowAlertBridge] Failed to read forwarded alert: " + throwable.getMessage());
        }
    }
}
