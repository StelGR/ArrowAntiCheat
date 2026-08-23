package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Cross-platform packet-driven chunk and block update listener for PacketEvents.
 * Works uniformly on Fabric, Folia, Paper, and Spigot without relying on Bukkit event classes.
 */
public class ChunkCacheListener extends PacketListenerAbstract implements PacketListener {

    private final ChunkCache cache;

    public ChunkCacheListener(ChunkCache cache) {
        this.cache = cache;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {

        Player player = event.getPlayer();
        World world = player != null ? player.getWorld() : null;
        String worldName = world != null ? world.getName() : "world";

        PacketTypeCommon packetType = event.getPacketType();

        if (packetType.equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            try {
                WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
                int x = wrapper.getBlockPosition().getX();
                int y = wrapper.getBlockPosition().getY();
                int z = wrapper.getBlockPosition().getZ();

                WrappedBlockState state = wrapper.getBlockState();
                Material material = PEMaterials.materialFromState(state.getType());
                if (material != null) {
                    cache.setBlock(worldName, x, y, z, material);
                    boolean isWl = PEMaterials.isWaterlogged(state) || material.name().contains("WATER");
                    cache.setWaterLogged(worldName, x, y, z, isWl);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        if (packetType.equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            try {
                WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);

                for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
                    int x = block.getX();
                    int y = block.getY();
                    int z = block.getZ();

                    try {
                        WrappedBlockState state = block.getBlockState(ClientVersion.UNKNOWN);
                        Material material = PEMaterials.materialFromState(state.getType());
                        if (material != null) {
                            cache.setBlock(worldName, x, y, z, material);
                            boolean isWl = PEMaterials.isWaterlogged(state) || material.name().contains("WATER");
                            cache.setWaterLogged(worldName, x, y, z, isWl);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        if (packetType.equals(PacketType.Play.Server.CHUNK_DATA)) {
            int[] chunk = extractChunkXZ(event);
            if (chunk != null) {
                cache.getChunk(worldName, chunk[0], chunk[1]);
            }
        }
    }

    private int[] extractChunkXZ(PacketSendEvent event) {
        try {
            Class<?> clazz = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData");
            Object wrapper = clazz.getConstructor(PacketSendEvent.class).newInstance(event);
            Integer x = readInt(wrapper, "getChunkX", "getX");
            Integer z = readInt(wrapper, "getChunkZ", "getZ");
            if (x != null && z != null) {
                return new int[]{x, z};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Integer readInt(Object object, String... methods) {
        if (object == null || methods == null) return null;
        for (String m : methods) {
            try {
                Object res = object.getClass().getMethod(m).invoke(object);
                if (res instanceof Number) return ((Number) res).intValue();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
