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
import me.arrow.backend.bukkit.PlatformBackend;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Pure PacketEvents chunk and block update listener for ArrowAntiCheat.
 *
 * All chunk cache population is driven exclusively by packets sent from the
 * server to clients — no Bukkit events are used. This makes the cache reliable
 * across Fabric, Folia, Paper and Spigot, and avoids the race conditions that
 * Packet block data is authoritative for chunks currently being sent to a client;
 * the cache also bootstraps already-loaded server chunks on startup.
 *
 * Chunks are NEVER evicted from the cache. Once a chunk is loaded by any
 * player it stays cached for the lifetime of the server process. This ensures
 * the anticheat always has block data available regardless of which players
 * currently have a chunk in their view distance. Block-change packets keep the
 * cached data up-to-date for the entire duration.
 *
 * Handled packet types:
 *   CHUNK_DATA         — full chunk load; populates global cache once per chunk.
 *   BLOCK_CHANGE       — single-block server update; updates global cache.
 *   MULTI_BLOCK_CHANGE — batch server block update; updates global cache.
 *
 * Block-change packets are applied globally: the first player whose packet
 * stream carries the update writes it to the shared cache; subsequent players
 * receiving the same update are idempotent (same state = same result).
 */
public class ChunkCacheListener extends PacketListenerAbstract implements PacketListener {

    private final ChunkCache cache;

    public ChunkCacheListener(ChunkCache cache) {
        this.cache = cache;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon packetType = event.getPacketType();

        String worldName = resolveWorldName(event);
        if (worldName == null) return;

        if (packetType.equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleBlockChange(event, worldName);
            return;
        }

        if (packetType.equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleMultiBlockChange(event, worldName);
            return;
        }

        if (packetType.equals(PacketType.Play.Server.CHUNK_DATA)) {
            handleChunkData(event, worldName);
        }
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

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
                try {
                    int x = block.getX();
                    int y = block.getY();
                    int z = block.getZ();

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
}
