package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Cross-platform packet-driven chunk and block update listener for PacketEvents.
 * Handles three packet types:
 * <ul>
 *   <li>{@code BLOCK_CHANGE} — single block updates</li>
 *   <li>{@code MULTI_BLOCK_CHANGE} — batch block updates</li>
 *   <li>{@code CHUNK_DATA} — full chunk load from server (populates cache from packet data)</li>
 *   <li>{@code UNLOAD_CHUNK} — chunk unload (evicts from cache to prevent memory leak)</li>
 * </ul>
 * Works uniformly on Fabric, Folia, Paper, and Spigot without relying on Bukkit event classes.
 */
public class ChunkCacheListener extends PacketListenerAbstract implements PacketListener {

    private final ChunkCache cache;

    public ChunkCacheListener(ChunkCache cache) {
        this.cache = cache;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Player player = (event.getPlayer() instanceof Player) ? (Player) event.getPlayer() : null;
        World world = player != null ? player.getWorld() : null;
        String worldName = world != null ? world.getName() : "world";

        PacketTypeCommon packetType = event.getPacketType();

        // --- Single block update ---
        if (packetType.equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleBlockChange(event, worldName);
            return;
        }

        // --- Batch block update ---
        if (packetType.equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleMultiBlockChange(event, worldName);
            return;
        }

        // --- Full chunk load: populate cache from packet data ---
        if (packetType.equals(PacketType.Play.Server.CHUNK_DATA)) {
            handleChunkData(event, worldName);
            return;
        }

        // --- Chunk unload: evict from cache to prevent memory leak ---
        if (packetType.equals(PacketType.Play.Server.UNLOAD_CHUNK)) {
            handleUnloadChunk(event, worldName);
        }
    }

    // =========================================================================
    // Packet Handlers
    // =========================================================================

    private void handleBlockChange(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            int x = wrapper.getBlockPosition().getX();
            int y = wrapper.getBlockPosition().getY();
            int z = wrapper.getBlockPosition().getZ();

            WrappedBlockState state = wrapper.getBlockState();
            Material material = PEMaterials.materialFromState(state.getType());
            if (material != null) {
                cache.setBlock(worldName, x, y, z, material);
                boolean waterlogged = PEMaterials.isWaterlogged(state)
                        || material.name().contains("WATER");
                cache.setWaterLogged(worldName, x, y, z, waterlogged);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleMultiBlockChange(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
                try {
                    int x = block.getX();
                    int y = block.getY();
                    int z = block.getZ();

                    WrappedBlockState state = block.getBlockState(ClientVersion.UNKNOWN);
                    Material material = PEMaterials.materialFromState(state.getType());
                    if (material != null) {
                        cache.setBlock(worldName, x, y, z, material);
                        boolean waterlogged = PEMaterials.isWaterlogged(state)
                                || material.name().contains("WATER");
                        cache.setWaterLogged(worldName, x, y, z, waterlogged);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Populates the cache when a full chunk is sent to the client.
     * Uses PacketEvents' built-in {@link WrapperPlayServerChunkData} to parse
     * chunk sections directly from the packet — no reflection needed.
     */
    private void handleChunkData(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            Column column = wrapper.getColumn();
            if (column == null) return;

            int chunkX = column.getX();
            int chunkZ = column.getZ();
            BaseChunk[] sections = column.getChunks();
            if (sections == null) return;

            ChunkCache.CachedChunk cached = new ChunkCache.CachedChunk(chunkX, chunkZ);

            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                BaseChunk section = sections[sectionIndex];
                if (section == null || section.isEmpty()) continue;

                // Section Y index: on 1.18+ (minY=-64) sectionIndex 0 maps to Y=-64
                // On older versions, sectionIndex 0 maps to Y=0
                // We store raw Y coordinates in the cache, so this works for all versions
                int baseY = sectionIndex << 4;

                for (int localX = 0; localX < 16; localX++) {
                    for (int localY = 0; localY < 16; localY++) {
                        for (int localZ = 0; localZ < 16; localZ++) {
                            try {
                                WrappedBlockState state = section.get(localX, localY, localZ);
                                if (state == null) continue;

                                Material material = PEMaterials.materialFromState(state.getType());
                                if (material != null && material != Material.AIR) {
                                    int worldY = baseY + localY;
                                    cached.set(localX, worldY, localZ, material);

                                    boolean waterlogged = PEMaterials.isWaterlogged(state)
                                            || material.name().contains("WATER");
                                    if (waterlogged) {
                                        cached.setWaterlogged(localX, worldY, localZ, true);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            cache.putChunk(worldName, chunkX, chunkZ, cached);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Evicts a chunk from the cache when the server tells the client to unload it.
     * This prevents the cache from growing unbounded over time (memory leak fix).
     */
    private void handleUnloadChunk(PacketSendEvent event, String worldName) {
        try {
            WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
            cache.removeChunk(worldName, wrapper.getChunkX(), wrapper.getChunkZ());
        } catch (Throwable ignored) {
        }
    }
}
