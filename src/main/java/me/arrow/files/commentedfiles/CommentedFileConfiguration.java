package me.arrow.files.commentedfiles;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.DumperOptions;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.stream.Stream;
import org.bukkit.configuration.InvalidConfigurationException;

public class CommentedFileConfiguration extends CommentedConfigurationSection {

    private int comments;
    private final CommentedFileConfigurationHelper helper;
    private final File file;
    private final boolean safeToSave;

    public CommentedFileConfiguration(Reader configStream, File configFile, int comments, JavaPlugin plugin) {
        this(loadExistingConfiguration(configStream, configFile), configFile, comments, plugin);
    }

    private CommentedFileConfiguration(LoadedConfiguration loaded, File configFile, int comments, JavaPlugin plugin) {
        super(loaded.configuration);
        this.comments = comments;
        this.helper = new CommentedFileConfigurationHelper(plugin);
        this.file = configFile;
        this.safeToSave = loaded.safeToSave;
    }

    private static LoadedConfiguration loadExistingConfiguration(Reader configStream, File configFile) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.load(configStream);
            return new LoadedConfiguration(parsed, true);
        } catch (InvalidConfigurationException | java.io.IOException transformedFailure) {
            Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not parse comment-preserving YAML for " + configFile.getName()
                            + "; retrying the original file and disabling saves for this load.", transformedFailure);
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfiguration", "caught-exception", transformedFailure,
                    "file=" + configFile.getName() + ", parser=comment-preserving");
        }

        // A broken comment conversion must never make the plugin treat an existing
        // user's file as empty and save defaults over it. YAML ignores comments, so
        // parsing the original file recovers every setting while we leave the file
        // untouched for manual/next-build repair.
        YamlConfiguration raw = new YamlConfiguration();
        try {
            raw.load(configFile);
            return new LoadedConfiguration(raw, false);
        } catch (InvalidConfigurationException | java.io.IOException rawFailure) {
            Bukkit.getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not parse original YAML for " + configFile.getName()
                            + "; leaving the existing file untouched.", rawFailure);
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfiguration", "caught-exception", rawFailure,
                    "file=" + configFile.getName() + ", parser=raw-yaml");
            return new LoadedConfiguration(raw, false);
        }
    }

    private static final class LoadedConfiguration {
        private final YamlConfiguration configuration;
        private final boolean safeToSave;

        private LoadedConfiguration(YamlConfiguration configuration, boolean safeToSave) {
            this.configuration = configuration;
            this.safeToSave = safeToSave;
        }
    }

    public static CommentedFileConfiguration loadConfiguration(JavaPlugin plugin, File file) {
        return new CommentedFileConfigurationHelper(plugin).getNewConfig(file);
    }

    public void set(String path, Object value, String... comments) {
        if (!this.contains(path)) {
            int subpathIndex = path.lastIndexOf('.');
            String subpath = subpathIndex == -1 ? "" : path.substring(0, subpathIndex) + '.';

            for (String comment : comments) {
                String line = comment.isEmpty() ? "" : " " + comment;
                this.set(subpath + this.helper.getPluginName() + "_COMMENT_" + this.comments, line);
                this.comments++;
            }

        }

        this.set(path, value);
    }

    public void addComments(String... comments) {
        for (String comment : comments) {
            this.set(this.helper.getPluginName() + "_COMMENT_" + this.comments, " " + comment);
            this.comments++;
        }
    }

    public void reloadConfig() {
        YamlConfiguration reloaded = new YamlConfiguration();
        try {
            reloaded.load(this.file);
        } catch (Exception e) {
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfiguration", "caught-exception", e);
            return;
        }

        copyCommentKeys(this.config, reloaded);
        this.config = reloaded;
    }

    private void copyCommentKeys(ConfigurationSection source, ConfigurationSection destination) {
        String commentPrefix = this.helper.getPluginName() + "_COMMENT_";
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (key.startsWith(commentPrefix)) {
                destination.set(key, value);
            } else if (value instanceof ConfigurationSection) {
                ConfigurationSection destinationSection = destination.getConfigurationSection(key);
                if (destinationSection == null) {
                    destinationSection = destination.createSection(key);
                }
                copyCommentKeys((ConfigurationSection) value, destinationSection);
            }
        }
    }

    public void save() {
        this.save(false);
    }

    public void save(boolean compactLines) {
        if (!this.safeToSave) return;
        String config = this.getConfigAsString();
        this.helper.saveConfig(config, this.file, compactLines);
    }

    public void save(File file) {
        this.save(file, false);
    }

    public void save(File file, boolean compactLines) {
        if (!this.safeToSave) return;
        String config = this.getConfigAsString();
        this.helper.saveConfig(config, file, compactLines);
    }

    public boolean needsFormatting() {
        return this.safeToSave && this.helper.needsFormatting(this.file);
    }

    public boolean isSafeToSave() {
        return this.safeToSave;
    }

    private String getConfigAsString() {
        if (!(this.config instanceof YamlConfiguration))
            throw new UnsupportedOperationException("Cannot get config string of non-YamlConfiguration");

        YamlConfiguration yamlConfiguration = (YamlConfiguration) this.config;

        // Edit the configuration to how we want it
        try {
            Field field_yamlOptions;
            try {
                field_yamlOptions = YamlConfiguration.class.getDeclaredField("yamlOptions");
            } catch (NoSuchFieldException e) { // This is used for 1.18.1+
                field_yamlOptions = YamlConfiguration.class.getDeclaredField("yamlDumperOptions");
            }

            field_yamlOptions.setAccessible(true);
            DumperOptions yamlOptions = (DumperOptions) field_yamlOptions.get(yamlConfiguration);
            yamlOptions.setWidth(Integer.MAX_VALUE);

//            if (Stream.of(DumperOptions.class.getDeclaredMethods()).anyMatch(x -> x.getName().equals("setIndicatorIndent")))
//                yamlOptions.setIndicatorIndent(2);
//
//            if (Stream.of(DumperOptions.class.getDeclaredMethods()).anyMatch(x -> x.getName().equals("setProcessComments")))
//                yamlOptions.setProcessComments(false);

            if (Stream.of(DumperOptions.class.getDeclaredMethods()).anyMatch(x -> x.getName().equals("setSplitLines")))
                yamlOptions.setSplitLines(false);
        } catch (ReflectiveOperationException e) {
            me.arrow.managers.errors.ErrorMonitor.report("CommentedFileConfiguration", "caught-exception", e);
        }

        return yamlConfiguration.saveToString();
    }

}
