package me.arrow.commands.subcommands;

import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.Permissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GuiCommand extends SubCommand {


    Arrow plugin;

    public GuiCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "gui";
    }

    @Override
    protected String getDescription() {
        return "Open the gui to get anticheat information";
    }

    @Override
    protected String getSyntax() {
        return "gui";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_GUI.getPermission();
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

        Arrow.getGuiManager().openArrowGUI((Player) sender);
    }
}

