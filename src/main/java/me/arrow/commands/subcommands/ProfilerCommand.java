package me.arrow.commands.subcommands;


import me.arrow.Arrow;
import me.arrow.commands.SubCommand;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.profiler.ProfilerData;
import me.arrow.managers.profiler.Profiler;
import org.bukkit.command.CommandSender;

import java.util.Comparator;
import java.util.Map;

import static me.arrow.utils.customutils.OtherUtility.translate;

public class ProfilerCommand extends SubCommand {

    Arrow plugin;

    public ProfilerCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "profiler";
    }

    @Override
    protected String getDescription() {
        return "Displays profiler statistics.";
    }

    @Override
    protected String getSyntax() {
        return "profiler";
    }

    @Override
    protected String getPermission() {
        return Permissions.COMMAND_PROFILER.getPermission();
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

        if (args.length > 1 &&
                (args[1].equalsIgnoreCase("reset")
                        || args[1].equalsIgnoreCase("clear"))) {

            Profiler.reset();

            sender.sendMessage(translate(
                    MsgType.PREFIX.getMessage() + "&aProfiler statistics have been reset."
            ));

            return;
        }

        sender.sendMessage(translate("&8&m----------------------------------------"));
        sender.sendMessage(translate(MsgType.PREFIX.getMessage() + "&bProfiler Results"));

        Profiler.getProfiles().entrySet().stream()
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<String, ProfilerData> e) ->
                                e.getValue().getAverageTime()
                ).reversed())
                .forEach(entry -> {

                    ProfilerData data = entry.getValue();

                    sender.sendMessage(translate(
                            "&7" + entry.getKey()
                                    + " &8» "
                                    + "&fCalls: &b" + data.getCalls()
                                    + " &7| "
                                    + "&fAvg: &b" + String.format("%.2f", data.getAverageTime() / 1000D) + " μs"
                                    + " &7| "
                                    + "&fMax: &b" + String.format("%.2f", data.getMaxTime() / 1000D) + " μs"
                    ));
                });

        sender.sendMessage(translate("&8&m----------------------------------------"));
    }
}
