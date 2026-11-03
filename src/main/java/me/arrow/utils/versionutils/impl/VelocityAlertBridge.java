package me.arrow.utils.versionutils.impl;

import com.google.common.collect.Iterables;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
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
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VelocityAlertBridge implements PluginMessageListener {

    private static final String MODERN_CHANNEL = "arrow:alerts";
    private static final String LEGACY_CHANNEL = "ArrowAlerts";

    private static Plugin pluginInstance;
    private static VelocityAlertBridge listenerInstance;
    private static final Map<String, Long> RECENT_FORWARDED_ALERTS = new ConcurrentHashMap<>();

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
        RECENT_FORWARDED_ALERTS.clear();
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
            sendPayload(messenger, data);
        } catch (Throwable ignored) {
        }
    }

    public static void sendPreference(Player player, boolean enabled) {
        if (pluginInstance == null || player == null || !player.isOnline()) return;

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("ALERT_PREFERENCE");
            out.writeUTF(player.getUniqueId().toString());
            out.writeBoolean(enabled);
            out.flush();
            sendPayload(player, bytes.toByteArray());
        } catch (Throwable ignored) {
        }
    }

    public static void syncOnlinePreferences() {
        if (pluginInstance == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = Arrow.getInstance() == null || Arrow.getInstance().getProfileManager() == null
                    ? null
                    : Arrow.getInstance().getProfileManager().getProfile(player);
            if (profile != null) {
                sendPreference(player, profile.isVelocityAlerts());
            }
        }
    }

    private static void sendPayload(Player player, byte[] data) {
        String channel = MODERN_CHANNEL;
        try {
            if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_13)) {
                channel = LEGACY_CHANNEL;
            }
        } catch (Throwable ignored) {
        }
        player.sendPluginMessage(pluginInstance, channel, data);
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

            Set<UUID> deliveredPlayerIds = new HashSet<>();
            boolean hasRecipientList = in.available() >= Integer.BYTES;
            if (hasRecipientList) {
                int recipientCount = in.readInt();
                if (recipientCount < 0 || recipientCount > 10000) {
                    throw new IllegalArgumentException("Invalid proxy alert recipient count: " + recipientCount);
                }
                for (int i = 0; i < recipientCount; i++) {
                    deliveredPlayerIds.add(UUID.fromString(in.readUTF()));
                }
            }

            if (isDuplicateForward(serverDisplay, playerUuidStr, checkName, checkType, vl)) {
                return;
            }

            String colored = ChatUtils.format(formattedMessage);

            // 1. Print alert to this server's console
            Bukkit.getConsoleSender().sendMessage(colored);

            // 2. Broadcast to operators or players with arrow.alerts on this server
            for (Player online : Bukkit.getOnlinePlayers()) {
                Profile profile = Arrow.getInstance() == null || Arrow.getInstance().getProfileManager() == null
                        ? null
                        : Arrow.getInstance().getProfileManager().getProfile(online);
                if (profile != null && !profile.isVelocityAlerts()) {
                    continue;
                }

                boolean isOp = online.isOp();
                boolean hasPerm = online.hasPermission("arrow.alerts");

                if (hasRecipientList) {
                    if (!deliveredPlayerIds.contains(online.getUniqueId()) && (isOp || hasPerm)) {
                        online.sendMessage(colored);
                    }
                    continue;
                }

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

    private static boolean isDuplicateForward(String server, String playerUuid, String check,
                                              String checkType, int vl) {
        long now = System.currentTimeMillis();
        RECENT_FORWARDED_ALERTS.entrySet().removeIf(entry -> now - entry.getValue() > 1000L);

        String key = server + '|' + playerUuid + '|' + check + '|' + checkType + '|' + vl;
        Long previous = RECENT_FORWARDED_ALERTS.put(key, now);
        return previous != null && now - previous <= 1000L;
    }
}
