package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
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
 * arise when Bukkit's ChunkLoadEvent fires before the CHUNK_DATA packet arrives
 * (which would populate the cache with material-only snapshot data and block
 * the stateful PacketEvents path from running).
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

        if (packetType.equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleBlockChange(event, resolveWorldName(event));
            return;
        }

        if (packetType.equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleMultiBlockChange(event, resolveWorldName(event));
            return;
        }

        if (packetType.equals(PacketType.Play.Server.CHUNK_DATA)) {
            handleChunkData(event, resolveWorldName(event));
        }
    }

    // =========================================================================
    // World name resolution
    // =========================================================================

    /**
     * Resolves the world name for the packet's recipient.
     * Falls back to "world" when it cannot be determined (e.g. on Fabric).
     */
    private String resolveWorldName(PacketSendEvent event) {
        try {
            Player player = (event.getPlayer() instanceof Player) ? (Player) event.getPlayer() : null;
            if (player != null) {
                World w = player.getWorld();
                if (w != null) return w.getName();
            }
            if (!PlatformBackend.get().isFabric()) {
                if (!Bukkit.getWorlds().isEmpty()) {
                    World w = Bukkit.getWorlds().get(0);
                    if (w != null) return w.getName();
                }
            }
        } catch (Throwable ignored) {}
        return "world";
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

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
                try {
                    int x = block.getX();
                    int y = block.getY();
                    int z = block.getZ();

                    WrappedBlockState state = block.getBlockState(ClientVersion.UNKNOWN);
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
     * Deduplication is handled inside ChunkCache#queuePacketChunk: if the chunk
     * is already cached with complete stateful collision data, or is already
     * being processed, the call returns immediately. Only the first player to
     * load a given chunk triggers actual work.
     */
    private void handleChunkData(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            Column column = wrapper.getColumn();
            int chunkX = column.getX();
            int chunkZ = column.getZ();

            // Already fully cached with stateful data — skip.
            ChunkCache.CachedChunk existing = cache.getChunk(worldName, chunkX, chunkZ);
            if (existing != null && existing.hasCompleteStatefulCollisionData()) return;

            Player player = (event.getPlayer() instanceof Player) ? (Player) event.getPlayer() : null;
            World world = player != null ? player.getWorld() : null;
            if (world == null && worldName != null && !PlatformBackend.get().isFabric()) {
                try {
                    world = Bukkit.getWorld(worldName);
                } catch (Throwable ignored) {}
            }

            final int minY = ChunkCache.getWorldMinY(world);
            cache.queuePacketChunk(worldName, chunkX, chunkZ, minY, column);
        } catch (Throwable ignored) {
        }
    }
}