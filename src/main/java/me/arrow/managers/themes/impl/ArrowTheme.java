package me.arrow.managers.themes.impl;

import me.arrow.managers.themes.BaseTheme;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public class ArrowTheme extends BaseTheme {
    public ArrowTheme(JavaPlugin plugin, String themeName) {
        super(plugin, themeName);
    }

    @Override
    public void create() {
        get().addDefault("main_theme_color", "&6");
        get().addDefault("second_theme_color", "&f");
        get().addDefault("experimental_symbol", "&6⚠");
        get().addDefault("hover_symbol", "⇒");
        get().addDefault("prefix", "&6&l➢ ");
        get().addDefault("no_perm", "&cYou do not have permission to do that!");
        get().addDefault("console_commands", "&c&lYou cannot run this command through the console :(");
        get().addDefault("alert_message", "&6%player% &ffailed &6%check%&8[%debug%&8]");
        get().addDefault("punish_broadcast",
                Arrays.asList(
                        "&7&m                                                ",
                        "&6&l✗ &6&l%player% &7&lhas been removed by &c&l&k➢&6&lArrow&c&l&k➢ &7&lfor &c&lCheating&7&l.",
                        "&7&m                                                "
                ));
    }
}
