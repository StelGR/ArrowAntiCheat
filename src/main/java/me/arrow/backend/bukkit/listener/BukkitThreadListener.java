package me.arrow.backend.bukkit.listener;

import me.arrow.Arrow;
import me.arrow.core.thread.ThreadManager;
import me.arrow.managers.profile.Profile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Translates Bukkit's player lifecycle into the platform-neutral thread manager. */
public final class BukkitThreadListener implements Listener {

    private final Arrow arrow;
    private final ThreadManager threadManager;

    public BukkitThreadListener(Arrow arrow, ThreadManager threadManager) {
        this.arrow = arrow;
        this.threadManager = threadManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Profile profile = arrow.getProfileManager().getProfile(event.getPlayer());
        if (profile != null) {
            threadManager.release(profile.getProfileThread());
        }
    }
}
