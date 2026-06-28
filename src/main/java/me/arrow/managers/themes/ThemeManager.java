package me.arrow.managers.themes;

import lombok.Getter;
import me.arrow.files.Config;
import me.arrow.managers.Initializer;
import me.arrow.managers.themes.impl.ArrowTheme;
import me.arrow.managers.themes.impl.DefaultTheme;
import me.arrow.managers.themes.impl.ExampleTheme;
import me.arrow.managers.themes.impl.SonixTheme;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThemeManager implements Initializer {

    private final JavaPlugin plugin;

    @Getter
    private final List<Theme> themes = new ArrayList<>();

    @Getter
    private Theme theme;

    public ThemeManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void initialize() {

        final File folder = new File(this.plugin.getDataFolder(), "themes");

        if (!folder.exists()) folder.mkdirs();

        Arrays.asList(
                new DefaultTheme(this.plugin, "default"),
                new ArrowTheme(this.plugin, "arrow"),
                new SonixTheme(this.plugin, "sonix"),
                new ExampleTheme(this.plugin, "example")
                //
        ).forEach(BaseTheme::create);

        final File[] files = folder.listFiles();

        if (files == null) return;

        Arrays.stream(files).filter(File::isFile).forEach(file -> this.themes.add(new Theme(file)));

        this.themes.forEach(Theme::reload);

        setThemeFromName(Config.Setting.THEME.getString());

        final Theme defaultTheme = getThemeByName("default");

        if (this.theme == null) this.theme = defaultTheme;

        if (this.theme != defaultTheme) {

            AtomicBoolean changed = new AtomicBoolean(false);

            defaultTheme.getConfig().getKeys(false)
                    .stream()
                    .filter(key -> !this.theme.getConfig().getKeys(false).contains(key))
                    .forEach(key -> {

                        this.theme.getConfig().addDefault(key, defaultTheme.getConfig().get(key));

                        if (!changed.get()) changed.set(true);
                    });

            if (changed.get()) {

                try {

                    this.theme.getConfig().save(this.theme.getFile());

                    this.theme.reload();

                } catch (IOException e) {

                    e.printStackTrace();
                }
            }
        }
    }

    public void reload() {
        shutdown();
        initialize();
    }

    public void setThemeFromName(String name) {
        this.theme = getThemeByName(name);
    }

    public Theme getThemeByName(String name) {

        for (Theme theme : this.themes) {

            if (theme.getThemeName().equalsIgnoreCase(name)) {

                return theme;
            }
        }

        return null;
    }

    @Override
    public void shutdown() {
        this.themes.clear();
    }
}