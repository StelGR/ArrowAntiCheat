package me.arrow.files;

import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.files.commentedfiles.CommentedFileConfiguration;
import me.arrow.managers.Initializer;
import me.arrow.utils.customutils.animationSystem.Animation;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

@Getter
public class Config implements Initializer {

    private static final String[] HEADER = new String[] {
            " $$$$$$\\   $$$$$$\\   $$$$$$\\   $$$$$$\\  $$\\  $$\\  $$\\ ",
            " \\____$$\\ $$  __$$\\ $$  __$$\\ $$  __$$\\ $$ | $$ | $$ |",
            " $$$$$$$ |$$ |  \\__|$$ |  \\__|$$ /  $$ |$$ | $$ | $$ |",
            "$$  __$$ |$$ |      $$ |      $$ |  $$ |$$ | $$ | $$ | ",
            "\\$$$$$$$ |$$ |      $$ |      \\$$$$$$  |\\$$$$$\\$$$$  |",
            " \\_______|\\__|      \\__|       \\______/  \\_____\\____/ "
    };

    private final JavaPlugin plugin;
    private CommentedFileConfiguration configuration;
    private static boolean exists;

    public Config(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return the config.yml as a CommentedFileConfiguration
     */
    public CommentedFileConfiguration getConfig() {
        return this.configuration;
    }

    @Override
    public void initialize() {

        File configFile = new File(this.plugin.getDataFolder(), "config.yml");

        exists = configFile.exists();

        boolean setHeaderFooter = !configFile.exists();

        boolean changed = setHeaderFooter;

        this.configuration = CommentedFileConfiguration.loadConfiguration(this.plugin, configFile);

        if (setHeaderFooter) this.configuration.addComments(HEADER);

        for (Setting setting : Setting.values()) {

            setting.reset();

            changed |= setting.setIfNotExists(this.configuration);
        }

        if (changed) this.configuration.save();
    }

    @Override
    public void shutdown() {
        for (Setting setting : Setting.values()) setting.reset();
    }

    public enum Setting {
        THEME("theme", "default", "The theme that the anticheat is going to use"),

        TOGGLE_ALERTS_ON_JOIN("toggle_alerts_on_join", true, "Should we enable alerts for admins when they join?"),

        IGNORE_BEDROCK("ignore_bedrock_players", false, "Should we ignore bedrock players? I don't recommend this, unless you are facing issues with bedrock players."),

        TRANSACTION_KICKS("transaction_kicks", true, "Should we kick for transactions? Do not turn this off unless you are facing issues, because the entire anti cheat almost can be disabled by a cheater cancelling transaction packets."),

        BAN_ANIMATION("animation", "", "Animation Settings"),
        BAN_ANIMATION_ENABLED("animation.enabled", true, "Should we use ban animations?"),
        BAN_ANIMATION_CURRENT("animation.currentAnimation", Animation.Type.DESTROYED.name(), "Which animation should we use? (ANVIL, DESTROYED or LOUD)"),

        //DISABLE_BYPASS_PERMISSION("disable_bypass_permission", true, "Should we disable the bypass permission?", "Disable this for some perfomance gain"),

        CHECK_SETTINGS("check_settings", "", "Check Settings"),
        CHECK_SETTINGS_ALERT_CONSOLE("check_settings.alert_console", false, "Should we also send alerts in console?"),
        CHECK_SETTINGS_VIOLATION_RESET_INTERVAL("check_settings.violation_reset_interval", 10, "How often should we clear the player violations? (In minutes)"),

        LOGS("logs", "", "Log Settings"),
        LOGS_ENABLED("logs.enabled", true, "Should we enable logging?"),
        //LOGS_TYPE("logs.type", "YAML", "What type of Database should we use for logging?"),
        LOGS_CLEAR_DAYS("logs.clear_days", 7, "Logs older than this value of Days will be cleared"),

        PUNISH("punishments", "", "Punishment settings"),
        PUNISH_ENABLED("punishments.enabled", true, "Should we punish players for cheating?"),
        PUNISH_COMMAND("punishments.command", "ban %player% &8[&6Arrow&8] &cUnfair Advantage", "The command we should run to punish the player"),

        WEBHOOK("webhook", "", "Webhook settings"),
        WEBHOOK_ENABLED("webhook.enabled", false, "Should we print anticheat alerts and punishments in our discord?"),
        WEBHOOK_FREQUENCY("webhook.frequency", 5, "How often should an alert be sent in chat. It goes by alert values, so every 5, or 10, or 15. 5 is the minimum to prevent discord from being spammed."),
        WEBHOOK_LINK("webhook.link", "", "The discord webhook link, if empty it will be disabled."),

        TEST_SERVER_MODE("test_server_mode", "", "testserver mode settings"),
        TEST_SERVER_MODE_ENABLED("test_server_mode.enabled", false, "Should we enable the test server mode?", " This is used to test the anticheat in your own server environment before pushing to production"),
        TEST_SERVER_MODE_PREVENT_DAMAGE("test_server_mode.prevent_damage", true, "Should we prevent all players from taking any damage?","This is useful if you want to test combat checks without the worry of death"),
        TEST_SERVER_MODE_WORLD("test_server_mode.world", "anticheattest", "The world test server mode works in"),
        TEST_SERVER_MODE_ENABLED_MOTD("test_server_mode.motd", true, "Should we let the test server motd override the current server motd?"),


        TEST_SERVER_MODE_BUILD_ZONE("test_server_mode.build_zone", "", "Test server mode build zone settings"),
        TEST_SERVER_MODE_BUILD_ZONE_ENABLE("test_server_mode.build_zone.enabled", false, "Should we enable custom build zone for the test server mode?","Enabling this wont allow you to place/break anywhere else in survival", "Must have test server mode enabled for this to work"),
        TEST_SERVER_MODE_BUILD_ZONE_REGION("test_server_mode.build_zone.region", "10, 0, -10 | -10, 50, 10", "Where should we place the build zone?","(Format must be exactly like the default or it will cause errors)"),
        TEST_SERVER_MODE_BUILD_ZONE_ITEMS("test_server_mode.build_zone.items", false, "Should we give custom items for people to use in our build zone and other areas?", "Do not use this if you have other items because it would clear peoples items for a diamond sword and diamond blocks (this also makes the diamond blocks re-apear after placing in your inventory if enabled)"),
        TEST_SERVER_MODE_BUILD_ZONE_SPAWN("test_server_mode.build_zone.spawn", "0, 1, 0", "Spawn point when build mode is enabled"),
        DEBUG("debug", false, "DO NOT TOUCH UNLESS YOU KNOW WHAT YOU ARE DOING");

        @Getter
        private final String key;
        private final Object defaultValue;
        private boolean excluded;
        private final String[] comments;
        private Object value = null;

        Setting(String key, Object defaultValue, String... comments) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.comments = comments != null ? comments : new String[0];
        }

        Setting(String key, Object defaultValue, boolean excluded, String... comments) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.comments = comments != null ? comments : new String[0];
            this.excluded = excluded;
        }

        /**
         * Gets the setting as a boolean
         *
         * @return The setting as a boolean
         */
        public boolean getBoolean() {
            this.loadValue();
            return (boolean) this.value;
        }

        /**
         * @return the setting as an int
         */
        public int getInt() {
            this.loadValue();
            return (int) this.getNumber();
        }

        /**
         * @return the setting as a long
         */
        public long getLong() {
            this.loadValue();
            return (long) this.getNumber();
        }

        /**
         * @return the setting as a double
         */
        public double getDouble() {
            this.loadValue();
            return this.getNumber();
        }

        /**
         * @return the setting as a float
         */
        public float getFloat() {
            this.loadValue();
            return (float) this.getNumber();
        }

        /**
         * @return the setting as a String
         */
        public String getString() {
            this.loadValue();
            return String.valueOf(this.value);
        }

        private double getNumber() {
            if (this.value instanceof Integer) {
                return (int) this.value;
            } else if (this.value instanceof Short) {
                return (short) this.value;
            } else if (this.value instanceof Byte) {
                return (byte) this.value;
            } else if (this.value instanceof Float) {
                return (float) this.value;
            }

            return (double) this.value;
        }

        /**
         * @return the setting as a string list
         */
        @SuppressWarnings("unchecked")
        public List<String> getStringList() {
            this.loadValue();
            return (List<String>) this.value;
        }

        private boolean setIfNotExists(CommentedFileConfiguration fileConfiguration) {
            this.loadValue();

            if (exists && this.excluded) return false;

            if (fileConfiguration.get(this.key) == null) {
                List<String> comments = Stream.of(this.comments).toList();
                if (this.defaultValue != null) {
                    fileConfiguration.set(this.key, this.defaultValue, comments.toArray(new String[0]));
                } else {
                    fileConfiguration.addComments(comments.toArray(new String[0]));
                }

                return true;
            }

            return false;
        }

        /**
         * Resets the cached value
         */
        public void reset() {
            this.value = null;
        }

        public void setValue(String value) {
            this.value = value;
            Arrow.getInstance().getConfiguration().set(this.key, value);
            Arrow.getInstance().getConfiguration().save();
        }

        public void setValue(double value) {
            this.value = value;
            Arrow.getInstance().getConfiguration().set(this.key, value);
            Arrow.getInstance().getConfiguration().save();
        }

        public void setValue(int value) {
            this.value = value;
            Arrow.getInstance().getConfiguration().set(this.key, value);
            Arrow.getInstance().getConfiguration().save();
        }

        public void setValue(float value) {
            this.value = value;
            Arrow.getInstance().getConfiguration().set(this.key, value);
            Arrow.getInstance().getConfiguration().save();
        }

        public void setValue(long value) {
            this.value = value;
            Arrow.getInstance().getConfiguration().set(this.key, value);
            Arrow.getInstance().getConfiguration().save();
        }

        public void setValue(boolean value) {
            this.value = value;
            Arrow.getInstance().getConfiguration().set(this.key, value);
            Arrow.getInstance().getConfiguration().save();
        }


        /**
         * @return true if this setting is only a section and doesn't contain an actual value
         */
        public boolean isSection() {
            return this.defaultValue == null;
        }

        /**
         * Loads the value from the config and caches it if it isn't set yet
         */
        private void loadValue() {
            if (this.value != null) return;
            this.value = Arrow.getInstance().getConfiguration().get(this.key);
        }
    }
}