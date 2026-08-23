package me.arrow.managers.themes;

import me.arrow.utils.MiscUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public abstract class BaseTheme {

    private final FileConfiguration data;

    public BaseTheme(JavaPlugin plugin, String themeName) {
        File dataFolder = plugin != null ? plugin.getDataFolder() : me.arrow.Arrow.getInstance().getDataFolder();
        File themesFolder = new File(dataFolder, "themes");
        if (!themesFolder.exists()) {
            themesFolder.mkdirs();
        }
        File file = new File(themesFolder, themeName + ".yml");

        try {

            file.createNewFile();

        } catch (IOException ignored) {
        }

        this.data = MiscUtils.loadConfigurationUTF_8(file);

        create();

        get().options().copyDefaults(true);

        try {

            this.data.save(file);

        } catch (IOException ignored) {
        }
    }

    protected FileConfiguration get() {
        return this.data;
    }

    public abstract void create();
}