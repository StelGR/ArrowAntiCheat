package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkDataBulk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import me.arrow.backend.bukkit.PlatformBackend;
import me.arrow.managers.errors.ErrorMonitor;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure PacketEvents chunk and block update listener for ArrowAntiCheat.
 *
 * Chunk and block cache data is populated from packets sent by the server.
 * Bukkit lifecycle events remove a player's tracked chunks on quit or world
 * change. Chunk contents and block changes are populated only from outgoing
 * PacketEvents packets.
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
    private final Map<UUID, String> playerWorlds = new ConcurrentHashMap<>();

    public ChunkCacheListener(ChunkCache cache) {
        this.cache = cache;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon packetType = event.getPacketType();

        boolean blockChange = packetType.equals(PacketType.Play.Server.BLOCK_CHANGE);
        boolean multiBlockChange = packetType.equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE);
        boolean chunkData = packetType.equals(PacketType.Play.Server.CHUNK_DATA);
        boolean chunkDataBulk = packetType.equals(PacketType.Play.Server.MAP_CHUNK_BULK);
        boolean chunkUnload = packetType.equals(PacketType.Play.Server.UNLOAD_CHUNK);

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

        if (chunkDataBulk) {
            handleChunkDataBulk(event, worldName);
            return;
        }

        if (chunkUnload) {
            handleChunkUnload(event, worldName);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        playerWorlds.put(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerWorlds.remove(event.getPlayer().getUniqueId());
        cache.removePlayerFromChunks(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        playerWorlds.put(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getName());
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
            if (user.getDimensionType() != null) {
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
                if (expectedEnvironment == null || w.getEnvironment() == expectedEnvironment) {
                    playerWorlds.put(player.getUniqueId(), w.getName());
                    return w.getName();
                }

                // The player/world event and respawn packet can briefly be observed in
                // different orders. Use the last Bukkit lifecycle world if it matches
                // the packet dimension, and never guess another world with that dimension.
                String trackedWorldName = playerWorlds.get(player.getUniqueId());
                World trackedWorld = trackedWorldName != null ? Bukkit.getWorld(trackedWorldName) : null;
                if (trackedWorld != null && (expectedEnvironment == null
                        || trackedWorld.getEnvironment() == expectedEnvironment)) {
                    return trackedWorld.getName();
                }
                return null;
            }
            if (!PlatformBackend.get().isFabric()) {
                for (World world : Bukkit.getWorlds()) {
                    if (world != null && (expectedEnvironment == null
                            || world.getEnvironment() == expectedEnvironment)) {
                        return world.getName();
                    }
                }
            }
        } catch (Throwable failure) {
            reportPacketFailure(event, "resolve-world", failure, null);
        }
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

            boolean legacyServer = isLegacyServer();
            int combinedBlockId = legacyServer ? wrapper.getBlockId() : -1;
            cache.setBlockState(worldName, x, y, z, state, combinedBlockId);
            if (legacyServer) {
                cache.setLegacyBlockData(
                        worldName, x, y, z,
                        PEMaterials.encodeLegacyBlockData(state, combinedBlockId)
                );
            }
        } catch (Throwable failure) {
            reportPacketFailure(event, "block-change", failure, null);
        }
    }

    /**
     * Applies a batch of block updates to the global cache.
     */
    private void handleMultiBlockChange(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            ClientVersion packetVersion = event.getServerVersion().toClientVersion();
            if (packetVersion == ClientVersion.UNKNOWN) {
                packetVersion = event.getClientVersion();
            }

            boolean markedLoaded = false;
            boolean legacyServer = isLegacyServer();
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
                int x = -1;
                int y = -1;
                int z = -1;
                try {
                    x = block.getX();
                    y = block.getY();
                    z = block.getZ();

                    if (!markedLoaded && event.getPlayer() instanceof Player) {
                        Player player = event.getPlayer();
                        cache.markChunkLoaded(player.getUniqueId(), worldName, x >> 4, z >> 4);
                        markedLoaded = true;
                    }

                    WrappedBlockState state = block.getBlockState(packetVersion);

                    int combinedBlockId = legacyServer ? block.getBlockId() : -1;
                    cache.setBlockState(worldName, x, y, z, state, combinedBlockId);
                    if (legacyServer) {
                        cache.setLegacyBlockData(
                                worldName, x, y, z,
                                PEMaterials.encodeLegacyBlockData(state, combinedBlockId)
                        );
                    }
                } catch (Throwable failure) {
                    reportPacketFailure(event, "multi-block-change-entry", failure,
                            "x=" + x + ",y=" + y + ",z=" + z);
                }
            }
        } catch (Throwable failure) {
            reportPacketFailure(event, "multi-block-change", failure, null);
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

            if (player != null) {
                cache.markChunkLoaded(player.getUniqueId(), worldName, chunkX, chunkZ);
            }
            final int minY = getPacketMinY(event.getUser());
            cache.queuePacketChunk(worldName, chunkX, chunkZ, minY, column);
        } catch (Throwable failure) {
            reportPacketFailure(event, "chunk-data", failure, null);
        }
    }

    private boolean isLegacyServer() {
        try {
            return PacketEvents.getAPI().getServerManager().getVersion()
                    .isOlderThan(ServerVersion.V_1_13);
        } catch (Throwable failure) {
            ErrorMonitor.report("ChunkCacheListener", "detect-legacy-server", failure);
            return false;
        }
    }

    private int getPacketMinY(User user) {
        try {
            if (PacketEvents.getAPI().getServerManager().getVersion()
                    .isNewerThanOrEquals(ServerVersion.V_1_18)) {
                return user != null && user.getDimensionType() != null
                        ? user.getMinWorldHeight()
                        : -64;
            }
        } catch (Throwable failure) {
            reportPacketFailure(user, "read-packet-min-height", failure, null);
        }
        return 0;
    }

    /** Handles legacy Map Chunk Bulk packets, which carry several chunks at once. */
    private void handleChunkDataBulk(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerChunkDataBulk wrapper = new WrapperPlayServerChunkDataBulk(event);
            int[] chunkXs = wrapper.getX();
            int[] chunkZs = wrapper.getZ();
            Player player = event.getPlayer() instanceof Player ? (Player) event.getPlayer() : null;
            int minY = getPacketMinY(event.getUser());
            if (chunkXs == null || chunkZs == null) return;
            BaseChunk[][] chunks = wrapper.getChunks();
            if (chunks == null) return;

            int count = Math.min(Math.min(chunkXs.length, chunkZs.length), chunks.length);
            for (int i = 0; i < count; i++) {
                int chunkX = chunkXs[i];
                int chunkZ = chunkZs[i];
                if (player != null) {
                    cache.markChunkLoaded(player.getUniqueId(), worldName, chunkX, chunkZ);
                }
                cache.queuePacketChunk(worldName, chunkX, chunkZ, minY, chunks[i]);
            }
        } catch (Throwable failure) {
            reportPacketFailure(event, "chunk-data-bulk", failure, null);
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
        } catch (Throwable failure) {
            reportPacketFailure(event, "chunk-unload", failure, null);
        }
    }

    private void reportPacketFailure(PacketSendEvent event, String operation, Throwable failure, String extra) {
        String playerName = "unknown";
        String packetName = "unknown";
        try {
            if (event.getPlayer() instanceof Player) playerName = ((Player) event.getPlayer()).getName();
            packetName = String.valueOf(event.getPacketType());
        } catch (Throwable ignored) {
            // Keep diagnostic construction from masking the original packet failure.
        }
        String context = "world=" + (worldNameForDiagnostics(event))
                + ",player=" + playerName + ",packet=" + packetName
                + (extra == null ? "" : "," + extra);
        ErrorMonitor.report("ChunkCacheListener", operation, failure, context);
    }

    private void reportPacketFailure(User user, String operation, Throwable failure, String extra) {
        String context = "user=" + (user == null ? "unknown" : user.getUUID())
                + ",clientVersion=" + (user == null ? "unknown" : user.getClientVersion())
                + (extra == null ? "" : "," + extra);
        ErrorMonitor.report("ChunkCacheListener", operation, failure, context);
    }

    private String worldNameForDiagnostics(PacketSendEvent event) {
        try {
            if (event.getPlayer() instanceof Player) {
                return ((Player) event.getPlayer()).getWorld().getName();
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}
