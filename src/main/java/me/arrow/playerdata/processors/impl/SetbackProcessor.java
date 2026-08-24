package me.arrow.playerdata.processors.impl;

import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.processors.Processor;
import me.arrow.tasks.TickTask;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import static me.arrow.utils.customutils.OtherUtility.setbackDebug;

public class SetbackProcessor implements Processor {

    private final SampleList<CustomLocation> safeGroundLocations = new SampleList<>(20, true);

    @Getter
    @Setter
    private CustomLocation lastSafeGroundLocation;

    final Profile profile;
    int lastSetbackTicks;
    int lastStoredLocationTicks;

    @Getter
    @Setter
    public int flags = 0;

    public SetbackProcessor(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void process() {
        MovementData movementData = profile.getMovementData();
        CustomLocation location = movementData.getLocation();

        // Initialize with spawn / current location if unset
        if (this.lastSafeGroundLocation == null) {
            this.lastSafeGroundLocation = location.clone();
            this.safeGroundLocations.add(location.clone());
        }

        // Only record as a valid setback location when the player is genuinely supported on solid ground
        boolean onSolidGround = (movementData.isServerGround() || movementData.isOnGround()) && !movementData.isCustomInAir();

        if (onSolidGround && !movementData.isOnBoat() && !movementData.isNearBoat()) {
            World world = location.getWorld();
            int blockX = location.getBlockX();
            int blockY = (int) Math.floor(location.getY() - 0.1D);
            int blockZ = location.getBlockZ();

            Material matUnder = ChunkCache.get().getBlock(world, blockX, blockY, blockZ);
            if (matUnder != null && (matUnder.isSolid() || PEMaterials.hasPotentialCollision(matUnder))) {
                this.lastSafeGroundLocation = location.clone();
                this.safeGroundLocations.add(location.clone());
                movementData.setLastSetBackLocation(location.clone());
            }
        }

        this.lastStoredLocationTicks = TickTask.getCurrentTick();
    }

    public void causeSetBack(String reason) {
        setback(reason);
    }

    public void setback(String reason) {
        try {
            Player player = profile.getPlayer();
            if (player == null || !player.isOnline()) {
                return;
            }

            CustomLocation target = getCustomLocation(player);

            // Sync movement data so immediate post-setback check evaluations don't re-evaluate against cheated coords
//            if (profile.getMovementData() != null) {
//                profile.getMovementData().setLocation(target.clone());
//                profile.getMovementData().setLastLocation(target.clone());
//            }

            this.lastSetbackTicks = TickTask.getCurrentTick();
            teleportSetback(player, target);

            setbackDebug(
                    profile,
                    "&c" + reason + " &7setback -> &6"
                            + target.getBlockX() + ", "
                            + target.getBlockY() + ", "
                            + target.getBlockZ()
            );

        } catch (Exception e) {
            setbackDebug(profile, "&cSetback failed: &7" + e.getMessage());
            e.printStackTrace();
        }
    }

    private CustomLocation getCustomLocation(Player player) {
        CustomLocation target = this.lastSafeGroundLocation;

        if (target == null && !this.safeGroundLocations.isEmpty()) {
            target = this.safeGroundLocations.getLast();
        }

        if (target == null && profile.getMovementData() != null) {
            target = profile.getMovementData().getLastSetBackLocation();
        }

        if (target == null) {
            target = new CustomLocation(player.getLocation());
        }

        if (target.getWorld() != null && !player.getWorld().equals(target.getWorld())) {
            target = new CustomLocation(player.getLocation());
        }
        return target;
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