package me.arrow.commands;

import me.arrow.Arrow;
import me.arrow.commands.subcommands.*;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static me.arrow.utils.customutils.OtherUtility.translate;

public class CommandManager implements TabExecutor {

    private final List<SubCommand> subCommands = new ArrayList<>();

    public CommandManager(Arrow plugin) {
        this.subCommands.add(new AlertsCommand(plugin));
        this.subCommands.add(new VerboseCommand(plugin));
        this.subCommands.add(new SetbacksCommand(plugin));
        this.subCommands.add(new GuiCommand(plugin));
        this.subCommands.add(new LogsCommand(plugin));
        this.subCommands.add(new PasteLogsCommand(plugin));
        this.subCommands.add(new PingCommand(plugin));
        this.subCommands.add(new InfoCommand(plugin));
        this.subCommands.add(new ReloadCommand(plugin));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {

        if (args.length > 0) {

            for (SubCommand subCommand : this.subCommands) {

                if (args[0].equalsIgnoreCase(subCommand.getName())) {

                    if (!subCommand.canConsoleExecute() && sender instanceof ConsoleCommandSender) {
                        sender.sendMessage(MsgType.CONSOLE_COMMANDS.getMessage());
                        return true;
                    }

                    if (!sender.hasPermission(subCommand.getPermission())) {
                        sender.sendMessage(MsgType.NO_PERMISSION.getMessage());
                        return true;
                    }

                    subCommand.perform(sender, args);
                    return true;
                }

                if (args[0].equalsIgnoreCase("help")) {
                    helpMessage(sender);
                    return true;
                }
            }
        }

        helpMessage(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return this.subCommands.stream().map(SubCommand::getName).collect(Collectors.toList());
        }

        if (args.length == 2 && sender instanceof Player player && args[0].equals("verbose")) {
            Profile profile = Arrow.getInstance().getProfileManager().getProfile(player);
            if (profile == null) return Collections.emptyList();

            return Arrays.stream(profile.getCheckHolder().getChecks())
                    .map(check -> check.getClass().getSimpleName()) // only class name, no package
                    .filter(name -> name.startsWith(args[1]))
                    .toList();
        }

        return null;
    }

    private void helpMessage(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage( translate(MsgType.PREFIX.getMessage() + MsgType.SECOND_THEME_COLOR.getMessage() +  "Available Commands"));
        sender.sendMessage("");

        this.subCommands.stream()
                .filter(subCommand -> sender.hasPermission(subCommand.getPermission()))
                .forEach(subCommand ->
                        sender.sendMessage(translate(MsgType.MAIN_THEME_COLOR.getMessage() + subCommand.getSyntax() + ChatColor.DARK_GRAY + " - "
                                + ChatColor.GRAY + subCommand.getDescription())));

        sender.sendMessage("");
    }
}