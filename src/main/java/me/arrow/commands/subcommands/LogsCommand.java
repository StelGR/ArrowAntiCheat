package me.arrow.commands.subcommands;

import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.Permissions;
import me.arrow.managers.logs.LogExporter;
import me.arrow.managers.logs.PlayerLog;
import me.arrow.utils.TaskUtils;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

import static me.arrow.utils.ChatUtils.format;
import static me.arrow.utils.customutils.OtherUtility.translate;

public class LogsCommand extends SubCommand {

    private final Arrow plugin;
    private static final int PER_PAGE = 10;

    public LogsCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override protected String getName() { return "logs"; }
    @Override protected String getDescription() { return "See a specific player's previous logs"; }
    @Override protected String getSyntax() { return "logs"; }
    @Override protected String getPermission() { return Permissions.COMMAND_LOGS.getPermission(); }
    @Override protected int maxArguments() { return 3; }
    @Override protected boolean canConsoleExecute() { return true; }

    @Override
    protected void perform(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(translate("&cUsage: /arrow logs <player> [page]"));
            return;
        }

        String inputName = args[1];

        int requestedPage = 1;
        if (args.length >= 3) {
            try {
                requestedPage = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException ignored) {
            }
        }

        final int page = requestedPage;

        OfflinePlayer offline = Bukkit.getOfflinePlayer(inputName);
        String targetName = offline != null && offline.getName() != null ? offline.getName() : inputName;

        sender.sendMessage(translate("&7Loading logs for &e" + targetName + "&7..."));

        TaskUtils.taskAsync(() -> {
            LogExporter.PagedLogs pagedLogs = plugin.getLogManager()
                    .getLogExporter()
                    .getLogsForPlayer(targetName, page, PER_PAGE);

            TaskUtils.task(() -> sendLogs(sender, targetName, pagedLogs));
        });
    }

    private void sendLogs(CommandSender sender, String targetName, LogExporter.PagedLogs pagedLogs) {
        List<PlayerLog> logs = pagedLogs.getLogs();

        if (logs == null || logs.isEmpty()) {
            sender.sendMessage(translate("&cNo logs found for " + targetName));
            return;
        }

        int page = pagedLogs.getPage();
        int maxPages = pagedLogs.getMaxPages();
        int total = pagedLogs.getTotalLogs();

        sender.sendMessage(translate("&6Logs for &e" + targetName + " &7- Page &e" + page + "&7/&e" + maxPages + " &7(" + total + " entries)"));

        for (PlayerLog log : logs) {
            String lineMsg = "&6" + log.getTimeStamp() + " &7failed &6" + log.getCheck();

            TextComponent lineComp = new TextComponent(translate(lineMsg));

            String hover = log.getInformation() == null || log.getInformation().isEmpty()
                    ? "No details"
                    : log.getInformation();

            lineComp.setHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(format(hover)).create()
            ));

            lineComp.setClickEvent(new ClickEvent(
                    ClickEvent.Action.RUN_COMMAND,
                    "/tp " + targetName
            ));

            if (sender instanceof Player) {
                ((Player) sender).spigot().sendMessage(lineComp);
            } else {
                sender.sendMessage(translate(lineMsg));

                if (!hover.isEmpty()) {
                    String[] parts = hover.split("\\n");
                    for (String s : parts) {
                        sender.sendMessage(translate(" &7" + s));
                    }
                }
            }
        }

        if (sender instanceof Player) {
            Player player = (Player) sender;

            TextComponent nav = new TextComponent("");

            if (page > 1) {
                TextComponent prev = new TextComponent(translate("&a[Previous] "));
                prev.setClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        "/arrow logs " + targetName + " " + (page - 1)
                ));
                nav.addExtra(prev);
            }

            if (page < maxPages) {
                TextComponent next = new TextComponent(translate("&a[Next]"));
                next.setClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        "/arrow logs " + targetName + " " + (page + 1)
                ));
                nav.addExtra(next);
            }

            if (page > 1 || page < maxPages) {
                player.spigot().sendMessage(nav);
            }
        } else {
            sender.sendMessage(translate("&7Navigate: /arrow logs " + targetName + " <page>"));
        }
    }
}