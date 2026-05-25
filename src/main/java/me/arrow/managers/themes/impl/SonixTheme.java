package me.arrow.managers.themes.impl;

import me.arrow.managers.themes.BaseTheme;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public class SonixTheme extends BaseTheme {
    public SonixTheme(JavaPlugin plugin, String themeName) {
        super(plugin, themeName);
    }

    @Override
    public void create() {
        get().addDefault("main_theme_color", "&c");
        get().addDefault("second_theme_color", "&f");
        get().addDefault("experimental_symbol", "&c⚠");
        get().addDefault("hover_symbol", "*");
        get().addDefault("prefix", "&0[&cSonix&0] &c");
        get().addDefault("no_perm", "&cYou do not have permission to do that!");
        get().addDefault("console_commands", "&c&lYou cannot run this command through the console :(");
        get().addDefault("alert_message", "&c%player% &ffailed &c%check%&0[%debug%&0]");
        get().addDefault("punish_broadcast",
                Arrays.asList(
                        "&7&m                                                ",
                        "&6&l✗ &c&l%player% &7&lhas been removed by &0&l&k➢&c&lSonix&0&l&k➢ &7&lfor &c&lCheating&7&l.",
                        "&7&m                                                "
                ));
    }
}

