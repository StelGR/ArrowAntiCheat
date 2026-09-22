package me.arrow.backend.bukkit.command;

import me.arrow.files.Config;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import static me.arrow.utils.customutils.OtherUtility.parseLocation;

//this is test server /spawn command, renamed to not conflict with other plugins

public class Stuck implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String Label, String[] args) {
        if (!(sender instanceof Player player)) {
            OtherUtility.log("&cOnly players may use this command.");
            return false;
        }

        if (Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
            player.teleport(parseLocation(Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_SPAWN.getString(), Config.Setting.TEST_SERVER_MODE_WORLD.getString()), PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.sendMessage(OtherUtility.translate("&7Teleported to &cSpawn&7."));
        }

        return true;
    }
}
