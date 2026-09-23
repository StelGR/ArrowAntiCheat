package me.arrow.playerdata.data.impl.worldcomp;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks client-only block deltas; ChunkCache owns authoritative world chunks. */
public class ClientWorldTracker implements Data {
    private static final int PENDING_UPDATE_TICKS = 4;
    private static final int AUTO_SYNC_COOLDOWN_TICKS = 10;
    private static final int MAX_CLIENT_OVERRIDES = 2048;

    private final Profile profile;
    private final Map<Long, ClientBlock> clientOverrides = new ConcurrentHashMap<>();
    private final Map<Long, PendingArea> pendingAreas = new ConcurrentHashMap<>();
    private volatile CollisionResult lastCollisionResult = new CollisionResult();
    private int tick, lastCollisionScanTick = -1, lastAutoSyncTick = AUTO_SYNC_COOLDOWN_TICKS + 1;

    public ClientWorldTracker(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;
        tick++;
        lastAutoSyncTick++;
        pendingAreas.entrySet().removeIf(entry -> entry.getValue().expiresAt < tick);
        preCheckScan();
        if (Config.Setting.GHOST_BLOCK_FIX.getBoolean() && tick % 2 == 0) syncPlayerArea();
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleBlockChange(new WrapperPlayServerBlockChange(event));
        } else if (event.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleMultiBlockChange(new WrapperPlayServerMultiBlockChange(event));
        } else if (event.getPacketType().equals(PacketType.Play.Server.CHUNK_DATA)) {
            clearOverridesForChunk(event);
        }
    }

    private void handleBlockChange(WrapperPlayServerBlockChange change) {
        int x = change.getBlockPosition().getX(), y = change.getBlockPosition().getY(), z = change.getBlockPosition().getZ();
        try {
            updateClientBlock(x, y, z, material(change.getBlockState().getType()));
        } catch (Throwable ignored) {
        }
    }

    private void handleMultiBlockChange(WrapperPlayServerMultiBlockChange change) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (WrapperPlayServerMultiBlockChange.EncodedBlock block : change.getBlocks()) {
            int x = block.getX(), y = block.getY(), z = block.getZ();
            Material material = null;
            try {
                material = material(block.getBlockState(profile.getVersion()).getType());
            } catch (Throwable ignored) {
            }
            if (material == null) continue;
            updateClientBlock(x, y, z, material);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        if (minX != Integer.MAX_VALUE) markPending(new PendingArea(minX, minY, minZ, maxX, maxY, maxZ, tick + PENDING_UPDATE_TICKS));
    }

    private void updateClientBlock(int x, int y, int z, Material material) {
        if (material == null) return;
        if (clientOverrides.size() >= MAX_CLIENT_OVERRIDES) clientOverrides.clear();
        clientOverrides.put(blockKey(x, y, z), new ClientBlock(x, y, z, material));
        markPending(new PendingArea(x, y, z, x, y, z, tick + PENDING_UPDATE_TICKS));
    }

    private void clearOverridesForChunk(PacketSendEvent event) {
        try {
            WrapperPlayServerChunkData chunk = new WrapperPlayServerChunkData(event);
            int chunkX = chunk.getColumn().getX(), chunkZ = chunk.getColumn().getZ();
            clientOverrides.entrySet().removeIf(entry -> entry.getValue().inChunk(chunkX, chunkZ));
        } catch (Throwable ignored) {
        }
    }

    public CollisionResult preCheckScan() {
        if (lastCollisionScanTick == tick) return lastCollisionResult;
        CollisionResult result = scanPlayerCollision();
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

    public CollisionResult scanPlayerCollision() {
        CollisionResult result = new CollisionResult();
        CustomLocation location = location();
        if (location == null) return result;
        int minX = floor(location.getX() - .31D) - 2, maxX = floor(location.getX() + .31D) + 2;
        int minY = floor(location.getY() - .08D) - 2, maxY = floor(location.getY() + 1.82D) + 2;
        int minZ = floor(location.getZ() - .31D) - 2, maxZ = floor(location.getZ() + .31D) + 2;
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            Material client = getClientMaterial(x, y, z), server = getServerMaterial(x, y, z);
            if (client == null || server == null) {
                result.unknownClientChunk = true;
                continue;
            }
            if (hasPendingNear(x, y, z, 2)) {
                result.pendingLagCompensated = true;
                continue;
            }
            if (sameFamily(client, server)) continue;
            boolean feet = feetOn(location, x, y, z), body = intersects(location, x, y, z, .001D);
            boolean head = headIn(location, x, y, z), wall = wallTouch(location, x, y, z, .04D);
            boolean physicsContact = physicsContact(location, x, y, z, client, server);
            if (!feet && !body && !head && !wall && !physicsContact && !near(location, x, y, z, 2D, 2D)) continue;
            applyMismatch(result, x, y, z, client, server, feet, body, head, wall, physicsContact);
        }
        if (result.nearGhostBlock) result.lastDesyncTick = tick;
        return result;
    }

    private void applyMismatch(CollisionResult result, int x, int y, int z, Material client, Material server,
                               boolean feet, boolean body, boolean head, boolean wall, boolean physicsContact) {
        boolean clientCollision = hasCollision(client), serverCollision = hasCollision(server), physics = isPhysics(client) || isPhysics(server);
        if (clientCollision == serverCollision && !physics) return;
        result.nearGhostBlock = true;
        result.interactingGhostBlock |= feet || body || head || wall || physicsContact;
        result.desyncCount++;
        result.closestGhost = new Vector(x, y, z);
        result.onGhostBlock |= feet;
        result.insideGhostBlock |= body;
        result.underGhostBlock |= head;
        result.nextToGhostWall |= wall;
        if (clientCollision && !serverCollision) result.clientOnlyBlock = true;
        if (serverCollision && !clientCollision) {
            result.serverOnlyBlock = true;
            result.insideServerOnlyBlock |= body;
        }
        if (physics) {
            result.physicsMismatch = true;
            result.touchingPhysicsGhost |= physicsContact;
        }
    }

    /** BlockProcessor is the only owner of client block-resend mechanics. */
    public void syncCollisionArea(CollisionResult result) {
        Vector center = result == null ? null : result.closestGhost;
        CustomLocation location = location();
        if (center == null && location != null) center = new Vector(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (center == null || profile.getBlockProcessor() == null) return;
        int x = center.getBlockX(), y = center.getBlockY(), z = center.getBlockZ();
        profile.getBlockProcessor().syncRealBlocksInBoxAsync(x - 1, y - 1, z - 1, x + 1, y + 2, z + 1, 36, true);
    }

    public void syncBlocksAround(Vector center, int radiusXZ, int down, int up) {
        if (center == null || profile.getBlockProcessor() == null) return;
        int x = center.getBlockX(), y = center.getBlockY(), z = center.getBlockZ();
        int volume = (radiusXZ * 2 + 1) * (down + up + 1) * (radiusXZ * 2 + 1);
        profile.getBlockProcessor().syncRealBlocksInBoxAsync(x - radiusXZ, y - down, z - radiusXZ,
                x + radiusXZ, y + up, z + radiusXZ, volume, true);
    }

    private void syncPlayerArea() {
        CustomLocation location = location();
        if (location != null) syncBlocksAround(new Vector(location.getBlockX(), location.getBlockY(), location.getBlockZ()), 2, 2, 4);
    }

    public boolean hasPendingNear(int x, int y, int z, int margin) {
        for (PendingArea pending : pendingAreas.values()) if (pending.contains(x, y, z, margin)) return true;
        return false;
    }

    private void markPending(PendingArea area) {
        pendingAreas.put(area.key(), area);
    }

    private Material getClientMaterial(int x, int y, int z) {
        ClientBlock override = clientOverrides.get(blockKey(x, y, z));
        return override == null ? getServerMaterial(x, y, z) : override.material;
    }

    private Material getServerMaterial(int x, int y, int z) {
        Player player = profile.getPlayer();
        if (player == null || !player.isOnline()) return null;
        World world = player.getWorld();
        return ChunkCache.get().getBlock(world, x, y, z);
    }

    private Material material(StateType state) {
        return state == null ? null : PEMaterials.materialFromState(state);
    }

    private boolean hasCollision(Material material) {
        if (isAir(material)) return false;
        try {
            return isPhysics(material) || PEMaterials.hasCollision(material, profile.getVersion());
        } catch (Throwable ignored) {
            return isPhysics(material) || material.isSolid();
        }
    }

    private boolean isPhysics(Material material) {
        if (isAir(material)) return false;
        return containsAny(material.name(), "WATER", "LAVA", "WEB", "HONEY", "SLIME", "POWDER_SNOW", "BUBBLE", "ICE",
                "SOUL_SAND", "RAIL", "PISTON", "LADDER", "VINE", "SCAFFOLDING");
    }

    private boolean sameFamily(Material first, Material second) {
        if (first == second || (isAir(first) && isAir(second))) return true;
        return first != null && second != null && family(first.name()).equals(family(second.name()));
    }

    private String family(String name) {
        if (containsAny(name, "CAVE_AIR", "VOID_AIR")) return "AIR";
        if (name.equals("WEB") || name.equals("COBWEB")) return "WEB";
        if (name.equals("WATER") || name.equals("STATIONARY_WATER")) return "WATER";
        if (name.equals("LAVA") || name.equals("STATIONARY_LAVA")) return "LAVA";
        return name.equals("GRASS") || name.equals("GRASS_BLOCK") ? "GRASS_BLOCK" : name;
    }

    private boolean physicsContact(CustomLocation loc, int x, int y, int z, Material client, Material server) {
        String name = isPhysics(client) ? client.name() : isPhysics(server) ? server.name() : "";
        if (name.isEmpty()) return false;
        if (containsAny(name, "WATER", "LAVA", "WEB", "POWDER_SNOW", "BUBBLE")) return intersects(loc, x, y, z, -.04D);
        if (containsAny(name, "HONEY", "LADDER", "VINE", "SCAFFOLDING")) return wallTouch(loc, x, y, z, .14D) || intersects(loc, x, y, z, .001D);
        return feetOn(loc, x, y, z) || intersects(loc, x, y, z, .001D);
    }

    private boolean feetOn(CustomLocation loc, int x, int y, int z) {
        return overlapsXZ(loc, x, z, .001D) && Math.abs(loc.getY() - (y + 1D)) <= .075D;
    }

    private boolean headIn(CustomLocation loc, int x, int y, int z) {
        double head = loc.getY() + 1.8D;
        return overlapsXZ(loc, x, z, .001D) && head > y + .001D && head < y + .999D;
    }

    private boolean overlapsXZ(CustomLocation loc, int x, int z, double epsilon) {
        return loc.getX() + .3001D > x + epsilon && loc.getX() - .3001D < x + 1D - epsilon
                && loc.getZ() + .3001D > z + epsilon && loc.getZ() - .3001D < z + 1D - epsilon;
    }

    private boolean intersects(CustomLocation loc, int x, int y, int z, double epsilon) {
        return overlapsXZ(loc, x, z, epsilon) && loc.getY() + 1.799D > y + epsilon && loc.getY() + .001D < y + 1D - epsilon;
    }

    private boolean wallTouch(CustomLocation loc, int x, int y, int z, double expansion) {
        if (loc.getY() + 1.799D <= y || loc.getY() + .001D >= y + 1D) return false;
        double minX = loc.getX() - .3001D, maxX = loc.getX() + .3001D, minZ = loc.getZ() - .3001D, maxZ = loc.getZ() + .3001D;
        return (maxZ > z - .03D && minZ < z + 1.03D && (Math.abs(maxX - x) <= expansion || Math.abs(minX - (x + 1D)) <= expansion))
                || (maxX > x - .03D && minX < x + 1.03D && (Math.abs(maxZ - z) <= expansion || Math.abs(minZ - (z + 1D)) <= expansion));
    }

    private boolean near(CustomLocation loc, int x, int y, int z, double horizontal, double vertical) {
        return Math.abs(loc.getX() - (x + .5D)) <= horizontal && Math.abs(loc.getZ() - (z + .5D)) <= horizontal
                && Math.abs(loc.getY() - (y + .5D)) <= vertical;
    }

    private CustomLocation location() {
        return profile.getMovementData() == null ? null : profile.getMovementData().getLocation();
    }


    private boolean isAir(Material material) {
        return material == null || material == Material.AIR || containsAny(material.name(), "CAVE_AIR", "VOID_AIR");
    }

    private boolean containsAny(String value, String... parts) {
        for (String part : parts) if (value.contains(part)) return true;
        return false;
    }

    private long blockKey(int x, int y, int z) {
        long key = 1469598103934665603L;
        key = (key ^ x) * 1099511628211L;
        key = (key ^ y) * 1099511628211L;
        return (key ^ z) * 1099511628211L;
    }

    private int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    public static final class CollisionResult {
        public boolean nearGhostBlock, onGhostBlock, insideGhostBlock, underGhostBlock, nextToGhostWall, interactingGhostBlock;
        public boolean clientOnlyBlock, serverOnlyBlock, insideServerOnlyBlock, physicsMismatch, touchingPhysicsGhost;
        public boolean pendingLagCompensated, unknownClientChunk;
        public int desyncCount, lastDesyncTick;
        public Vector closestGhost;

        public boolean shouldExemptMovementChecks() {
            return false;
        }

        public boolean shouldAutoSync() {
            return clientOnlyBlock || serverOnlyBlock || physicsMismatch || touchingPhysicsGhost || onGhostBlock
                    || insideGhostBlock || underGhostBlock || nextToGhostWall;
        }

        public boolean shouldSetback() {
            return shouldHardSetback();
        }

        public boolean shouldHardSetback() {
            return !pendingLagCompensated && !unknownClientChunk && serverOnlyBlock && insideServerOnlyBlock;
        }

        public boolean shouldPhaseExempt() {
            return pendingLagCompensated || unknownClientChunk || nearGhostBlock || interactingGhostBlock || clientOnlyBlock
                    || serverOnlyBlock || physicsMismatch || touchingPhysicsGhost || nextToGhostWall;
        }
    }

    private static final class ClientBlock {
        private final int x, y, z;
        private final Material material;

        private ClientBlock(int x, int y, int z, Material material) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
        }

        private boolean inChunk(int chunkX, int chunkZ) {
            return (x >> 4) == chunkX && (z >> 4) == chunkZ;
        }
    }

    private static final class PendingArea {
        private final int minX, minY, minZ, maxX, maxY, maxZ, expiresAt;

        private PendingArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int expiresAt) {
            this.minX = Math.min(minX, maxX); this.minY = Math.min(minY, maxY); this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX); this.maxY = Math.max(minY, maxY); this.maxZ = Math.max(minZ, maxZ);
            this.expiresAt = expiresAt;
        }

        private boolean contains(int x, int y, int z, int margin) {
            return x >= minX - margin && x <= maxX + margin && y >= minY - margin && y <= maxY + margin
                    && z >= minZ - margin && z <= maxZ + margin;
        }

        private long key() {
            long key = 1469598103934665603L;
            for (int value : new int[]{minX, minY, minZ, maxX, maxY, maxZ}) key = (key ^ value) * 1099511628211L;
            return key;
        }
    }
}
