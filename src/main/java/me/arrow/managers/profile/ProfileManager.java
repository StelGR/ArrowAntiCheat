package me.arrow.managers.profile;

import me.arrow.Arrow;
import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.backend.bukkit.listener.ClientBrandListener;
import me.arrow.managers.Initializer;
import me.arrow.backend.bukkit.PlatformBackend;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A profile manager class that we'll use in order to create or get a player profile.
 */
public class ProfileManager implements Initializer {

    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private File profileFile;
    private FileConfiguration profileData;

    @Override
    public void initialize() {
        loadProfileData();

        if (PlatformBackend.get().getServer() == null) return;

        if (!Arrow.getInstance().isHasLoaded() && !Arrow.isReloading()) {
            PlatformBackend.get().getServer().getOnlinePlayers().stream().filter(Objects::nonNull).forEach(player -> player.kickPlayer("Server is still loading, please wait."));
            return;
        }
        PlatformBackend.get().getServer().getOnlinePlayers()
                .stream()
                .filter(Objects::nonNull)
                .forEach(this::createProfile);
    }

    public synchronized void createProfile(Player player) {

        UUID uuid = player.getUniqueId();

        if (this.profiles.containsKey(uuid)) return;

        Profile profile = new Profile(player);
        String pendingBrand = ClientBrandListener.consumePendingBrand(uuid);
        if (pendingBrand != null) {
            profile.setClient(pendingBrand);
        }
        String path = "profiles." + uuid;
        boolean hasSavedProfile = profileData != null && profileData.isConfigurationSection(path);

        profile.setAlerts(hasSavedProfile
                ? profileData.getBoolean(path + ".alerts", false)
                : Config.Setting.TOGGLE_ALERTS_ON_JOIN.getBoolean()
                    && player.hasPermission(Permissions.ALERTS.getPermission()));
        profile.setVelocityAlerts(!hasSavedProfile || profileData.getBoolean(path + ".velocity-alerts", true));
        profile.setTrustScore(hasSavedProfile ? profileData.getDouble(path + ".trust-score", 70.0) : 70.0);
        profile.setTotalLogs(hasSavedProfile ? profileData.getInt(path + ".total-logs", 0) : 0);
        this.profiles.put(uuid, profile);
    }

    public void removeProfile(Player player) {
        Profile profile = this.profiles.remove(player.getUniqueId());
        if (profile != null) {
            saveProfile(profile);
        }
    }

    public synchronized void saveProfile(Profile profile) {
        if (profile == null || profileData == null) return;

        String path = "profiles." + profile.getUUID();
        profileData.set(path + ".uuid", profile.getUUID().toString());
        profileData.set(path + ".name", profile.getPlayer() == null ? "Unknown" : profile.getPlayer().getName());
        profileData.set(path + ".alerts", profile.isAlerts());
        profileData.set(path + ".velocity-alerts", profile.isVelocityAlerts());
        profileData.set(path + ".trust-score", profile.getTrustScore());

        if (Arrow.getInstance() != null && Arrow.getInstance().getLogManager() != null) {
            String name = profile.getPlayer() == null ? null : profile.getPlayer().getName();
            if (name != null) {
                profile.setTotalLogs(Arrow.getInstance().getLogManager()
                        .getTotalLogsForPlayer(profile.getUUID(), name));
            }
        }
        profileData.set(path + ".total-logs", profile.getTotalLogs());
        saveProfileData();
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
    public synchronized void shutdown() {
        for (Profile profile : this.profiles.values()) {
            if (profile == null || profileData == null) continue;

            String path = "profiles." + profile.getUUID();
            profileData.set(path + ".uuid", profile.getUUID().toString());
            profileData.set(path + ".name", profile.getPlayer() == null ? "Unknown" : profile.getPlayer().getName());
            profileData.set(path + ".alerts", profile.isAlerts());
            profileData.set(path + ".velocity-alerts", profile.isVelocityAlerts());
            profileData.set(path + ".trust-score", profile.getTrustScore());

            if (Arrow.getInstance() != null && Arrow.getInstance().getLogManager() != null
                    && profile.getPlayer() != null) {
                profile.setTotalLogs(Arrow.getInstance().getLogManager()
                        .getTotalLogsForPlayer(profile.getUUID(), profile.getPlayer().getName()));
            }
            profileData.set(path + ".total-logs", profile.getTotalLogs());
        }
        saveProfileData();
        this.profiles.clear();
    }

    private void loadProfileData() {
        File dataFolder = Arrow.getInstance().getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            Arrow.getInstance().getHost().getLogger().warning("Could not create Arrow data folder for profile.yml");
            return;
        }

        this.profileFile = new File(dataFolder, "profile.yml");
        this.profileData = YamlConfiguration.loadConfiguration(profileFile);
        if (!profileFile.exists()) {
            saveProfileData();
        }
    }

    private synchronized void saveProfileData() {
        if (profileFile == null || profileData == null) return;
        try {
            profileData.save(profileFile);
        } catch (IOException exception) {
            if (Arrow.getInstance() != null && Arrow.getInstance().getHost() != null) {
                Arrow.getInstance().getHost().getLogger().warning("Could not save profile.yml: " + exception.getMessage());
            }
        }
    }
}
