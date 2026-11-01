package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import lombok.Getter;
import me.arrow.backend.bukkit.PlatformBackend;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe, packet-driven Chunk and Block Cache for ArrowAntiCheat.
 * Populates chunks from outgoing PacketEvents chunk data and block updates,
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
    private static final boolean LEGACY_BLOCK_DATA_SUPPORTED;

    static {
        boolean legacy = false;

        try {
            // Bukkit BlockData was introduced in 1.13.
            Class.forName("org.bukkit.block.data.BlockData");
        } catch (Throwable ignored) {
            legacy = true;
        }

        LEGACY_BLOCK_DATA_SUPPORTED = legacy;
    }

    // World Name -> (ChunkKey -> CachedChunk)
    private final Map<String, Map<Long, CachedChunk>> worldChunks = new ConcurrentHashMap<>();
    // World Name -> Set of queued ChunkKeys currently waiting or being processed
    private final Map<String, Set<Long>> pendingQueue = new ConcurrentHashMap<>();
    // Snapshot bootstrap jobs are tracked separately so they never suppress packet refreshes.
    private final Map<String, Set<Long>> pendingSnapshotQueue = new ConcurrentHashMap<>();
    private final Object cacheUpdateLock = new Object();
    // Packet block changes received while a full chunk is being parsed, keyed by local block position.
    private final Map<String, Map<Long, Map<Long, WrappedBlockState>>> pendingBlockUpdates = new java.util.HashMap<>();
    // State IDs currently waiting for one safe live-server voxel-shape read.
    private final Set<Integer> pendingCollisionShapes = ConcurrentHashMap.newKeySet();
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
                    public Thread newThread(@NonNull Runnable r) {
                        Thread t = new Thread(r, "Arrow-ChunkQueue-Worker-" + id.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static boolean shouldStoreCollisionState(WrappedBlockState state) {
        if (state == null) {
            return false;
        }

        return PEMaterials.isSlab(state)
                || PEMaterials.isPistonHead(state)
                || PEMaterials.isPistonBase(state)
                || PEMaterials.requiresStatefulCollision(state);
    }

    /** Cache already-loaded server chunks; new chunks are populated from outgoing PacketEvents packets. */
    public boolean isChunkCached(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) return false;
        CachedChunk chunk = getChunk(worldName, chunkX, chunkZ);
        return chunk != null && chunk.hasCompleteStatefulCollisionData();
    }

    /** Cache chunks already loaded by each world without loading any new chunks. */
    public void cacheAllLoadedChunksWithDelay(long delayMs) {
        try {
            org.bukkit.Server server = PlatformBackend.get().getServer();
            if (server == null || PlatformBackend.get().isFabric()) return;
            World[] worlds = TaskUtils.callSync(() -> server.getWorlds().toArray(new World[0]));
            if (worlds == null) return;
            for (World world : worlds) {
                if (world == null) continue;
                Chunk[] loaded = TaskUtils.callSync(world::getLoadedChunks);
                if (loaded == null) continue;
                for (Chunk chunk : loaded) {
                    if (chunk == null || isChunkCached(world.getName(), chunk.getX(), chunk.getZ())) continue;
                    ChunkSnapshot snapshot = TaskUtils.callSync(
                            () -> chunk.getChunkSnapshot(false, false, false));
                    if (snapshot != null) queueChunkSnapshot(world, chunk.getX(), chunk.getZ(), snapshot);
                    if (delayMs > 0L) Thread.sleep(delayMs);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        } finally {
            this.initialized = true;
        }
    }

    /**
     * Fast path: iterate only non-empty sections of a ChunkSnapshot.
     * Each section covers a 16-block vertical slice (sectionY = y >> 4).
     * Fully compatible with 1.7 through 26.2 via SnapshotAdapter.
     */
    private void cacheFromSnapshot(
            ChunkSnapshot snapshot,
            CachedChunk cached,
            int minY,
            int maxY
    ) {
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

                        Material type =
                                SnapshotAdapter.getMaterial(
                                        snapshot,
                                        x,
                                        y,
                                        z
                                );

                        if (type == null || type == Material.AIR) {
                            continue;
                        }


                        /*
                         * Modern state / legacy MaterialData state.
                         */
                        WrappedBlockState state =
                                SnapshotAdapter.getState(
                                        snapshot,
                                        x,
                                        y,
                                        z
                                );

                        if (PEMaterials.requiresStatefulCollision(state)
                                || PEMaterials.isPistonHead(state)
                                || PEMaterials.isPistonBase(state)) {

                            cached.setState(
                                    x,
                                    y,
                                    z,
                                    type,
                                    state.getGlobalId(),
                                    true
                            );

                        } else {
                            cached.set(x, y, z, type);
                        }

                        /*
                         * Store legacy metadata AFTER set()/setState(), because those methods
                         * may clear state-related data.
                         */
                        if (LEGACY_BLOCK_DATA_SUPPORTED) {
                            int legacyData =
                                    SnapshotAdapter.getLegacyBlockData(
                                            snapshot,
                                            x,
                                            y,
                                            z
                                    );

                            cached.setLegacyBlockData(
                                    x,
                                    y,
                                    z,
                                    legacyData
                            );
                        }

                        if (SnapshotAdapter.isWaterlogged(
                                snapshot,
                                x,
                                y,
                                z,
                                type
                        )) {
                            cached.setWaterlogged(
                                    x,
                                    y,
                                    z,
                                    true
                            );
                        }
                    }
                }
            }
        }
        cached.setCompleteStatefulCollisionData();
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
        return ord < WATER_MATERIALS.length && WATER_MATERIALS[ord];
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

    public void putChunk(String worldName, int chunkX, int chunkZ, CachedChunk chunk) {
        if (worldName == null || chunk == null) return;
        worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                .put(chunkKey(chunkX, chunkZ), chunk);
    }

    public void clear() {
        pendingQueue.clear();
        pendingSnapshotQueue.clear();
        synchronized (cacheUpdateLock) {
            pendingBlockUpdates.clear();
        }
        worldChunks.clear();
    }

    /**
     * Stores the authoritative packet block state for partial collision blocks.
     * Full cubes keep the compact material-only representation.
     */
    public void setBlockState(String worldName, int x, int y, int z, WrappedBlockState state) {
        if (worldName == null || state == null) return;
        int cx = x >> 4;
        int cz = z >> 4;
        long key = chunkKey(cx, cz);
        synchronized (cacheUpdateLock) {
            if (isChunkQueued(worldName, cx, cz) || isSnapshotQueued(worldName, cx, cz)) {
                long blockKey = (((long) y) << 8) | ((long) (z & 15) << 4) | (x & 15);
                pendingBlockUpdates
                        .computeIfAbsent(worldName, ignored -> new java.util.HashMap<>())
                        .computeIfAbsent(key, ignored -> new java.util.HashMap<>())
                        .put(blockKey, state);
            }
            CachedChunk chunk = worldChunks
                    .computeIfAbsent(worldName, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(key, ignored -> new CachedChunk(cx, cz));
            writePacketBlockState(chunk, x & 15, y, z & 15, state);
        }
    }

    private static void writePacketBlockState(CachedChunk chunk, int x, int y, int z, WrappedBlockState state) {
        Material material = PEMaterials.materialFromState(state.getType());
        if (material == null) return;
        chunk.setState(x, y, z, material, state.getGlobalId(), shouldStoreCollisionState(state));
        chunk.setWaterlogged(x, y, z, isWaterMaterial(material) || PEMaterials.isWaterlogged(state));
        if (LEGACY_BLOCK_DATA_SUPPORTED) {
            int legacyData = PEMaterials.extractLegacyData(state, material);
            if (legacyData >= 0) chunk.setLegacyBlockData(x, y, z, legacyData);
        }
    }

    private boolean isSnapshotQueued(String worldName, int cx, int cz) {
        Set<Long> set = pendingSnapshotQueue.get(worldName);
        return set != null && set.contains(chunkKey(cx, cz));
    }

    private void applyPendingBlockUpdates(String worldName, int cx, int cz, CachedChunk chunk) {
        Map<Long, Map<Long, WrappedBlockState>> worldUpdates = pendingBlockUpdates.get(worldName);
        if (worldUpdates == null) return;
        Map<Long, WrappedBlockState> updates = worldUpdates.remove(chunkKey(cx, cz));
        if (worldUpdates.isEmpty()) pendingBlockUpdates.remove(worldName);
        if (updates == null) return;
        for (Map.Entry<Long, WrappedBlockState> entry : updates.entrySet()) {
            long blockKey = entry.getKey();
            int x = (int) (blockKey & 15L);
            int z = (int) ((blockKey >>> 4) & 15L);
            int y = (int) (blockKey >> 8);
            writePacketBlockState(chunk, x, y, z, entry.getValue());
        }
    }

    /**
     * Learns one authoritative voxel shape on the owning server region, then
     * shares it by state ID. Packet-thread collision checks never read Bukkit.
     */
    public void requestCollisionShape(World world, int x, int y, int z, WrappedBlockState state) {
        if (world == null || PlatformBackend.get().isFabric()
                || !shouldStoreCollisionState(state)
                || PEMaterials.hasCachedCollisionShape(state)) {
            return;
        }

        int stateId = state.getGlobalId();
        if (!pendingCollisionShapes.add(stateId)) {
            return;
        }

        Location location = new Location(world, x, y, z);
        try {
            TaskUtils.regionLater(location, 0L, () -> {
                try {
                    if (world.isChunkLoaded(x >> 4, z >> 4)) {
                        PEMaterials.cacheCollisionShape(state, world.getBlockAt(x, y, z));
                    }
                } catch (Throwable ignored) {
                } finally {
                    pendingCollisionShapes.remove(stateId);
                }
            });
        } catch (Throwable ignored) {
            pendingCollisionShapes.remove(stateId);
        }
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
        if (chunk != null && chunk.hasCompleteStatefulCollisionData()) {
            return chunk.get(x & 15, y, z & 15);
        }
        return null;
    }


    public Material getBlock(CustomLocation location) {
        if (location == null || location.getWorld() == null) return Material.AIR;
        return getBlock(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Returns the exact packet state when this partial block was cached, otherwise null. */
    public WrappedBlockState getBlockState(CustomLocation location) {
        if (location == null || location.getWorld() == null) return null;
        return getBlockState(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public WrappedBlockState getBlockState(World world, int x, int y, int z) {
        if (world == null) return null;
        return getBlockState(world.getName(), x, y, z);
    }

    public WrappedBlockState getBlockState(String worldName, int x, int y, int z) {
        if (worldName == null) return null;

        CachedChunk chunk = getChunk(worldName, x >> 4, z >> 4);
        if (chunk == null || !chunk.hasCompleteStatefulCollisionData()) return null;

        int stateId = chunk.getStateId(x & 15, y, z & 15);
        if (stateId < 0) return null;

        try {
            return WrappedBlockState.getByGlobalId(stateId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns true after a complete packet chunk has been decoded; entries persist until shutdown. */
    public boolean isChunkLoaded(World world, int chunkX, int chunkZ) {
        if (world == null) return false;
        CachedChunk chunk = getChunk(world.getName(), chunkX, chunkZ);
        return chunk != null && chunk.hasCompleteStatefulCollisionData();
    }


    public int getLegacyBlockData(
            String worldName,
            int x,
            int y,
            int z
    ) {
        if (worldName == null) {
            return -1;
        }

        CachedChunk chunk =
                getChunk(
                        worldName,
                        x >> 4,
                        z >> 4
                );

        if (chunk == null || !chunk.hasCompleteStatefulCollisionData()) {
            return -1;
        }

        return chunk.getLegacyBlockData(
                x & 15,
                y,
                z & 15
        );
    }

    public int getLegacyBlockData(CustomLocation location) {
        if (location == null
                || location.getWorld() == null) {
            return -1;
        }

        return getLegacyBlockData(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
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
        return chunk != null && chunk.hasCompleteStatefulCollisionData()
                && chunk.isWaterlogged(x & 15, y, z & 15);
    }

    public boolean isWaterLogged(CustomLocation location) {
        if (location == null || location.getWorld() == null) return false;
        return isWaterLogged(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
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
        if (!markQueued(worldName, chunkX, chunkZ)) return;

        try {
            chunkExecutor.execute(() -> {
                try {
                    CachedChunk cached =
                            parsePacketColumn(
                                    chunkX,
                                    chunkZ,
                                    minY,
                                    column
                            );

                    if (cached != null) {
                        synchronized (cacheUpdateLock) {
                            applyPendingBlockUpdates(worldName, chunkX, chunkZ, cached);
                            putChunk(worldName, chunkX, chunkZ, cached);
                            unmarkQueued(worldName, chunkX, chunkZ);
                        }
                        return;
                    }
                } catch (Throwable ignored) {
                } finally {
                    unmarkQueued(
                            worldName,
                            chunkX,
                            chunkZ
                    );
                }
            });
        } catch (RejectedExecutionException ignored) {
            unmarkQueued(
                    worldName,
                    chunkX,
                    chunkZ
            );
        }
    }

    public void queueChunkSnapshot(World world, int cx, int cz, ChunkSnapshot snapshot) {
        if (world == null || snapshot == null) return;
        String worldName = world.getName();
        long chunkKey = chunkKey(cx, cz);
        if (!pendingSnapshotQueue.computeIfAbsent(worldName, ignored -> ConcurrentHashMap.newKeySet()).add(chunkKey)) {
            return;
        }
        int minY = getWorldMinY(world);
        int maxY = getWorldMaxY(world);
        try {
            chunkExecutor.execute(() -> {
                try {
                    CachedChunk cached = new CachedChunk(cx, cz);
                    cacheFromSnapshot(snapshot, cached, minY, maxY);
                    synchronized (cacheUpdateLock) {
                        CachedChunk current = getChunk(worldName, cx, cz);
                        if (!isChunkQueued(worldName, cx, cz)
                                && (current == null || !current.hasCompleteStatefulCollisionData())) {
                            applyPendingBlockUpdates(worldName, cx, cz, cached);
                            putChunk(worldName, cx, cz, cached);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    Set<Long> queued = pendingSnapshotQueue.get(worldName);
                    if (queued != null) queued.remove(chunkKey);
                }
            });
        } catch (RejectedExecutionException ignored) {
            Set<Long> queued = pendingSnapshotQueue.get(worldName);
            if (queued != null) queued.remove(chunkKey);
        }
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
                            StateType type = state.getType();

                            Material material = PEMaterials.materialFromState(type);
                            if (material != null && material != Material.AIR) {
                                int worldY = baseY + localY;
                                boolean storeState = shouldStoreCollisionState(state);

                                cached.setState(
                                        localX,
                                        worldY,
                                        localZ,
                                        material,
                                        state.getGlobalId(),
                                        storeState
                                );

                                // On pre-1.13 (legacy) servers, extract the real legacy metadata
                                // for stairs, slabs, etc. from state and packet chunk data.
                                if (LEGACY_BLOCK_DATA_SUPPORTED) {
                                    int legacyData = PEMaterials.extractLegacyData(state, material);
                                    if (legacyData < 0) {
                                        try {
                                            legacyData = section.getBlockId(localX, localY, localZ) & 0xF;
                                        } catch (Throwable ignored) {}
                                    }
                                    if (legacyData >= 0) {
                                        cached.setLegacyBlockData(
                                                localX,
                                                worldY,
                                                localZ,
                                                legacyData
                                        );
                                    }
                                }

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
        cached.setCompleteStatefulCollisionData();
        return cached;
    }

    public void shutdown() {
        try {
            chunkExecutor.shutdownNow();
        } catch (Throwable ignored) {}
        clear();
        pendingCollisionShapes.clear();
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
        private volatile boolean completeStatefulCollisionData;

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

                if (sec != null) {
                    sec.set(
                            relX,
                            y & 15,
                            relZ,
                            Material.AIR
                    );

                    sec.setStateId(
                            relX,
                            y & 15,
                            relZ,
                            -1
                    );

                    sec.setLegacyBlockData(
                            relX,
                            y & 15,
                            relZ,
                            -1
                    );

                    // Clear stale waterlogged bit — AIR is never waterlogged
                    sec.setWaterlogged(relX, y & 15, relZ, false);
                }

                return;
            }

            if (sections[idx] == null) sections[idx] = new CachedSection();
            sections[idx].set(relX, y & 15, relZ, material);
            sections[idx].setStateId(relX, y & 15, relZ, -1);
            // Clear stale waterlogged bit for non-water materials (e.g. sand/door replacing water)
            if (!ChunkCache.isWaterMaterial(material)) {
                sections[idx].setWaterlogged(relX, y & 15, relZ, false);
            }
        }

        public int getLegacyBlockData(
                int relX,
                int y,
                int relZ
        ) {
            if (relX < 0 || relX > 15
                    || relZ < 0 || relZ > 15) {
                return -1;
            }

            int idx =
                    (y >> 4) + SECTION_OFFSET;

            if (!validIndex(idx)) {
                return -1;
            }

            CachedSection section =
                    sections[idx];

            return section != null
                    ? section.getLegacyBlockData(
                    relX,
                    y & 15,
                    relZ
            )
                    : -1;
        }

        public void setLegacyBlockData(
                int relX,
                int y,
                int relZ,
                int data
        ) {
            if (relX < 0 || relX > 15
                    || relZ < 0 || relZ > 15) {
                return;
            }

            int idx =
                    (y >> 4) + SECTION_OFFSET;

            if (!validIndex(idx)) {
                return;
            }

            if (sections[idx] == null) {
                sections[idx] = new CachedSection();
            }

            sections[idx].setLegacyBlockData(
                    relX,
                    y & 15,
                    relZ,
                    data
            );
        }

        public void setState(
                int relX,
                int y,
                int relZ,
                Material material,
                int stateId,
                boolean storeState
        ) {
            set(relX, y, relZ, material);

            if (!storeState
                    || material == null
                    || material == Material.AIR) {
                return;
            }

            int idx = (y >> 4) + SECTION_OFFSET;

            if (!validIndex(idx) || sections[idx] == null) {
                return;
            }

            sections[idx].setStateId(
                    relX,
                    y & 15,
                    relZ,
                    stateId
            );
        }

        public int getStateId(int relX, int y, int relZ) {
            if (relX < 0 || relX > 15 || relZ < 0 || relZ > 15) return -1;
            int idx = (y >> 4) + SECTION_OFFSET;
            if (!validIndex(idx) || sections[idx] == null) return -1;
            return sections[idx].getStateId(relX, y & 15, relZ);
        }

        public boolean hasCompleteStatefulCollisionData() {
            return completeStatefulCollisionData;
        }

        public void setCompleteStatefulCollisionData() {
            this.completeStatefulCollisionData = true;
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

        /*
         * Material ordinal:
         * 0 = AIR
         * material.ordinal() + 1 = stored value
         */
        private final char[] blockOrdinals = new char[4096];

        /*
         * 4096 bits = 512 bytes.
         */
        private final long[] waterloggedMask = new long[64];

        /*
         * Only allocated if this section actually contains
         * stateful collision blocks.
         *
         * -1 = no state stored
         */
        private volatile int[] stateIds;

        /*
         * ONLY allocated on legacy servers.
         */
        private byte[] legacyBlockData;

        private static final Material[] MATERIAL_VALUES = Material.values();

        public Material get(int localX, int localY, int localZ) {
            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            char ord = blockOrdinals[index];

            if (ord == 0) {
                return Material.AIR;
            }

            int idx = ord - 1;

            return idx < MATERIAL_VALUES.length
                    ? MATERIAL_VALUES[idx]
                    : Material.AIR;
        }

        public void set(
                int localX,
                int localY,
                int localZ,
                Material material
        ) {
            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            if (material == null || material == Material.AIR) {
                blockOrdinals[index] = 0;

                /*
                 * Do not allocate state storage just to clear it.
                 */
                int[] states = stateIds;
                if (states != null) {
                    states[index] = -1;
                }

                /*
                 * Legacy storage is only relevant on old servers.
                 */
                if (LEGACY_BLOCK_DATA_SUPPORTED && legacyBlockData != null) {
                    legacyBlockData[index] = -1;
                }

                return;
            }

            blockOrdinals[index] =
                    (char) (material.ordinal() + 1);

            /*
             * A normal material write removes any previously stored
             * state ID for that block.
             */
            int[] states = stateIds;
            if (states != null) {
                states[index] = -1;
            }
        }

        public void setStateId(
                int localX,
                int localY,
                int localZ,
                int stateId
        ) {
            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            if (stateId < 0) {
                int[] states = stateIds;

                if (states != null) {
                    states[index] = -1;
                }

                return;
            }

            int[] states = stateIds;

            if (states == null) {
                synchronized (this) {
                    states = stateIds;

                    if (states == null) {
                        states = new int[4096];
                        java.util.Arrays.fill(states, -1);
                        stateIds = states;
                    }
                }
            }

            states[index] = stateId;
        }

        public int getStateId(
                int localX,
                int localY,
                int localZ
        ) {
            int[] states = stateIds;

            if (states == null) {
                return -1;
            }

            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            return states[index];
        }

        public int getLegacyBlockData(
                int localX,
                int localY,
                int localZ
        ) {
            if (!LEGACY_BLOCK_DATA_SUPPORTED) {
                return -1;
            }

            byte[] legacy = legacyBlockData;

            if (legacy == null) {
                return -1;
            }

            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            return legacy[index];
        }

        public void setLegacyBlockData(
                int localX,
                int localY,
                int localZ,
                int data
        ) {
            if (!LEGACY_BLOCK_DATA_SUPPORTED || data < 0) {
                return;
            }

            byte[] legacy = legacyBlockData;

            if (legacy == null) {
                synchronized (this) {
                    legacy = legacyBlockData;

                    if (legacy == null) {
                        legacy = new byte[4096];
                        java.util.Arrays.fill(legacy, (byte) -1);
                        legacyBlockData = legacy;
                    }
                }
            }

            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            legacy[index] = (byte) (data & 0xFF);
        }

        public boolean isWaterlogged(
                int localX,
                int localY,
                int localZ
        ) {
            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            return (waterloggedMask[index >> 6]
                    & (1L << (index & 63))) != 0;
        }

        public void setWaterlogged(
                int localX,
                int localY,
                int localZ,
                boolean waterlogged
        ) {
            int index = (localY << 8)
                    | (localZ << 4)
                    | localX;

            if (waterlogged) {
                waterloggedMask[index >> 6] |=
                        (1L << (index & 63));
            } else {
                waterloggedMask[index >> 6] &=
                        ~(1L << (index & 63));
            }
        }
    }
}
