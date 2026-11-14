package me.arrow.managers.themes.impl;


import me.arrow.managers.themes.BaseTheme;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.events.Event;

import java.util.Arrays;

public class EveTheme extends BaseTheme {
    public EveTheme(JavaPlugin plugin, String themeName) {
        super(plugin, themeName);
    }

    @Override
    public void create() {
        get().addDefault("main_theme_color", "&a");
        get().addDefault("second_theme_color", "&7");
        get().addDefault("experimental_symbol", "⚠");
        get().addDefault("hover_symbol", "★");
        get().addDefault("prefix", "&aEve ");
        get().addDefault("no_perm", "&cYou do not have permission to do that!");
        get().addDefault("console_commands", "&4&lYou cannot run this command through the console.");
        get().addDefault("alert_message", "&7caught &a%player% &7for &a%checknameandtype%%experimental% &8[&7VL: &a%vl%&7/&a%maxvl%&8]");
        get().addDefault("punish_broadcast",
                Arrays.asList(
                        "&a&l%player% &7&lhas been removed by &a&lEve &7&lfor &c&lCheating&7&l."
                ));
    }
}
