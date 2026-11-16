package me.arrow.playerdata.cache;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import lombok.Getter;
import me.arrow.managers.errors.ErrorMonitor;
import me.arrow.managers.profiler.Profiler;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import me.arrow.utils.TaskUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
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
import java.util.function.Consumer;
import java.lang.reflect.Method;

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
    private static final Method MATERIAL_GET_BY_ID = findLegacyMaterialMethod();
    private static final Map<Integer, Material> LEGACY_MATERIAL_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, WrappedBlockState> LEGACY_SNAPSHOT_STATE_CACHE = new ConcurrentHashMap<>();

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
                        // Chunk parsing is background work; let server and network
                        // threads win CPU time when the machine is busy.
                        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                        t.setUncaughtExceptionHandler((thread, failure) -> {
                            ErrorMonitor.report("ChunkCache", "uncaught-worker", failure,
                                    "thread=" + thread.getName());
                        });
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.evictionThread = new Thread(this::runEvictionWorker, "Arrow-ChunkCache-Eviction");
        this.evictionThread.setDaemon(true);
        this.evictionThread.setUncaughtExceptionHandler((thread, failure) -> {
            ErrorMonitor.report("ChunkCache", "uncaught-eviction-worker", failure,
                    "thread=" + thread.getName());
        });
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
                ErrorMonitor.report("ChunkCache", "eviction-worker",
                        ignored, "queueSize=" + evictionQueue.size());
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

    /**
     * Seeds the cache from chunks already loaded at startup without scanning their
     * blocks on the server thread. Bukkit creates each immutable ChunkSnapshot on
     * the main thread; conversion and cache construction then run on the existing
     * chunk workers. The bounded capture loop works on Bukkit 1.7 through modern
     * versions and never requests a chunk that was not already loaded.
     */
    public void preloadLoadedChunksAsync(Consumer<Integer> completion) {
        long profileStart = Profiler.startChunkCache();
        if (!Bukkit.isPrimaryThread()) {
            IllegalStateException failure = new IllegalStateException(
                    "Loaded chunk snapshots must be captured on the Bukkit server thread");
            ErrorMonitor.report("ChunkCache", "startup-preload-thread", failure,
                    "thread=" + Thread.currentThread().getName());
            return;
        }

        List<StartupChunk> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (world == null) continue;
            try {
                int minY = getWorldMinHeight(world);
                int maxY = world.getMaxHeight();
                for (Chunk chunk : world.getLoadedChunks()) {
                    if (chunk != null) {
                        chunks.add(new StartupChunk(world, chunk, minY, maxY));
                    }
                }
            } catch (Throwable failure) {
                ErrorMonitor.report("ChunkCache", "startup-list-loaded-chunks", failure,
                        "world=" + world.getName());
            }
        }
        Profiler.stopChunkCache("preloadLoadedChunks", profileStart);
        new StartupPreload(chunks, completion).start();
    }

    private final class StartupPreload {
        private static final int MAX_SNAPSHOTS_PER_TICK = 2;
        private final List<StartupChunk> chunks;
        private final Consumer<Integer> completion;
        private int nextIndex;
        private int inFlight;
        private int cachedCount;
        private boolean finished;
        private TaskUtils.CancellableTask ticker;

        private StartupPreload(List<StartupChunk> chunks, Consumer<Integer> completion) {
            this.chunks = chunks;
            this.completion = completion;
        }

        private void start() {
            ticker = TaskUtils.taskTimer(this::captureBatch, 1L, 1L);
        }

        private void captureBatch() {
            if (!running || finished) {
                cancel();
                return;
            }

            int captured = 0;
            int workerLimit = Math.max(1, chunkExecutor.getMaximumPoolSize());
            while (captured < MAX_SNAPSHOTS_PER_TICK && inFlight < workerLimit
                    && nextIndex < chunks.size()) {
                StartupChunk source = chunks.get(nextIndex++);
                boolean reservedWorker = false;
                try {
                    if (!source.world.isChunkLoaded(source.chunk.getX(), source.chunk.getZ())) continue;
                    org.bukkit.ChunkSnapshot snapshot = source.chunk.getChunkSnapshot();
                    inFlight++;
                    reservedWorker = true;
                    chunkExecutor.execute(() -> parseStartupSnapshot(source, snapshot));
                    captured++;
                } catch (Throwable failure) {
                    if (reservedWorker) inFlight--;
                    ErrorMonitor.report("ChunkCache", "startup-snapshot", failure,
                            "world=" + source.worldName + ",chunkX=" + source.chunkX
                                    + ",chunkZ=" + source.chunkZ);
                }
            }

            if (nextIndex >= chunks.size() && inFlight == 0) finish();
        }

        private void parseStartupSnapshot(StartupChunk source, org.bukkit.ChunkSnapshot snapshot) {
            long workerStart = Profiler.startChunkCache();
            CachedChunk cached = null;
            Throwable failure = null;
            try {
                cached = buildStartupSnapshot(source, snapshot);
            } catch (Throwable parseFailure) {
                failure = parseFailure;
            } finally {
                Profiler.stopChunkCache("startupChunkWorker", workerStart);
            }

            final CachedChunk parsed = cached;
            final Throwable parseFailure = failure;
            TaskUtils.task(() -> {
                if (!running || finished) return;
                if (parseFailure == null && parsed != null) {
                    synchronized (cacheUpdateLock) {
                        putChunkInternal(source.worldName, source.chunkX, source.chunkZ, parsed);
                    }
                    cachedCount++;
                } else if (parseFailure != null) {
                    ErrorMonitor.report("ChunkCache", "startup-preload-chunk", parseFailure,
                            "world=" + source.worldName + ",chunkX=" + source.chunkX
                                    + ",chunkZ=" + source.chunkZ + ",minY=" + source.minY
                                    + ",maxY=" + source.maxY);
                }
                inFlight--;
                if (nextIndex >= chunks.size() && inFlight == 0) finish();
            });
        }

        private void finish() {
            if (finished) return;
            finished = true;
            cancel();
            initialized = cachedCount > 0;
            if (completion != null) completion.accept(cachedCount);
        }

        private void cancel() {
            if (ticker != null) {
                ticker.cancel();
                ticker = null;
            }
        }
    }

    private static CachedChunk buildStartupSnapshot(StartupChunk source,
                                                     org.bukkit.ChunkSnapshot snapshot) throws Exception {
        CachedChunk cached = new CachedChunk(source.chunkX, source.chunkZ);
        cached.setFullChunkData(true);
        SnapshotReader reader = new SnapshotReader(snapshot);
        for (int y = source.minY; y < source.maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Object rawType = reader.read(reader.blockType, x, y, z);
                    Object rawTypeId = reader.read(reader.blockTypeId, x, y, z);
                    Object rawData = reader.read(reader.blockData, x, y, z);
                    Material material = rawType instanceof Material
                            ? (Material) rawType
                            : rawTypeId instanceof Number
                                    ? materialFromLegacyTypeId(((Number) rawTypeId).intValue())
                                    : null;
                    String name = material == null ? null : material.name();
                    if (material == Material.AIR || "CAVE_AIR".equals(name) || "VOID_AIR".equals(name)) continue;

                    int legacyId = rawTypeId instanceof Number ? ((Number) rawTypeId).intValue() : -1;
                    int legacyData = rawData instanceof Number ? ((Number) rawData).intValue() & 0xF : 0;
                    Object blockData = rawData instanceof Number ? null : rawData;
                    int combinedBlockId = LEGACY_BLOCK_DATA_SUPPORTED && legacyId >= 0
                            ? (legacyId << 4) | legacyData : -1;
                    WrappedBlockState state = LEGACY_BLOCK_DATA_SUPPORTED && combinedBlockId >= 0
                            ? LEGACY_SNAPSHOT_STATE_CACHE.get(combinedBlockId) : null;
                    if (state == null) state = PEMaterials.fromBukkitBlockData(blockData);
                    if (state == null && material != null) {
                        state = LEGACY_BLOCK_DATA_SUPPORTED && combinedBlockId >= 0
                                ? legacySnapshotState(combinedBlockId, material, legacyData)
                                : PEMaterials.fromBukkitMaterialData(
                                        new org.bukkit.material.MaterialData(material, (byte) legacyData));
                    }
                    if (state == null && material != null) state = PEMaterials.fromBukkitMaterial(material);
                    if (state == null || state.getType() == null) {
                        if (legacyId == 0 || material == Material.AIR) continue;
                        throw new IllegalStateException("Could not map snapshot block at " + x + "," + y + "," + z
                                + " (type=" + rawType + ",typeId=" + rawTypeId + ")");
                    }
                    if (state.getType().isAir()) continue;

                    writePacketBlockState(cached, x, y, z, state, combinedBlockId);
                }
            }
        }
        cached.setCompleteStatefulCollisionData();
        return cached;
    }

    private static Material materialFromLegacyTypeId(int typeId) {
        Material cached = LEGACY_MATERIAL_CACHE.get(typeId);
        if (cached != null) return cached;
        try {
            Object material = MATERIAL_GET_BY_ID == null ? null : MATERIAL_GET_BY_ID.invoke(null, typeId);
            if (material instanceof Material) {
                Material mapped = (Material) material;
                Material previous = LEGACY_MATERIAL_CACHE.putIfAbsent(typeId, mapped);
                return previous == null ? mapped : previous;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static WrappedBlockState legacySnapshotState(int combinedBlockId, Material material, int data) {
        WrappedBlockState cached = LEGACY_SNAPSHOT_STATE_CACHE.get(combinedBlockId);
        if (cached != null) return cached;
        WrappedBlockState state = PEMaterials.fromBukkitMaterialData(
                new org.bukkit.material.MaterialData(material, (byte) data));
        if (state == null) return null;
        WrappedBlockState previous = LEGACY_SNAPSHOT_STATE_CACHE.putIfAbsent(combinedBlockId, state);
        return previous == null ? state : previous;
    }

    private static Method findLegacyMaterialMethod() {
        try {
            return Material.class.getMethod("getMaterial", int.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class StartupChunk {
        private final World world;
        private final Chunk chunk;
        private final String worldName;
        private final int chunkX;
        private final int chunkZ;
        private final int minY;
        private final int maxY;

        private StartupChunk(World world, Chunk chunk, int minY, int maxY) {
            this.world = world;
            this.chunk = chunk;
            this.worldName = world.getName();
            this.chunkX = chunk.getX();
            this.chunkZ = chunk.getZ();
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private static final class SnapshotReader {
        private final org.bukkit.ChunkSnapshot snapshot;
        private final Method blockType;
        private final Method blockTypeId;
        private final Method blockData;

        private SnapshotReader(org.bukkit.ChunkSnapshot snapshot) {
            this.snapshot = snapshot;
            Class<?> type;
            try {
                // Resolve against the public Bukkit interface so access also works
                // when a server's concrete snapshot implementation is non-public.
                type = Class.forName("org.bukkit.ChunkSnapshot");
            } catch (Throwable ignored) {
                type = snapshot.getClass();
            }
            this.blockType = findSnapshotMethod(type, "getBlockType");
            this.blockTypeId = findSnapshotMethod(type, "getBlockTypeId");
            this.blockData = findSnapshotMethod(type, "getBlockData");
        }

        private Object read(Method method, int x, int y, int z) throws Exception {
            if (method == null) return null;
            try {
                return method.invoke(snapshot, x, y, z);
            } catch (Throwable ignored) {
                // These compatibility accessors were removed or changed across
                // Bukkit versions. A failed legacy accessor must not prevent the
                // modern block type/data accessor from providing the state.
                return null;
            }
        }

        private static Method findSnapshotMethod(Class<?> type, String name) {
            try {
                Method method = type.getMethod(name, int.class, int.class, int.class);
                return method;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static int getWorldMinHeight(World world) {
        try {
            Object value = world.getClass().getMethod("getMinHeight").invoke(world);
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void putChunkInternal(String worldName, int chunkX, int chunkZ, CachedChunk chunk) {
        worldChunks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                .put(chunkKey(chunkX, chunkZ), chunk);
    }

    private void markChunkIncomplete(String worldName, int chunkX, int chunkZ) {
        synchronized (cacheUpdateLock) {
            markChunkIncompleteLocked(worldName, chunkX, chunkZ);
        }
    }

    private void markChunkIncompleteLocked(String worldName, int chunkX, int chunkZ) {
        CachedChunk chunk = getChunk(worldName, chunkX, chunkZ);
        if (chunk != null) {
            chunk.setCompleteStatefulCollisionData(false);
        }
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

        try {
            resolvePacketMaterial(state, combinedBlockId);
        } catch (Throwable failure) {
            synchronized (cacheUpdateLock) {
                if (isChunkQueued(worldName, cx, cz)) {
                    long blockKey = (((long) y) << 8) | ((long) (z & 15) << 4) | (x & 15);
                    pendingBlockUpdates
                            .computeIfAbsent(worldName, ignored -> new HashMap<>())
                            .computeIfAbsent(key, ignored -> new HashMap<>())
                            .put(blockKey, new PendingBlockState(state, combinedBlockId));
                }
                markChunkIncompleteLocked(worldName, cx, cz);
            }
            String context = "world=" + worldName + ",x=" + x + ",y=" + y + ",z=" + z
                    + ",chunkX=" + cx + ",chunkZ=" + cz
                    + ",state=" + state.getType().getName()
                    + ",combinedBlockId=" + combinedBlockId;
            ErrorMonitor.report("ChunkCache", "map-block-update", failure, context);
            return;
        }

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
        Material material = resolvePacketMaterial(state, combinedBlockId);
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

    private static Material resolvePacketMaterial(WrappedBlockState state, int combinedBlockId) {
        if (state == null || state.getType() == null) {
            throw new IllegalStateException("Packet contains a null block state/type");
        }

        StateType type = state.getType();
        Material material = PEMaterials.materialFromState(type);
        // PacketEvents block IDs are global state IDs on modern protocols. Only
        // interpret them as legacy (block ID << 4 | metadata) values on legacy
        // servers; otherwise unrelated states can be misread as legacy water.
        if (LEGACY_BLOCK_DATA_SUPPORTED
                && (material == null || material == Material.AIR)
                && combinedBlockId >= 0) {
            Material legacyMaterial = PEMaterials.materialFromLegacyCombinedId(combinedBlockId);
            if (legacyMaterial != null) material = legacyMaterial;
        }

        if (material == null || (material == Material.AIR && !type.isAir())) {
            Material fallback = PEMaterials.matchMaterialCompat(type.getName());
            if (fallback != null) material = fallback;
        }

        if (material == null || (material == Material.AIR && !type.isAir())) {
            throw new IllegalStateException("Unmapped non-air block state '" + type.getName() + "'");
        }
        return material;
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
        long profileStart = Profiler.startChunkCache();
        try {
        Map<Long, Map<Long, PendingBlockState>> worldUpdates = pendingBlockUpdates.get(worldName);
        long key = chunkKey(cx, cz);
        if (worldUpdates != null) {
            Map<Long, PendingBlockState> updates = worldUpdates.get(key);
            if (updates != null) {
                for (Map.Entry<Long, PendingBlockState> entry : updates.entrySet()) {
                    long blockKey = entry.getKey();
                    int x = (int) (blockKey & 15L);
                    int z = (int) ((blockKey >>> 4) & 15L);
                    int y = (int) (blockKey >> 8);
                    PendingBlockState pending = entry.getValue();
                    writePacketBlockState(chunk, x, y, z, pending.state, pending.combinedBlockId);
                }
                worldUpdates.remove(key);
                if (worldUpdates.isEmpty()) pendingBlockUpdates.remove(worldName);
            }
        }

        Map<Long, Map<Long, Integer>> worldLegacy = pendingLegacyBlockData.get(worldName);
        if (worldLegacy == null) return;
        Map<Long, Integer> legacy = worldLegacy.get(key);
        if (legacy == null) return;
        for (Map.Entry<Long, Integer> entry : legacy.entrySet()) {
            long blockKey = entry.getKey();
            int x = (int) (blockKey & 15L);
            int z = (int) ((blockKey >>> 4) & 15L);
            int y = (int) (blockKey >> 8);
            chunk.setLegacyBlockData(x, y, z, entry.getValue());
        }
        worldLegacy.remove(key);
        if (worldLegacy.isEmpty()) pendingLegacyBlockData.remove(worldName);
        } finally {
            Profiler.stopChunkCache("applyPendingUpdates", profileStart);
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
        long profileStart = Profiler.startChunkCache();
        try {
        if (worldName == null) return Material.AIR;
        int cx = x >> 4;
        int cz = z >> 4;
        CachedChunk chunk = getChunk(worldName, cx, cz);
        if (chunk != null && chunk.hasCompleteStatefulCollisionData()) {
            return chunk.get(x & 15, y, z & 15);
        }
        return null;
        } finally {
            Profiler.stopChunkCache("blockLookup", profileStart);
        }
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

        long enqueueStart = Profiler.startChunkCache();
        try {
            chunkExecutor.execute(() -> {
                long workerStart = Profiler.startChunkCache();
                boolean releasedQueued = false;
                try {
                    CachedChunk cached =
                            parsePacketColumnWithRetry(
                                    worldName,
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
                                CachedChunk partial = parsePacketColumnWithRetry(
                                        worldName, chunkX, chunkZ, update.minY, update.sections, update.fullChunk
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
                } catch (Throwable failure) {
                    markChunkIncomplete(worldName, chunkX, chunkZ);
                    String context = "world=" + worldName + ",chunkX=" + chunkX + ",chunkZ=" + chunkZ
                            + ",minY=" + minY + ",fullChunk=" + fullChunk
                            + ",worker=" + Thread.currentThread().getName();
                    ErrorMonitor.report("ChunkCache", "packet-chunk-worker", failure, context);
                } finally {
                    if (!releasedQueued) unmarkQueued(worldName, chunkX, chunkZ);
                    Profiler.stopChunkCache("packetChunkWorker", workerStart);
                }
            });
            Profiler.stopChunkCache("enqueuePacketChunk", enqueueStart);
        } catch (RejectedExecutionException ignored) {
            Profiler.stopChunkCache("enqueuePacketChunk", enqueueStart);
            unmarkQueued(
                    worldName,
                    chunkX,
                    chunkZ
            );
            ErrorMonitor.report("ChunkCache", "enqueue-packet-chunk", ignored,
                    "world=" + worldName + ",chunkX=" + chunkX + ",chunkZ=" + chunkZ
                            + ",queueSize=" + chunkExecutor.getQueue().size()
                            + ",shutdown=" + chunkExecutor.isShutdown());
        }
    }

    /**
     * Highly optimized, allocation-free parser for PacketEvents Column chunk packets.
     */
    public CachedChunk parsePacketColumn(int chunkX, int chunkZ, int minY, Column column) {
        return column == null ? null : parsePacketColumnWithRetry(
                null, chunkX, chunkZ, minY, column.getChunks(), column.isFullChunk()
        );
    }

    public CachedChunk parsePacketColumn(int chunkX, int chunkZ, int minY, BaseChunk[] sections) {
        return parsePacketColumnWithRetry(null, chunkX, chunkZ, minY, sections, true);
    }

    private CachedChunk parsePacketColumnWithRetry(String worldName, int chunkX, int chunkZ, int minY,
                                                   BaseChunk[] sections, boolean fullChunk) {
        if (sections == null) return null;

        long parseStart = Profiler.startChunkCache();
        Throwable lastFailure = null;
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                long attemptStart = Profiler.startChunkCache();
                try {
                    CachedChunk parsed = parsePacketColumnStrict(chunkX, chunkZ, minY, sections, fullChunk);
                    if (worldName != null) {
                        synchronized (cacheUpdateLock) {
                            applyPendingBlockUpdates(worldName, chunkX, chunkZ, parsed);
                        }
                    }
                    return parsed;
                } catch (Throwable failure) {
                    lastFailure = failure;
                } finally {
                    Profiler.stopChunkCache("parseAttempt", attemptStart);
                }
            }

            if (worldName != null) {
                markChunkIncomplete(worldName, chunkX, chunkZ);
            }
            String context = "world=" + (worldName == null ? "<direct-parse>" : worldName)
                    + ",chunkX=" + chunkX + ",chunkZ=" + chunkZ + ",minY=" + minY
                    + ",sections=" + sections.length + ",fullChunk=" + fullChunk;
            ErrorMonitor.report("ChunkCache", "parse-packet-column", lastFailure, context);
            return null;
        } finally {
            Profiler.stopChunkCache("parsePacketColumn", parseStart);
        }
    }

    private CachedChunk parsePacketColumnStrict(int chunkX, int chunkZ, int minY,
                                               BaseChunk[] sections, boolean fullChunk) {
        if (sections == null) return null;
        if (fullChunk && sections.length == 0) {
            throw new IllegalStateException("Full chunk packet contains no sections");
        }

        CachedChunk cached = new CachedChunk(chunkX, chunkZ);
        cached.setFullChunkData(fullChunk);
        int minSection = minY >> 4;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            BaseChunk section = sections[sectionIndex];
            int sectionY = minSection + sectionIndex;
            if (fullChunk || section != null) cached.markSectionPresent(sectionY);
            if (section == null || section.isEmpty()) continue;

            if (sectionY < -4 || sectionY > 23) {
                throw new IllegalStateException("Non-empty chunk section outside cache height: " + sectionY);
            }

            int baseY = sectionY << 4;

            for (int localX = 0; localX < 16; localX++) {
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        try {
                            WrappedBlockState state = section.get(localX, localY, localZ);
                            if (state == null || state.getType() == null) {
                                throw new IllegalStateException("Packet contains a null block state");
                            }
                            int combinedBlockId = -1;
                            if (LEGACY_BLOCK_DATA_SUPPORTED) {
                                try {
                                    combinedBlockId = section.getBlockId(localX, localY, localZ);
                                } catch (Throwable ignored) {
                                }
                            }
                            Material material = resolvePacketMaterial(state, combinedBlockId);
                            if (material != Material.AIR) {
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
                        } catch (Throwable failure) {
                            throw new IllegalStateException(
                                    "Failed decoding chunk block at " + (chunkX * 16 + localX) + ","
                                            + (baseY + localY) + "," + (chunkZ * 16 + localZ),
                                    failure
                            );
                        }
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
        // Non-empty sections outside this range cause packet parsing to fail instead
        // of being silently treated as air and marking the chunk complete.
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

        private void setCompleteStatefulCollisionData(boolean complete) {
            this.completeStatefulCollisionData = complete;
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
