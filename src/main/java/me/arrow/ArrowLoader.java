package me.arrow;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;


// this was used to have the loader call Arrow class outside of the plugin, but the plugin it self can still be loaded normally
// The premium tier has been removed, the entire anticheat will be free to use and open source
public class ArrowLoader extends JavaPlugin {
    @Getter
    @Setter
    private Arrow arrow;

    @Getter
    public static ArrowLoader instance;

    @Getter
    public File jarFile = getFile();


    @Override
    public void onEnable() {
        instance = this;
        arrow = new Arrow(this, this.getDataFolder());
        arrow.onEnable();
    }

    @Override
    public void onDisable() {
        if (arrow != null) arrow.onDisable();
    }

}
