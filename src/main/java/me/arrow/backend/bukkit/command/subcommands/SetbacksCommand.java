package me.arrow.backend.bukkit.command.subcommands;

import me.arrow.Arrow;
import me.arrow.backend.bukkit.command.SubCommand;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.profile.Profile;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static me.arrow.utils.customutils.OtherUtility.*;

public class SetbacksCommand extends SubCommand {

    private final Arrow plugin;

    public SetbacksCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "setbacks";
    }

    @Override
    protected String getDescription() {
        return "Toggle the setbacks debug";
    }

    @Override
    protected String getSyntax() {
        return "setbacks";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_SETBACKS.getPermission();
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

        final Profile profile = plugin.getProfileManager().getProfile((Player) sender);

        if (profile.isSetbackDebug()) {

            profile.setSetbackDebug(false);

            sender.sendMessage(translate( MsgType.PREFIX.getMessage() + "&cYou have disabled Setback debug"));

        } else {

            profile.setSetbackDebug(true);

            sender.sendMessage(translate(MsgType.PREFIX.getMessage() + "&aYou have enabled Setback debug"));
        }
    }
}
