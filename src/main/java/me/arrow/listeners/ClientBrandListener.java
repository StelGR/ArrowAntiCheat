package me.arrow.listeners;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.ExpiringSet;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

// Cleaned up version of NikV2's clientbrand listener, with packetevents 2.12.2 support

public class ClientBrandListener implements Data {

    private final Arrow plugin;
    private final ExpiringSet<UUID> cache = new ExpiringSet<>(5000L);

    public ClientBrandListener(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPlayer() == null) return;

        // Play plugin message. The toString fallback makes this more tolerant if PacketEvents changes phase naming.
        if (!event.getPacketType().equals(PacketType.Play.Client.PLUGIN_MESSAGE)
                && !String.valueOf(event.getPacketType()).toUpperCase(Locale.ROOT).contains("PLUGIN_MESSAGE")) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        WrapperPlayClientPluginMessage payload = new WrapperPlayClientPluginMessage(event);
        String channel = payload.getChannelName();

        if (channel == null || !channel.toLowerCase(Locale.ROOT).endsWith("brand")) {
            return;
        }

        if (this.cache.contains(uuid)) {
            return;
        }

        String brand = decodeBrand(payload.getData());

        if (brand == null || brand.isEmpty()) {
            return;
        }

        this.cache.add(uuid);

        TaskUtils.playerLater(player, 20L, () -> {
            if (!player.isOnline()) return;

            Profile profile = this.plugin.getProfileManager().getProfile(player);

            if (profile == null) {
                this.plugin.getProfileManager().createProfile(player);
                profile = this.plugin.getProfileManager().getProfile(player);
            }

            if (profile == null) return;

            profile.setClient(normalizeBrand(brand));
        });
    }

    private String decodeBrand(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        String decoded = tryReadMinecraftString(data);

        if (decoded == null || decoded.isEmpty()) {
            decoded = new String(data, StandardCharsets.UTF_8);
        }

        decoded = ChatUtils.stripColorCodes(decoded);
        decoded = decoded.replace("\u0000", "");
        decoded = decoded.replace("\r", "");
        decoded = decoded.replace("\n", "");
        decoded = decoded.replaceAll("^[\\p{Cntrl}]+", "");
        decoded = decoded.trim();

        return decoded;
    }

    private String tryReadMinecraftString(byte[] data) {
        try {
            int[] result = readVarInt(data, 0);
            int length = result[0];
            int index = result[1];

            if (length < 0 || index < 0 || index + length > data.length) {
                return null;
            }

            return new String(data, index, length, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int[] readVarInt(byte[] data, int start) {
        int value = 0;
        int position = 0;
        int index = start;

        while (true) {
            if (index >= data.length) {
                throw new IllegalArgumentException("VarInt out of bounds");
            }

            byte currentByte = data[index++];

            value |= (currentByte & 0x7F) << position;

            if ((currentByte & 0x80) == 0) {
                break;
            }

            position += 7;

            if (position >= 32) {
                throw new IllegalArgumentException("VarInt too big");
            }
        }

        return new int[] { value, index };
    }

    private String normalizeBrand(String brand) {
        if (brand == null) return "Unknown";

        String lower = brand.toLowerCase(Locale.ROOT);

        if (lower.contains("lunarclient") || lower.contains("lunar client")) {
            return "Lunar Client";
        }

        if (lower.contains("badlion")) {
            return "Badlion Client";
        }

        if (lower.contains("labymod")) {
            return "LabyMod";
        }

        if (lower.contains("feather")) {
            return "Feather Client";
        }

        if (lower.contains("forge")) {
            return "Forge";
        }

        if (lower.contains("fabric")) {
            return "Fabric";
        }

        if (lower.contains("quilt")) {
            return "Quilt";
        }

        if (lower.contains("salwyrr")) {
            return "Salwyrr";
        }

        if (lower.contains("plc18")) {
            return "PvPLounge";
        }

        if (lower.equalsIgnoreCase("cave client")) {
            return "Cave Client";
        }

        return brand;
    }

    @Override
    public void processSend(PacketSendEvent event) {
    }
}