package me.arrow.playerdata.cache;

import lombok.Getter;
import me.arrow.platform.PlatformBackend;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.*;
import org.bukkit.block.Block;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, packet-driven Chunk and Block Cache for ArrowAntiCheat.
 * Caches all loaded chunks on startup and listens to server block/chunk packets and events,
 * allowing collision and ground checks to query blocks in O(1) time without touching
 * Bukkit worlds asynchronously or causing Folia thread check exceptions.
 */
public class ChunkCache {

    private static final ChunkCache INSTANCE = new ChunkCache();

    public static ChunkCache get() {
        return INSTANCE;
    }

    private static final int DEFAULT_MIN_Y = 0;
    private static final int DEFAULT_MAX_Y = 256;

    // World Name -> (ChunkKey -> CachedChunk)
    private final Map<String, Map<Long, CachedChunk>> worldChunks = new ConcurrentHashMap<>();

    @Getter
    private volatile boolean initialized = false;

    public ChunkCache() {
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Cache all currently loaded chunks across all worlds.
     * Called on plugin enable. Collects chunk references on the calling thread,
     * then processes them asynchronously to avoid blocking server startup.
     */
    public void cacheAllLoadedChunks() {
        if (PlatformBackend.get().isFabric()) {
            this.initialized = true;
            return;
        }

        try {
            if (PlatformBackend.get().getServer() == null) {
                this.initialized = true;
                return;
            }

            // Collect chunk references on the main thread (required by Bukkit API)
            java.util.List<Chunk> allChunks = new java.util.ArrayList<>();
            for (World world : PlatformBackend.get().getServer().getWorlds()) {
                if (world == null) continue;
                Chunk[] loaded = world.getLoadedChunks();
                if (loaded == null) continue;
                java.util.Collections.addAll(allChunks, loaded);
            }

            if (allChunks.isEmpty()) {
                this.initialized = true;
                return;
            }

            // Process the chunks asynchronously to avoid blocking the main thread
            me.arrow.utils.TaskUtils.taskAsync(() -> {
                try {
                    for (Chunk chunk : allChunks) {
                        cacheBukkitChunk(chunk);
                    }
                } catch (Throwable ignored) {
                } finally {
                    this.initialized = true;
                }
            });
        } catch (Throwable ignored) {
            this.initialized = true;
        }
    }

    /**
     * Cache a single Bukkit chunk using its fast ChunkSnapshot.
     * Skips entirely-air sections via {@code isSectionEmpty()} to avoid
     * iterating thousands of air blocks unnecessarily.
     */
    public void cacheBukkitChunk(Chunk chunk) {
        if (chunk == null) return;
        try {
            World world = chunk.getWorld();
            int cx = chunk.getX();
            int cz = chunk.getZ();
            int minY = getWorldMinY(world);
            int maxY = getWorldMaxY(world);

            CachedChunk cached = new CachedChunk(cx, cz);

            try {
                ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
                cacheFromSnapshot(snapshot, cached, minY, maxY);
            } catch (Throwable t) {
                // Fallback: block-by-block if snapshot fails (very old servers)
                cacheFromBlocks(chunk, cached, minY, maxY);
            }

            putChunk(world.getName(), cx, cz, cached);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Fast path: iterate only non-empty sections of a ChunkSnapshot.
     * Each section covers a 16-block vertical slice (sectionY = y >> 4).
     */
    private void cacheFromSnapshot(ChunkSnapshot snapshot, CachedChunk cached, int minY, int maxY) {
        int minSection = minY >> 4;
        int maxSection = maxY >> 4;

        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            // Skip entirely-air sections — avoids 4096 iterations per empty section
            try {
                if (snapshot.isSectionEmpty(sectionY)) continue;
            } catch (Throwable ignored) {
                // isSectionEmpty() may not exist on very old Bukkit versions; proceed anyway
            }

            int baseY = sectionY << 4;
            int startY = Math.max(baseY, minY);
            int endY = Math.min(baseY + 15, maxY);

            for (int y = startY; y <= endY; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Material type = snapshot.getBlockType(x, y, z);
                        if (type != Material.AIR) {
                            cached.set(x, y, z, type);
                            if (isWaterMaterial(type)) {
                                cached.setWaterlogged(x, y, z, true);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Slow fallback: iterate block-by-block directly from the chunk.
     * Used only when ChunkSnapshot is unavailable (e.g. very old server versions).
     */
    private void cacheFromBlocks(Chunk chunk, CachedChunk cached, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block b = chunk.getBlock(x, y, z);
                    Material type = b.getType();
                    if (type != Material.AIR) {
                        cached.set(x, y, z, type);
                        if (isWaterMaterial(type)) {
                            cached.setWaterlogged(x, y, z, true);
                        }
                    }
                }
            }
        }
    }

    /**
     * Quick check if a material name indicates water.
     * Covers WATER, STATIONARY_WATER, and waterlogged-by-name blocks.
     */
    private static boolean isWaterMaterial(Material material) {
        return material != null && material.name().contains("WATER");
    }

    public void putChunk(String worldName, int chunkX, int chunkZ, CachedChunk chunk) {
        if (worldName == null || chunk == null) return;
        worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                .put(chunkKey(chunkX, chunkZ), chunk);
    }

    public CachedChunk getChunk(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) return null;
        Map<Long, CachedChunk> map = worldChunks.get(worldName);
        return map != null ? map.get(chunkKey(chunkX, chunkZ)) : null;
    }

    public void removeChunk(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) return;
        Map<Long, CachedChunk> map = worldChunks.get(worldName);
        if (map != null) {
            map.remove(chunkKey(chunkX, chunkZ));
        }
    }

    /**
     * Evict all cached chunks for a world (e.g. on WorldUnloadEvent).
     */
    public void removeWorld(String worldName) {
        if (worldName == null) return;
        worldChunks.remove(worldName);
    }

    public void clear() {
        worldChunks.clear();
    }

    /**
     * Updates a single block from a server packet or event.
     */
    public void setBlock(World world, int x, int y, int z, Material material) {
        if (world == null) return;
        setBlock(world.getName(), x, y, z, material);
    }

    public void setBlock(String worldName, int x, int y, int z, Material material) {
        if (worldName == null) return;
        int cx = x >> 4;
        int cz = z >> 4;

        Map<Long, CachedChunk> map = worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        CachedChunk chunk = map.computeIfAbsent(chunkKey(cx, cz), k -> new CachedChunk(cx, cz));
        chunk.set(x & 15, y, z & 15, material != null ? material : Material.AIR);
    }

    public void setBlock(CustomLocation location, Material material) {
        if (location == null || location.getWorld() == null) return;
        setBlock(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), material);
    }

    /**
     * Thread-safe O(1) block material lookup.
     */
    public Material getBlock(World world, int x, int y, int z) {
        if (world == null) return Material.AIR;
        return getBlock(world.getName(), x, y, z);
    }

    public Material getBlock(String worldName, int x, int y, int z) {
        if (worldName == null) return Material.AIR;
        int cx = x >> 4;
        int cz = z >> 4;
        CachedChunk chunk = getChunk(worldName, cx, cz);
        if (chunk != null) {
            return chunk.get(x & 15, y, z & 15);
        }
        return null;
    }

    public Material getBlock(CustomLocation location) {
        if (location == null || location.getWorld() == null) return Material.AIR;
        return getBlock(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Returns true if this chunk has been sent to the client (i.e. exists in the cache).
     * Uses only the packet-driven cache — NOT Bukkit's world.isChunkLoaded() — because
     * the anticheat cares about what the client can see, not the server-side load state.
     * Populated by CHUNK_DATA packets, evicted by UNLOAD_CHUNK packets.
     */
    public boolean isChunkLoaded(World world, int chunkX, int chunkZ) {
        if (world == null) return false;
        return getChunk(world.getName(), chunkX, chunkZ) != null;
    }

    public boolean isChunkLoaded(CustomLocation location) {
        if (location == null || location.getWorld() == null) return false;
        return isChunkLoaded(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public boolean isChunkLoaded(Location location) {
        if (location == null || location.getWorld() == null) return false;
        return isChunkLoaded(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }


    private static Method WORLD_GET_MIN_HEIGHT;
    private static Method WORLD_GET_MAX_HEIGHT;

    static {
        try {
            WORLD_GET_MIN_HEIGHT = World.class.getMethod("getMinHeight");
        } catch (Throwable ignored) {
        }
        try {
            WORLD_GET_MAX_HEIGHT = World.class.getMethod("getMaxHeight");
        } catch (Throwable ignored) {
        }
    }

    public static int getWorldMinY(World world) {
        if (world == null) return DEFAULT_MIN_Y;
        if (WORLD_GET_MIN_HEIGHT != null) {
            try {
                Object res = WORLD_GET_MIN_HEIGHT.invoke(world);
                if (res instanceof Number) {
                    return ((Number) res).intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return DEFAULT_MIN_Y;
    }

    public static int getWorldMaxY(World world) {
        if (world == null) return DEFAULT_MAX_Y;
        if (WORLD_GET_MAX_HEIGHT != null) {
            try {
                Object res = WORLD_GET_MAX_HEIGHT.invoke(world);
                if (res instanceof Number) {
                    return ((Number) res).intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            return world.getMaxHeight();
        } catch (Throwable ignored) {
        }
        return DEFAULT_MAX_Y;
    }

    public boolean isWaterLogged(World world, int x, int y, int z) {
        if (world == null) return false;
        return isWaterLogged(world.getName(), x, y, z);
    }

    public boolean isWaterLogged(String worldName, int x, int y, int z) {
        if (worldName == null) return false;
        CachedChunk chunk = getChunk(worldName, x >> 4, z >> 4);
        return chunk != null && chunk.isWaterlogged(x & 15, y, z & 15);
    }

    public boolean isWaterLogged(CustomLocation location) {
        if (location == null || location.getWorld() == null) return false;
        return isWaterLogged(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public void setWaterLogged(World world, int x, int y, int z, boolean waterlogged) {
        if (world == null) return;
        setWaterLogged(world.getName(), x, y, z, waterlogged);
    }

    public void setWaterLogged(String worldName, int x, int y, int z, boolean waterlogged) {
        if (worldName == null) return;
        int cx = x >> 4;
        int cz = z >> 4;
        Map<Long, CachedChunk> map = worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        CachedChunk chunk = map.computeIfAbsent(chunkKey(cx, cz), k -> new CachedChunk(cx, cz));
        chunk.setWaterlogged(x & 15, y, z & 15, waterlogged);
    }

    // ==========================================
    // Inner Chunk and Section Data Structures
    // ==========================================

    public static class CachedChunk {
        // Covers sectionY -4 (Y=-64, 1.18+) through 23 (Y=383, theoretical max).
        // 1.7–1.17 worlds use sectionY 0–15, 1.18+ worlds use -4–19.
        // Any sectionY outside this range (exotic modded worlds) is silently ignored.
        private static final int SECTION_OFFSET = 4;
        private static final int SECTION_COUNT  = 28; // indices 0..27 = sectionY -4..23

        int chunkX;
        int chunkZ;
        private final CachedSection[] sections = new CachedSection[SECTION_COUNT];

        public CachedChunk(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        /** Returns true if the section array index for the given sectionY is valid. */
        private static boolean validIndex(int idx) {
            return idx >= 0 && idx < SECTION_COUNT;
        }

        public Material get(int relX, int y, int relZ) {
            if (relX < 0 || relX > 15 || relZ < 0 || relZ > 15) return Material.AIR;
            int idx = (y >> 4) + SECTION_OFFSET;
            if (!validIndex(idx)) return Material.AIR;
            CachedSection sec = sections[idx];
            return sec != null ? sec.get(relX, y & 15, relZ) : Material.AIR;
        }

        public void set(int relX, int y, int relZ, Material material) {
            if (relX < 0 || relX > 15 || relZ < 0 || relZ > 15) return;
            int idx = (y >> 4) + SECTION_OFFSET;
            if (!validIndex(idx)) return;

            if (material == null || material == Material.AIR) {
                CachedSection sec = sections[idx];
                if (sec != null) sec.set(relX, y & 15, relZ, Material.AIR);
                return;
            }

            if (sections[idx] == null) sections[idx] = new CachedSection();
            sections[idx].set(relX, y & 15, relZ, material);
        }

        public boolean isWaterlogged(int relX, int y, int relZ) {
            if (relX < 0 || relX > 15 || relZ < 0 || relZ > 15) return false;
            int idx = (y >> 4) + SECTION_OFFSET;
            if (!validIndex(idx)) return false;
            CachedSection sec = sections[idx];
            return sec != null && sec.isWaterlogged(relX, y & 15, relZ);
        }

        public void setWaterlogged(int relX, int y, int relZ, boolean waterlogged) {
            if (relX < 0 || relX > 15 || relZ < 0 || relZ > 15) return;
            int idx = (y >> 4) + SECTION_OFFSET;
            if (!validIndex(idx)) return;

            if (!waterlogged) {
                CachedSection sec = sections[idx];
                if (sec != null) sec.setWaterlogged(relX, y & 15, relZ, false);
                return;
            }

            if (sections[idx] == null) sections[idx] = new CachedSection();
            sections[idx].setWaterlogged(relX, y & 15, relZ, true);
        }
    }

    public static class CachedSection {
        // Fast compact array storing material ordinals (char is 16-bit unsigned, fits all materials)
        private final char[] blockOrdinals = new char[4096];
        // 64 longs = 4096 bits representing waterlogged state for each block in the section
        private final long[] waterloggedMask = new long[64];
        private static final Material[] MATERIAL_VALUES = Material.values();

        public Material get(int localX, int localY, int localZ) {
            int index = (localY << 8) | (localZ << 4) | localX;
            char ord = blockOrdinals[index];
            if (ord == 0) return Material.AIR;
            int idx = ord - 1;
            return idx < MATERIAL_VALUES.length ? MATERIAL_VALUES[idx] : Material.AIR;
        }

        public void set(int localX, int localY, int localZ, Material material) {
            int index = (localY << 8) | (localZ << 4) | localX;
            if (material == null || material == Material.AIR) {
                blockOrdinals[index] = 0;
            } else {
                blockOrdinals[index] = (char) (material.ordinal() + 1);
            }
        }

        public boolean isWaterlogged(int localX, int localY, int localZ) {
            int index = (localY << 8) | (localZ << 4) | localX;
            return (waterloggedMask[index >> 6] & (1L << (index & 63))) != 0;
        }

        public void setWaterlogged(int localX, int localY, int localZ, boolean waterlogged) {
            int index = (localY << 8) | (localZ << 4) | localX;
            if (waterlogged) {
                waterloggedMask[index >> 6] |= (1L << (index & 63));
            } else {
                waterloggedMask[index >> 6] &= ~(1L << (index & 63));
            }
        }
    }
}
