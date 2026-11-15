package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import lombok.Getter;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.*;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe, packet-driven Chunk and Block Cache for ArrowAntiCheat.
 * Populates chunks from outgoing PacketEvents chunk data and block updates,
 * allowing collision and ground checks to query blocks in O(1) time without touching
 * Bukkit worlds asynchronously or causing Folia thread check exceptions.
 */

// Done by AI, i cannot bother doing chunk shit for every MC version, if you can, good for you.
public class ChunkCache {

    private static final ChunkCache INSTANCE = new ChunkCache();

    public static ChunkCache get() {
        return INSTANCE;
    }

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
    private final Object cacheUpdateLock = new Object();
    // Packet block changes received while a full chunk is being parsed, keyed by local block position.
    private final Map<String, Map<Long, Map<Long, PendingBlockState>>> pendingBlockUpdates = new java.util.HashMap<>();
    private final Map<String, Map<Long, List<PendingColumnUpdate>>> pendingColumnUpdates = new java.util.HashMap<>();
    // Legacy metadata (low nibble) plus a double-slab marker (bit 0x10), applied after queued parses.
    private final Map<String, Map<Long, Map<Long, Integer>>> pendingLegacyBlockData = new java.util.HashMap<>();
    // Dedicated worker thread pool for asynchronous chunk caching
    private final ThreadPoolExecutor chunkExecutor;
    // Shared cache residency: a chunk stays active while any player has it loaded.
    private final Object chunkViewLock = new Object();
    private final Map<WorldChunkKey, Set<UUID>> chunkViewers = new HashMap<>();
    private final Map<UUID, Set<WorldChunkKey>> playerChunks = new HashMap<>();
    private final Map<WorldChunkKey, PendingChunkEviction> pendingEvictions = new HashMap<>();
    private final DelayQueue<PendingChunkEviction> evictionQueue = new DelayQueue<>();
    private final Thread evictionThread;
    private volatile boolean running = true;

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

        this.evictionThread = new Thread(this::runEvictionWorker, "Arrow-ChunkCache-Eviction");
        this.evictionThread.setDaemon(true);
        this.evictionThread.start();
    }

    public void markChunkLoaded(UUID playerId, String worldName, int chunkX, int chunkZ) {
        if (playerId == null || worldName == null) return;

        WorldChunkKey key = new WorldChunkKey(worldName, chunkX, chunkZ);
        synchronized (chunkViewLock) {
            PendingChunkEviction pending = pendingEvictions.remove(key);
            if (pending != null) evictionQueue.remove(pending);

            chunkViewers.computeIfAbsent(key, ignored -> new HashSet<>()).add(playerId);
            playerChunks.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(key);
        }
    }

    public void markChunkUnloaded(UUID playerId, String worldName, int chunkX, int chunkZ) {
        if (playerId == null || worldName == null) return;

        WorldChunkKey key = new WorldChunkKey(worldName, chunkX, chunkZ);
        synchronized (chunkViewLock) {
            Set<WorldChunkKey> loadedChunks = playerChunks.get(playerId);
            if (loadedChunks != null) {
                loadedChunks.remove(key);
                if (loadedChunks.isEmpty()) playerChunks.remove(playerId);
            }

            Set<UUID> viewers = chunkViewers.get(key);
            if (viewers != null) {
                viewers.remove(playerId);
                if (viewers.isEmpty()) {
                    chunkViewers.remove(key);
                    scheduleEvictionLocked(key);
                }
            } else {
                // Also expire chunks first observed before player tracking began.
                if (!pendingEvictions.containsKey(key)) scheduleEvictionLocked(key);
            }
        }
    }

    public void removePlayerFromChunks(UUID playerId) {
        if (playerId == null) return;

        synchronized (chunkViewLock) {
            Set<WorldChunkKey> loadedChunks = playerChunks.remove(playerId);
            if (loadedChunks == null) return;

            for (WorldChunkKey key : loadedChunks) {
                Set<UUID> viewers = chunkViewers.get(key);
                if (viewers == null) continue;

                viewers.remove(playerId);
                if (viewers.isEmpty()) {
                    chunkViewers.remove(key);
                    scheduleEvictionLocked(key);
                }
            }
        }
    }

    private void scheduleEvictionIfUnviewed(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) return;
        WorldChunkKey key = new WorldChunkKey(worldName, chunkX, chunkZ);
        synchronized (chunkViewLock) {
            if (!chunkViewers.containsKey(key) && !pendingEvictions.containsKey(key)) {
                scheduleEvictionLocked(key);
            }
        }
    }

    private void scheduleEvictionLocked(WorldChunkKey key) {
        PendingChunkEviction previous = pendingEvictions.remove(key);
        if (previous != null) evictionQueue.remove(previous);

        PendingChunkEviction eviction = new PendingChunkEviction(
                key,
                System.nanoTime() + TimeUnit.MINUTES.toNanos(5L)
        );
        pendingEvictions.put(key, eviction);
        evictionQueue.offer(eviction);
    }

    private void runEvictionWorker() {
        while (running) {
            try {
                PendingChunkEviction eviction = evictionQueue.take();
                synchronized (chunkViewLock) {
                    if (pendingEvictions.get(eviction.key) != eviction) continue;
                    pendingEvictions.remove(eviction.key);
                    if (chunkViewers.containsKey(eviction.key)) continue;

                    synchronized (cacheUpdateLock) {
                        Map<Long, CachedChunk> chunks = worldChunks.get(eviction.key.worldName);
                        if (chunks != null) {
                            chunks.remove(eviction.key.chunkKey);
                            if (chunks.isEmpty()) worldChunks.remove(eviction.key.worldName, chunks);
                        }

                        Map<Long, Map<Long, PendingBlockState>> updates =
                                pendingBlockUpdates.get(eviction.key.worldName);
                        if (updates != null) {
                            updates.remove(eviction.key.chunkKey);
                            if (updates.isEmpty()) pendingBlockUpdates.remove(eviction.key.worldName);
                        }
                        Map<Long, List<PendingColumnUpdate>> columnUpdates =
                                pendingColumnUpdates.get(eviction.key.worldName);
                        if (columnUpdates != null) {
                            columnUpdates.remove(eviction.key.chunkKey);
                            if (columnUpdates.isEmpty()) pendingColumnUpdates.remove(eviction.key.worldName);
                        }
                        Map<Long, Map<Long, Integer>> legacy =
                                pendingLegacyBlockData.get(eviction.key.worldName);
                        if (legacy != null) {
                            legacy.remove(eviction.key.chunkKey);
                            if (legacy.isEmpty()) pendingLegacyBlockData.remove(eviction.key.worldName);
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                if (!running) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Throwable ignored) {
                // A failed eviction must not stop future cache cleanup.
            }
        }
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static boolean shouldStoreCollisionState(WrappedBlockState state) {
        if (state == null) {
            return false;
        }

        return PEMaterials.isSlab(state)
                || PEMaterials.isCarpet(state)
                || PEMaterials.isPistonHead(state)
                || PEMaterials.isPistonBase(state)
                || PEMaterials.requiresStatefulCollision(state);
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
        putChunkInternal(worldName, chunkX, chunkZ, chunk);
        scheduleEvictionIfUnviewed(worldName, chunkX, chunkZ);
    }

    private void putChunkInternal(String worldName, int chunkX, int chunkZ, CachedChunk chunk) {
        worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                .put(chunkKey(chunkX, chunkZ), chunk);
    }

    public void clear() {
        pendingQueue.clear();
        synchronized (chunkViewLock) {
            chunkViewers.clear();
            playerChunks.clear();
            pendingEvictions.clear();
            evictionQueue.clear();
        }
        synchronized (cacheUpdateLock) {
            pendingBlockUpdates.clear();
            pendingColumnUpdates.clear();
            pendingLegacyBlockData.clear();
        }
        worldChunks.clear();
    }

    /**
     * Stores the authoritative packet block state for partial collision blocks.
     * Full cubes keep the compact material-only representation.
     */
    public void setBlockState(String worldName, int x, int y, int z, WrappedBlockState state) {
        setBlockState(worldName, x, y, z, state, -1);
    }

    public void setBlockState(String worldName, int x, int y, int z,
                              WrappedBlockState state, int combinedBlockId) {
        if (worldName == null || state == null) return;
        int cx = x >> 4;
        int cz = z >> 4;
        long key = chunkKey(cx, cz);
        synchronized (cacheUpdateLock) {
            if (isChunkQueued(worldName, cx, cz)) {
                long blockKey = (((long) y) << 8) | ((long) (z & 15) << 4) | (x & 15);
                pendingBlockUpdates
                        .computeIfAbsent(worldName, ignored -> new java.util.HashMap<>())
                        .computeIfAbsent(key, ignored -> new java.util.HashMap<>())
                        .put(blockKey, new PendingBlockState(state, combinedBlockId));
            }
            CachedChunk chunk = worldChunks
                    .computeIfAbsent(worldName, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(key, ignored -> new CachedChunk(cx, cz));
            writePacketBlockState(chunk, x & 15, y, z & 15, state, combinedBlockId);
        }
        scheduleEvictionIfUnviewed(worldName, cx, cz);
    }

    private static void writePacketBlockState(CachedChunk chunk, int x, int y, int z,
                                              WrappedBlockState state, int combinedBlockId) {
        Material material = PEMaterials.materialFromState(state.getType());
        // PacketEvents block IDs are global state IDs on modern protocols. Only
        // interpret them as legacy (block ID << 4 | metadata) values on legacy
        // servers; otherwise unrelated states can be misread as legacy water.
        if (LEGACY_BLOCK_DATA_SUPPORTED
                && (material == null || material == Material.AIR)
                && combinedBlockId >= 0) {
            material = PEMaterials.materialFromLegacyCombinedId(combinedBlockId);
        }
        if (material == null) return;
        chunk.setState(x, y, z, material, state.getGlobalId(), shouldStoreCollisionState(state));
        if (!LEGACY_BLOCK_DATA_SUPPORTED && isWaterMaterial(material)) {
            chunk.setFluidLevel(x, y, z, getFluidLevel(state));
        }
        chunk.setWaterlogged(x, y, z, isWaterMaterial(material) || PEMaterials.isWaterlogged(state));
        if (LEGACY_BLOCK_DATA_SUPPORTED) {
            int legacyData = PEMaterials.encodeLegacyBlockData(state, combinedBlockId);
            if (legacyData < 0) {
                legacyData = PEMaterials.extractLegacyData(state, material);
            }
            if (legacyData >= 0) chunk.setLegacyBlockData(x, y, z, legacyData);
        }
    }

    /** Stores legacy metadata; bit 0x10 marks double slabs when old materials collapse their state. */
    public void setLegacyBlockData(String worldName, int x, int y, int z, int data) {
        if (!LEGACY_BLOCK_DATA_SUPPORTED || worldName == null || data < 0) return;
        int cx = x >> 4;
        int cz = z >> 4;
        long key = chunkKey(cx, cz);
        long blockKey = (((long) y) << 8) | ((long) (z & 15) << 4) | (x & 15);
        synchronized (cacheUpdateLock) {
            if (isChunkQueued(worldName, cx, cz)) {
                pendingLegacyBlockData
                        .computeIfAbsent(worldName, ignored -> new HashMap<>())
                        .computeIfAbsent(key, ignored -> new HashMap<>())
                        .put(blockKey, data & 0x1F);
            }
            CachedChunk chunk = worldChunks
                    .computeIfAbsent(worldName, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(key, ignored -> new CachedChunk(cx, cz));
            chunk.setLegacyBlockData(x & 15, y, z & 15, data & 0x1F);
        }
    }

    private void applyPendingBlockUpdates(String worldName, int cx, int cz, CachedChunk chunk) {
        Map<Long, Map<Long, PendingBlockState>> worldUpdates = pendingBlockUpdates.get(worldName);
        if (worldUpdates != null) {
            Map<Long, PendingBlockState> updates = worldUpdates.remove(chunkKey(cx, cz));
            if (worldUpdates.isEmpty()) pendingBlockUpdates.remove(worldName);
            if (updates != null) {
                for (Map.Entry<Long, PendingBlockState> entry : updates.entrySet()) {
                    long blockKey = entry.getKey();
                    int x = (int) (blockKey & 15L);
                    int z = (int) ((blockKey >>> 4) & 15L);
                    int y = (int) (blockKey >> 8);
                    PendingBlockState pending = entry.getValue();
                    writePacketBlockState(chunk, x, y, z, pending.state, pending.combinedBlockId);
                }
            }
        }

        Map<Long, Map<Long, Integer>> worldLegacy = pendingLegacyBlockData.get(worldName);
        if (worldLegacy == null) return;
        Map<Long, Integer> legacy = worldLegacy.remove(chunkKey(cx, cz));
        if (worldLegacy.isEmpty()) pendingLegacyBlockData.remove(worldName);
        if (legacy == null) return;
        for (Map.Entry<Long, Integer> entry : legacy.entrySet()) {
            long blockKey = entry.getKey();
            int x = (int) (blockKey & 15L);
            int z = (int) ((blockKey >>> 4) & 15L);
            int y = (int) (blockKey >> 8);
            chunk.setLegacyBlockData(x, y, z, entry.getValue());
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

    /** Returns the cached modern fluid level, or -1 when no fluid metadata is available. */
    public int getFluidLevel(String worldName, int x, int y, int z) {
        if (worldName == null) return -1;
        CachedChunk chunk = getChunk(worldName, x >> 4, z >> 4);
        if (chunk == null || !chunk.hasCompleteStatefulCollisionData()) return -1;
        return chunk.getFluidLevel(x & 15, y, z & 15);
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
        queuePacketChunk(worldName, chunkX, chunkZ, minY, column.getChunks(), column.isFullChunk());
    }

    public void queuePacketChunk(String worldName, int chunkX, int chunkZ, int minY, BaseChunk[] sections) {
        queuePacketChunk(worldName, chunkX, chunkZ, minY, sections, true);
    }

    private void queuePacketChunk(String worldName, int chunkX, int chunkZ, int minY,
                                  BaseChunk[] sections, boolean fullChunk) {
        if (worldName == null || sections == null) return;
        if (!markQueued(worldName, chunkX, chunkZ)) {
            synchronized (cacheUpdateLock) {
                if (isChunkQueued(worldName, chunkX, chunkZ)) {
                    pendingColumnUpdates
                            .computeIfAbsent(worldName, ignored -> new HashMap<>())
                            .computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>())
                            .add(new PendingColumnUpdate(minY, sections, fullChunk));
                    return;
                }
            }
            queuePacketChunk(worldName, chunkX, chunkZ, minY, sections, fullChunk);
            return;
        }

        try {
            chunkExecutor.execute(() -> {
                boolean releasedQueued = false;
                try {
                    CachedChunk cached =
                            parsePacketColumn(
                                    chunkX,
                                    chunkZ,
                                    minY,
                                    sections,
                                    fullChunk
                            );

                    if (cached != null) {
                        synchronized (cacheUpdateLock) {
                            applyPendingBlockUpdates(worldName, chunkX, chunkZ, cached);
                            CachedChunk previous = getChunk(worldName, chunkX, chunkZ);
                            if (fullChunk || previous == null) {
                                putChunkInternal(worldName, chunkX, chunkZ, cached);
                            } else {
                                previous.mergeSectionsFrom(cached);
                            }
                            initialized = true;
                        }

                        while (true) {
                            List<PendingColumnUpdate> updates;
                            synchronized (cacheUpdateLock) {
                                Map<Long, List<PendingColumnUpdate>> worldUpdates =
                                        pendingColumnUpdates.get(worldName);
                                updates = worldUpdates != null
                                        ? worldUpdates.remove(chunkKey(chunkX, chunkZ))
                                        : null;
                                if (worldUpdates != null && worldUpdates.isEmpty()) {
                                    pendingColumnUpdates.remove(worldName);
                                }
                                if (updates == null || updates.isEmpty()) {
                                    unmarkQueued(worldName, chunkX, chunkZ);
                                    releasedQueued = true;
                                    break;
                                }
                            }

                            for (PendingColumnUpdate update : updates) {
                                CachedChunk partial = parsePacketColumn(
                                        chunkX, chunkZ, update.minY, update.sections, update.fullChunk
                                );
                                if (partial == null) continue;
                                synchronized (cacheUpdateLock) {
                                    applyPendingBlockUpdates(worldName, chunkX, chunkZ, partial);
                                    CachedChunk current = getChunk(worldName, chunkX, chunkZ);
                                    if (update.fullChunk || current == null) {
                                        putChunkInternal(worldName, chunkX, chunkZ, partial);
                                    } else {
                                        current.mergeSectionsFrom(partial);
                                    }
                                }
                            }
                        }
                        scheduleEvictionIfUnviewed(worldName, chunkX, chunkZ);
                        return;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!releasedQueued) unmarkQueued(worldName, chunkX, chunkZ);
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

    /**
     * Highly optimized, allocation-free parser for PacketEvents Column chunk packets.
     */
    public CachedChunk parsePacketColumn(int chunkX, int chunkZ, int minY, Column column) {
        return column == null ? null : parsePacketColumn(
                chunkX, chunkZ, minY, column.getChunks(), column.isFullChunk()
        );
    }

    public CachedChunk parsePacketColumn(int chunkX, int chunkZ, int minY, BaseChunk[] sections) {
        return parsePacketColumn(chunkX, chunkZ, minY, sections, true);
    }

    private CachedChunk parsePacketColumn(int chunkX, int chunkZ, int minY,
                                          BaseChunk[] sections, boolean fullChunk) {
        if (sections == null) return null;

        CachedChunk cached = new CachedChunk(chunkX, chunkZ);
        cached.setFullChunkData(fullChunk);
        int minSection = minY >> 4;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            BaseChunk section = sections[sectionIndex];
            int sectionY = minSection + sectionIndex;
            if (fullChunk || section != null) cached.markSectionPresent(sectionY);
            if (section == null || section.isEmpty()) continue;

            int baseY = sectionY << 4;

            for (int localX = 0; localX < 16; localX++) {
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        try {
                            WrappedBlockState state = section.get(localX, localY, localZ);
                            StateType type = state.getType();

                            int combinedBlockId = -1;
                            if (LEGACY_BLOCK_DATA_SUPPORTED) {
                                try {
                                    combinedBlockId = section.getBlockId(localX, localY, localZ);
                                } catch (Throwable ignored) {
                                }
                            }
                            Material material = PEMaterials.materialFromState(type);
                            if (LEGACY_BLOCK_DATA_SUPPORTED
                                    && (material == null || material == Material.AIR)
                                    && combinedBlockId >= 0) {
                                material = PEMaterials.materialFromLegacyCombinedId(combinedBlockId);
                            }
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
                                if (!LEGACY_BLOCK_DATA_SUPPORTED && isWaterMaterial(material)) {
                                    cached.setFluidLevel(localX, worldY, localZ, getFluidLevel(state));
                                }

                                if (LEGACY_BLOCK_DATA_SUPPORTED) {
                                    int legacyData;
                                    try {
                                        // Legacy packet section values contain block ID + metadata nibble.
                                        legacyData = PEMaterials.encodeLegacyBlockData(
                                                state,
                                                combinedBlockId
                                        );
                                    } catch (Throwable ignored) {
                                        legacyData = PEMaterials.extractLegacyData(state, material);
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
        if (fullChunk) cached.setCompleteStatefulCollisionData();
        return cached;
    }

    /** Extract the compact level/source representation needed by fluid prediction. */
    private static int getFluidLevel(WrappedBlockState state) {
        if (state == null) return 0;

        try {
            return Math.max(0, Math.min(15, state.getLevel()));
        } catch (Throwable ignored) {
        }

        String description = String.valueOf(state).toLowerCase(java.util.Locale.ROOT);
        int levelIndex = description.indexOf("level=");
        if (levelIndex >= 0) {
            int start = levelIndex + 6;
            int end = start;
            while (end < description.length() && Character.isDigit(description.charAt(end))) end++;
            if (end > start) {
                try {
                    return Math.max(0, Math.min(15, Integer.parseInt(description.substring(start, end))));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (description.contains("falling=true") || description.contains("is_falling=true")) return 8;
        if (description.contains("source=false") || description.contains("issourceblock=false")
                || description.contains("issource=false")) return 1;
        return 0;
    }

    public void shutdown() {
        running = false;
        evictionThread.interrupt();
        try {
            chunkExecutor.shutdownNow();
        } catch (Throwable ignored) {}
        clear();
    }

    private static final class WorldChunkKey {
        private final String worldName;
        private final long chunkKey;

        private WorldChunkKey(String worldName, int chunkX, int chunkZ) {
            this.worldName = worldName;
            this.chunkKey = ChunkCache.chunkKey(chunkX, chunkZ);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof WorldChunkKey)) return false;
            WorldChunkKey that = (WorldChunkKey) object;
            return chunkKey == that.chunkKey && worldName.equals(that.worldName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldName, chunkKey);
        }
    }

    private static final class PendingBlockState {
        private final WrappedBlockState state;
        private final int combinedBlockId;

        private PendingBlockState(WrappedBlockState state, int combinedBlockId) {
            this.state = state;
            this.combinedBlockId = combinedBlockId;
        }
    }

    private static final class PendingColumnUpdate {
        private final int minY;
        private final BaseChunk[] sections;
        private final boolean fullChunk;

        private PendingColumnUpdate(int minY, BaseChunk[] sections, boolean fullChunk) {
            this.minY = minY;
            this.sections = sections;
            this.fullChunk = fullChunk;
        }
    }

    private static final class PendingChunkEviction implements Delayed {
        private final WorldChunkKey key;
        private final long expiresAtNanos;

        private PendingChunkEviction(WorldChunkKey key, long expiresAtNanos) {
            this.key = key;
            this.expiresAtNanos = expiresAtNanos;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(expiresAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) return 0;
            long difference = expiresAtNanos - ((PendingChunkEviction) other).expiresAtNanos;
            return difference < 0L ? -1 : difference > 0L ? 1 : 0;
        }
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
        private final boolean[] packetSectionsPresent = new boolean[SECTION_COUNT];
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
                    sec.setFluidLevel(relX, y & 15, relZ, -1);
                }

                return;
            }

            if (sections[idx] == null) sections[idx] = new CachedSection();
            sections[idx].set(relX, y & 15, relZ, material);
            sections[idx].setStateId(relX, y & 15, relZ, -1);
            sections[idx].setFluidLevel(relX, y & 15, relZ, -1);
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

        public int getFluidLevel(int relX, int y, int relZ) {
            if (relX < 0 || relX > 15 || relZ < 0 || relZ > 15) return -1;
            int idx = (y >> 4) + SECTION_OFFSET;
            if (!validIndex(idx) || sections[idx] == null) return -1;
            return sections[idx].getFluidLevel(relX, y & 15, relZ);
        }

        public void setFluidLevel(int relX, int y, int relZ, int level) {
            if (relX < 0 || relX > 15 || relZ < 0 || relZ > 15) return;
            int idx = (y >> 4) + SECTION_OFFSET;
            if (!validIndex(idx)) return;
            if (sections[idx] == null) {
                if (level < 0) return;
                sections[idx] = new CachedSection();
            }
            sections[idx].setFluidLevel(relX, y & 15, relZ, level);
        }

        public boolean hasCompleteStatefulCollisionData() {
            return completeStatefulCollisionData;
        }

        public void setCompleteStatefulCollisionData() {
            this.completeStatefulCollisionData = true;
        }

        private void setFullChunkData(boolean fullChunk) {
            if (fullChunk) {
                java.util.Arrays.fill(packetSectionsPresent, true);
            }
        }

        private void markSectionPresent(int sectionY) {
            int idx = sectionY + SECTION_OFFSET;
            if (validIndex(idx)) packetSectionsPresent[idx] = true;
        }

        private void mergeSectionsFrom(CachedChunk update) {
            if (update == null) return;
            for (int i = 0; i < sections.length; i++) {
                if (update.packetSectionsPresent[i]) {
                    sections[i] = update.sections[i];
                    packetSectionsPresent[i] = true;
                }
            }
            if (update.completeStatefulCollisionData) {
                completeStatefulCollisionData = true;
            }
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

        // One compact fluid level per block; allocated only for sections with fluids.
        private volatile byte[] fluidLevels;

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

        public int getFluidLevel(int localX, int localY, int localZ) {
            byte[] levels = fluidLevels;
            if (levels == null) return -1;
            int index = (localY << 8) | (localZ << 4) | localX;
            return levels[index] & 0x0F;
        }

        public void setFluidLevel(int localX, int localY, int localZ, int level) {
            int index = (localY << 8) | (localZ << 4) | localX;
            byte[] levels = fluidLevels;
            if (level < 0) {
                if (levels != null) levels[index] = 0;
                return;
            }
            if (levels == null) {
                synchronized (this) {
                    levels = fluidLevels;
                    if (levels == null) fluidLevels = levels = new byte[4096];
                }
            }
            levels[index] = (byte) Math.min(15, level);
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
