package me.arrow.backend.bukkit.command.subcommands;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.arrow.Arrow;
import me.arrow.backend.bukkit.command.SubCommand;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.profile.Profile;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static me.arrow.utils.customutils.OtherUtility.*;

public class PingCommand extends SubCommand {

    private final Arrow plugin;

    public PingCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "ping";
    }

    @Override
    protected String getDescription() {
        return "See connection information";
    }

    @Override
    protected String getSyntax() {
        return "ping";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_PING.getPermission();
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

        sender.sendMessage(translate(DASH_LINE));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+"Transactions:"));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+" * Transaction Ping: "+MsgType.MAIN_THEME_COLOR.getMessage()+profile.getConnectionData().getTransPing()));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+" * Transaction Ping Last: "+MsgType.MAIN_THEME_COLOR.getMessage()+profile.getConnectionData().getLastTransPing()));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+" * Transaction Ping Average: "+MsgType.MAIN_THEME_COLOR.getMessage()+profile.getConnectionData().getAverageTransactionPing()));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+" * Transaction Client Tick(s): "+MsgType.MAIN_THEME_COLOR.getMessage()+profile.getConnectionData().getClientTickTrans()));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+" * Transaction Drop Time: "+MsgType.MAIN_THEME_COLOR.getMessage()+profile.getConnectionData().getDropTransTime()));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+" * Transaction Drop Tick(s): "+MsgType.MAIN_THEME_COLOR.getMessage()+profile.getConnectionData().getTransDropTick()));
        sender.sendMessage(translate("&f"));
        sender.sendMessage(translate(MsgType.SECOND_THEME_COLOR.getMessage()+"&fServer Ping:"));
        sender.sendMessage(translate(
                MsgType.SECOND_THEME_COLOR.getMessage() + " * Ping: "
                        + MsgType.MAIN_THEME_COLOR.getMessage()
                        + (
                        PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_17)
                                ? profile.getPing()           // NEW method
                                : getLegacyPing(profile.getPlayer())      // OLD method
                )
        ));

        sender.sendMessage(translate(DASH_LINE));

    }

    public int getLegacyPing(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return (int) handle.getClass().getField("ping").get(handle);
        } catch (Exception e) {
            return -1;
        }
    }

}
