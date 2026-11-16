package me.arrow.managers.themes;

import lombok.Getter;
import me.arrow.utils.MiscUtils;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;

public class Theme {

    @Getter
    private final File file;
    private final String name;
    @Getter
    private FileConfiguration config;

    public Theme(File file) {
        this.file = file;
        this.name = file.getName().replace(".yml", "");
        reload();
    }

    public void reload() {
        try {
            this.config = MiscUtils.loadConfigurationUTF_8(this.file);
            this.config.save(this.file);
        } catch (IOException e) {
            me.arrow.managers.errors.ErrorMonitor.report("Theme", "caught-exception", e);
        }
    }

    public String getAuthor() {
        return this.config.getString("theme_author");
    }

    public String getPrefix() {
        return this.config.getString("prefix");
    }

    public String getString(String path) {
        return this.config.getString(path, "null");
    }

    public String getThemeName() {
        return this.name;
    }
}