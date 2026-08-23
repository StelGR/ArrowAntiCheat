package me.arrow.playerdata.processors.impl;

import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.processors.Processor;
import me.arrow.tasks.TickTask;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import static me.arrow.utils.customutils.OtherUtility.setbackDebug;

// idk what is going on in here tbh but i have it use my setback logs

public class SetbackProcessor implements Processor {

    SampleList<CustomLocation> locations = new SampleList<>(10, true);

    private final Profile profile;
    int lastSetbackTicks, lastStoredLocationTicks;


    @Getter
    @Setter
    public int flags = 0;

    public SetbackProcessor(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void process() {

        this.locations.add(profile.getMovementData().getLocation());

        this.lastStoredLocationTicks = TickTask.getCurrentTick();
    }

    public void causeSetBack(String reason) {

        try {

            Player player = profile.getPlayer();

            if (player == null || !player.isOnline()) {
                return;
            }


            if (profile.getMovementData().isCustomInAir() && profile.getMovementData().getCustomAirTicks() >= 13) {
                setback(reason);
            }
            else {
                CustomLocation teleportLocation = profile.getMovementData().getLastGroundLocation();

                if (teleportLocation != null) {
                    teleportSetback(player, teleportLocation);

                    setbackDebug(
                            profile,
                            "&c" + reason + " &7setback -> &6"
                                    + teleportLocation.getBlockX() + ", "
                                    + teleportLocation.getBlockY() + ", "
                                    + teleportLocation.getBlockZ()
                    );
                }
            }

        } catch (Exception e) {

            setbackDebug(
                    profile,
                    "&cSetback failed: &7" + e.getMessage()
            );

            e.printStackTrace();
        }
    }


    public void setback(String reason) {
        this.lastSetbackTicks = TickTask.getCurrentTick();

        Player p = profile.getPlayer();

        if (p == null) return;

        if (this.locations.isEmpty()) {

            final CustomLocation cloned = profile.getMovementData().getLastLocation().clone();
            int count = 0;

            while (count++ < 5) {
                Block b = cloned.getBlock();
                if (b == null || !b.getRelative(BlockFace.DOWN).isEmpty()) {
                    break;
                }
                cloned.subtract(0D, 1D, 0D);
            }

            teleportSetback(p, cloned);

            setbackDebug(
                    profile,
                    "&c" + reason + " &7setback -> &6"
                            + cloned.getBlockX() + ", "
                            + cloned.getBlockY() + ", "
                            + cloned.getBlockZ()
            );
//            TaskUtils.task(() -> p.teleport(cloned.toBukkit(), PlayerTeleportEvent.TeleportCause.PLUGIN));

            return;
        }

        final CustomLocation setbackLocation = locations.getLast();

        if (setbackLocation.getWorld() != p.getWorld()) return;

        teleportSetback(p, setbackLocation);

        setbackDebug(
                profile,
                "&c" + reason + " &7setback -> &6"
                        + setbackLocation.getBlockX() + ", "
                        + setbackLocation.getBlockY() + ", "
                        + setbackLocation.getBlockZ()
        );
//        TaskUtils.task(() -> p.teleport(setbackLocation, PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    private void teleportSetback(Player player, CustomLocation location) {
        if (player == null || location == null) {
            return;
        }

        if (TaskUtils.isFoliaServer()) {
            tryTeleportAsync(player, location.toBukkit());
            return;
        }

        TaskUtils.task(() -> {
            if (player.isOnline()) {
                player.teleport(location.toBukkit(), PlayerTeleportEvent.TeleportCause.PLUGIN);
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