package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.BlockBreakHelper;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Serializes dig tracking and captures Bukkit player data on the server thread. */
public final class FastBreakTracker {

    private Session session;

    public void handle(PacketReceiveEvent event, Profile profile, FastBreakA checkA, FastBreakB checkB) {
        if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) return;

        final DiggingAction action;
        final int x;
        final int y;
        final int z;
        final long packetTime = System.nanoTime();
        String worldName = null;
        Material cachedMaterial = null;
        try {
            WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
            action = packet.getAction();
            Vector3i position = packet.getBlockPosition();
            x = position.getX();
            y = position.getY();
            z = position.getZ();
        } catch (Throwable ignored) {
            return;
        }
        if (x == -1 && y == -1 && z == -1) return;

        CustomLocation location = profile.getMovementData().getLocation();
        if (location != null && location.getWorld() != null) {
            worldName = location.getWorld().getName();
            if (action == DiggingAction.START_DIGGING) {
                cachedMaterial = ChunkCache.get().getBlock(worldName, x, y, z);
            }
        }
        final String packetWorldName = worldName;
        final Material packetMaterial = cachedMaterial;

        Runnable update = () -> {
            BreakAttempt attempt = update(action, x, y, z, packetTime, packetWorldName, packetMaterial, profile);
            if (attempt != null) {
                checkA.handleAttempt(attempt);
                checkB.handleAttempt(attempt);
            }
        };

        if (Bukkit.isPrimaryThread() && !TaskUtils.isFoliaServer()) update.run();
        else TaskUtils.player(profile.getPlayer(), update);
    }

    private BreakAttempt update(DiggingAction action, int x, int y, int z, long packetTime,
                                String worldName, Material cachedMaterial, Profile profile) {
        Player player = profile.getPlayer();
        if (player == null || !player.isOnline()) {
            session = null;
            return null;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            session = null;
            return null;
        }
        if (worldName == null) {
            session = null;
            return null;
        }

        if (action == DiggingAction.START_DIGGING) {
            Material material = cachedMaterial;
            if (material == null || material == Material.AIR) {
                session = null;
                return null;
            }

            float progress = BlockBreakHelper.getBreakProgress(player, material);
            session = new Session(worldName, x, y, z, packetTime, material, progress,
                    BlockBreakHelper.getMinimumBreakTicks(progress),
                    BlockBreakHelper.formatHeldItemWithEnchants(player),
                    BlockBreakHelper.formatHaste(player));
            return null;
        }

        if (action == DiggingAction.CANCELLED_DIGGING) {
            if (matches(x, y, z)) session = null;
            return null;
        }

        if (action != DiggingAction.FINISHED_DIGGING || !matches(x, y, z)) return null;

        Session completed = session;
        session = null;
        if (!completed.worldName.equals(worldName)) return null;
        long elapsedNanos = Math.max(0L, packetTime - completed.startNanos);
        if (elapsedNanos > 30_000_000_000L) return null;
        return new BreakAttempt(completed, elapsedNanos);
    }

    private boolean matches(int x, int y, int z) {
        return session != null && session.x == x && session.y == y && session.z == z;
    }

    public static final class BreakAttempt {
        public final int x, y, z;
        public final String worldName;
        public final Material material;
        public final float progress;
        public final int minimumTicks;
        public final long elapsedNanos;
        public final String heldItem;
        public final String haste;

        private BreakAttempt(Session session, long elapsedNanos) {
            this.x = session.x;
            this.y = session.y;
            this.z = session.z;
            this.worldName = session.worldName;
            this.material = session.material;
            this.progress = session.progress;
            this.minimumTicks = session.minimumTicks;
            this.elapsedNanos = elapsedNanos;
            this.heldItem = session.heldItem;
            this.haste = session.haste;
        }
    }

    private static final class Session {
        private final int x, y, z;
        private final String worldName;
        private final long startNanos;
        private final Material material;
        private final float progress;
        private final int minimumTicks;
        private final String heldItem;
        private final String haste;

        private Session(String worldName, int x, int y, int z, long startNanos, Material material, float progress,
                        int minimumTicks, String heldItem, String haste) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.startNanos = startNanos;
            this.material = material;
            this.progress = progress;
            this.minimumTicks = minimumTicks;
            this.heldItem = heldItem;
            this.haste = haste;
        }
    }
}
