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
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static me.arrow.utils.customutils.OtherUtility.*;

// idk what is going on in here tbh but i have it use my setback logs

public class SetbackProcessor implements Processor {

    private final SampleList<CustomLocation> locations = new SampleList<>(10, true);
    private static final Map<UUID, List<String>> SETBACK_HISTORY = new ConcurrentHashMap<>();

    private final Profile profile;
    private int lastSetbackTicks, lastStoredLocationTicks;


    public HashMap<Player, Boolean> reduce = new HashMap<>();
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
                teleportLocation = MathUtil.getGroundLocation(profile);
            }

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
            TaskUtils.task(() -> {
                player.teleport(finalLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
            });

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
        if (history.size() > 50) {
            history.remove(0);
        }
    }

    public void setback(boolean exemptTime, String callingClass) {
        if (exemptTime && MathUtils.elapsedTicks(this.lastSetbackTicks) < 5) return;

        this.lastSetbackTicks = TickTask.getCurrentTick();

        Player p = profile.getPlayer();

        if (p == null) return;

        if (this.locations.isEmpty()) {

            final CustomLocation cloned = profile.getMovementData().getLastLocation().clone();

            int count = 0;

            while (cloned.getBlock().getRelative(BlockFace.DOWN).isEmpty()) {

                cloned.subtract(0D, 1D, 0D);

                //Prevents crashes
                if (count++ > 5) break;
            }

            TaskUtils.task(() -> p.teleport(cloned.toBukkit(), PlayerTeleportEvent.TeleportCause.PLUGIN));

            setbackDebug(profile, "&c"+callingClass + " &7caused setback at &6"+ cloned.toBukkit());

            return;
        }

        final Location setbackLocation = locations.getLast().toBukkit();

        if (setbackLocation.getWorld() != p.getWorld()) return;

        TaskUtils.task(() -> p.teleport(setbackLocation, PlayerTeleportEvent.TeleportCause.PLUGIN));

        setbackDebug(profile, "&c"+callingClass + " &7caused setback at &6"+ setbackLocation);
    }
}