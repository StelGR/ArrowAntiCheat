package me.arrow.files;

import me.arrow.Arrow;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

//Currently Unused, will be used in the future for anti-xray
public class BlockLocations {
    private static File file;
    private static FileConfiguration info;

    public static void setup() {
        file = new File(Arrow.getInstance().getDataFolder(), "placedblocks.yml");
        if (!file.exists())
            try {
                file.createNewFile();
            } catch (IOException ee) {
                me.arrow.managers.errors.ErrorMonitor.report("BlockLocations", "create-data-file", ee,
                        "path=" + file.getAbsolutePath());
            }
        info = YamlConfiguration.loadConfiguration(file);
    }

    public static FileConfiguration get() {
        return info;
    }

    public static void save() {
        try {
            info.save(file);
        } catch (IOException e) {
            me.arrow.managers.errors.ErrorMonitor.report("BlockLocations", "save-data-file", e,
                    "path=" + (file == null ? "unknown" : file.getAbsolutePath()));
        }
    }

    public static void reload() {
        info = YamlConfiguration.loadConfiguration(file);
    }
}
