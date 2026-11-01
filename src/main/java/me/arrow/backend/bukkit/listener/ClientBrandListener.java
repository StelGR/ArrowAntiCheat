package me.arrow.backend.bukkit.listener;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.backend.bukkit.PlatformBackend;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.logs.PlayerLog;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.OtherUtility;
import me.arrow.utils.versionutils.VersionUtils;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

// Cleaned up version of NikV2's clientbrand listener, with packetevents 2.12.2 support

public class ClientBrandListener implements Data {

    private final Arrow plugin;
    private final Profile profile;

    boolean sentRegisterChannel;
    boolean sentBrand;
    boolean kicked;

    // ViaVersion client version verification
    int proxyClientProtocol = -1;
    int modClientProtocol = -1;
    int appClientProtocol = -1;


    public ClientBrandListener(Arrow plugin, Profile profile) {
        this.plugin = plugin;
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPlayer() == null) return;

        // Play plugin message. The toString fallback makes this more tolerant if PacketEvents changes phase naming.
        if (!event.getPacketType().equals(PacketType.Play.Client.PLUGIN_MESSAGE)) {
            return;
        }

        Player player = event.getPlayer();
        Profile prof = this.profile != null ? this.profile : this.plugin.getProfileManager().getProfile(player);

        WrapperPlayClientPluginMessage payload = new WrapperPlayClientPluginMessage(event);
        String channel = payload.getChannelName();
        String channelLower = channel.toLowerCase(Locale.ROOT);

        boolean isNamespacedRegister = channel.equals("minecraft:register");
        boolean isLegacyRegister = channel.equalsIgnoreCase("REGISTER");
        boolean isRegister = isNamespacedRegister || isLegacyRegister;

        boolean isLegacyBrand = channel.equals("MC|Brand") || channel.equals("MC|BOpen");
        boolean isModernBrand = channel.equals("minecraft:brand");
        boolean isBrandChannel = isLegacyBrand || isModernBrand || (channelLower.endsWith("brand") && !isRegister) || channelLower.contains(":brand");


        // 1. Detect ViaVersion client-side version spoofing.
        //
        // IMPORTANT:
        // PacketEvents on a Velocity backend may report the translated backend
        // protocol rather than the client's original protocol.
        //
        // vv:proxy_details = Original client protocol observed by Velocity.
        // vv:mod_details   = Protocol reported by ViaFabric / ViaForge / ViaFabricPlus.
        // vv:app_details   = Protocol reported by standalone Via applications.
        //
        // These payloads can arrive in any order, so store them and compare
        // whenever both the proxy and client details are available.

        if (channel.equals("vv:proxy_details")) {

            int protocol = parseRealProtocolFromDetails(
                    new String(payload.getData(), StandardCharsets.UTF_8)
            );

            if (protocol > 0) {
                this.proxyClientProtocol = protocol;

                // Check any client details that arrived before the proxy details.
                if (this.modClientProtocol > 0 &&
                        checkReportedProtocol(player, event, "vv:mod_details", this.modClientProtocol)) {
                    return;
                }

                if (this.appClientProtocol > 0 &&
                        checkReportedProtocol(player, event, "vv:app_details", this.appClientProtocol)) {
                    return;
                }
            }

            return;
        }

        // Client-side ViaFabric / ViaForge / ViaFabricPlus details.
        if (channel.equals("vv:mod_details")) {

            int protocol = parseRealProtocolFromDetails(
                    new String(payload.getData(), StandardCharsets.UTF_8)
            );

            if (protocol > 0) {
                this.modClientProtocol = protocol;

                if (checkReportedProtocol(player, event, channel, protocol)) {
                    return;
                }
            }

            return;
        }

        // Standalone ViaProxy application details.
        if (channel.equals("vv:app_details")) {

            int protocol = parseRealProtocolFromDetails(
                    new String(payload.getData(), StandardCharsets.UTF_8)
            );

            if (protocol > 0) {
                this.appClientProtocol = protocol;

                if (checkReportedProtocol(player, event, channel, protocol)) {
                    return;
                }
            }

            return;
        }


        // 2. Direct channel check for legacy 1.8 FML channels sent by a player claiming modern 26.3
        // If a player claims to be modern 26.3 / 1.20+, but sends legacy 1.8 Forge channels, they are running ViaForge on 1.8!
        if (isVersion26_3(prof, player)) {
            if (channel.equals("FML|HS") || channel.equals("FML|MP") || channel.equals("FML")) {
                kick(player, event, "Claiming modern version but sent legacy 1.8 Forge channel (ViaForge): " + channel);
                return;
            }
        }

        // 3. Inspect registered channels for client-side Via or version spoofing tools
        if (isRegister) {
            this.sentRegisterChannel = true;
            String registeredChannels = new String(payload.getData(), StandardCharsets.UTF_8);
            String regLower = registeredChannels.toLowerCase(Locale.ROOT);

            // Only kick if the player is claiming modern (26.3) while running 1.8 Forge / ViaForge!
            if (isVersion26_3(prof, player)) {
                // A) Check for 1.8 Forge / FML channel leaks when claiming 26.3 / modern
                // ViaForge on 1.8 Forge registers legacy 1.8 FML channels (FML|HS, FML|MP, FML).
                // A genuine modern 26.3 client will NEVER register legacy 1.8 FML channels.
                if (registeredChannels.contains("FML|HS") || registeredChannels.contains("FML|MP") || registeredChannels.contains("FML")) {
                    kick(player, event, "Claiming modern version but registered legacy 1.8 Forge channels (ViaForge leak): " + registeredChannels);
                    return;
                }

                // B) Check if client registers Via client channels while claiming modern
                if (regLower.contains("vv:mod_details") || regLower.contains("vv:app_details")) {
                    kick(player, event, "Claiming modern version but registered ViaForge/ViaFabric channel: " + registeredChannels);
                    return;
                }
            }

            //return; // Channel registration packet is not a brand payload; don't decode it as brand!
        }

        // 4. Handle client brand
        if (isBrandChannel) {
            this.sentBrand = true;
            String brand = decodeBrand(payload.getData());

            if (brand == null || brand.isEmpty()) {
                return;
            }

            String brandLower = brand.toLowerCase(Locale.ROOT);
            if (isVersion26_3(prof, player) && (brandLower.contains("viaforge") || brandLower.contains("viafabric") || brandLower.contains("viaproxy"))) {
                kick(player, event, "Detected spoofed client brand: " + brand);
                return;
            }

        }

        if (!player.isOnline()) return;

        Profile p = prof != null ? prof : this.plugin.getProfileManager().getProfile(player);
        if (p == null) {
            this.plugin.getProfileManager().createProfile(player);
            p = this.plugin.getProfileManager().getProfile(player);
        }

        if (p == null) return;

        p.setClient(normalizeBrand(decodeBrand(payload.getData())));
    }

    private boolean isVersion26_3(Profile prof, Player player) {
        if (prof != null && prof.getVersion() != null) {
            ClientVersion v = prof.getVersion();
            if (v == ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS) return true;
            if (v.isNewerThanOrEquals(ClientVersion.V_1_20_3)) return true;
            String name = v.name().toLowerCase(Locale.ROOT);
            if (name.contains("26") || name.contains("20_3") || name.contains("20_4") || name.contains("20_5") || name.contains("21")) {
                return true;
            }
        }
        ClientVersion fallback = VersionUtils.getClientVersion(player);
        if (fallback != null) {
            if (fallback == ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS) return true;
            if (fallback.isNewerThanOrEquals(ClientVersion.V_1_20_3)) return true;
            String name = fallback.name().toLowerCase(Locale.ROOT);
            if (name.contains("26") || name.contains("20_3") || name.contains("20_4") || name.contains("20_5") || name.contains("21")) {
                return true;
            }
        }
        int protocol = VersionUtils.getProtocolVersion(player);
        // Protocols: 1.20.2 is 764, 1.20.3 is 765, 1.21 is 767, 26.3 is 777
        return protocol >= 764;
    }

    private int parseRealProtocolFromDetails(String json) {
        if (json == null) return -1;
        try {
            int idx = json.indexOf("\"version\":");
            if (idx == -1) {
                idx = json.indexOf("\"version\" :");
            }
            if (idx != -1) {
                int start = idx + 10;
                while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == ':')) {
                    start++;
                }
                int end = start;
                while (end < json.length() && Character.isDigit(json.charAt(end))) {
                    end++;
                }
                if (end > start) {
                    return Integer.parseInt(json.substring(start, end));
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private void kick(Player player, PacketReceiveEvent event, String reason) {
        if (this.kicked || player == null || !player.isOnline()) {
            return;
        }
        this.kicked = true;

        if (event != null) {
            event.setCancelled(true);
        }

        ChatUtils.log("Kicking " + player.getName() + " for invalid version / spoofing: " + reason);

        try {
            Arrow.getInstance().getLogManager().addLogToQueue(new PlayerLog(
                    player.getName(),
                    player.getUniqueId().toString(),
                    "Client Brand Listener",
                    ChatColor.stripColor("&cInvalid Version: " + reason)
            ));

            for (Player staff : PlatformBackend.get().getServer().getOnlinePlayers()) {
                if (staff == null) continue;

                final Profile staffProfile = Arrow.getInstance().getProfileManager().getProfile(staff);

                if (staffProfile == null || !staffProfile.isAlerts()) {
                    continue;
                }

                if (!staff.hasPermission(Permissions.ALERTS.getPermission())) {
                    continue;
                }

                staff.sendMessage(OtherUtility.translate(MsgType.PREFIX.getMessage() + player.getDisplayName() + MsgType.MAIN_THEME_COLOR.getMessage() + " was kicked due to invalid version."));
            }
        } catch (Throwable e) {
            OtherUtility.log("Failed to kick/log invalid version: " + e.getMessage());
        }

        Profile prof = this.profile != null ? this.profile : this.plugin.getProfileManager().getProfile(player);
        if (prof != null) {
            prof.kick("Invalid Version");
        } else {
            TaskUtils.player(player, () -> {
                if (player.isOnline()) {
                    player.kickPlayer("Invalid Version");
                }
            });
        }
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
        decoded = decoded.replaceAll("^\\p{Cntrl}+", "");
        decoded = decoded.trim();

        return decoded;
    }

    private String tryReadMinecraftString(byte[] data) {
        try {
            int[] result = readVarInt(data, 0);
            int length = result[0];
            int index = result[1];

            if (length < 0 || index < 0) {
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

        if (lower.contains("lunar") || lower.contains("lunarclient") || lower.contains("lunar client")) {
            return "Lunar Client";
        }

        if (lower.contains("badlion")) {
            return "Badlion Client";
        }

        if (lower.contains("laby")) {
            return "LabyMod";
        }

        if (lower.contains("feather")) {
            return "Feather Client";
        }

        if (lower.contains("forg")) {
            return "Forge";
        }

        if (lower.contains("fabri")) {
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

        if (lower.contains("baritone")) {
            return "Baritone";
        }

        return brand;
    }


    private boolean checkReportedProtocol(
            Player player,
            PacketReceiveEvent event,
            String source,
            int reportedProtocol
    ) {

        // We cannot verify the client report until Velocity has supplied
        // its authoritative observation of the original client protocol.
        if (this.proxyClientProtocol <= 0 || reportedProtocol <= 0) {
            return false;
        }

        // The client-reported protocol matches what Velocity observed.
        if (reportedProtocol == this.proxyClientProtocol) {
            return false;
        }

        kick(
                player,
                event,
                "Version spoof detected via " + source
                        + " (claimed=" + reportedProtocol
                        + ", actual=" + this.proxyClientProtocol + ")"
        );

        return true;
    }


    @Override
    public void processSend(PacketSendEvent event) {

    }
}
