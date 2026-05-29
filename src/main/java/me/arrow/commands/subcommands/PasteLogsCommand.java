package me.arrow.commands.subcommands;

import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.Permissions;
import me.arrow.managers.logs.Hastebin;
import me.arrow.managers.logs.PlayerLog;
import me.arrow.utils.TaskUtils;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import static me.arrow.utils.customutils.OtherUtility.translate;

public class PasteLogsCommand extends SubCommand {

    private static final Pattern AMP_HEX_COLOR = Pattern.compile("(?i)&#[0-9a-f]{6}");
    private static final Pattern AMP_LEGACY_COLOR = Pattern.compile("(?i)&[0-9a-fk-orx]");
    private static final Pattern SECTION_HEX_COLOR = Pattern.compile("(?i)§x(§[0-9a-f]){6}");
    private static final Pattern SECTION_LEGACY_COLOR = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern ANSI_COLOR = Pattern.compile("\\u001B\\[[;\\d]*m");

    private final Arrow plugin;

    public PasteLogsCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "pastelogs";
    }

    @Override
    protected String getDescription() {
        return "Paste a player's logs online";
    }

    @Override
    protected String getSyntax() {
        return "pastelogs <player>";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_LOGS.getPermission();
    }

    @Override
    protected int maxArguments() {
        return 2;
    }

    @Override
    protected boolean canConsoleExecute() {
        return true;
    }

    @Override
    protected void perform(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(translate("&cUsage: /arrow pastelogs <player>"));
            return;
        }

        String inputName = args[1];

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(inputName);
        String targetName = offlinePlayer != null && offlinePlayer.getName() != null
                ? offlinePlayer.getName()
                : inputName;

        sender.sendMessage(translate("&7Preparing logs for &e" + targetName + "&7..."));

        TaskUtils.taskAsync(() -> {
            List<PlayerLog> logs = plugin.getLogManager()
                    .getLogExporter()
                    .getLogsForPlayer(targetName);

            if (logs == null || logs.isEmpty()) {
                sendSync(sender, translate("&cNo logs found for " + targetName));
                return;
            }

            String contents = buildPasteContents(targetName, logs);
            String pasteUrl = Hastebin.uploadPaste(contents);

            if (pasteUrl == null) {
                sendSync(sender, translate("&cCouldn't paste logs. The paste may be too large or the paste service may be down."));
                return;
            }

            TaskUtils.task(() -> sendPasteMessage(sender, targetName, logs.size(), pasteUrl));
        });
    }

    private String buildPasteContents(String targetName, List<PlayerLog> logs) {
        StringBuilder builder = new StringBuilder();

        builder.append("Arrow Anticheat Logs").append('\n');
        builder.append("Player: ").append(clean(targetName)).append('\n');
        builder.append("Total Logs: ").append(logs.size()).append('\n');
        builder.append("Generated: ").append(new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(new Date())).append('\n');
        builder.append("Plugin: ").append(plugin.getHost().getDescription().getName())
                .append(" ")
                .append(plugin.getHost().getDescription().getVersion())
                .append('\n');

        builder.append('\n');
        builder.append("============================================================").append('\n');
        builder.append('\n');

        int index = 1;

        for (PlayerLog log : logs) {
            if (log == null) {
                continue;
            }

            builder.append("#").append(index++).append('\n');
            builder.append("Time: ").append(clean(log.getTimeStamp())).append('\n');
            builder.append("Player: ").append(clean(log.getPlayer())).append('\n');
            builder.append("UUID: ").append(clean(log.getUuid())).append('\n');
            builder.append("Check: ").append(clean(log.getCheck())).append('\n');

            String information = clean(log.getInformation());

            if (information.isEmpty()) {
                builder.append("Info: No details").append('\n');
            } else {
                builder.append("Info:").append('\n');

                String[] split = information.split("\\n");

                for (String line : split) {
                    builder.append("  ").append(line).append('\n');
                }
            }

            builder.append('\n');
        }

        return builder.toString();
    }

    private void sendPasteMessage(CommandSender sender, String targetName, int amount, String pasteUrl) {
        String message = translate("&aPasted &e" + amount + " &alogs for &e" + targetName + "&a: &7" + pasteUrl);

        if (!(sender instanceof Player player)) {
            sender.sendMessage(message);
            return;
        }

        TextComponent component = new TextComponent(translate("&aPasted &e" + amount + " &alogs for &e" + targetName + "&a: "));

        TextComponent link = new TextComponent(translate("&b&nClick here"));
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, pasteUrl));
        link.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(translate("&7Open pasted logs:\n&b" + pasteUrl)).create()
        ));

        component.addExtra(link);

        player.spigot().sendMessage(component);
    }

    private void sendSync(CommandSender sender, String message) {
        TaskUtils.task(() -> sender.sendMessage(message));
    }

    private String clean(String input) {
        if (input == null) {
            return "";
        }

        String output = input;

        output = ANSI_COLOR.matcher(output).replaceAll("");
        output = SECTION_HEX_COLOR.matcher(output).replaceAll("");
        output = SECTION_LEGACY_COLOR.matcher(output).replaceAll("");
        output = AMP_HEX_COLOR.matcher(output).replaceAll("");
        output = AMP_LEGACY_COLOR.matcher(output).replaceAll("");

        String stripped = ChatColor.stripColor(output);

        if (stripped != null) {
            output = stripped;
        }

        return output
                .replace("\r", "")
                .replace("\t", "    ")
                .trim();
    }
}
