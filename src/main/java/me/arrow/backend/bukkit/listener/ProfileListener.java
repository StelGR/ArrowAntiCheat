package me.arrow.backend.bukkit.listener;

import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.versionutils.impl.VelocityAlertBridge;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class ProfileListener implements Listener {

    Arrow plugin;

    public ProfileListener(Arrow plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (this.plugin.isShuttingDown()) return;

        if (this.plugin.getProfileManager() == null) {
            // Initialization can overlap a join during /updatearrow. Defer
            // profile creation instead of disconnecting a valid player.
            waitForProfileManager(player, 1, 100);
            return;
        }

        this.plugin.getProfileManager().createProfile(player);
        resolveClientBrand(player, 5);

        Profile profile = this.plugin.getProfileManager().getProfile(player);

        if (profile == null) {
            TaskUtils.playerLater(player, 1L, () -> {
                if (!player.isOnline()) return;

                if (this.plugin.getProfileManager().getProfile(player) == null) {
                    this.plugin.getProfileManager().createProfile(player);
                }
                syncPreferences(player);
            });
            return;
        }

        syncPreferences(player);
    }

    private void waitForProfileManager(Player player, int attempt, int maxAttempts) {
        TaskUtils.playerLater(player, 1L, () -> {
            if (!player.isOnline() || this.plugin.isShuttingDown()) return;
            if (this.plugin.getProfileManager() == null) {
                if (attempt < maxAttempts) waitForProfileManager(player, attempt + 1, maxAttempts);
                return;
            }

            this.plugin.getProfileManager().createProfile(player);
            syncPreferences(player);
            resolveClientBrand(player, 5);
        });
    }

    private void resolveClientBrand(Player player, int attemptsRemaining) {
        TaskUtils.playerLater(player, 1L, () -> {
            if (!player.isOnline()) return;

            Profile profile = this.plugin.getProfileManager().getProfile(player);
            if (profile != null && ClientBrandListener.applyServerBrandFallback(player, profile)) {
                return;
            }

            if (attemptsRemaining > 1) {
                resolveClientBrand(player, attemptsRemaining - 1);
            }
        });
    }

    private void syncPreferences(Player player) {
        Profile profile = this.plugin.getProfileManager().getProfile(player);
        if (profile == null) return;

        if (profile.isAlerts()) {
            this.plugin.getAlertManager().addPlayerToAlerts(player.getUniqueId());
        } else {
            this.plugin.getAlertManager().removePlayerFromAlerts(player.getUniqueId());
        }

        VelocityAlertBridge.sendPreference(player, profile.isVelocityAlerts());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLeave(PlayerQuitEvent event) {
        if (this.plugin.getProfileManager() != null) {
            this.plugin.getProfileManager().removeProfile(event.getPlayer());
        }
    }
}
