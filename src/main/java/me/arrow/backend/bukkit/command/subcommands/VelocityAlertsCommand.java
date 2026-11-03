package me.arrow.backend.bukkit.command.subcommands;

import me.arrow.Arrow;
import me.arrow.backend.bukkit.command.SubCommand;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.versionutils.impl.VelocityAlertBridge;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static me.arrow.utils.customutils.OtherUtility.translate;

public class VelocityAlertsCommand extends SubCommand {

    private final Arrow plugin;

    public VelocityAlertsCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "velocityalerts";
    }

    @Override
    protected String getDescription() {
        return "Toggle cross-server Arrow alerts on this server";
    }

    @Override
    protected String getSyntax() {
        return "velocityalerts";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_VELOCITY_ALERTS.getPermission();
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
        Profile profile = plugin.getProfileManager().getProfile(player);
        if (profile == null) {
            sender.sendMessage(translate("&cYour Arrow profile is not ready yet."));
            return;
        }

        boolean enabled = !profile.isVelocityAlerts();
        profile.setVelocityAlerts(enabled);
        plugin.getProfileManager().saveProfile(profile);
        VelocityAlertBridge.sendPreference(player, enabled);

        sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                + (enabled
                ? "&aCross-server Arrow alerts are enabled on this server."
                : "&cCross-server Arrow alerts are disabled on this server.")));
    }
}
