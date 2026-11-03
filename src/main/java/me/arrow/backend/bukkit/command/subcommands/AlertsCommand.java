package me.arrow.backend.bukkit.command.subcommands;

import me.arrow.Arrow;
import me.arrow.backend.bukkit.command.SubCommand;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.enums.Permissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static me.arrow.utils.customutils.OtherUtility.*;

public class AlertsCommand extends SubCommand {

    Arrow plugin;

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

        Player player = (Player) sender;
        Profile profile = this.plugin.getProfileManager().getProfile(player);
        if (profile == null) {
            sender.sendMessage(translate("&cYour Arrow profile is not ready yet."));
            return;
        }

        boolean enabled = !profile.isAlerts();
        profile.setAlerts(enabled);
        if (enabled) {
            this.plugin.getAlertManager().addPlayerToAlerts(player.getUniqueId());
        } else {
            this.plugin.getAlertManager().removePlayerFromAlerts(player.getUniqueId());
        }
        this.plugin.getProfileManager().saveProfile(profile);
        sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                + (enabled ? "&aYou have enabled the Alerts" : "&cYou have disabled the Alerts")));
    }
}
