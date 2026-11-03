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

        if (!Arrow.getInstance().isHasLoaded() && !Arrow.isReloading()) {
            player.kickPlayer("Server is still loading, please wait.");
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
        this.plugin.getProfileManager().removeProfile(event.getPlayer());
    }
}
