package me.arrow.commands.subcommands;

import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.CheckHolder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

import static me.arrow.utils.customutils.OtherUtility.translate;

public class VerboseCommand extends SubCommand {

    private final Arrow plugin;

    public VerboseCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "verbose";
    }

    @Override
    protected String getDescription() {
        return "Toggle verbose for a check";
    }

    @Override
    protected String getSyntax() {
        return "verbose <check|All> [player]";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_VERBOSE.getPermission();
    }

    @Override
    protected int maxArguments() {
        return 3;
    }

    @Override
    protected boolean canConsoleExecute() {
        return false;
    }

    @Override
    protected void perform(CommandSender sender, String[] args) {
        if (!(sender instanceof Player senderPlayer)) {
            sender.sendMessage(translate("&cOnly players can use this command."));
            return;
        }

        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(translate("&cUsage: /arrow verbose <check|All> [player]"));
            return;
        }

        Profile senderProfile = plugin.getProfileManager().getProfile(senderPlayer);

        if (senderProfile == null) {
            sender.sendMessage(translate("&cYour profile is not loaded."));
            return;
        }

        String input = args[1];

        if (args.length == 2) {
            String checkName = resolveCheckName(senderProfile, input);

            if (checkName == null) {
                sender.sendMessage(translate("&cUnknown check: " + input));
                return;
            }

            toggleVerbose(sender, senderProfile, senderPlayer, checkName, true);
            return;
        }

        Player targetPlayer = Bukkit.getPlayerExact(args[2]);

        if (targetPlayer == null) {
            targetPlayer = Bukkit.getPlayer(args[2]);
        }

        if (targetPlayer == null || !targetPlayer.isOnline()) {
            sender.sendMessage(translate("&cThat player is not online."));
            return;
        }

        Profile targetProfile = plugin.getProfileManager().getProfile(targetPlayer);

        if (targetProfile == null) {
            sender.sendMessage(translate("&cThat player's profile is not loaded."));
            return;
        }

        String checkName = resolveCheckName(targetProfile, input);

        if (checkName == null) {
            sender.sendMessage(translate("&cUnknown check: " + input));
            return;
        }

        toggleVerbose(sender, targetProfile, targetPlayer, checkName, false);
    }

    private String resolveCheckName(Profile profile, String input) {
        if (profile == null || input == null || input.trim().isEmpty()) {
            return null;
        }

        if (input.equalsIgnoreCase("All")) {
            return "All";
        }

        CheckHolder holder = profile.getCheckHolder();

        if (holder == null || holder.getChecks() == null) {
            return null;
        }

        return Arrays.stream(holder.getChecks())
                .map(check -> check.getClass().getSimpleName())
                .filter(name -> name.equalsIgnoreCase(input))
                .findFirst()
                .orElse(null);
    }

    private void toggleVerbose(CommandSender sender, Profile targetProfile, Player targetPlayer, String checkName, boolean self) {
        String previous = targetProfile.getVerbosingClass();

        if (previous == null) {
            previous = "None";
        }

        boolean disablingSameCheck = targetProfile.isVerbose() && previous.equalsIgnoreCase(checkName);

        if (disablingSameCheck) {
            targetProfile.setVerbose(false);
            targetProfile.setVerbosingClass("None");

            if (self) {
                sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                        + "&cDisabled verbose for &f" + checkName));
            } else {
                sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                        + "&cDisabled verbose for &f" + checkName
                        + " &7on &f" + targetPlayer.getName()));
            }

            return;
        }

        targetProfile.setVerbose(true);
        targetProfile.setVerbosingClass(checkName);

        if (self) {
            if (previous.equalsIgnoreCase("None")) {
                sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                        + "&aEnabled verbose for &f" + checkName));
            } else {
                sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                        + "&aEnabled verbose for &f" + checkName
                        + " &7and disabled verbose for &f" + previous));
            }
        } else {
            if (previous.equalsIgnoreCase("None")) {
                sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                        + "&aEnabled verbose for &f" + checkName
                        + " &7on &f" + targetPlayer.getName()));
            } else {
                sender.sendMessage(translate(MsgType.PREFIX.getMessage()
                        + "&aEnabled verbose for &f" + checkName
                        + " &7on &f" + targetPlayer.getName()
                        + " &7and disabled verbose for &f" + previous));
            }
        }
    }
}