package me.arrow.playerdata.processors.impl;

import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.processors.Processor;
import me.arrow.tasks.TickTask;
import me.arrow.utils.MathUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static me.arrow.utils.customutils.OtherUtility.setbackDebug;

// idk what is going on in here tbh but i have it use my setback logs

public class SetbackProcessor implements Processor {

    SampleList<CustomLocation> locations = new SampleList<>(10, true);
    private static final Map<UUID, List<String>> SETBACK_HISTORY = new ConcurrentHashMap<>();

    private final Profile profile;
    private int lastSetbackTicks, lastStoredLocationTicks;


    @Getter
    @Setter
    public int flags = 0;

    public SetbackProcessor(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void process() {
        if (MathUtils.elapsedTicks(this.lastStoredLocationTicks) < 20
                || MathUtils.elapsedTicks(this.lastSetbackTicks) < 40) return;

        this.locations.add(profile.getMovementData().getLocation());

        this.lastStoredLocationTicks = TickTask.getCurrentTick();
    }

    public void causeSetBack(String reason) {

        try {

            Player player = profile.getPlayer();

            if (player == null || !player.isOnline()) {
                return;
            }

            Location teleportLocation = profile.getMovementData().getLastGroundLocation();


            if (teleportLocation == null) {
                return;
            }

            profile.isExempt().setSetback(true);

            final Location finalLocation = teleportLocation.clone();

            /*
             * Store setback history
             */
            addSetbackHistory(
                    player.getUniqueId(),
                    reason,
                    finalLocation
            );

            /*
             * Teleport sync
             */
            teleportSetback(player, finalLocation);

            setbackDebug(
                    profile,
                    "&c" + reason + " &7setback -> &6"
                            + finalLocation.getBlockX() + ", "
                            + finalLocation.getBlockY() + ", "
                            + finalLocation.getBlockZ()
            );



        } catch (Exception e) {

            setbackDebug(
                    profile,
                    "&cSetback failed: &7" + e.getMessage()
            );

            e.printStackTrace();
        }
    }

    private void addSetbackHistory(UUID uuid, String reason, Location location) {

        List<String> history = SETBACK_HISTORY.computeIfAbsent(
                uuid,
                k -> new CopyOnWriteArrayList<>()
        );

        history.add(
                "[" + System.currentTimeMillis() + "] "
                        + reason
                        + " -> "
                        + location.getBlockX() + ", "
                        + location.getBlockY() + ", "
                        + location.getBlockZ()
        );

        /*
         * Prevent infinite growth
         */
        if (history.size() > 60) {
            history.remove(0);
        }
    }

    private void teleportSetback(Player player, Location location) {
        if (player == null || location == null) {
            return;
        }

        if (TaskUtils.isFoliaServer()) {
            tryTeleportAsync(player, location);
            return;
        }

        TaskUtils.task(() -> {
            if (player.isOnline()) {
                player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        });
    }

    private void tryTeleportAsync(Player player, Location location) {
        if (player == null || location == null || !player.isOnline()) {
            return;
        }

        try {
            // Paper/Folia: Entity#teleportAsync(Location, TeleportCause)
            player.getClass()
                    .getMethod("teleportAsync", Location.class, PlayerTeleportEvent.TeleportCause.class)
                    .invoke(player, location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            return;
        } catch (Throwable ignored) {
        }

        try {
            // Older Paper style: Entity#teleportAsync(Location)
            player.getClass()
                    .getMethod("teleportAsync", Location.class)
                    .invoke(player, location);
        } catch (Throwable ignored) {
            setbackDebug(profile, "&cSetback failed: &7teleportAsync is not available on this server/API");
        }
    }
}