package me.arrow.managers.themes.impl;

import me.arrow.managers.themes.BaseTheme;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public class ExampleTheme extends BaseTheme {
    public ExampleTheme(JavaPlugin plugin, String themeName) {
        super(plugin, themeName);
    }

    @Override
    public void create() {
        get().addDefault("main_theme_color", "&9");
        get().addDefault("second_theme_color", "&f");
        get().addDefault("experimental_symbol", " &8(&9Experimental&8)");
        get().addDefault("hover_symbol", "*");
        get().addDefault("prefix", "&9Example &7- ");
        get().addDefault("no_perm", "&cYou do not have permission to do that!");
        get().addDefault("console_commands", "&c&lYou cannot run this command through the console.");
        get().addDefault("alert_message", "&9%player% &ffailed &9%checkname% &8(&9%checktype%&8)%experimental% &8[&8VL: &9%vl%&8/&9%maxvl%&8, &8Ping: &9%ping%&8, &8TPS: &9%tps%&8]");
        get().addDefault("punish_broadcast",
                Arrays.asList(
                        "&6&l%player% &7&lhas been removed by &6&lAntiCheat &7&lfor &c&lCheating&7&l."
                ));
    }
}