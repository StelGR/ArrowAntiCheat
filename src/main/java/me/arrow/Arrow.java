package me.arrow;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import lombok.Getter;
import lombok.Setter;
import me.arrow.API.ArrowAPIProvider;
import me.arrow.api.internal.ArrowAPIImpl;
import me.arrow.commands.CommandManager;
import me.arrow.commands.bukkitCommands.Stuck;
import me.arrow.files.Checks;
import me.arrow.files.Config;
import me.arrow.files.commentedfiles.CommentedFileConfiguration;
import me.arrow.listeners.ProfileListener;
import me.arrow.listeners.ViolationListener;
import me.arrow.managers.AlertManager;
import me.arrow.managers.logs.LogManager;
import me.arrow.managers.profile.ProfileManager;
import me.arrow.managers.themes.ThemeManager;
import me.arrow.managers.threads.ThreadManager;
import me.arrow.nms.NmsManager;
import me.arrow.playerdata.data.impl.RodData;
import me.arrow.processors.BukkitListener;
import me.arrow.processors.NetworkListener;
import me.arrow.tasks.LogsTask;
import me.arrow.tasks.TickTask;
import me.arrow.tasks.ViolationTask;
import me.arrow.utils.MiscUtils;
import me.arrow.utils.ReflectionUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.GeyserConfigEnforcer;
import me.arrow.utils.customutils.GuiStuff.GuiListener;
import me.arrow.utils.customutils.GuiStuff.GuiManager;
import me.arrow.utils.customutils.OtherUtility;
import me.arrow.utils.customutils.animationSystem.AnimationManager;
import me.arrow.utils.versionutils.impl.VelocityClientVersionBridge;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import static me.arrow.utils.customutils.OtherUtility.*;
import static org.bukkit.Bukkit.getServer;

// this is gonna be where i say my reasoning for open sourcing so early
// but anyway, it was a private project and i wanted to help smaller servers
// while preventing retards from having access to code they will just mock because it's not perfectly written like they want
// but if you use a tool like GPT to do that for you, then you are being spoon fed or lazy,
// because all it matters, is that they are satisfied with the way you write your code, and you must credit everything on every single class
// the minecraft community is an insanely toxic place, but that wont stop me from making a very good, free anticheat, to help the people who can't afford polar
// obviously, if you can afford polar, then you should buy polar, as i know it's currently the best public anticheat, and much better than this, especially for combat
// will i make velocity checks? i don't know, as i don't want to push checks that wont work on ALL versions, that's why i didn't wanna make inventory checks, but
// apparently it's impossible to detect, from packets, if the player is in his own inventory, so for modern i just made an inventory stealer check
// and will improve it later on, also will detect autoarmor, such as when breaking or totem popping, it should be easy to flag for instant swaps
// such as lower than 10 ms, anyway, the goal here was to make a good movement anticheat that does not lazily exempt every scenario i can't be asked
// while that is happening in some cases right now, it's simply because i don't know how to solve it yet, but i want to, i want to account for every minecraft scenario
// and not lazily ignore them, like verus does, i don't want to bash them too much, as it seems like people will buy anything anyway
// but i am not here for the money, i am here to help people wherever i can (Galatians 6:1-2)

// i am gonna post credits here as well, because probably not everyone will read the readme on github,
// also, there will still be a loader, for the people who wish to not be asked to load through code, and want to get automatic updates
// i will make the loader, as well, open source, thank you everyone for helping me improve this project over the years, if you got any ideas you can always
// talk to me on discord: stel9178, or telegram @stel9178
// i hope we can lower the toxicity of the community and come together to fight cheaters, i also want to support forge and fabric in the future
// but i know this will be insanely difficult without a full physics check, and what would also be fun would be
// minecraft beta and alpha versions support, and older than 1.7 support as well :)
// have a good day, and God bless you all.

// all comments on the classes were written on 13/05/2026, 2:42:30 AM GMT+2. :D


//Credits:
//
//https://modrinth.com/plugin/packetevents
//
//https://github.com/NikV2/AnticheatBase

public final class Arrow {
    @Getter
    private static Arrow instance;

    @Getter
    @Setter
    private boolean floodgatePresent;
    @Getter
    @Setter
    private boolean geyserPresent;

    @Getter
    private static final GuiManager guiManager = new GuiManager();

    @Getter
    private final String version = "107-pre2";

    @Getter
    private final JavaPlugin host;     // the “real” plugin, either ArrowPlugin or ArrowLoader
    private final File dataFolder;     // where configs/checks are stored

    private Config configuration;
    private Checks checks;

    @Getter
    private ProfileManager profileManager;

    @Getter
    private final NmsManager nmsManager = new NmsManager();

    @Getter
    private LogManager logManager;

    @Getter
    private ThreadManager threadManager;

    @Getter
    public String bukkitVersion;

    @Getter
    private AlertManager alertManager;

    @Getter
    private ThemeManager themeManager;

    @Getter
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    @Getter
    private AnimationManager animationManager;

    @Getter
    public boolean hasLoaded;

    public Arrow(JavaPlugin host, File dataFolder) {
        this.host = host;
        this.dataFolder = dataFolder;
    }

    // Called by ArrowPlugin (offline) or ArrowLoader (memory)
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        instance = this;
        PluginManager pm = instance.getHost().getServer().getPluginManager();

        floodgatePresent =
                pm.getPlugin("floodgate") != null
                        || pm.getPlugin("Floodgate") != null
                        || pm.getPlugin("Floodgate-Spigot") != null
                        || pm.getPlugin("floodgate-spigot") != null;

        geyserPresent =
                pm.getPlugin("geyser") != null ||
                        pm.getPlugin("Geyser") != null ||
                        pm.getPlugin("Geyser-Spigot") != null ||
                        pm.getPlugin("Geyser-Velocity") != null ||
                        pm.getPlugin("Geyser-Velocity-Plugin") != null;
        hasLoaded = false;

        TaskUtils.taskLater(() -> {
            log("");
            final String[] HEADER = new String[] {
                    " $$$$$$\\   $$$$$$\\   $$$$$$\\   $$$$$$\\  $$\\  $$\\  $$\\ ",
                    " \\____$$\\ $$  __$$\\ $$  __$$\\ $$  __$$\\ $$ | $$ | $$ |",
                    " $$$$$$$ |$$ |  \\__|$$ |  \\__|$$ /  $$ |$$ | $$ | $$ |",
                    "$$  __$$ |$$ |      $$ |      $$ |  $$ |$$ | $$ | $$ | ",
                    "\\$$$$$$$ |$$ |      $$ |      \\$$$$$$  |\\$$$$$\\$$$$  |",
                    " \\_______|\\__|      \\__|       \\______/  \\_____\\____/ "
            };
            List<String> printMessage = new ArrayList<>(List.of(HEADER));
            printMessage.forEach(msg -> log (translate("&6" + msg)));
            printMessage.removeAll(List.of(HEADER));
            log("");
            log(translate("&6" + "=================================================================="));
            log(translate("&6" + "➪  Version&7: &b" +  getVersion()));
            log("");
            PacketEvents.getAPI().init();
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(Arrow.getInstance().getHost()));
            PacketEvents.getAPI().load();

            if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_8)) {
                OtherUtility.log(translate("&cUNSUPPORTED MINECRAFT VERSION, DISABLING"));
                getInstance().onDisable();
            }

            (this.configuration = new Config(getHost())).initialize();
            log(translate("&6" + "➪  Config file Initialized"));
            (this.checks = new Checks(getHost())).initialize();
            log(translate("&6" + "➪  Checks file Initialized"));



            PacketEvents.getAPI().getEventManager().registerListener(new NetworkListener(this));
            log(translate("&6" + "➪  NetworkListener Initialized"));

            (this.profileManager = new ProfileManager()).initialize();
            log(translate("&6" + "➪  Profile Manager Initialized"));
            (this.themeManager = new ThemeManager(getHost())).initialize();
            log(translate("&6" + "➪  Theme Manager Initialized"));
            (this.logManager = new LogManager(getHost())).initialize();
            log(translate("&6" + "➪  Log Manager Initialized"));
            (this.threadManager = new ThreadManager(this)).initialize();
            log(translate("&6" + "➪  Thread Manager Initialized"));
            (this.alertManager = new AlertManager()).initialize();
            log(translate("&6" + "➪  Alert Manager Initialized"));
            this.bukkitVersion = getServer().getClass().getPackage().getName().substring(22);

            logBedrockSupport();

            VelocityClientVersionBridge.register(getInstance().getHost());

            (new TickTask(this)).runTaskTimerAsynchronously(host, 50L, 0L);
            log(translate("&6" + "➪  TickTask Initialized"));
            if (Config.Setting.LOGS_ENABLED.getBoolean()) {
                (new LogsTask(this)).runTaskTimerAsynchronously(host, 6000L, 6000L);
                log(translate("&6" + "➪  LogTask Initialized"));
            }

            (new ViolationTask(this)).runTaskTimerAsynchronously(host,
                    Config.Setting.CHECK_SETTINGS_VIOLATION_RESET_INTERVAL.getLong() * 1200L,
                    Config.Setting.CHECK_SETTINGS_VIOLATION_RESET_INTERVAL.getLong() * 1200L);
            log(translate("&6" + "➪  ViolationTask Initialized"));

            Bukkit.getPluginManager().registerEvents(new ProfileListener(this), host);
            Bukkit.getPluginManager().registerEvents(new ViolationListener(this), host);
            Bukkit.getPluginManager().registerEvents(new BukkitListener(), host);
            Bukkit.getPluginManager().registerEvents(new GuiListener(), host);



            log(translate("&6" + "➪  Bukkit Listeners Initialized"));

            ArrowAPIProvider.set(new ArrowAPIImpl(profileManager));

            log(translate("&6" + "➪  API Initialized"));

            Objects.requireNonNull(getHost().getCommand("arrow")).setExecutor(new CommandManager(this));

            log(translate("&6" + "➪  Command Manager Initialized"));

            if (Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
                Objects.requireNonNull(getHost().getCommand("stuck")).setExecutor(new Stuck());
            }

            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) System.setProperty("com.viaversion.handlePingsAsInvAcknowledgements", "true");
            try {
                MiscUtils.initializeClasses(
                        "me.arrow.utils.fastmath.FastMath",
                        "me.arrow.utils.fastmath.NumbersUtils",
                        "me.arrow.utils.fastmath.FastMathLiteralArrays",
                        "me.arrow.utils.minecraft.MathHelper",
                        "me.arrow.utils.CollisionUtils",
                        "me.arrow.utils.MoveUtils"
                );
                log(translate("&6" + "➪  MathUtil Initialized"));
            } catch (ClassNotFoundException e) {
                log(translate("&cAn error was thrown during initialization, The anticheat may not work properly."));
                e.printStackTrace();
            }

            this.animationManager = new AnimationManager(getHost());
            log(translate("&6" + "➪  Animation Manager initialized"));

            RodData.init(getHost());

            long endTime = System.currentTimeMillis();


            log("");
            log(translate("&6" + "➪  Plugin loaded in &b" + (endTime - startTime) + "ms"));
            log("");
            log(translate("&6" + "➪  Arrow &ahas been successfully enabled."));
            log(translate("&6" + "=================================================================="));
            log("");

            hasLoaded = true;

        }, 10L);

        TaskUtils.taskLater(() -> {
            log("➪ Remember, stay away from the cult of islam.");
            log("➪ The cult that degrades young girls, Quran 65:4");
            log("➪ Calls them braindead. 2:282");
            log("➪ And your sex slaves. 2:223, 4:24");
            log("");
            log("➪ Ephesians 5:11 NRSVUE");
            log("➪ Take no part in the unfruitful works of darkness; rather, expose them.");
        }, 20L);
    }

    public void onDisable() {
        long startTime = System.currentTimeMillis();
        try {
            this.configuration.shutdown();
            this.checks.shutdown();
            this.profileManager.shutdown();
            this.alertManager.shutdown();
            this.threadManager.shutdown();
            this.themeManager.shutdown();
            ArrowAPIProvider.clear();
            VelocityClientVersionBridge.unregister(getInstance().getHost());
            ReflectionUtils.clear();
            HandlerList.unregisterAll(host);
            Bukkit.getScheduler().cancelTasks(host);
            instance = null;
        } catch (Exception exception) {
            exception.printStackTrace();
            log(translate("&cArrow &chad an error while shutting down."));
            return;
        }
        long endTime = System.currentTimeMillis();
        log("");
        log(translate("&6" + "=================================================================="));
        log(translate("&6" + "➪  Arrow &chas been successfully disabled."));
        log("");
        log(translate("&6" + "➪  Plugin shutdown in &b" + (endTime - startTime) + "ms"));
        log("");
        log(translate("&6" + "➪  Version&7: &b" + getVersion()));
        log(translate("&6" + "=================================================================="));
        log("");
    }

    public CommentedFileConfiguration getConfiguration() {
        return this.configuration.getConfig();
    }

    public CommentedFileConfiguration getChecks() {
        return this.checks.getConfig();
    }

    public PluginDescriptionFile getDescription() {
        return host.getDescription();
    }

    public File getDataFolder() {
        return dataFolder != null ? dataFolder : host.getDataFolder();
    }

    @Getter
    private long serverTick;

    public void logBedrockSupport() {
        boolean enabled = floodgatePresent || geyserPresent;
        log(translate("&6➪  Bedrock support is " + (enabled ? "&aENABLED" : "&cDISABLED")));

        if (Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")
                || Bukkit.getPluginManager().isPluginEnabled("Geyser-Bukkit")
                || Bukkit.getPluginManager().isPluginEnabled("Geyser")) {

            GeyserConfigEnforcer.enforceForwardPlayerPing(getHost(), true);
        }
    }

}

