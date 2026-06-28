package me.arrow.commands.subcommands;

import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.Permissions;
import org.bukkit.command.CommandSender;

public class ReloadCommand extends SubCommand {


    Arrow plugin;

    public ReloadCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "reload";
    }

    @Override
    protected String getDescription() {
        return "Reload the plugin";
    }

    @Override
    protected String getSyntax() {
        return "reload";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_RELOAD.getPermission();
    }

    @Override
    protected int maxArguments() {
        return 1;
    }

    @Override
    protected boolean canConsoleExecute() {
        return false;
    }

    @Override
    protected void perform(CommandSender sender, String[] args) {
        sender.sendMessage("&bReloading config.yml...");
        Arrow.getInstance().getConfiguration().reloadConfig();
        sender.sendMessage("&bReloaded config.yml.");
        sender.sendMessage("&bReloading checks.yml...");
        Arrow.getInstance().getChecks().reloadConfig();
        sender.sendMessage("&bReloaded checks.yml.");
        sender.sendMessage("&bReloading the Theme Manager...");
        Arrow.getInstance().getThemeManager().reload();
        sender.sendMessage("&bReloaded the Theme Manager.");
        sender.sendMessage("&bReloading the Profile Manager...");
        Arrow.getInstance().getProfileManager().shutdown();
        Arrow.getInstance().getProfileManager().initialize();
        sender.sendMessage("&bReloaded the Profile Manager.");

    }
}
