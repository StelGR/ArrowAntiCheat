package me.arrow.managers.profile;

import me.arrow.Arrow;
import me.arrow.managers.Initializer;
import me.arrow.platform.PlatformBackend;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A profile manager class that we'll use in order to create or get a player profile.
 */
public class ProfileManager implements Initializer {

    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();

    @Override
    public void initialize() {
        if (PlatformBackend.get().getServer() == null) return;

        if (!Arrow.getInstance().isHasLoaded()) {
            PlatformBackend.get().getServer().getOnlinePlayers().stream().filter(Objects::nonNull).forEach(player -> player.kickPlayer("Server is still loading, please wait."));
            return;
        }
        PlatformBackend.get().getServer().getOnlinePlayers()
                .stream()
                .filter(Objects::nonNull)
                .forEach(this::createProfile);
    }

    public void createProfile(Player player) {

        UUID uuid = player.getUniqueId();

        if (this.profiles.containsKey(uuid)) return;

        this.profiles.put(uuid, new Profile(player));
    }

    public void removeProfile(Player player) {
        this.profiles.remove(player.getUniqueId());
    }

    public Profile getProfile(Player player) {
        return this.profiles.get(player.getUniqueId());
    }

    public Profile getProfile(UUID uuid) {
        return this.profiles.get(uuid);
    }

    public Map<UUID, Profile> getProfileMap() {
        return this.profiles;
    }

    @Override
    public void shutdown() {
        this.profiles.clear();
    }
}