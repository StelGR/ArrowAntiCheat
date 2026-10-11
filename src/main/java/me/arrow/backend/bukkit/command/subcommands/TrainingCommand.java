package me.arrow.backend.bukkit.command.subcommands;

import me.arrow.Arrow;
import me.arrow.backend.bukkit.command.SubCommand;
import me.arrow.enums.Permissions;
import me.arrow.neural.NeuralNetClient;
import me.arrow.neural.NeuralNetManager;
import me.arrow.neural.TrainingManager;
import org.bukkit.command.CommandSender;

import static me.arrow.utils.customutils.OtherUtility.translate;

public class TrainingCommand extends SubCommand {

    Arrow plugin;

    public TrainingCommand(Arrow plugin) {
        this.plugin = plugin;
    }

    @Override
    protected String getName() {
        return "training";
    }

    @Override
    protected String getDescription() {
        return "Start/stop neural network training data collection";
    }

    @Override
    protected String getSyntax() {
        return "training <start|stop> <player> [legit|cheating]";
    }

    @Override
    protected String getPermission() {
        return Permissions.ADMIN.getPermission();
    }

    @Override
    protected int maxArguments() {
        return 4;
    }

    @Override
    protected boolean canConsoleExecute() {
        return true;
    }

    @Override
    protected void perform(CommandSender sender, String[] args) {
        NeuralNetClient client = NeuralNetManager.getClient();

        if (client == null) {
            sender.sendMessage(translate("&cNeural net is disabled or server_url is empty in config.yml."));
            return;
        }

        if (!client.isAvailable() && !client.checkHealthSync()) {
            sender.sendMessage(translate("&cNeural server is unreachable at: &f" + me.arrow.files.Config.Setting.NEURAL_SERVER_URL.getString()));
            sender.sendMessage(translate("&7Ensure the Python server is running on the host and the URL in config.yml is correct."));
            return;
        }

        // Ask the server if training is enabled (controlled by --training flag on the server)
        if (!client.checkTrainingStatus()) {
            sender.sendMessage(translate("&cTraining is disabled on the neural server."));
            sender.sendMessage(translate("&7Start the server with &fpython server.py --training &7to enable."));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(translate("&cUsage: /arrow training start <player> [legit|cheating]"));
            sender.sendMessage(translate("&cUsage: /arrow training stop <player>"));
            return;
        }

        String action = args[1].toLowerCase();
        String playerName = args[2];

        if (action.equals("start")) {
            String label = args.length >= 4 ? args[3].toLowerCase() : "legit";
            boolean cheating;

            if (label.equals("cheating") || label.equals("cheat")) {
                cheating = true;
            } else if (label.equals("legit") || label.equals("clean")) {
                cheating = false;
            } else {
                sender.sendMessage(translate("&cLabel must be 'legit' or 'cheating'"));
                return;
            }

            String result = TrainingManager.startSession(playerName, cheating);
            sender.sendMessage(translate(result));

        } else if (action.equals("stop")) {
            String result = TrainingManager.stopSession(playerName);
            sender.sendMessage(translate(result));

        } else {
            sender.sendMessage(translate("&cUsage: /arrow training start <player> [legit|cheating]"));
            sender.sendMessage(translate("&cUsage: /arrow training stop <player>"));
        }
    }
}
