package me.arrow.commands.subcommands;

import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.Permissions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static me.arrow.utils.customutils.OtherUtility.translate;

public class InfoCommand extends SubCommand {


    Arrow plugin;

    public InfoCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "info";
    }

    @Override
    protected String getDescription() {
        return "See a specific player's information";
    }

    @Override
    protected String getSyntax() {
        return "info";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_INFO.getPermission();
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

        //final Profile profile = plugin.getProfileManager().getProfile((Player) sender);

        if (args.length < 2) {

            sender.sendMessage(translate( "&cUsage: /arrow info <player>"));
        } else {
            Player player = Bukkit.getPlayer(args[1]);

            if (player == null) {
                sender.sendMessage(translate( "&cThat player is not online"));
            } else {
                Arrow.getGuiManager().openPlayerInfoGUI((Player) sender, player);
            }
        }
    }
}

