package me.arrow.commands.subcommands;

import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

import static me.arrow.utils.customutils.OtherUtility.*;

public class AlertsCommand extends SubCommand {

    private final Arrow plugin;

    public AlertsCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "alerts";
    }

    @Override
    protected String getDescription() {
        return "Toggle the alerts";
    }

    @Override
    protected String getSyntax() {
        return "alerts";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_ALERTS.getPermission();
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

        final UUID uuid = ((Player) sender).getUniqueId();

        if (this.plugin.getAlertManager().hasAlerts(uuid)) {

            this.plugin.getProfileManager().getProfile(((Player) sender).getUniqueId()).setAlerts(false);
            this.plugin.getAlertManager().removePlayerFromAlerts(uuid);

            sender.sendMessage(translate(MsgType.PREFIX.getMessage() + "&cYou have disabled the Alerts"));

        } else {

            this.plugin.getProfileManager().getProfile(((Player) sender).getUniqueId()).setAlerts(true);
            this.plugin.getAlertManager().addPlayerToAlerts(uuid);

            sender.sendMessage(translate(MsgType.PREFIX.getMessage() + "&aYou have enabled the Alerts"));
        }
    }
}
