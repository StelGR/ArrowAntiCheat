package me.arrow.api.internal;


import me.arrow.API.ArrowAPI;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profile.ProfileManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class ArrowAPIImpl implements ArrowAPI {
    private final ProfileManager profileManager;

    public ArrowAPIImpl(ProfileManager profileManager) {
        this.profileManager = profileManager;
    }

    @Override
    public @NotNull APIPlayerData getPlayerData(@NotNull Player player) {
        return getPlayerData(player.getUniqueId());
    }

    @Override
    public @NotNull APIPlayerData getPlayerData(@NotNull UUID uuid) {
        Profile profile = profileManager.getProfile(uuid);
        if (profile == null) {
            throw new IllegalStateException("No Arrow profile loaded for " + uuid);
        }
        return new APIPlayerData(profile);
    }

    @Override
    public boolean isLoaded(@NotNull UUID uuid) {
        return profileManager.getProfile(uuid) != null;
    }
}
