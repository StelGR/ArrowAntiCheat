package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import me.arrow.backend.bukkit.PlatformBackend;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Pure PacketEvents chunk and block update listener for ArrowAntiCheat.
 *
 * Chunk and block cache data is populated from packets sent by the server.
 * Bukkit lifecycle events remove a player's tracked chunks on quit or world
 * change, and startup state is seeded for players already online. Packet block
 * data is authoritative for chunks currently being sent to a client; the cache
 * also bootstraps already-loaded server chunks on startup.
 *
 * Chunks are shared globally and remain cached while any tracked player has
 * them loaded. After the final player unloads a chunk, the cache retains it for
 * five minutes before evicting it. Block-change packets keep cached data current.
 *
 * Handled packet types:
 *   CHUNK_DATA         — full chunk load; populates global cache once per chunk.
 *   UNLOAD_CHUNK       — removes one player's interest and starts the shared cache TTL if last.
 *   BLOCK_CHANGE       — single-block server update; updates global cache.
 *   MULTI_BLOCK_CHANGE — batch server block update; updates global cache.
 *
 * Block-change packets are applied globally: the first player whose packet
 * stream carries the update writes it to the shared cache; subsequent players
 * receiving the same update are idempotent (same state = same result).
 */
public class ChunkCacheListener extends PacketListenerAbstract implements PacketListener, Listener {

    private final ChunkCache cache;

    public ChunkCacheListener(ChunkCache cache) {
        this.cache = cache;
    }

    /** Seeds viewer state for players already online during a plugin reload. */
    public void seedOnlinePlayers() {
        Server server = PlatformBackend.get().getServer();
        if (server == null) return;

        int serverViewDistance = readDistance(server, "getViewDistance", 10);
        for (Player player : server.getOnlinePlayers()) {
            try {
                World world = player.getWorld();
                Location location = player.getLocation();
                if (world == null || location == null) continue;

                int clientViewDistance = readDistance(player, "getClientViewDistance", serverViewDistance);
                int viewDistance = Math.max(2, Math.min(serverViewDistance, clientViewDistance));
                int centerX = location.getBlockX() >> 4;
                int centerZ = location.getBlockZ() >> 4;
                Chunk[] loadedChunks = world.getLoadedChunks();

                for (Chunk chunk : loadedChunks) {
                    if (chunk == null
                            || Math.abs(chunk.getX() - centerX) > viewDistance
                            || Math.abs(chunk.getZ() - centerZ) > viewDistance) {
                        continue;
                    }
                    cache.markChunkLoaded(player.getUniqueId(), world.getName(), chunk.getX(), chunk.getZ());
                }
            } catch (Throwable ignored) {
                // Packet events will populate this player's viewer state as chunks are sent.
            }
        }
    }

    private int readDistance(Object target, String methodName, int fallback) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            if (value instanceof Number) {
                int distance = ((Number) value).intValue();
                if (distance > 0) return Math.max(2, Math.min(32, distance));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon packetType = event.getPacketType();

        boolean blockChange = packetType.equals(PacketType.Play.Server.BLOCK_CHANGE);
        boolean multiBlockChange = packetType.equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE);
        boolean chunkData = packetType.equals(PacketType.Play.Server.CHUNK_DATA);
        boolean chunkUnload = packetType.equals(PacketType.Play.Server.UNLOAD_CHUNK);
        if (!blockChange && !multiBlockChange && !chunkData && !chunkUnload) return;

        String worldName = resolveWorldName(event);
        if (worldName == null) return;

        if (blockChange) {
            handleBlockChange(event, worldName);
            return;
        }

        if (multiBlockChange) {
            handleMultiBlockChange(event, worldName);
            return;
        }

        if (chunkData) {
            handleChunkData(event, worldName);
            return;
        }

        if (chunkUnload) {
            handleChunkUnload(event, worldName);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cache.removePlayerFromChunks(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        cache.removePlayerFromChunks(event.getPlayer().getUniqueId());
    }

    // =========================================================================
    // World name resolution
    // =========================================================================

    /** Resolve packet dimension first so a world transition cannot contaminate another world's cache. */
    private String resolveWorldName(PacketSendEvent event) {
        try {
            Player player = (event.getPlayer() instanceof Player) ? (Player) event.getPlayer() : null;
            World.Environment expectedEnvironment = null;
            User user = event.getUser();
            if (user != null && user.getDimensionType() != null) {
                String dimension = String.valueOf(user.getDimensionType().getName());
                if (dimension.endsWith(":the_nether")) {
                    expectedEnvironment = World.Environment.NETHER;
                } else if (dimension.endsWith(":the_end")) {
                    expectedEnvironment = World.Environment.THE_END;
                } else if (dimension.endsWith(":overworld")) {
                    expectedEnvironment = World.Environment.NORMAL;
                }
            }

            if (player != null) {
                World w = player.getWorld();
                if (w != null && (expectedEnvironment == null || w.getEnvironment() == expectedEnvironment)) {
                    return w.getName();
                }
            }
            if (!PlatformBackend.get().isFabric()) {
                for (World world : Bukkit.getWorlds()) {
                    if (world != null && (expectedEnvironment == null
                            || world.getEnvironment() == expectedEnvironment)) {
                        return world.getName();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // Packet Handlers
    // =========================================================================

    /**
     * Applies a single-block update to the global cache.
     * Idempotent: re-applying the same state writes identical bytes.
     * Clears stale waterlogged bits for non-water materials (e.g. sand or
     * a door placed where water previously was).
     */
    private void handleBlockChange(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            int x = wrapper.getBlockPosition().getX();
            int y = wrapper.getBlockPosition().getY();
            int z = wrapper.getBlockPosition().getZ();

            Player player = (event.getPlayer() instanceof Player) ? (Player) event.getPlayer() : null;
            if (player != null) cache.markChunkLoaded(player.getUniqueId(), worldName, x >> 4, z >> 4);

            WrappedBlockState state = wrapper.getBlockState();
            if (state == null) return;

            cache.setBlockState(worldName, x, y, z, state);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Applies a batch of block updates to the global cache.
     */
    private void handleMultiBlockChange(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            ClientVersion packetVersion = event.getServerVersion() == null
                    ? event.getClientVersion()
                    : event.getServerVersion().toClientVersion();
            if (packetVersion == null || packetVersion == ClientVersion.UNKNOWN) {
                packetVersion = event.getClientVersion();
            }

            boolean markedLoaded = false;
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
                try {
                    int x = block.getX();
                    int y = block.getY();
                    int z = block.getZ();

                    if (!markedLoaded && event.getPlayer() instanceof Player) {
                        Player player = (Player) event.getPlayer();
                        cache.markChunkLoaded(player.getUniqueId(), worldName, x >> 4, z >> 4);
                        markedLoaded = true;
                    }

                    WrappedBlockState state = block.getBlockState(packetVersion);
                    if (state == null) continue;

                    cache.setBlockState(worldName, x, y, z, state);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Populates the global cache when any player receives a full chunk packet.
     * A full chunk packet refreshes cached data so changes made while no client
     * was watching the chunk cannot leave old blocks in the persistent cache.
     */
    private void handleChunkData(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            Column column = wrapper.getColumn();
            int chunkX = column.getX();
            int chunkZ = column.getZ();

            Player player = (event.getPlayer() instanceof Player) ? (Player) event.getPlayer() : null;
            World world = player != null ? player.getWorld() : null;

            if (player != null) {
                cache.markChunkLoaded(player.getUniqueId(), worldName, chunkX, chunkZ);
            }
            if (world == null && worldName != null && !PlatformBackend.get().isFabric()) {
                try {
                    world = Bukkit.getWorld(worldName);
                } catch (Throwable ignored) {}
            }

            User user = event.getUser();
            final int minY = user != null && user.getDimensionType() != null
                    ? user.getMinWorldHeight()
                    : ChunkCache.getWorldMinY(world);
            cache.queuePacketChunk(worldName, chunkX, chunkZ, minY, column);
        } catch (Throwable ignored) {
        }
    }

    private void handleChunkUnload(PacketSendEvent event, String worldName) {
        try {
            Player player = (event.getPlayer() instanceof Player) ? (Player) event.getPlayer() : null;
            if (player == null) return;

            WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
            cache.markChunkUnloaded(
                    player.getUniqueId(),
                    worldName,
                    wrapper.getChunkX(),
                    wrapper.getChunkZ()
            );
        } catch (Throwable ignored) {
        }
    }
}
