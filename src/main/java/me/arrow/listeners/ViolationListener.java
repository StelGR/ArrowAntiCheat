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
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static me.arrow.utils.ChatUtils.format;
import static me.arrow.utils.customutils.OtherUtility.calculatePercentage;
import static me.arrow.utils.customutils.OtherUtility.translate;

// This listener keeps alert creation and fan-out away from packet/player threads.
public class ViolationListener implements Listener {

    static LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacySection();

    Arrow plugin;
    boolean systemChatPackets;

    Map<UUID, Map<String, Integer>> basicAlertVLs = new ConcurrentHashMap<>();
    Map<UUID, Long> lastReset = new ConcurrentHashMap<>();

    public ViolationListener(Arrow plugin) {
        this.plugin = plugin;

        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        this.systemChatPackets = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_19);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onViolation(AnticheatViolationEvent event) {
        ViolationAlert alert = ViolationAlert.from(event);
        this.plugin.getAlertManager().queueAlert(() -> processViolation(alert));
    }

    private void processViolation(ViolationAlert alert) {
        Player punishedPlayer = alert.player;

        if (punishedPlayer == null || !punishedPlayer.isOnline()) {
            return;
        }

        Profile punishedProfile = this.plugin.getProfileManager().getProfile(punishedPlayer);

        if (punishedProfile == null) {
            return;
        }

        String tps = String.valueOf(TickTask.getTPS());
        String ping = String.valueOf(punishedProfile.getConnectionData().getTransPing());
        String checkType = alert.checkType;
        String checkName = alert.checkName;
        String checkCategory = alert.checkCategory;

        punishedProfile.setLastFlaggedCheck(checkName);

        String checkPlusCheckType;

        if (checkType.isEmpty() || checkName.equals(" ")) {
            checkPlusCheckType = checkName;
        } else {
            checkPlusCheckType = checkName + " (" + checkType + ")";
        }

        boolean experimental = alert.experimental;
        String experimentalCheck = experimental ? MsgType.EXPERIMENTAL_SYMBOL.getMessage() + " " : " ";
        String experimentalFormat = experimental ? MsgType.EXPERIMENTAL_SYMBOL.getMessage() : "";
        String informationTitle = MsgType.MAIN_THEME_COLOR.getMessage() + alert.informationTitle;
        String informationTitleFormatted =
                MsgType.MAIN_THEME_COLOR.getMessage()
                        + MsgType.HOVER_SYMBOL.getMessage()
                        + " "
                        + alert.informationTitle;

        String prefixFormatted =
                MsgType.SECOND_THEME_COLOR.getMessage()
                        + " "
                        + MsgType.HOVER_SYMBOL.getMessage()
                        + " ";

        String informationFormatted = prefixLines(alert.information, prefixFormatted);
        String information = prefixLines(alert.information, MsgType.PREFIX.getMessage());
        String playerName = punishedPlayer.getName();
        int vl = alert.vl;
        int maxvl = alert.maxVl;

        String composedCheck = checkPlusCheckType
                + (experimental ? " " + MsgType.EXPERIMENTAL_SYMBOL.getMessage() : "");

        if (vl >= maxvl) {
            composedCheck = "&c" + checkPlusCheckType
                    + (experimental ? " " + MsgType.EXPERIMENTAL_SYMBOL.getMessage() : "");
        }

        this.plugin.getLogManager().addLogToQueue(new PlayerLog(
                playerName,
                punishedPlayer.getUniqueId().toString(),
                composedCheck,
                sanitizeForLog(informationTitleFormatted + "\n" + informationFormatted)
        ));

        String hoverMessage = MsgType.ALERT_HOVER.getMessage()
                .replace("%description%", alert.description)
                .replace("%informationtitleformatted%", informationTitleFormatted)
                .replace("%informationformatted%", informationFormatted)
                .replace("%informationtitle%", informationTitle)
                .replace("%information%", information)
                .replace("%ping%", ping)
                .replace("%tps%", tps);

        Component hoverComponent = legacy(format(hoverMessage));
        String alertMessage = MsgType.ALERT_MESSAGE.getMessage();
        String formattedDebugString =
                MsgType.SECOND_THEME_COLOR.getMessage() + "x"
                        + MsgType.MAIN_THEME_COLOR.getMessage() + "%vl%"
                        + MsgType.SECOND_THEME_COLOR.getMessage() + ", "
                        + MsgType.SECOND_THEME_COLOR.getMessage() + "Ping: "
                        + MsgType.MAIN_THEME_COLOR.getMessage() + "%ping%"
                        + MsgType.SECOND_THEME_COLOR.getMessage() + ", "
                        + MsgType.SECOND_THEME_COLOR.getMessage() + "TPS: "
                        + MsgType.MAIN_THEME_COLOR.getMessage() + "%tps%";

        String fullDisplayCheck = checkPlusCheckType + experimentalCheck;
        String basicCategory = getCategory(checkName);
        Map<String, String> basicMessages = new HashMap<>();
        Map<String, Component> components = new HashMap<>();
        Map<String, Component> componentsWithHover = new HashMap<>();
        String normalMessage = null;
        String debugMessage = null;

        for (UUID staffId : this.plugin.getAlertManager().getPlayersWithAlerts()) {
            Profile staffProfile = this.plugin.getProfileManager().getProfile(staffId);

            if (staffProfile == null || !staffProfile.isAlerts()) {
                continue;
            }

            Player staff = staffProfile.getPlayer();

            if (staff == null || !staff.isOnline()
                    || !staff.hasPermission(Permissions.ALERTS.getPermission())) {
                continue;
            }

            boolean debug = staff.hasPermission(Permissions.DEBUG.getPermission());
            boolean hover = staff.hasPermission(Permissions.HOVER.getPermission());
            String messageToSend;

            if (debug) {
                if (debugMessage == null) {
                    debugMessage = buildAlertMessage(
                            alertMessage, formattedDebugString, true, playerName, fullDisplayCheck, vl, tps,
                            checkName, checkType, maxvl, checkCategory, checkPlusCheckType, experimentalFormat, ping
                    );
                }

                messageToSend = debugMessage;
            } else if (basicCategory != null
                    && staff.hasPermission(Permissions.BASIC_ALERTS.getPermission())) {
                int categoryVL = incrementCategoryVL(staffId, basicCategory);
                String displayCheck = basicCategory
                        + ChatColor.DARK_GRAY + " ["
                        + ChatColor.GRAY + "x"
                        + MsgType.MAIN_THEME_COLOR.getMessage() + categoryVL
                        + ChatColor.DARK_GRAY + "]";

                messageToSend = basicMessages.get(displayCheck);

                if (messageToSend == null) {
                    messageToSend = buildAlertMessage(
                            alertMessage, "", false, playerName, displayCheck, vl, tps, checkName,
                            checkType, maxvl, checkCategory, checkPlusCheckType, experimentalFormat, ping
                    );
                    basicMessages.put(displayCheck, messageToSend);
                }
            } else {
                if (normalMessage == null) {
                    normalMessage = buildAlertMessage(
                            alertMessage, "", false, playerName, fullDisplayCheck, vl, tps,
                            checkName, checkType, maxvl, checkCategory, checkPlusCheckType, experimentalFormat, ping
                    );
                }

                messageToSend = normalMessage;
            }

            Component messageComponent = getOrCreateComponent(
                    messageToSend,
                    playerName,
                    hover,
                    hoverComponent,
                    components,
                    componentsWithHover
            );

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
            queueViolationWebhook(punishedPlayer, checkName, checkType, experimental, vl, maxvl, ping, tps, informationTitle);
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
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVerbose(VerboseEvent event) {
        VerboseAlert alert = VerboseAlert.from(event);
        this.plugin.getAlertManager().queueVerbose(() -> processVerbose(alert));
    }

    private void processVerbose(VerboseAlert alert) {
        Player punishedPlayer = alert.player;

        if (punishedPlayer == null || !punishedPlayer.isOnline()) {
            return;
        }

        Profile punishedProfile = this.plugin.getProfileManager().getProfile(punishedPlayer);

        if (punishedProfile == null) {
            return;
        }

        String tps = String.valueOf(TickTask.getTPS());
        String checkPlusCheckType = alert.checkName + " (" + alert.checkType + ")";
        String playerName = punishedPlayer.getName();

        String hoverMessage = "%information%\n Ping: %ping%, TPS: %tps%"
                .replace("%information%", alert.information)
                .replace("%ping%", String.valueOf(punishedProfile.getConnectionData().getTransPing()))
                .replace("%tps%", tps);

        String formattedDebugString =
                ChatColor.DARK_GRAY + " ("
                        + MsgType.MAIN_THEME_COLOR.getMessage()
                        + calculatePercentage(alert.vl, alert.maxVl)
                        + ChatColor.DARK_GRAY + ") "
                        + ChatColor.DARK_GRAY + "("
                        + MsgType.MAIN_THEME_COLOR.getMessage()
                        + alert.vl
                        + MsgType.SECOND_THEME_COLOR.getMessage()
                        + "/"
                        + MsgType.MAIN_THEME_COLOR.getMessage()
                        + alert.maxVl
                        + ChatColor.DARK_GRAY + ")";

        String alertMessage = "&6%player% &7verbosed &6%check%%debug%"
                .replace("%player%", playerName)
                .replace("%debug%", formattedDebugString)
                .replace("%check%", checkPlusCheckType);

        Component hoverComponent = legacy(format(hoverMessage));
        Component messageComponent = legacy(format(alertMessage))
                .hoverEvent(HoverEvent.showText(hoverComponent))
                .clickEvent(ClickEvent.runCommand("/tp " + playerName));

        for (UUID staffId : this.plugin.getAlertManager().getPlayersWithAlerts()) {
            Profile staffProfile = this.plugin.getProfileManager().getProfile(staffId);

            if (staffProfile == null || !staffProfile.isAlerts()) {
                continue;
            }

            Player staff = staffProfile.getPlayer();

            if (staff == null || !staff.isOnline()
                    || !staff.hasPermission(Permissions.VERBOSE.getPermission())) {
                continue;
            }

            sendChatPacket(staff, messageComponent);
        }
    }

    private String buildAlertMessage(
            String alertMessage,
            String debug,
            boolean debugMessage,
            String playerName,
            String displayCheck,
            int vl,
            String tps,
            String checkName,
            String checkType,
            int maxvl,
            String checkCategory,
            String checkPlusCheckType,
            String experimentalFormat,
            String ping
    ) {
        String message = alertMessage
                .replace("%debug%", debug)
                .replace("%player%", playerName)
                .replace("%check%", displayCheck)
                .replace("%vl%", String.valueOf(vl))
                .replace("%tps%", tps);

        if (debugMessage) {
            message = message
                    .replace("%checkname%", checkName)
                    .replace("%checktype%", checkType)
                    .replace("%maxvl%", String.valueOf(maxvl));
        } else {
            message = message
                    .replace("%maxvl%", String.valueOf(maxvl))
                    .replace("%checkname%", checkName)
                    .replace("%checktype%", checkType);
        }

        return message
                .replace("%checkcategory%", checkCategory)
                .replace("%checknameandtype%", checkPlusCheckType)
                .replace("%experimental%", experimentalFormat)
                .replace("%ping%", ping);
    }

    private Component getOrCreateComponent(
            String message,
            String playerName,
            boolean hover,
            Component hoverComponent,
            Map<String, Component> components,
            Map<String, Component> componentsWithHover
    ) {
        Component component = components.get(message);

        if (component == null) {
            component = legacy(format(message))
                    .clickEvent(ClickEvent.runCommand("/tp " + playerName));
            components.put(message, component);
        }

        if (!hover) {
            return component;
        }

        Component componentWithHover = componentsWithHover.get(message);

        if (componentWithHover == null) {
            componentWithHover = component.hoverEvent(HoverEvent.showText(hoverComponent));
            componentsWithHover.put(message, componentWithHover);
        }

        return componentWithHover;
    }

    private void sendChatPacket(Player player, Component component) {
        if (player == null || !player.isOnline()) {
            return;
        }

        try {
            PacketWrapper<?> packet;

            if (systemChatPackets) {
                packet = new WrapperPlayServerSystemChatMessage(false, component);
            } else {
                packet = new WrapperPlayServerChatMessage(
                        new ChatMessageLegacy(component, ChatTypes.CHAT)
                );
            }

            // Alert packets do not need to re-enter Arrow's outgoing packet listener.
            // A normal send would run every recipient through all data processors and
            // the full check holder again, multiplying work during alert bursts.
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        } catch (Throwable throwable) {
            String legacyMessage = LEGACY_SERIALIZER.serialize(component);

            TaskUtils.player(player, () -> {
                if (player.isOnline()) {
                    player.spigot().sendMessage(
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(legacyMessage)
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

    private String prefixLines(String input, String prefix) {
        String[] lines = input.split("\\n");
        StringBuilder builder = new StringBuilder(input.length() + (prefix.length() * lines.length));

        for (int i = 0; i < lines.length; i++) {
            builder.append(prefix).append(lines[i]);

            if (i < lines.length - 1) {
                builder.append('\n');
            }
        }

        return builder.toString();
    }

    private String getCategory(String checkName) {
        if (checkName == null || checkName.isEmpty()) {
            return null;
        }

        String lower = checkName.toLowerCase(Locale.ROOT);

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

    private void queueViolationWebhook(
            Player player,
            String check,
            String type,
            boolean experimental,
            int vl,
            int maxVl,
            String ping,
            String tps,
            String description
    ) {
        if (player == null) {
            return;
        }

        Profile profile = this.plugin.getProfileManager().getProfile(player);

        if (profile == null) {
            return;
        }

        String playerName = player.getName();
        String uuid = profile.getClient();

        String safePlayer = escapeJson(playerName);
        String safeCheck = escapeJson(check);
        String safeType = escapeJson(type);

        String clientBrand = getClientBrand(player);
        String clientVersion = getClientVersion(player);

        var location = profile.getMovementData().getLocation();

        String world = location.getWorld() == null
                ? "unknown"
                : location.getWorld().getName();

        String locationText = String.format(
                "(%s, %d, %d, %d)",
                world,
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );

        String checkText = "**`" + safePlayer + "`** failed "
                + "**" + safeCheck + "**";

        if (type != null && !type.isEmpty()) {
            checkText += " **(" + safeType + ")**";
        }

        if (experimental) {
            checkText += " " + escapeJson(
                    OtherUtility.stripColorCodes(
                            OtherUtility.translate(
                                    MsgType.EXPERIMENTAL_SYMBOL.getMessage()
                            )
                    )
            );
        }

        checkText += " [" + vl + "/" + maxVl + "]";

        String json = "{"
                + "\"embeds\":[{"
                + "\"title\":\"Arrow Alert\","
                + "\"description\":\"" + checkText + "\","

                + "\"thumbnail\":{"
                + "\"url\":\"https://mc-heads.net/avatar/" + uuid + "/100\""
                + "},"

                + "\"fields\":["
                + "{"
                + "\"name\":\"Description\","
                + "\"value\":\"" + escapeJson(OtherUtility.stripColorCodes(description)) + "\","
                + "\"inline\":false"
                + "},"
                + "{"
                + "\"name\":\"Client Brand\","
                + "\"value\":\"" + escapeJson(clientBrand) + "\","
                + "\"inline\":false"
                + "},"
                + "{"
                + "\"name\":\"Client Version\","
                + "\"value\":\"" + escapeJson(clientVersion) + "\","
                + "\"inline\":false"
                + "},"
                + "{"
                + "\"name\":\"Ping | TPS\","
                + "\"value\":\"" + escapeJson(ping) + "ms | " + escapeJson(tps) + "\","
                + "\"inline\":false"
                + "},"
                + "{"
                + "\"name\":\"Location\","
                + "\"value\":\"" + escapeJson(locationText) + "\","
                + "\"inline\":false"
                + "}"
                + "],"

                + "\"color\":16711680"
                + "}]"
                + "}";

        this.plugin.getAlertManager().queueWebhook(
                Config.Setting.WEBHOOK_LINK.getString(),
                json,
                "Invalid webhook URL, failed to send alert message. Please check your configuration"
        );
    }

    private String getClientBrand(Player player) {
        Profile profile = this.plugin.getProfileManager().getProfile(player);

        if (profile == null) {
            return "Unresolved";
        }

        String brand = profile.getClient();

        return brand == null || brand.isEmpty()
                ? "Unresolved"
                : brand;
    }

    private String getClientVersion(Player player) {
        Profile profile = this.plugin.getProfileManager().getProfile(player);

        if (profile == null) {
            return "Unresolved";
        }

        String version = profile.getVersion().toString();

        return version == null || version.isEmpty()
                ? "Unresolved"
                : version;
    }

    private int incrementCategoryVL(UUID uuid, String category) {
        long now = System.currentTimeMillis();

        lastReset.compute(uuid, (key, resetAt) -> {
            if (resetAt == null || now - resetAt >= 60000L) {
                basicAlertVLs.put(key, new ConcurrentHashMap<>());
                return now;
            }

            return resetAt;
        });

        return basicAlertVLs
                .computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>())
                .merge(category, 1, Integer::sum);
    }

    public void addCategoryVL(Player player, String category) {
        incrementCategoryVL(player.getUniqueId(), category);
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
        out = out.replace("\r", "").trim();

        int max = 1500;

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

    private static class ViolationAlert {
        Player player;
        String checkName;
        String description;
        String checkCategory;
        String checkType;
        String information;
        String informationTitle;
        int vl;
        int maxVl;
        boolean experimental;

        private ViolationAlert(
                Player player,
                String checkName,
                String description,
                String checkCategory,
                String checkType,
                String information,
                String informationTitle,
                int vl,
                int maxVl,
                boolean experimental
        ) {
            this.player = player;
            this.checkName = checkName;
            this.description = description;
            this.checkCategory = checkCategory;
            this.checkType = checkType;
            this.information = information;
            this.informationTitle = informationTitle;
            this.vl = vl;
            this.maxVl = maxVl;
            this.experimental = experimental;
        }

        private static ViolationAlert from(AnticheatViolationEvent event) {
            return new ViolationAlert(
                    event.getPlayer(),
                    event.getCheck() == null ? "" : event.getCheck(),
                    event.getDescription() == null ? "" : event.getDescription(),
                    String.valueOf(event.getCheckCategory()),
                    event.getType() == null ? "" : event.getType(),
                    event.getInformation() == null ? "" : event.getInformation(),
                    event.getInformationTitle() == null ? "" : event.getInformationTitle(),
                    event.getVl(),
                    event.getMaxVl(),
                    event.isExperimental()
            );
        }
    }

    private static class VerboseAlert {
        Player player;
        String checkName;
        String checkType;
        String information;
        double vl;
        double maxVl;

        private VerboseAlert(
                Player player,
                String checkName,
                String checkType,
                String information,
                double vl,
                double maxVl
        ) {
            this.player = player;
            this.checkName = checkName;
            this.checkType = checkType;
            this.information = information;
            this.vl = vl;
            this.maxVl = maxVl;
        }

        private static VerboseAlert from(VerboseEvent event) {
            return new VerboseAlert(
                    event.getPlayer(),
                    event.getCheckName() == null ? "" : event.getCheckName(),
                    event.getType() == null ? "" : event.getType(),
                    event.getInformation() == null ? "" : event.getInformation(),
                    event.getVl(),
                    event.getMaxVl()
            );
        }
    }
}
