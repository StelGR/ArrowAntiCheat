package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import lombok.Getter;
import me.arrow.platform.PlatformBackend;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.*;
import org.bukkit.block.Block;
import me.arrow.managers.profiler.Profiler;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final int DEFAULT_MIN_Y = -64;
    private static final int DEFAULT_MAX_Y = 320;

    // World Name -> (ChunkKey -> CachedChunk)
    private final Map<String, Map<Long, CachedChunk>> worldChunks = new ConcurrentHashMap<>();
    // World Name -> Set of queued ChunkKeys currently waiting or being processed
    private final Map<String, Set<Long>> pendingQueue = new ConcurrentHashMap<>();
    // Dedicated worker thread pool for asynchronous chunk caching
    private final ThreadPoolExecutor chunkExecutor;

    @Getter
    private volatile boolean initialized = false;

    public ChunkCache() {
        int workers = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        this.chunkExecutor = new ThreadPoolExecutor(
                workers,
                workers,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger id = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "Arrow-ChunkQueue-Worker-" + id.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    }
                }
        );
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Load all currently loaded chunks in batches of 20. Intended to be called from a dedicated thread
     * to avoid blocking the main server thread. This method processes chunks synchronously within the
     * calling thread but limits the number of chunks processed at once to reduce CPU spikes.
     */
    public void cacheAllLoadedChunksBatched() {
        if (PlatformBackend.get().isFabric()) {
            this.initialized = true;
            return;
        }

        try {
            // Give server time to load worlds before gathering chunks


            if (PlatformBackend.get().getServer() == null) {
                this.initialized = true;
                return;
            }

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

            int batchSize = 20;
            int totalBatches = (allChunks.size() + batchSize - 1) / batchSize;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(totalBatches);

            for (int i = 0; i < allChunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allChunks.size());
                java.util.List<Chunk> batch = allChunks.subList(i, end);
                // Process each batch asynchronously in the dedicated chunk worker pool
                chunkExecutor.execute(() -> {
                    try {
                        batch.forEach(this::ensureChunkCached);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all async batch tasks to finish
            latch.await();
        } catch (Throwable ignored) {
            // In case of unexpected errors, ensure the cache is marked initialized to avoid repeated attempts.
        } finally {
            this.initialized = true;
        }
    }

    /**
     * Load all loaded chunks with a pause between each chunk (delayMs ms).
     * This runs on the calling thread, so it should be executed from a dedicated thread
     * to keep the main server thread free.
     */
    public boolean isChunkCached(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) return false;
        Map<Long, CachedChunk> map = worldChunks.get(worldName);
        return map != null && map.containsKey(chunkKey(chunkX, chunkZ));
    }

    /**
     * Load all loaded chunks with a pause between each chunk (delayMs ms).
     * This runs on the calling thread, so it should be executed from a dedicated thread
     * to keep the main server thread free.
     */
    public void cacheAllLoadedChunksWithDelay(long delayMs) {
        if (PlatformBackend.get().isFabric()) {
            this.initialized = true;
            return;
        }
        try {
            int retryAttempts = 0;
            // Retry waiting for server worlds and loaded chunks if server is still starting up
            while (retryAttempts < 20) {
                boolean hasLoaded = false;
                if (PlatformBackend.get().getServer() != null) {
                    for (World world : PlatformBackend.get().getServer().getWorlds()) {
                        if (world == null) continue;
                        Chunk[] loaded = world.getLoadedChunks();
                        if (loaded != null && loaded.length > 0) {
                            hasLoaded = true;
                            break;
                        }
                    }
                }
                if (hasLoaded) {
                    break;
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ignored) {
                    break;
                }
                retryAttempts++;
            }

            boolean hasMore = true;
            while (hasMore) {
                java.util.List<Chunk> batch = new java.util.ArrayList<>();
                if (PlatformBackend.get().getServer() != null) {
                    for (World world : PlatformBackend.get().getServer().getWorlds()) {
                        if (world == null) continue;
                        Chunk[] loaded = world.getLoadedChunks();
                        if (loaded == null) continue;
                        for (Chunk c : loaded) {
                            if (c != null && !isChunkCached(world.getName(), c.getX(), c.getZ())) {
                                batch.add(c);
                            }
                        }
                    }
                }

                if (batch.isEmpty()) {
                    hasMore = false;
                } else {
                    for (Chunk chunk : batch) {
                        ensureChunkCached(chunk);
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ignored) {
                            hasMore = false;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Swallow unexpected errors but still mark initialized
        } finally {
            this.initialized = true;
        }
    }

    /**
     * Cache a single Bukkit chunk using its fast ChunkSnapshot.
     * Skips entirely-air sections via SnapshotAdapter to avoid
     * iterating thousands of air blocks unnecessarily.
     */
    public void cacheBukkitChunk(Chunk chunk) {
        cacheBukkitChunk(chunk, null);
    }

    public void cacheBukkitChunk(Chunk chunk, ChunkSnapshot snapshot) {
        if (chunk == null) return;
        long start = Profiler.start();
        try {
            World world = chunk.getWorld();
            if (world == null) return;
            int cx = chunk.getX();
            int cz = chunk.getZ();
            int minY = getWorldMinY(world);
            int maxY = getWorldMaxY(world);

            if (snapshot == null) {
                if (Bukkit.isPrimaryThread()) {
                    try {
                        snapshot = chunk.getChunkSnapshot(false, false, false);
                    } catch (Throwable ignored) {}
                } else {
                    try {
                        snapshot = chunk.getChunkSnapshot(false, false, false);
                    } catch (Throwable ignored) {
                        try {
                            snapshot = TaskUtils.callSync(() -> chunk.getChunkSnapshot(false, false, false));
                        } catch (Throwable ignored2) {}
                    }
                }
            }

            if (snapshot != null) {
                CachedChunk cached = new CachedChunk(cx, cz);
                cacheFromSnapshot(snapshot, cached, minY, maxY);
                putChunk(world.getName(), cx, cz, cached);
            }
        } catch (Throwable ignored) {
        } finally {
            Profiler.stop("ChunkCache cacheBukkitChunk", start);
        }
    }

    /**
     * Ensures the chunk the player is currently standing in is loaded and
     * queued for asynchronous caching.
     *
     * This is useful when the client/server moves into a chunk which has not
     * yet been observed through CHUNK_DATA.
     */
    public void ensurePlayerChunkLoaded(CustomLocation location) {
        if (location == null || PlatformBackend.get().isFabric()) {
            return;
        }

        try {
            World world = location.getWorld();

            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;

            String worldName = world.getName();

            // Already cached.
            if (getChunk(worldName, chunkX, chunkZ) != null) {
                return;
            }

            // Already waiting to be processed.
            if (isChunkQueued(worldName, chunkX, chunkZ)) {
                return;
            }

            // Bukkit chunk operations should happen on the server thread.
            TaskUtils.task(() -> {
                try {
                    // Re-check after scheduling.
                    if (getChunk(worldName, chunkX, chunkZ) != null) {
                        return;
                    }

                    if (isChunkQueued(worldName, chunkX, chunkZ)) {
                        return;
                    }

                    /*
                     * This explicitly loads the chunk if it isn't loaded yet.
                     */
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    if (chunk == null) {
                        return;
                    }

                    ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);

                    /*
                     * Hand the expensive parsing/storage work to the
                     * existing async chunk queue.
                     */
                    queueChunkSnapshot(world, chunkX, chunkZ, snapshot);

                } catch (Throwable ignored) {
                }
            });

        } catch (Throwable ignored) {
        }
    }


    /**
     * Fast path: iterate only non-empty sections of a ChunkSnapshot.
     * Each section covers a 16-block vertical slice (sectionY = y >> 4).
     * Fully compatible with 1.7 through 26.2 via SnapshotAdapter.
     */
    private void cacheFromSnapshot(ChunkSnapshot snapshot, CachedChunk cached, int minY, int maxY) {
        if (snapshot == null || cached == null) return;
        int minSection = minY >> 4;
        int maxSection = maxY >> 4;

        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            if (SnapshotAdapter.isSectionEmpty(snapshot, sectionY)) {
                continue;
            }

            int baseY = sectionY << 4;
            int startY = Math.max(baseY, minY);
            int endY = Math.min(baseY + 15, maxY);

            for (int y = startY; y <= endY; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Material type = SnapshotAdapter.getMaterial(snapshot, x, y, z);
                        if (type != null && type != Material.AIR) {
                            cached.set(x, y, z, type);
                            if (SnapshotAdapter.isWaterlogged(snapshot, x, y, z, type)) {
                                cached.setWaterlogged(x, y, z, true);
                            }
                        }
                    }
                }
            }
        }
    }


    // Detect if the server version supports the Waterlogged interface (1.13+).
    private static final boolean WATERLOGGED_SUPPORTED;
    static {
        boolean supported = false;
        try {
            Class.forName("org.bukkit.block.data.Waterlogged");
            supported = true;
        } catch (Throwable ignored) {
        }
        WATERLOGGED_SUPPORTED = supported;
    }

    /**
     * Checks whether the given block is waterlogged using the Waterlogged interface when available.
     * This method is safe on older versions (e.g., 1.7) where the interface does not exist.
     */
    private static boolean isBlockWaterlogged(Block block) {
        // Preserve compatibility: custom water check will be performed even if Waterlogged interface is unavailable
        // Only process tall seagrass to reduce console spam and avoid unnecessary checks

        try {
            // First, attempt the standard Waterlogged interface check when supported.
            boolean result = false;
            if (WATERLOGGED_SUPPORTED) {
                Object bd = block.getBlockData();
                Class<?> waterloggedClass = Class.forName("org.bukkit.block.data.Waterlogged");
                if (waterloggedClass.isInstance(bd)) {
                    result = (boolean) waterloggedClass.getMethod("isWaterlogged").invoke(bd);
                }
            }

            // Custom handling for tall seagrass: consider it water‑logged if the block directly below is water.
            if (block.getType() == Material.TALL_SEAGRASS) {
                if (!result) {
                    result = true;
                }
//                OtherUtility.log("[WaterlogCheck] " + block.getType()
//                        + " @ " + block.getWorld().getName()
//                        + "," + block.getX() + "," + block.getY() + "," + block.getZ()
//                        + " -> " + result);
            }

            return result;
        } catch (Throwable ignored) {
        }
        return false;
    }
    /**
     * Fast O(1) check if a material is water, avoiding all string allocations.
     */
    public static boolean isWaterMaterial(Material material) {
        if (material == null) return false;
        int ord = material.ordinal();
        return ord >= 0 && ord < WATER_MATERIALS.length && WATER_MATERIALS[ord];
    }

    private static final boolean[] WATER_MATERIALS;
    static {
        Material[] values = Material.values();
        WATER_MATERIALS = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            Material m = values[i];
            if (m != null) {
                String name = m.name();
                if (name.contains("WATER")
                        || name.equals("TALL_SEAGRASS")
                        || name.equals("SEAGRASS")
                        || name.equals("KELP")
                        || name.equals("KELP_PLANT")
                        || name.equals("BUBBLE_COLUMN")
                        || name.equals("BUBBLE_COLUMN_CAULDRON")) {
                    WATER_MATERIALS[i] = true;
                }
            }
        }
    }

    public CachedChunk getChunk(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) return null;
        Map<Long, CachedChunk> map = worldChunks.get(worldName);
        return map != null ? map.get(chunkKey(chunkX, chunkZ)) : null;
    }

    public void queueMissingChunk(String worldName, int chunkX, int chunkZ) {
        if (worldName == null || PlatformBackend.get().isFabric()) return;
        if (getChunk(worldName, chunkX, chunkZ) != null) return;
        if (!markQueued(worldName, chunkX, chunkZ)) return;
        TaskUtils.task(() -> {
            try {
                World world = Bukkit.getWorld(worldName);
                if (world != null && world.isChunkLoaded(chunkX, chunkZ)) {
                    Chunk c = world.getChunkAt(chunkX, chunkZ);
                    if (c != null) {
                        ChunkSnapshot snapshot = c.getChunkSnapshot(false, false, false);
                        chunkExecutor.execute(() -> {
                            try {
                                if (getChunk(worldName, chunkX, chunkZ) != null) return;
                                CachedChunk cached = new CachedChunk(chunkX, chunkZ);
                                cacheFromSnapshot(snapshot, cached, getWorldMinY(world), getWorldMaxY(world));
                                putChunk(worldName, chunkX, chunkZ, cached);
                            } finally {
                                unmarkQueued(worldName, chunkX, chunkZ);
                            }
                        });
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            unmarkQueued(worldName, chunkX, chunkZ);
        });
    }

    public void putChunk(String worldName, int chunkX, int chunkZ, CachedChunk chunk) {
        if (worldName == null || chunk == null) return;
        worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                .put(chunkKey(chunkX, chunkZ), chunk);
    }

    public void clear() {
        pendingQueue.clear();
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
        queueMissingChunk(worldName, cx, cz);
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
        if (world != null && WORLD_GET_MIN_HEIGHT != null) {
            try {
                Object res = WORLD_GET_MIN_HEIGHT.invoke(world);
                if (res instanceof Number) {
                    return ((Number) res).intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            if (com.github.retrooper.packetevents.PacketEvents.getAPI()
                    .getServerManager()
                    .getVersion()
                    .isNewerThanOrEquals(com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_18)) {
                return -64;
            }
        } catch (Throwable ignored) {
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

    /**
     * Returns the total number of cached chunks across all worlds.
     */
    public int getCachedChunkCount() {
        return worldChunks.values().stream().mapToInt(Map::size).sum();
    }

    public boolean markQueued(String worldName, int cx, int cz) {
        if (worldName == null) return false;
        return pendingQueue.computeIfAbsent(worldName, k -> ConcurrentHashMap.newKeySet()).add(chunkKey(cx, cz));
    }

    public void unmarkQueued(String worldName, int cx, int cz) {
        if (worldName == null) return;
        Set<Long> set = pendingQueue.get(worldName);
        if (set != null) {
            set.remove(chunkKey(cx, cz));
        }
    }

    public boolean isChunkQueued(String worldName, int cx, int cz) {
        if (worldName == null) return false;
        Set<Long> set = pendingQueue.get(worldName);
        return set != null && set.contains(chunkKey(cx, cz));
    }

    public int getQueuedChunkCount() {
        return chunkExecutor.getQueue().size();
    }

    /**
     * Enqueues a chunk received via CHUNK_DATA packet for asynchronous processing.
     * Deduplicates automatically: if already cached or already queued, it returns immediately.
     */
    public void queuePacketChunk(String worldName, int chunkX, int chunkZ, int minY, Column column) {
        if (worldName == null || column == null) return;
        if (getChunk(worldName, chunkX, chunkZ) != null) return;
        if (!markQueued(worldName, chunkX, chunkZ)) return;

        chunkExecutor.execute(() -> {
            try {
                if (getChunk(worldName, chunkX, chunkZ) != null) return;
                CachedChunk cached = parsePacketColumn(chunkX, chunkZ, minY, column);
                if (cached != null) {
                    putChunk(worldName, chunkX, chunkZ, cached);
                }
            } catch (Throwable ignored) {
            } finally {
                unmarkQueued(worldName, chunkX, chunkZ);
            }
        });
    }

    public void queueChunkSnapshot(World world, int cx, int cz, ChunkSnapshot snapshot) {
        if (world == null || snapshot == null) return;
        String worldName = world.getName();
        if (getChunk(worldName, cx, cz) != null) return;
        if (!markQueued(worldName, cx, cz)) return;

        int minY = getWorldMinY(world);
        int maxY = getWorldMaxY(world);

        chunkExecutor.execute(() -> {
            try {
                if (getChunk(worldName, cx, cz) != null) return;
                CachedChunk cached = new CachedChunk(cx, cz);
                cacheFromSnapshot(snapshot, cached, minY, maxY);
                putChunk(worldName, cx, cz, cached);
            } catch (Throwable ignored) {
            } finally {
                unmarkQueued(worldName, cx, cz);
            }
        });
    }

    /**
     * Enqueues a Bukkit chunk (e.g. from ChunkLoadEvent) for asynchronous processing.
     * Deduplicates automatically: if already cached or already queued, it returns immediately.
     */
    public void queueBukkitChunk(Chunk chunk) {
        if (chunk == null) return;
        World world = chunk.getWorld();
        if (world == null) return;
        String worldName = world.getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        if (getChunk(worldName, cx, cz) != null) return;
        if (!markQueued(worldName, cx, cz)) return;

        ChunkSnapshot directSnapshot = null;
        if (Bukkit.isPrimaryThread()) {
            try {
                directSnapshot = chunk.getChunkSnapshot();
            } catch (Throwable ignored) {}
        }
        final ChunkSnapshot snapshot = directSnapshot;

        chunkExecutor.execute(() -> {
            try {
                if (getChunk(worldName, cx, cz) != null) return;
                cacheBukkitChunk(chunk, snapshot);
            } catch (Throwable ignored) {
            } finally {
                unmarkQueued(worldName, cx, cz);
            }
        });
    }

    /**
     * Highly optimized, allocation-free parser for PacketEvents Column chunk packets.
     */
    public CachedChunk parsePacketColumn(int chunkX, int chunkZ, int minY, Column column) {
        if (column == null) return null;
        BaseChunk[] sections = column.getChunks();

        CachedChunk cached = new CachedChunk(chunkX, chunkZ);
        int minSection = minY >> 4;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            BaseChunk section = sections[sectionIndex];
            if (section == null || section.isEmpty()) continue;

            int sectionY = minSection + sectionIndex;
            int baseY = sectionY << 4;

            for (int localX = 0; localX < 16; localX++) {
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        try {
                            WrappedBlockState state = section.get(localX, localY, localZ);
                            if (state == null) continue;
                            StateType type = state.getType();
                            if (type == null) continue;

                            Material material = PEMaterials.materialFromState(type);
                            if (material != null && material != Material.AIR) {
                                int worldY = baseY + localY;
                                cached.set(localX, worldY, localZ, material);
                                boolean waterlogged = isWaterMaterial(material) || PEMaterials.isWaterlogged(state);
                                if (waterlogged) {
                                    cached.setWaterlogged(localX, worldY, localZ, true);
                                }
                            }
                        } catch (Throwable ignored) { }
                    }
                }
            }
        }
        return cached;
    }

    public void shutdown() {
        try {
            chunkExecutor.shutdownNow();
        } catch (Throwable ignored) {}
        clear();
    }

    /**
     * Caches a chunk only if it hasn't been cached yet.
     * If the chunk is already present, we skip re‑caching to avoid duplicate work.
     */
    private void ensureChunkCached(Chunk chunk) {
        if (chunk == null) return;
        String worldName = chunk.getWorld().getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        long key = chunkKey(cx, cz);
        Map<Long, CachedChunk> map = worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        if (map.containsKey(key)) {
            // Already cached – skip heavy snapshot work.
            return;
        }
        // Not cached yet – use the existing cacheBukkitChunk logic to generate and store.
        cacheBukkitChunk(chunk);
    }

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
