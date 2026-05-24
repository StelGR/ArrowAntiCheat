package me.arrow.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import io.github.retrooper.packetevents.adventure.serializer.legacy.LegacyComponentSerializer;
import me.arrow.Arrow;
import me.arrow.api.events.AnticheatViolationEvent;
import me.arrow.api.events.VerboseEvent;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.managers.logs.PlayerLog;
import me.arrow.managers.profile.Profile;
import me.arrow.tasks.TickTask;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.OtherUtility;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static me.arrow.utils.ChatUtils.format;
import static me.arrow.utils.customutils.OtherUtility.calculatePercentage;
import static me.arrow.utils.customutils.OtherUtility.translate;

// this is our violation listener, here we run the alerts, and the verbose, verbose can be seen by anyone that has Permissions.VERBOSE
// idk, what else do you want me to say? that alerts have a basic mode that has a total alert VL combined from all checks to show each as a category instead?

public class ViolationListener implements Listener {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacySection();

    private final Arrow plugin;

    private final Map<UUID, Map<String, Integer>> basicAlertVLs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReset = new ConcurrentHashMap<>();

    public ViolationListener(Arrow plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onViolation(AnticheatViolationEvent event) {

        this.plugin.getAlertManager().getAlertExecutor().execute(() -> {

            final Player punishedPlayer = event.getPlayer();

            if (punishedPlayer == null || !punishedPlayer.isOnline()) {
                return;
            }

            final Profile punishedProfile = this.plugin.getProfileManager().getProfile(punishedPlayer);

            if (punishedProfile == null) {
                return;
            }

            final String tps = String.valueOf(TickTask.getTPS());

            final String checkType = event.getType() != null ? event.getType() : "";
            final String checkName = event.getCheck() != null ? event.getCheck() : "";

            final String checkPlusCheckType;

            if (checkType.isEmpty() || checkName.equals(" ")) {
                checkPlusCheckType = checkName;
            } else {
                checkPlusCheckType = checkName + " (" + checkType + ")";
            }

            final boolean experimental = event.isExperimental();
            final String experimentalCheck = experimental ? MsgType.EXPERIMENTAL_SYMBOL.getMessage() + " " : " ";

            final String description = event.getDescription() != null ? event.getDescription() : "";

            final String rawInformationTitle =
                    event.getInformationTitle() != null ? event.getInformationTitle() : "";

            String rawInformation = event.getInformation() != null ? event.getInformation() : "";

            final String informationTitle =
                    MsgType.MAIN_THEME_COLOR.getMessage() + rawInformationTitle;

            final String informationTitleFormatted =
                    MsgType.MAIN_THEME_COLOR.getMessage()
                            + MsgType.HOVER_SYMBOL.getMessage()
                            + " "
                            + rawInformationTitle;

            String informationFormatted = rawInformation;

            final String[] lines = informationFormatted.split("\\n");

            final String prefixFormatted =
                    MsgType.SECOND_THEME_COLOR.getMessage()
                            + " "
                            + MsgType.HOVER_SYMBOL.getMessage()
                            + " ";

            StringBuilder formattedInfoBuilder = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                formattedInfoBuilder.append(prefixFormatted).append(lines[i]);

                if (i < lines.length - 1) {
                    formattedInfoBuilder.append('\n');
                }
            }

            informationFormatted = formattedInfoBuilder.toString();

            final String prefix = MsgType.PREFIX.getMessage();

            StringBuilder normalInfoBuilder = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                normalInfoBuilder.append(prefix).append(lines[i]);

                if (i < lines.length - 1) {
                    normalInfoBuilder.append('\n');
                }
            }

            final String information = normalInfoBuilder.toString();

            final String playerName = punishedPlayer.getName();
            final int vl = event.getVl();

            String composedCheck = checkPlusCheckType + (experimental ? " " + MsgType.EXPERIMENTAL_SYMBOL.getMessage() : "");

            if (vl >= event.getMaxVl()) composedCheck = "&c"+checkPlusCheckType + (experimental ? " " + MsgType.EXPERIMENTAL_SYMBOL.getMessage() : "");

            this.plugin.getLogManager().addLogToQueue(new PlayerLog(
                    playerName,
                    punishedPlayer.getUniqueId().toString(),
                    composedCheck,
                    sanitizeForLog(informationTitleFormatted + "\n" + informationFormatted)
            ));

            final String hoverMessage = MsgType.ALERT_HOVER.getMessage()
                    .replace("%description%", description)
                    .replace("%informationtitleformatted%", informationTitleFormatted)
                    .replace("%informationformatted%", informationFormatted)
                    .replace("%informationtitle%", informationTitle)
                    .replace("%information%", information)
                    .replace("%ping%", String.valueOf(punishedProfile.getConnectionData().getTransPing()))
                    .replace("%tps%", tps);

            final Component hoverComponent = legacy(format(hoverMessage));

            final String alertMessage = MsgType.ALERT_MESSAGE.getMessage();

            final String formattedDebugString =
                    ChatColor.GRAY + "["
                            + MsgType.SECOND_THEME_COLOR.getMessage() + "x"
                            + MsgType.MAIN_THEME_COLOR.getMessage() + "%vl%"
                            + MsgType.SECOND_THEME_COLOR.getMessage() + ", "
                            + MsgType.SECOND_THEME_COLOR.getMessage() + "Ping: "
                            + MsgType.MAIN_THEME_COLOR.getMessage() + "%ping%"
                            + MsgType.SECOND_THEME_COLOR.getMessage() + ", "
                            + MsgType.SECOND_THEME_COLOR.getMessage() + "TPS: "
                            + MsgType.MAIN_THEME_COLOR.getMessage() + "%tps%"
                            + ChatColor.GRAY + "]";

            for (Player staff : Bukkit.getOnlinePlayers()) {

                final Profile staffProfile = this.plugin.getProfileManager().getProfile(staff);

                if (staffProfile == null || !staffProfile.isAlerts()) {
                    continue;
                }

                if (!staff.hasPermission(Permissions.ALERTS.getPermission())) {
                    continue;
                }

                final boolean debug = staff.hasPermission(Permissions.DEBUG.getPermission());
                final boolean hover = staff.hasPermission(Permissions.HOVER.getPermission());

                String displayCheck = checkPlusCheckType + experimentalCheck;

                if (staff.hasPermission(Permissions.BASIC_ALERTS.getPermission()) && !debug) {
                    String category = getCategory(checkName);

                    if (category != null) {
                        addCategoryVL(staff, category);

                        int catVL = getCategoryVL(staff, category);

                        displayCheck = category
                                + ChatColor.DARK_GRAY + " ["
                                + ChatColor.GRAY + "x"
                                + MsgType.MAIN_THEME_COLOR.getMessage() + catVL
                                + ChatColor.DARK_GRAY + "]";
                    }
                }

                final String messageToSend;

                if (debug) {
                    messageToSend = alertMessage
                            .replace("%debug%", formattedDebugString)
                            .replace("%player%", playerName)
                            .replace("%check%", displayCheck)
                            .replace("%vl%", String.valueOf(vl))
                            .replace("%tps%", tps)
                            .replace("%ping%", String.valueOf(punishedProfile.getConnectionData().getTransPing()));
                } else {
                    messageToSend = alertMessage
                            .replace("%debug%", "")
                            .replace("%player%", playerName)
                            .replace("%check%", displayCheck)
                            .replace("%vl%", String.valueOf(vl))
                            .replace("%tps%", tps)
                            .replace("%ping%", String.valueOf(punishedProfile.getConnectionData().getTransPing()));
                }

                Component messageComponent = legacy(format(messageToSend))
                        .clickEvent(ClickEvent.runCommand("/tp " + playerName));

                if (hover) {
                    messageComponent = messageComponent.hoverEvent(HoverEvent.showText(hoverComponent));
                }

                sendChatPacket(staff, messageComponent);
            }

            int frequency;

            try {
                frequency = Config.Setting.WEBHOOK_FREQUENCY.getInt();
            } catch (Exception e) {
                System.out.println("Webhook Frequency from the config seems to be an invalid number or empty");
                return;
            }

            if (frequency < 5) {
                frequency = 5;
            }

            if (Config.Setting.WEBHOOK_ENABLED.getBoolean() && vl % frequency == 0) {
                TaskUtils.taskAsync(() ->
                        sendWebhook(playerName, checkName, checkType, experimental, vl)
                );
            }

            if (Config.Setting.CHECK_SETTINGS_ALERT_CONSOLE.getBoolean()) {
                OtherUtility.log(translate(
                        MsgType.PREFIX.getMessage()
                                + MsgType.MAIN_THEME_COLOR.getMessage()
                                + playerName
                                + MsgType.SECOND_THEME_COLOR.getMessage()
                                + " failed "
                                + MsgType.MAIN_THEME_COLOR.getMessage()
                                + checkPlusCheckType
                                + experimentalCheck
                                + MsgType.SECOND_THEME_COLOR.getMessage()
                                + "x"
                                + vl
                ));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVerbose(VerboseEvent event) {

        final Player punishedPlayer = event.getPlayer();

        if (punishedPlayer == null || !punishedPlayer.isOnline()) {
            return;
        }

        final Profile punishedProfile = this.plugin.getProfileManager().getProfile(punishedPlayer);

        if (punishedProfile == null) {
            return;
        }

        final String tps = String.valueOf(TickTask.getTPS());

        final String checkType = event.getType() != null ? event.getType() : "";
        final String checkName = event.getCheckName() != null ? event.getCheckName() : "";

        final String checkPlusCheckType = checkName + " (" + checkType + ")";

        final String information = event.getInformation() != null ? event.getInformation() : "";

        final String playerName = punishedPlayer.getName();

        final double vl = event.getVl();
        final double maxVL = event.getMaxVl();

        final String hoverMessage = "%information%\n Ping: %ping%, TPS: %tps%"
                .replace("%information%", information)
                .replace("%ping%", String.valueOf(punishedProfile.getConnectionData().getTransPing()))
                .replace("%tps%", tps);

        final String formattedDebugString =
                ChatColor.DARK_GRAY + " ("
                        + MsgType.MAIN_THEME_COLOR.getMessage()
                        + calculatePercentage(vl, maxVL)
                        + ChatColor.DARK_GRAY + ") "
                        + ChatColor.DARK_GRAY + "("
                        + MsgType.MAIN_THEME_COLOR.getMessage()
                        + vl
                        + MsgType.SECOND_THEME_COLOR.getMessage()
                        + "/"
                        + MsgType.MAIN_THEME_COLOR.getMessage()
                        + maxVL
                        + ChatColor.DARK_GRAY + ")";

        final String alertMessage = "&6%player% &7verbosed &6%check%%debug%"
                .replace("%player%", playerName)
                .replace("%debug%", formattedDebugString)
                .replace("%check%", checkPlusCheckType);

        final Component hoverComponent = legacy(format(hoverMessage));

        final Component messageComponent = legacy(format(alertMessage))
                .hoverEvent(HoverEvent.showText(hoverComponent))
                .clickEvent(ClickEvent.runCommand("/tp " + playerName));

        for (Player staff : Bukkit.getOnlinePlayers()) {

            final Profile staffProfile = this.plugin.getProfileManager().getProfile(staff);

            if (staffProfile == null || !staffProfile.isAlerts()) {
                continue;
            }

            if (!staff.hasPermission(Permissions.VERBOSE.getPermission())) {
                continue;
            }

            sendChatPacket(staff, messageComponent);
        }
    }

    private void sendChatPacket(Player player, Component component) {
        if (player == null || !player.isOnline()) {
            return;
        }

        try {
            PacketWrapper<?> packet;

            ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();

            if (serverVersion.isNewerThanOrEquals(ServerVersion.V_1_19)) {
                packet = new WrapperPlayServerSystemChatMessage(false, component);
            } else {
                packet = new WrapperPlayServerChatMessage(
                        new ChatMessageLegacy(component, ChatTypes.CHAT)
                );
            }

            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        } catch (Throwable throwable) {
            TaskUtils.task(() -> {
                if (player.isOnline()) {
                    player.spigot().sendMessage(
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                    LEGACY_SERIALIZER.serialize(component)
                            )
                    );
                }
            });
        }
    }

    private Component legacy(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        return LEGACY_SERIALIZER.deserialize(input);
    }

    private String getCategory(String checkName) {
        if (checkName == null || checkName.isEmpty()) {
            return null;
        }

        String lower = checkName.toLowerCase();

        if (lower.startsWith("killaura")
                || lower.startsWith("aim")
                || lower.startsWith("autoclicker")
                || lower.startsWith("velocity")) {
            return "Combat Analysis";
        }

        if (lower.startsWith("speed")
                || lower.startsWith("fly")
                || lower.startsWith("motion")
                || lower.startsWith("elytra")
                || lower.startsWith("omnisprint")
                || lower.startsWith("noslowdown")) {
            return "Movement Analysis";
        }

        if (lower.startsWith("ground")
                || lower.startsWith("scaffold")
                || lower.startsWith("interact")
                || lower.startsWith("phase")) {
            return "World Analysis";
        }

        if (lower.startsWith("timer")
                || lower.startsWith("badpackets")) {
            return "Player Analysis";
        }

        return null;
    }

    private void sendWebhook(String player, String check, String type, boolean experimental, int vl) {
        try {
            String webhookUrl = Config.Setting.WEBHOOK_LINK.getString();

            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                System.out.println("Invalid webhook URL, failed to send alert message. please check your configuration");
                return;
            }

            URL url;

            try {
                url = new URL(webhookUrl);
            } catch (Exception e) {
                System.out.println("Invalid webhook URL, failed to send alert message. please check your configuration");
                return;
            }

            String safePlayer = escapeJson(player);
            String safeCheck = escapeJson(check);
            String safeType = escapeJson(type);

            String title = "Player failed " + safeCheck + " | " + safePlayer;

            String json = "{"
                    + "\"embeds\": [{"
                    + "\"title\": \"" + title + "\","
                    + "\"description\": \"**" + safePlayer + "** failed **" + safeCheck + " " + safeType
                    + (experimental ? " " + OtherUtility.stripColorCodes(OtherUtility.translate(MsgType.EXPERIMENTAL_SYMBOL.getMessage())) : "")
                    + "** x" + vl + "\","
                    + "\"color\": 16711680"
                    + "}]"
                    + "}";

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            try {
                conn.getInputStream().close();
            } catch (Exception ignored) {
                if (conn.getErrorStream() != null) {
                    conn.getErrorStream().close();
                }
            }

            conn.disconnect();
        } catch (Exception ignored) {
            System.out.println("Invalid webhook URL, failed to send alert message. please check your configuration");
        }
    }

    public void addCategoryVL(Player player, String category) {
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();

        lastReset.putIfAbsent(uuid, now);

        if (now - lastReset.get(uuid) >= 60000L) {
            basicAlertVLs.put(uuid, new ConcurrentHashMap<>());
            lastReset.put(uuid, now);
        }

        basicAlertVLs.putIfAbsent(uuid, new ConcurrentHashMap<>());

        Map<String, Integer> vlMap = basicAlertVLs.get(uuid);

        vlMap.put(category, vlMap.getOrDefault(category, 0) + 1);
    }

    public int getCategoryVL(Player player, String category) {
        return basicAlertVLs
                .getOrDefault(player.getUniqueId(), Collections.emptyMap())
                .getOrDefault(category, 0);
    }

    private String sanitizeForLog(String input) {
        if (input == null) {
            return "";
        }

        String out = ChatColor.stripColor(input);

        out = out.replace("\r", "");
        out = out.trim();

        final int max = 1500;

        if (out.length() > max) {
            out = out.substring(0, max);
        }

        return out;
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }

        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}