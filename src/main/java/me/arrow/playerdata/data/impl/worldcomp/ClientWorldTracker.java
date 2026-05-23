package me.arrow.playerdata.data.impl.worldcomp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.MaterialType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientWorldTracker implements Data {

    private static final int DEFAULT_MIN_Y = 0;
    private static final int DEFAULT_MAX_Y_OLD = 255;
    private static final int DEFAULT_MAX_Y_NEW = 319;

    private static final int MAX_PENDING_AREA_TICKS = 20;
    private static final int MAX_RECENT_DESYNC_TICKS = 20 * 5;
    private static final int MAX_UNKNOWN_CHUNK_GRACE_TICKS = 20 * 5;
    private static final int MAX_RECENT_PHYSICS_UPDATE_TICKS = 20 * 4;

    private static final int AUTO_SYNC_COOLDOWN_TICKS = 10;
    private static final int CLIENT_MIRROR_REPAIR_EVERY_TICKS = 2;
    private static final int CLIENT_MIRROR_REPAIR_RADIUS_XZ = 3;
    private static final int CLIENT_MIRROR_REPAIR_DOWN = 2;
    private static final int CLIENT_MIRROR_REPAIR_UP = 3;
    private static final int ACTIVE_WORLD_REPAIR_TICKS = 20;
    private static final int MAX_REPAIR_UPDATES_PER_TICK = 96;

    private static volatile Method cachedGetBlockDataMethod;
    private static volatile Method cachedModernSendBlockChangeMethod;
    private static volatile Method cachedLegacySendBlockChangeMethod;
    private static volatile Method cachedLegacyGetDataMethod;
    private static volatile Method cachedLegacyGetStateMethod;
    private static volatile boolean modernLookupDone;
    private static volatile boolean legacyLookupDone;

    private final Profile profile;

    private final Map<Long, ClientChunk> chunks = new ConcurrentHashMap<>();
    private final Map<Long, PendingArea> pendingAreas = new ConcurrentHashMap<>();
    private final Map<Long, RecentDesync> recentDesyncs = new ConcurrentHashMap<>();
    private final Map<Long, Integer> unknownChunkTicks = new ConcurrentHashMap<>();
    private final Map<Long, RecentPhysicsUpdate> recentPhysicsUpdates = new ConcurrentHashMap<>();

    private volatile CollisionResult lastCollisionResult = new CollisionResult();

    private int tick;
    private int lastCollisionScanTick = -1;
    private int lastAutoSyncTick = 100;
    private int activeWorldRepairTicks;

    public ClientWorldTracker(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (isMovement(event)) {
            tick++;
            lastAutoSyncTick++;
            age();
            ensurePlayerChunksKnown(1);

            if (activeWorldRepairTicks > 0 || tick % CLIENT_MIRROR_REPAIR_EVERY_TICKS == 0) {
                repairClientMirrorAroundPlayer(
                        CLIENT_MIRROR_REPAIR_RADIUS_XZ,
                        CLIENT_MIRROR_REPAIR_DOWN,
                        CLIENT_MIRROR_REPAIR_UP,
                        MAX_REPAIR_UPDATES_PER_TICK
                );

                if (activeWorldRepairTicks > 0) {
                    activeWorldRepairTicks--;
                }
            }

            preCheckScan();
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleBlockChange(event);
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleMultiBlockChange(event);
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Server.UNLOAD_CHUNK)) {
            handleUnloadChunk(event);
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Server.CHUNK_DATA)) {
            handleChunkData(event);
        }
    }

    private void handleBlockChange(PacketSendEvent event) {
        WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);

        int x = wrapper.getBlockPosition().getX();
        int y = wrapper.getBlockPosition().getY();
        int z = wrapper.getBlockPosition().getZ();

        Material clientMaterial = null;

        try {
            clientMaterial = materialFromState(wrapper.getBlockState().getType());
        } catch (Throwable ignored) {
        }

        if (clientMaterial == null) {
            clientMaterial = getServerMaterial(x, y, z);
        }

        Material previousClientMaterial = getClientMaterial(x, y, z);
        boolean trackableUpdate = shouldTrackServerUpdate(previousClientMaterial, clientMaterial);

        if (trackableUpdate) {
            rememberPhysicsUpdateIfNeeded(
                    x,
                    y,
                    z,
                    previousClientMaterial,
                    clientMaterial,
                    "block-change"
            );

            markPendingArea(AffectedArea.single(x, y, z));
        } else {
            clearHistoryAt(x, y, z);
        }

        setClientMaterial(x, y, z, clientMaterial);

        if (trackableUpdate || isBlockNearPlayer(x, y, z, 8.0D, 5.0D)) {
            requestActiveWorldRepair();
        }
    }

    private void handleMultiBlockChange(PacketSendEvent event) {
        WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);

        if (wrapper.getBlocks() == null || wrapper.getBlocks().length == 0) {
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean trackableArea = false;

        for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();

            Material clientMaterial = null;

            try {
                StateType type = block.getBlockState(profile.getVersion()).getType();
                clientMaterial = materialFromState(type);
            } catch (Throwable ignored) {
            }

            if (clientMaterial == null) {
                clientMaterial = getServerMaterial(x, y, z);
            }

            Material previousClientMaterial = getClientMaterial(x, y, z);
            boolean trackableUpdate = shouldTrackServerUpdate(previousClientMaterial, clientMaterial);

            if (trackableUpdate) {
                trackableArea = true;

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);

                rememberPhysicsUpdateIfNeeded(
                        x,
                        y,
                        z,
                        previousClientMaterial,
                        clientMaterial,
                        "multi-block-change"
                );
            } else {
                clearHistoryAt(x, y, z);
            }

            setClientMaterial(x, y, z, clientMaterial);
        }

        if (trackableArea && minX != Integer.MAX_VALUE) {
            markPendingArea(new AffectedArea(minX, minY, minZ, maxX, maxY, maxZ));
        }

        if (trackableArea) {
            requestActiveWorldRepair();
        }
    }

    private void handleChunkData(PacketSendEvent event) {
        int[] chunk = extractChunkXZ(event);

        if (chunk == null) {
            return;
        }

        int chunkX = chunk[0];
        int chunkZ = chunk[1];

        World world = profile.getPlayer() != null ? profile.getPlayer().getWorld() : null;
        int minY = world != null ? getWorldMinY(world) : DEFAULT_MIN_Y;
        int maxY = world != null ? getWorldMaxY(world) : DEFAULT_MAX_Y_NEW;

        chunks.put(chunkKey(chunkX, chunkZ), new ClientChunk(chunkX, chunkZ, minY, maxY));
        markPendingArea(AffectedArea.chunk(chunkX, chunkZ, minY, maxY));
    }

    private void handleUnloadChunk(PacketSendEvent event) {
        int[] chunk = extractChunkXZ(event);

        if (chunk == null) {
            return;
        }

        chunks.remove(chunkKey(chunk[0], chunk[1]));
    }

    public CollisionResult preCheckScan() {
        if (lastCollisionScanTick == tick) {
            return lastCollisionResult;
        }

        ensurePlayerChunksKnown(1);

        CollisionResult result = scanPlayerCollision();
        applyRecentPhysicsHistory(result);

        if (result.shouldAutoSync() && lastAutoSyncTick > AUTO_SYNC_COOLDOWN_TICKS) {
            syncCollisionArea(result);
            lastAutoSyncTick = 0;
        }

        lastCollisionResult = result;
        lastCollisionScanTick = tick;

        return result;
    }

    public CollisionResult getCollisionResult() {
        return preCheckScan();
    }

    public boolean shouldExemptWorldSensitiveChecks() {
        return getCollisionResult().shouldExemptMovementChecks();
    }

    public boolean shouldExemptPhysicsChecks() {
        CollisionResult result = getCollisionResult();

        return result.shouldExemptMovementChecks()
                || result.nextToGhostWall
                || result.physicsMismatch
                || result.touchingPhysicsGhost
                || result.onGhostBlock
                || result.insideGhostBlock
                || result.underGhostBlock;
    }

    public CollisionResult scanPlayerCollision() {
        CollisionResult result = new CollisionResult();

        if (profile.getMovementData() == null || profile.getMovementData().getLocation() == null) {
            return result;
        }

        CustomLocation loc = profile.getMovementData().getLocation();

        double minX = loc.getX() - 0.31D;
        double maxX = loc.getX() + 0.31D;
        double minY = loc.getY() - 0.08D;
        double maxY = loc.getY() + 1.82D;
        double minZ = loc.getZ() - 0.31D;
        double maxZ = loc.getZ() + 0.31D;

        int blockMinX = floor(minX);
        int blockMaxX = floor(maxX);
        int blockMinY = floor(minY);
        int blockMaxY = floor(maxY);
        int blockMinZ = floor(minZ);
        int blockMaxZ = floor(maxZ);

        for (int x = blockMinX - 2; x <= blockMaxX + 2; x++) {
            for (int y = blockMinY - 2; y <= blockMaxY + 2; y++) {
                for (int z = blockMinZ - 2; z <= blockMaxZ + 2; z++) {
                    Material client = getClientMaterial(x, y, z);
                    Material server = getServerMaterial(x, y, z);

                    if (client == null) {
                        result.unknownClientChunk = true;
                        markUnknownChunk(x >> 4, z >> 4);
                        continue;
                    }

                    if (hasPendingNear(x, y, z)) {
                        result.pendingLagCompensated = true;
                        continue;
                    }

                    if (sameMaterialFamily(client, server)) {
                        clearHistoryAt(x, y, z);
                        continue;
                    }

                    boolean clientCollidable = isCollidableForAnticheat(client);
                    boolean serverCollidable = isCollidableForAnticheat(server);
                    boolean clientPhysics = isPhysicsSensitive(client);
                    boolean serverPhysics = isPhysicsSensitive(server);

                    boolean feet = isFeetOnBlock(loc, x, y, z);
                    boolean body = isBodyInsideBlock(loc, x, y, z);
                    boolean head = isHeadInBlock(loc, x, y, z);
                    boolean wall = isWallTouch(loc, x, y, z);
                    boolean physicsContact = isPhysicsInfluenceContact(loc, x, y, z, client, server);

                    if (!feet && !body && !head && !wall && !physicsContact && !isNearPlayerAabb(loc, x, y, z, 2.0D, 2.0D)) {
                        continue;
                    }

                    if (clientCollidable && !serverCollidable) {
                        result.clientOnlyBlock = true;
                        result.nearGhostBlock = true;
                        result.interactingGhostBlock = result.interactingGhostBlock || feet || body || head || wall || physicsContact;
                        result.desyncCount++;
                        result.closestGhost = new Vector(x, y, z);

                        if (feet) result.onGhostBlock = true;
                        if (head) result.underGhostBlock = true;
                        if (body) result.insideGhostBlock = true;
                        if (wall) result.nextToGhostWall = true;

                        if (clientPhysics && physicsContact) {
                            result.physicsMismatch = true;
                            result.touchingPhysicsGhost = true;
                            result.nextToGhostWall = result.nextToGhostWall || wall;
                        }

                        rememberDesync(x, y, z, client, server, "client-only-block");
                    } else if (!clientCollidable && serverCollidable) {
                        result.serverOnlyBlock = true;
                        result.nearGhostBlock = true;
                        result.interactingGhostBlock = result.interactingGhostBlock || feet || body || head || wall || physicsContact;
                        result.desyncCount++;
                        result.closestGhost = new Vector(x, y, z);

                        if (body) {
                            result.insideServerOnlyBlock = true;
                        }

                        if (feet) result.onGhostBlock = true;
                        if (head) result.underGhostBlock = true;
                        if (wall) result.nextToGhostWall = true;

                        if (serverPhysics && physicsContact) {
                            result.physicsMismatch = true;
                            result.touchingPhysicsGhost = true;
                            result.nextToGhostWall = result.nextToGhostWall || wall;
                        }

                        rememberDesync(x, y, z, client, server, "server-only-block");
                    } else if (clientPhysics || serverPhysics) {
                        if (!physicsContact) {
                            continue;
                        }

                        result.physicsMismatch = true;
                        result.touchingPhysicsGhost = true;
                        result.nearGhostBlock = true;
                        result.interactingGhostBlock = true;
                        result.desyncCount++;
                        result.closestGhost = new Vector(x, y, z);

                        if (feet) result.onGhostBlock = true;
                        if (body) result.insideGhostBlock = true;
                        if (head) result.underGhostBlock = true;
                        if (wall) result.nextToGhostWall = true;

                        rememberDesync(x, y, z, client, server, "physics-mismatch");
                    }
                }
            }
        }

        if (result.nearGhostBlock) {
            result.lastDesyncTick = tick;
        }

        return result;
    }

    public void syncCollisionArea(CollisionResult result) {
        if (result == null) {
            return;
        }

        Vector center = result.closestGhost;

        if (center == null && profile.getMovementData() != null && profile.getMovementData().getLocation() != null) {
            CustomLocation loc = profile.getMovementData().getLocation();
            center = new Vector(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        if (center == null) {
            return;
        }

        syncBlocksAround(center, 1, 1, 2);
        requestActiveWorldRepair();
    }

    public void syncBlocksAround(Vector center, int radiusXZ, int down, int up) {
        if (center == null || profile.getPlayer() == null || !profile.getPlayer().isOnline()) {
            return;
        }

        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();

        TaskUtils.task(() -> {
            Player player = profile.getPlayer();

            if (player == null || !player.isOnline() || player.getWorld() == null) {
                return;
            }

            World world = player.getWorld();

            for (int x = baseX - radiusXZ; x <= baseX + radiusXZ; x++) {
                for (int y = baseY - down; y <= baseY + up; y++) {
                    for (int z = baseZ - radiusXZ; z <= baseZ + radiusXZ; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        sendBlockChangeCompat(player, block);
                        setClientMaterial(x, y, z, block.getType());
                        clearHistoryAt(x, y, z);
                    }
                }
            }
        });
    }

    public boolean hasRecentDesyncNear(Location location, double horizontal, double vertical) {
        if (location == null) {
            return false;
        }

        for (RecentDesync desync : recentDesyncs.values()) {
            if (tick - desync.tick > MAX_RECENT_DESYNC_TICKS) {
                continue;
            }

            double dx = (desync.x + 0.5D) - location.getX();
            double dy = (desync.y + 0.5D) - location.getY();
            double dz = (desync.z + 0.5D) - location.getZ();

            if (Math.abs(dx) <= horizontal && Math.abs(dy) <= vertical && Math.abs(dz) <= horizontal) {
                return true;
            }
        }

        return false;
    }

    public void requestActiveWorldRepair() {
        this.activeWorldRepairTicks = Math.max(this.activeWorldRepairTicks, ACTIVE_WORLD_REPAIR_TICKS);
    }

    public void forceRepairAroundPlayer() {
        repairClientMirrorAroundPlayer(4, 2, 4, 160);
        requestActiveWorldRepair();
    }

    private void repairClientMirrorAroundPlayer(int radiusXZ, int down, int up, int maxUpdates) {
        if (profile.getPlayer() == null || !profile.getPlayer().isOnline()) {
            return;
        }

        if (profile.getMovementData() == null || profile.getMovementData().getLocation() == null) {
            return;
        }

        CustomLocation loc = profile.getMovementData().getLocation();
        World world = profile.getPlayer().getWorld();

        if (world == null) {
            return;
        }

        int baseX = floor(loc.getX());
        int baseY = floor(loc.getY());
        int baseZ = floor(loc.getZ());
        int sent = 0;

        for (int x = baseX - radiusXZ; x <= baseX + radiusXZ; x++) {
            for (int y = baseY - down; y <= baseY + up; y++) {
                for (int z = baseZ - radiusXZ; z <= baseZ + radiusXZ; z++) {
                    if (sent >= maxUpdates) {
                        return;
                    }

                    Material client = getClientMaterial(x, y, z);
                    Material server = getServerMaterial(x, y, z);

                    if (!shouldRepairClientMirror(client, server)) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    sendBlockChangeCompat(profile.getPlayer(), block);
                    setClientMaterial(x, y, z, block.getType());
                    clearHistoryAt(x, y, z);
                    sent++;
                }
            }
        }
    }

    private boolean shouldRepairClientMirror(Material client, Material server) {
        if (client == null || server == null) {
            return false;
        }

        if (sameMaterialFamily(client, server)) {
            return false;
        }

        if (isAirLike(client) != isAirLike(server)) {
            return true;
        }

        return isWorldUpdateRelevant(client)
                || isWorldUpdateRelevant(server)
                || isPhysicsSensitive(client)
                || isPhysicsSensitive(server);
    }

    private boolean isBlockNearPlayer(int x, int y, int z, double horizontal, double vertical) {
        if (profile.getMovementData() == null || profile.getMovementData().getLocation() == null) {
            return false;
        }

        CustomLocation loc = profile.getMovementData().getLocation();

        return Math.abs(loc.getX() - (x + 0.5D)) <= horizontal
                && Math.abs(loc.getZ() - (z + 0.5D)) <= horizontal
                && Math.abs(loc.getY() - (y + 0.5D)) <= vertical;
    }

    private void markPendingArea(AffectedArea area) {
        if (area == null) {
            return;
        }

        pendingAreas.put(area.key(), new PendingArea(tick, area));
    }

    public boolean hasPendingNear(Vector vector, int margin) {
        if (vector == null) {
            return false;
        }

        return hasPendingNear(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ(), margin);
    }

    public boolean hasPendingNear(int x, int y, int z, int margin) {
        for (PendingArea pending : pendingAreas.values()) {
            if (pending.affects(x, y, z, margin)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasPendingNear(int x, int y, int z) {
        return hasPendingNear(x, y, z, 2);
    }

    private void ensurePlayerChunksKnown(int radius) {
        if (profile.getMovementData() == null || profile.getMovementData().getLocation() == null) {
            return;
        }

        CustomLocation loc = profile.getMovementData().getLocation();
        World world = loc.getWorld();

        if (world == null) {
            return;
        }

        int baseChunkX = loc.getBlockX() >> 4;
        int baseChunkZ = loc.getBlockZ() >> 4;

        int minY = getWorldMinY(world);
        int maxY = getWorldMaxY(world);

        for (int cx = baseChunkX - radius; cx <= baseChunkX + radius; cx++) {
            for (int cz = baseChunkZ - radius; cz <= baseChunkZ + radius; cz++) {
                long key = chunkKey(cx, cz);
                int finalCx = cx;
                int finalCz = cz;
                chunks.computeIfAbsent(key, ignored -> new ClientChunk(finalCx, finalCz, minY, maxY));
            }
        }
    }

    private void setClientMaterial(int x, int y, int z, Material material) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        long key = chunkKey(chunkX, chunkZ);
        World world = profile.getPlayer() != null ? profile.getPlayer().getWorld() : null;
        int minY = world != null ? getWorldMinY(world) : DEFAULT_MIN_Y;
        int maxY = world != null ? getWorldMaxY(world) : DEFAULT_MAX_Y_NEW;

        ClientChunk chunk = chunks.computeIfAbsent(key, ignored -> new ClientChunk(chunkX, chunkZ, minY, maxY));
        chunk.set(x & 15, y, z & 15, material);
    }

    private Material getClientMaterial(int x, int y, int z) {
        ClientChunk chunk = chunks.get(chunkKey(x >> 4, z >> 4));

        if (chunk == null) {
            return null;
        }

        Material cached = chunk.get(x & 15, y, z & 15);

        if (cached != null) {
            return cached;
        }

        return getServerMaterial(x, y, z);
    }

    private Material getServerMaterial(int x, int y, int z) {
        try {
            return Arrow.getInstance().getNmsManager().getNmsInstance2().getType(
                    profile.getPlayer().getWorld(),
                    x,
                    y,
                    z
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void sendBlockChangeCompat(Player player, Block block) {
        if (player == null || block == null || !player.isOnline()) {
            return;
        }

        Location location = block.getLocation();

        if (tryModernSendBlockChange(player, block, location)) {
            return;
        }

        tryLegacySendBlockChange(player, block, location);
    }

    private boolean tryModernSendBlockChange(Player player, Block block, Location location) {
        try {
            Method getBlockData = cachedGetBlockDataMethod;

            if (getBlockData == null && !modernLookupDone) {
                getBlockData = block.getClass().getMethod("getBlockData");
                cachedGetBlockDataMethod = getBlockData;
            }

            if (getBlockData == null) {
                return false;
            }

            Object blockData = getBlockData.invoke(block);

            if (blockData == null) {
                return false;
            }

            Method sendMethod = cachedModernSendBlockChangeMethod;

            if (sendMethod == null && !modernLookupDone) {
                for (Method method : player.getClass().getMethods()) {
                    if (!method.getName().equals("sendBlockChange")) {
                        continue;
                    }

                    Class<?>[] params = method.getParameterTypes();

                    if (params.length != 2) {
                        continue;
                    }

                    if (!params[0].isAssignableFrom(Location.class)) {
                        continue;
                    }

                    if (!params[1].isAssignableFrom(blockData.getClass())) {
                        continue;
                    }

                    cachedModernSendBlockChangeMethod = method;
                    sendMethod = method;
                    break;
                }

                modernLookupDone = true;
            }

            if (sendMethod == null) {
                return false;
            }

            sendMethod.invoke(player, location, blockData);
            return true;
        } catch (Throwable ignored) {
            modernLookupDone = true;
            return false;
        }
    }

    private void tryLegacySendBlockChange(Player player, Block block, Location location) {
        try {
            Method sendMethod = cachedLegacySendBlockChangeMethod;

            if (sendMethod == null && !legacyLookupDone) {
                for (Method method : player.getClass().getMethods()) {
                    if (!method.getName().equals("sendBlockChange")) {
                        continue;
                    }

                    Class<?>[] params = method.getParameterTypes();

                    if (params.length != 3) {
                        continue;
                    }

                    if (!params[0].isAssignableFrom(Location.class)) {
                        continue;
                    }

                    if (!params[1].isAssignableFrom(Material.class)) {
                        continue;
                    }

                    if (params[2] != byte.class && params[2] != Byte.class) {
                        continue;
                    }

                    cachedLegacySendBlockChangeMethod = method;
                    sendMethod = method;
                    break;
                }

                legacyLookupDone = true;
            }

            if (sendMethod == null) {
                return;
            }

            sendMethod.invoke(player, location, block.getType(), getLegacyBlockData(block));
        } catch (Throwable ignored) {
        }
    }

    private byte getLegacyBlockData(Block block) {
        try {
            Method method = cachedLegacyGetDataMethod;

            if (method == null) {
                method = block.getClass().getMethod("getData");
                cachedLegacyGetDataMethod = method;
            }

            Object value = method.invoke(block);

            if (value instanceof Number) {
                return ((Number) value).byteValue();
            }
        } catch (Throwable ignored) {
        }

        try {
            Method stateMethod = cachedLegacyGetStateMethod;

            if (stateMethod == null) {
                stateMethod = block.getClass().getMethod("getState");
                cachedLegacyGetStateMethod = stateMethod;
            }

            Object state = stateMethod.invoke(block);

            if (state != null) {
                Object value = state.getClass().getMethod("getRawData").invoke(state);

                if (value instanceof Number) {
                    return ((Number) value).byteValue();
                }
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }


    private boolean shouldTrackServerUpdate(Material oldMaterial, Material newMaterial) {
        if (oldMaterial == null && newMaterial == null) {
            return false;
        }

        if (sameMaterialFamily(oldMaterial, newMaterial)) {
            return false;
        }

        boolean oldRelevant = isWorldUpdateRelevant(oldMaterial);
        boolean newRelevant = isWorldUpdateRelevant(newMaterial);

        return oldRelevant || newRelevant;
    }

    private boolean isWorldUpdateRelevant(Material material) {
        if (material == null || isAirLike(material)) {
            return false;
        }

        String name = material.name();

        if (name.contains("BUTTON")
                || name.contains("LEVER")
                || name.contains("PRESSURE_PLATE")
                || name.contains("REDSTONE")
                || name.contains("SIGN")
                || name.contains("BANNER")
                || name.contains("TORCH")
                || name.contains("TRIPWIRE")
                || name.contains("STRING")
                || name.contains("FLOWER")
                || name.contains("SAPLING")
                || name.contains("TULIP")
                || name.contains("DANDELION")
                || name.contains("POPPY")
                || name.contains("ORCHID")
                || name.contains("ALLIUM")
                || name.contains("DAISY")
                || name.contains("DEAD_BUSH")
                || name.contains("MUSHROOM")
                || name.contains("ROOTS")
                || name.contains("SPROUTS")) {
            return false;
        }

        return isCollidableForAnticheat(material) || isPhysicsSensitive(material);
    }

    private void clearHistoryAt(int x, int y, int z) {
        long key = blockKey(x, y, z);

        recentDesyncs.remove(key);
        recentPhysicsUpdates.remove(key);
        pendingAreas.entrySet().removeIf(entry -> entry.getValue().affects(x, y, z, 0));
    }

    private void age() {
        pendingAreas.entrySet().removeIf(entry -> tick - entry.getValue().tick > MAX_PENDING_AREA_TICKS);
        recentDesyncs.entrySet().removeIf(entry -> tick - entry.getValue().tick > MAX_RECENT_DESYNC_TICKS);
        unknownChunkTicks.entrySet().removeIf(entry -> tick - entry.getValue() > MAX_UNKNOWN_CHUNK_GRACE_TICKS);
        recentPhysicsUpdates.entrySet().removeIf(entry -> tick - entry.getValue().tick > MAX_RECENT_PHYSICS_UPDATE_TICKS);
    }

    private void rememberDesync(int x, int y, int z, Material client, Material server, String reason) {
        recentDesyncs.put(blockKey(x, y, z), new RecentDesync(x, y, z, client, server, reason, tick));
    }

    private void rememberPhysicsUpdateIfNeeded(int x, int y, int z, Material oldMaterial, Material newMaterial, String reason) {
        if (!isPhysicsSensitive(oldMaterial) && !isPhysicsSensitive(newMaterial)) {
            return;
        }

        Material physicsMaterial = isPhysicsSensitive(oldMaterial) ? oldMaterial : newMaterial;

        recentPhysicsUpdates.put(
                blockKey(x, y, z),
                new RecentPhysicsUpdate(x, y, z, physicsMaterial, oldMaterial, newMaterial, reason, tick)
        );
    }

    private void applyRecentPhysicsHistory(CollisionResult result) {
        if (result == null || profile.getMovementData() == null || profile.getMovementData().getLocation() == null) {
            return;
        }

        CustomLocation loc = profile.getMovementData().getLocation();

        for (RecentPhysicsUpdate update : recentPhysicsUpdates.values()) {
            if (tick - update.tick > MAX_RECENT_PHYSICS_UPDATE_TICKS) {
                continue;
            }

            if (!isNearPhysicsUpdate(loc, update)) {
                continue;
            }

            Material clientNow = getClientMaterial(update.x, update.y, update.z);
            Material serverNow = getServerMaterial(update.x, update.y, update.z);

            if (sameMaterialFamily(clientNow, serverNow)) {
                clearHistoryAt(update.x, update.y, update.z);
                continue;
            }

            boolean physicsContact = isPhysicsInfluenceContact(loc, update.x, update.y, update.z, clientNow, serverNow);

            if (!physicsContact && !isLikelyAffectedByRecentPhysics(update)) {
                continue;
            }

            result.nearGhostBlock = true;
            result.interactingGhostBlock = true;
            result.physicsMismatch = true;
            result.touchingPhysicsGhost = result.touchingPhysicsGhost || physicsContact;
            result.desyncCount++;
            result.closestGhost = new Vector(update.x, update.y, update.z);

            if (isFeetOnBlock(loc, update.x, update.y, update.z)) result.onGhostBlock = true;
            if (isBodyInsideBlock(loc, update.x, update.y, update.z)) result.insideGhostBlock = true;
            if (isHeadInBlock(loc, update.x, update.y, update.z)) result.underGhostBlock = true;
            if (isWallTouch(loc, update.x, update.y, update.z)) result.nextToGhostWall = true;

            rememberDesync(
                    update.x,
                    update.y,
                    update.z,
                    clientNow,
                    serverNow,
                    "recent-physics-history:" + update.reason
            );

            return;
        }
    }

    private boolean isNearPhysicsUpdate(CustomLocation loc, RecentPhysicsUpdate update) {
        double dx = (update.x + 0.5D) - loc.getX();
        double dy = (update.y + 0.5D) - loc.getY();
        double dz = (update.z + 0.5D) - loc.getZ();

        return Math.abs(dx) <= 1.35D
                && Math.abs(dy) <= 2.25D
                && Math.abs(dz) <= 1.35D;
    }

    private boolean isLikelyAffectedByRecentPhysics(RecentPhysicsUpdate update) {
        if (profile.getMovementData() == null || profile.getMovementData().getLocation() == null) {
            return false;
        }

        CustomLocation loc = profile.getMovementData().getLocation();

        if (isFeetOnBlock(loc, update.x, update.y, update.z)
                || isBodyInsideBlock(loc, update.x, update.y, update.z)
                || isHeadInBlock(loc, update.x, update.y, update.z)
                || isWallTouch(loc, update.x, update.y, update.z)
                || isPhysicsInfluenceContact(loc, update.x, update.y, update.z, update.oldMaterial, update.newMaterial)) {
            return true;
        }

        double deltaY = profile.getMovementData().getDeltaY();
        double deltaXZ = profile.getMovementData().getDeltaXZ();
        double lastDeltaXZ = profile.getMovementData().getLastDeltaXZ();

        boolean stickyVertical =
                !profile.getMovementData().isServerGround()
                        && profile.getMovementData().getClientAirTicks() > 1
                        && Math.abs(deltaY) < 0.015D;

        boolean stickyHorizontal =
                profile.getMovementData().isMoving()
                        && lastDeltaXZ > 0.08D
                        && deltaXZ < lastDeltaXZ * 0.55D;

        boolean verySlowNearPhysics =
                profile.getMovementData().isMoving()
                        && deltaXZ < 0.035D;

        return stickyVertical || stickyHorizontal || verySlowNearPhysics;
    }

    private void markUnknownChunk(int chunkX, int chunkZ) {
        unknownChunkTicks.put(chunkKey(chunkX, chunkZ), tick);
    }

    private int[] extractChunkXZ(PacketSendEvent event) {
        try {
            Object wrapper;

            if (event.getPacketType().equals(PacketType.Play.Server.CHUNK_DATA)) {
                Class<?> clazz = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData");
                wrapper = clazz.getConstructor(PacketSendEvent.class).newInstance(event);
            } else if (event.getPacketType().equals(PacketType.Play.Server.UNLOAD_CHUNK)) {
                Class<?> clazz = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk");
                wrapper = clazz.getConstructor(PacketSendEvent.class).newInstance(event);
            } else {
                return null;
            }

            Integer x = readInt(wrapper, "getChunkX", "getX");
            Integer z = readInt(wrapper, "getChunkZ", "getZ");

            if (x != null && z != null) {
                return new int[]{x, z};
            }

            Object column = invoke(wrapper, "getColumn");

            if (column != null) {
                x = readInt(column, "getX", "getChunkX");
                z = readInt(column, "getZ", "getChunkZ");

                if (x != null && z != null) {
                    return new int[]{x, z};
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private Object invoke(Object object, String methodName) {
        try {
            return object.getClass().getMethod(methodName).invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer readInt(Object object, String... methods) {
        if (object == null || methods == null) {
            return null;
        }

        for (String methodName : methods) {
            try {
                Object value = object.getClass().getMethod(methodName).invoke(object);

                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private Material materialFromState(StateType type) {
        if (type == null || type.getName() == null) {
            return null;
        }

        return matchMaterialCompat(type.getName());
    }

    private Material matchMaterialCompat(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        String name = raw
                .replace("minecraft:", "")
                .replace("Material.", "")
                .replace("LEGACY_", "")
                .replace(" ", "_")
                .replace("-", "_")
                .toUpperCase();

        Material direct = Material.matchMaterial(name);

        if (direct != null) {
            return direct;
        }

        if (name.equals("COBWEB")) {
            Material legacy = Material.matchMaterial("WEB");

            if (legacy != null) {
                return legacy;
            }
        }

        if (name.equals("WEB")) {
            Material modern = Material.matchMaterial("COBWEB");

            if (modern != null) {
                return modern;
            }
        }

        return null;
    }

    private boolean isCollidableForAnticheat(Material material) {
        if (material == null || isAirLike(material)) {
            return false;
        }

        String name = material.name();

        if (MaterialType.isMaterial(name, MaterialType.LIQUID)) return true;
        if (MaterialType.isMaterial(name, MaterialType.WEB)) return true;
        if (MaterialType.isMaterial(name, MaterialType.HONEY)) return true;
        if (MaterialType.isMaterial(name, MaterialType.SLIME)) return true;
        if (MaterialType.isMaterial(name, MaterialType.ICE)) return true;
        if (MaterialType.isMaterial(name, MaterialType.BUBBLE)) return true;
        if (MaterialType.isMaterial(name, MaterialType.CLIMBABLE)) return true;
        if (MaterialType.isMaterial(name, MaterialType.FENCE)) return true;
        if (MaterialType.isMaterial(name, MaterialType.WALL)) return true;
        if (MaterialType.isMaterial(name, MaterialType.HALF_BLOCK)) return true;
        if (MaterialType.isMaterial(name, MaterialType.STAIRS)) return true;
        if (MaterialType.isMaterial(name, MaterialType.SLAB)) return true;

        if (name.contains("RAIL")) return true;
        if (name.contains("PISTON")) return true;
        if (name.contains("POWDER_SNOW")) return true;
        if (name.contains("SOUL_SAND")) return true;

        try {
            return material.isSolid();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isPhysicsSensitive(Material material) {
        if (material == null || isAirLike(material)) {
            return false;
        }

        String name = material.name();

        return name.contains("WATER")
                || name.contains("LAVA")
                || name.contains("WEB")
                || name.contains("COBWEB")
                || name.contains("HONEY")
                || name.contains("SLIME")
                || name.contains("POWDER_SNOW")
                || name.contains("BUBBLE")
                || name.contains("ICE")
                || name.contains("SOUL_SAND")
                || name.contains("RAIL")
                || name.contains("PISTON")
                || name.contains("LADDER")
                || name.contains("VINE")
                || name.contains("SCAFFOLDING");
    }

    private boolean sameMaterialFamily(Material a, Material b) {
        if (a == b) {
            return true;
        }

        if (a == null || b == null) {
            return false;
        }

        if (isAirLike(a) && isAirLike(b)) {
            return true;
        }

        String aa = a.name();
        String bb = b.name();

        if ((aa.equals("WEB") || aa.equals("COBWEB")) && (bb.equals("WEB") || bb.equals("COBWEB"))) {
            return true;
        }

        return aa.equals(bb);
    }

    private boolean isAirLike(Material material) {
        if (material == null) {
            return true;
        }

        if (material == Material.AIR) {
            return true;
        }

        String name = material.name();

        return name.equals("CAVE_AIR") || name.equals("VOID_AIR");
    }

    private boolean isNearPlayerAabb(CustomLocation loc, int x, int y, int z, double horizontal, double vertical) {
        double playerMinX = loc.getX() - 0.3001D;
        double playerMaxX = loc.getX() + 0.3001D;
        double playerMinY = loc.getY();
        double playerMaxY = loc.getY() + 1.8D;
        double playerMinZ = loc.getZ() - 0.3001D;
        double playerMaxZ = loc.getZ() + 0.3001D;

        double blockMaxX = x + 1.0D;
        double blockMaxY = y + 1.0D;
        double blockMaxZ = z + 1.0D;

        return playerMaxX >= (double) x - horizontal
                && playerMinX <= blockMaxX + horizontal
                && playerMaxY >= (double) y - vertical
                && playerMinY <= blockMaxY + vertical
                && playerMaxZ >= (double) z - horizontal
                && playerMinZ <= blockMaxZ + horizontal;
    }

    private boolean isPhysicsInfluenceContact(CustomLocation loc, int x, int y, int z, Material client, Material server) {
        Material physics = isPhysicsSensitive(client) ? client : server;

        if (physics == null || isAirLike(physics)) {
            return false;
        }

        String name = physics.name();

        if (name.contains("WATER")
                || name.contains("LAVA")
                || name.contains("WEB")
                || name.contains("COBWEB")
                || name.contains("POWDER_SNOW")
                || name.contains("BUBBLE")) {
            return intersectsPlayerBox(loc, x, y, z, -0.04D);
        }

        if (name.contains("HONEY")) {
            return isWallTouchExpanded(loc, x, y, z, 0.16D)
                    || isBodyInsideBlock(loc, x, y, z)
                    || isFeetOnBlock(loc, x, y, z);
        }

        if (name.contains("LADDER")
                || name.contains("VINE")
                || name.contains("SCAFFOLDING")) {
            return isWallTouchExpanded(loc, x, y, z, 0.14D)
                    || isBodyInsideBlock(loc, x, y, z);
        }

        if (name.contains("SLIME")
                || name.contains("SOUL_SAND")
                || name.contains("ICE")
                || name.contains("RAIL")) {
            return isFeetOnBlock(loc, x, y, z)
                    || isBodyInsideBlock(loc, x, y, z);
        }

        return isFeetOnBlock(loc, x, y, z)
                || isBodyInsideBlock(loc, x, y, z)
                || isHeadInBlock(loc, x, y, z)
                || isWallTouch(loc, x, y, z);
    }

    private boolean isWallTouchExpanded(CustomLocation loc, int x, int y, int z, double expansion) {
        double playerMinY = loc.getY() + 0.001D;
        double playerMaxY = loc.getY() + 1.799D;

        if (playerMaxY <= y + 0.001D || playerMinY >= y + 1.0D - 0.001D) {
            return false;
        }

        double playerMinX = loc.getX() - 0.3001D;
        double playerMaxX = loc.getX() + 0.3001D;
        double playerMinZ = loc.getZ() - 0.3001D;
        double playerMaxZ = loc.getZ() + 0.3001D;

        double blockMaxX = x + 1.0D;
        double blockMaxZ = z + 1.0D;

        double zOverlap = Math.min(playerMaxZ, blockMaxZ) - Math.max(playerMinZ, z);
        double xOverlap = Math.min(playerMaxX, blockMaxX) - Math.max(playerMinX, x);

        boolean touchingXWall = zOverlap > -0.03D
                && (Math.abs(playerMaxX - (double) x) <= expansion || Math.abs(playerMinX - blockMaxX) <= expansion);

        boolean touchingZWall = xOverlap > -0.03D
                && (Math.abs(playerMaxZ - (double) z) <= expansion || Math.abs(playerMinZ - blockMaxZ) <= expansion);

        return touchingXWall || touchingZWall;
    }

    private boolean isFeetOnBlock(CustomLocation loc, int x, int y, int z) {
        if (!overlapsPlayerXZ(loc, x, z, 0.001D)) {
            return false;
        }

        double feetY = loc.getY();
        double blockTop = y + 1.0D;

        return feetY >= blockTop - 0.075D
                && feetY <= blockTop + 0.075D;
    }

    private boolean isBodyInsideBlock(CustomLocation loc, int x, int y, int z) {
        return intersectsPlayerBox(loc, x, y, z, 0.001D);
    }

    private boolean isHeadInBlock(CustomLocation loc, int x, int y, int z) {
        if (!overlapsPlayerXZ(loc, x, z, 0.001D)) {
            return false;
        }

        double headY = loc.getY() + 1.8D;
        double blockTop = y + 1.0D;

        return headY > (double) y + 0.001D
                && headY < blockTop - 0.001D;
    }

    private boolean isWallTouch(CustomLocation loc, int x, int y, int z) {
        return isWallTouchExpanded(loc, x, y, z, 0.04D);
    }

    private boolean overlapsPlayerXZ(CustomLocation loc, int x, int z, double epsilon) {
        double playerMinX = loc.getX() - 0.3001D;
        double playerMaxX = loc.getX() + 0.3001D;
        double playerMinZ = loc.getZ() - 0.3001D;
        double playerMaxZ = loc.getZ() + 0.3001D;

        return playerMaxX > x + epsilon
                && playerMinX < x + 1.0D - epsilon
                && playerMaxZ > z + epsilon
                && playerMinZ < z + 1.0D - epsilon;
    }

    private boolean intersectsPlayerBox(CustomLocation loc, int x, int y, int z, double epsilon) {
        double playerMinX = loc.getX() - 0.3001D;
        double playerMaxX = loc.getX() + 0.3001D;
        double playerMinY = loc.getY() + 0.001D;
        double playerMaxY = loc.getY() + 1.799D;
        double playerMinZ = loc.getZ() - 0.3001D;
        double playerMaxZ = loc.getZ() + 0.3001D;

        return playerMaxX > x + epsilon
                && playerMinX < x + 1.0D - epsilon
                && playerMaxY > y + epsilon
                && playerMinY < y + 1.0D - epsilon
                && playerMaxZ > z + epsilon
                && playerMinZ < z + 1.0D - epsilon;
    }

    private int getWorldMinY(World world) {
        try {
            return (int) world.getClass().getMethod("getMinHeight").invoke(world);
        } catch (Throwable ignored) {
            return DEFAULT_MIN_Y;
        }
    }

    private int getWorldMaxY(World world) {
        try {
            int maxHeight = (int) world.getClass().getMethod("getMaxHeight").invoke(world);
            return maxHeight - 1;
        } catch (Throwable ignored) {
            return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_17)
                    ? DEFAULT_MAX_Y_NEW
                    : DEFAULT_MAX_Y_OLD;
        }
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private long blockKey(int x, int y, int z) {
        long key = 1469598103934665603L;
        key = (key ^ x) * 1099511628211L;
        key = (key ^ y) * 1099511628211L;
        key = (key ^ z) * 1099511628211L;
        return key;
    }

    private int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }



    public static final class CollisionResult {
        public boolean nearGhostBlock;
        public boolean onGhostBlock;
        public boolean insideGhostBlock;
        public boolean underGhostBlock;
        public boolean nextToGhostWall;
        public boolean interactingGhostBlock;

        public boolean clientOnlyBlock;
        public boolean serverOnlyBlock;
        public boolean insideServerOnlyBlock;
        public boolean physicsMismatch;
        public boolean touchingPhysicsGhost;
        public boolean pendingLagCompensated;
        public boolean unknownClientChunk;

        public int desyncCount;
        public int lastDesyncTick;
        public Vector closestGhost;

        public boolean shouldExemptMovementChecks() {
            return nearGhostBlock
                    || interactingGhostBlock
                    || clientOnlyBlock
                    || serverOnlyBlock
                    || physicsMismatch
                    || touchingPhysicsGhost
                    || pendingLagCompensated
                    || unknownClientChunk;
        }

        public boolean shouldAutoSync() {
            return clientOnlyBlock
                    || serverOnlyBlock
                    || physicsMismatch
                    || touchingPhysicsGhost
                    || onGhostBlock
                    || insideGhostBlock
                    || underGhostBlock
                    || nextToGhostWall;
        }

        public boolean shouldSetback() {
            return shouldHardSetback();
        }

        public boolean shouldHardSetback() {
            if (pendingLagCompensated || unknownClientChunk) {
                return false;
            }

            return serverOnlyBlock && insideServerOnlyBlock;
        }

        public boolean shouldPhaseExempt() {
            return pendingLagCompensated
                    || unknownClientChunk
                    || nearGhostBlock
                    || interactingGhostBlock
                    || clientOnlyBlock
                    || serverOnlyBlock
                    || physicsMismatch
                    || touchingPhysicsGhost
                    || nextToGhostWall;
        }
    }

    private static final class PendingArea {
        private final int tick;
        private final AffectedArea area;

        private PendingArea(int tick, AffectedArea area) {
            this.tick = tick;
            this.area = area;
        }

        private boolean affects(int x, int y, int z, int margin) {
            return area != null && area.contains(x, y, z, margin);
        }
    }

    private static final class AffectedArea {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;

        private AffectedArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
        }

        private static AffectedArea single(int x, int y, int z) {
            return new AffectedArea(x, y, z, x, y, z);
        }

        private static AffectedArea chunk(int chunkX, int chunkZ, int minY, int maxY) {
            return new AffectedArea(
                    chunkX << 4,
                    minY,
                    chunkZ << 4,
                    (chunkX << 4) + 15,
                    maxY,
                    (chunkZ << 4) + 15
            );
        }

        private long key() {
            long key = 1469598103934665603L;
            key = (key ^ minX) * 1099511628211L;
            key = (key ^ minY) * 1099511628211L;
            key = (key ^ minZ) * 1099511628211L;
            key = (key ^ maxX) * 1099511628211L;
            key = (key ^ maxY) * 1099511628211L;
            key = (key ^ maxZ) * 1099511628211L;
            return key;
        }

        private boolean contains(int x, int y, int z, int margin) {
            return x >= minX - margin
                    && x <= maxX + margin
                    && y >= minY - margin
                    && y <= maxY + margin
                    && z >= minZ - margin
                    && z <= maxZ + margin;
        }
    }

    private static final class RecentDesync {
        private final int x;
        private final int y;
        private final int z;
        private final Material clientMaterial;
        private final Material serverMaterial;
        private final String reason;
        private final int tick;

        private RecentDesync(int x, int y, int z, Material clientMaterial, Material serverMaterial, String reason, int tick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.clientMaterial = clientMaterial;
            this.serverMaterial = serverMaterial;
            this.reason = reason;
            this.tick = tick;
        }
    }

    private static final class RecentPhysicsUpdate {
        private final int x;
        private final int y;
        private final int z;
        private final Material physicsMaterial;
        private final Material oldMaterial;
        private final Material newMaterial;
        private final String reason;
        private final int tick;

        private RecentPhysicsUpdate(int x, int y, int z, Material physicsMaterial, Material oldMaterial, Material newMaterial, String reason, int tick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.physicsMaterial = physicsMaterial;
            this.oldMaterial = oldMaterial;
            this.newMaterial = newMaterial;
            this.reason = reason;
            this.tick = tick;
        }
    }

    private static final class ClientChunk {
        private final int chunkX;
        private final int chunkZ;
        private final int minY;
        private final int maxY;
        private final Map<Integer, Character> overrides = new ConcurrentHashMap<>();

        private ClientChunk(int chunkX, int chunkZ, int minY, int maxY) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minY = minY;
            this.maxY = maxY;
        }

        private Material get(int x, int y, int z) {
            int index = index(x, y, z);

            if (index < 0) {
                return null;
            }

            Character value = overrides.get(index);

            if (value == null) {
                return null;
            }

            return decode(value);
        }

        private void set(int x, int y, int z, Material material) {
            int index = index(x, y, z);

            if (index < 0) {
                return;
            }

            overrides.put(index, encode(material));
        }

        private int index(int x, int y, int z) {
            if (x < 0 || x > 15 || z < 0 || z > 15 || y < minY || y > maxY) {
                return -1;
            }

            int localY = y - minY;
            return (localY << 8) | (z << 4) | x;
        }

        private static char encode(Material material) {
            if (material == null) {
                material = Material.AIR;
            }

            return (char) material.ordinal();
        }

        private static Material decode(char value) {
            Material[] values = Material.values();

            if ((int) value < 0 || (int) value >= values.length) {
                return Material.AIR;
            }

            return values[value];
        }
    }
}