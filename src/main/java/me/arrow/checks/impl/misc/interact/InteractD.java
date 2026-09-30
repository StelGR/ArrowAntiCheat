package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import me.arrow.Arrow;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.backend.bukkit.PlatformBackend;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * InteractD – Detects attacking players through walls.
 *
 * <p>Works like InteractB (bed break raytrace) but for entity attacks:
 * casts a ray from the attacker's eye along their look direction. If the
 * first solid block the ray hits is <em>in front of</em> the target player
 * (i.e., between attacker and target), the attack is flagged as going
 * through a wall.
 *
 * <p>Fully lag-compensated: blocks the client has broken on their screen
 * are treated as air for a grace period proportional to their ping.
 * All block lookups use {@link ChunkCache} exclusively.
 */
@Experimental
public class InteractD extends Check {

    // ──────────────────────────────────────────────────────────────────
    //  Constants
    // ──────────────────────────────────────────────────────────────────
    private static final double MAX_RAY_DISTANCE = 6.0D;
    private static final int    MAX_TRACKED_BLOCKS = 32;
    private static final double BUFFER_INCREMENT = 1.0D;
    private static final double BUFFER_DECREMENT = 0.6D;
    private static final double BUFFER_DECAY_PER_TICK = 0.025D;
    private static final double BUFFER_THRESHOLD = 1.0D;
    private static final double BUFFER_MAX = 4.0D;
    private static final double BUFFER_RESET_AFTER_FLAG = 0.5D;

    // ──────────────────────────────────────────────────────────────────
    //  Per-player state
    // ──────────────────────────────────────────────────────────────────
    private final Map<BlockKey, Long> predictedClientAir  = new LinkedHashMap<>();
    private final Map<BlockKey, Long> pendingServerUpdates = new LinkedHashMap<>();
    private double wallHitBuffer;

    public InteractD(Profile profile) {
        super(profile, CheckType.INTERACT, "D", "Checks for attacking players through walls");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Server → Client packets
    // ──────────────────────────────────────────────────────────────────
    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
            rememberServerUpdate(packet.getBlockPosition());
        } else if (event.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : packet.getBlocks()) {
                rememberServerUpdate(new Vector3i(block.getX(), block.getY(), block.getZ()));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Client → Server packets
    // ──────────────────────────────────────────────────────────────────
    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - BUFFER_DECAY_PER_TICK);
            removeExpiredBlockHistory(System.currentTimeMillis());
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) {
            handleDigging(event);
            return;
        }

        // New 26.3 dedicated attack packet.
        if (event.getPacketType().equals(PacketType.Play.Client.ATTACK)) {
            WrapperPlayClientAttack packet = new WrapperPlayClientAttack(event);
            Player target = resolveTarget(packet.getEntityId());
            if (target != null) {
                handleAttack(target);
            }
            return;
        }

        // Legacy INTERACT_ENTITY with ATTACK action (pre-26.3).
        if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                Player target = resolveTarget(packet.getEntityId());
                if (target != null) {
                    handleAttack(target);
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Client block-break prediction
    // ──────────────────────────────────────────────────────────────────
    private void handleDigging(PacketReceiveEvent event) {
        Player player = profile.getPlayer();
        if (player == null) return;

        WrapperPlayClientPlayerDigging packet;
        try {
            packet = new WrapperPlayClientPlayerDigging(event);
        } catch (Throwable ignored) {
            return;
        }

        DiggingAction action = packet.getAction();
        if (action != DiggingAction.FINISHED_DIGGING
                && action != DiggingAction.CANCELLED_DIGGING) {
            return;
        }

        Vector3i position = packet.getBlockPosition();
        if (!isValidBlockPosition(position)) return;

        BlockKey key = BlockKey.of(player.getWorld(), position.getX(), position.getY(), position.getZ());

        if (action == DiggingAction.CANCELLED_DIGGING) {
            predictedClientAir.remove(key);
            return;
        }

        putBounded(predictedClientAir, key, System.currentTimeMillis() + getClientBreakGraceMillis());
    }

    // ──────────────────────────────────────────────────────────────────
    //  Server block-change tracking
    // ──────────────────────────────────────────────────────────────────
    private void rememberServerUpdate(Vector3i position) {
        Player player = profile.getPlayer();
        if (player == null || !isValidBlockPosition(position)) return;

        BlockKey key = BlockKey.of(player.getWorld(), position.getX(), position.getY(), position.getZ());
        putBounded(pendingServerUpdates, key, System.currentTimeMillis() + getServerUpdateGraceMillis());
    }

    // ══════════════════════════════════════════════════════════════════
    //  CORE: handle an attack packet
    //
    //  Cast direct rays from the attacker's eye toward the target's
    //  body (head, center, feet). If a solid block is between the
    //  attacker and the target on ALL probe lines, the attack is going
    //  through a wall → flag.
    //
    //  We do NOT use the look direction because killaura / cheats
    //  spoof server-side rotations to aim over or around blocks.
    //  Direct eye-to-target rays catch wall-hitting regardless of
    //  what rotation the client sends.
    // ══════════════════════════════════════════════════════════════════
    private void handleAttack(Player target) {
        if (!isEnabled() || isExempt(target)) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - 0.25D);
            return;
        }

        MovementData attackerMovement = profile.getMovementData();
        RotationData attackerRotation = profile.getRotationData();
        Profile targetProfile = Arrow.getInstance().getProfileManager().getProfile(target);

        if (attackerMovement == null
                || attackerRotation == null
                || attackerMovement.getLocation() == null
                || targetProfile == null
                || targetProfile.getMovementData() == null
                || targetProfile.getMovementData().getLocation() == null
                || targetProfile.shouldCancel()) {
            return;
        }

        CustomLocation attackerLocation = attackerMovement.getLocation();
        World world = attackerLocation.getWorld();
        if (world == null || !world.getUID().equals(target.getWorld().getUID())) return;

        int ping = getPingMillis();
        if (ping > 1_750) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - 0.5D);
            return;
        }

        // Ensure both chunks are cached.
        ChunkCache.get().ensurePlayerChunkLoaded(attackerLocation);
        ChunkCache.get().ensurePlayerChunkLoaded(targetProfile.getMovementData().getLocation());

        Player attacker = profile.getPlayer();
        if (attacker == null) return;

        // ── Attacker eye position ───────────────────────────────────
        double eyeHeight = getEyeHeight(attacker);
        Vector eye = new Vector(
                attackerLocation.getX(),
                attackerLocation.getY() + eyeHeight,
                attackerLocation.getZ()
        );

        // ── Target body probe points ────────────────────────────────
        CustomLocation targetLocation = targetProfile.getMovementData().getLocation();
        double targetHeight = getTargetHeight(target);
        double tx = targetLocation.getX();
        double ty = targetLocation.getY();
        double tz = targetLocation.getZ();

        Vector[] probes = {
            new Vector(tx, ty + targetHeight * 0.85D, tz),  // head
            new Vector(tx, ty + targetHeight * 0.5D,  tz),  // center
            new Vector(tx, ty + 0.2D,                 tz)    // feet
        };

        // ── Cast a ray from eye toward each probe point ─────────────
        // If ANY ray is clear, the attack is legitimate.
        // Only flag when ALL rays are blocked.
        BlockHit nearestHit = null;
        boolean anyVisible = false;

        for (Vector probe : probes) {
            Vector diff = probe.clone().subtract(eye);
            double dist = diff.length();

            if (dist <= 1.0E-5D) {
                anyVisible = true;
                break;
            }
            if (dist > MAX_RAY_DISTANCE) {
                continue;
            }

            Vector direction = diff.clone().normalize();
            BlockHit hit = stepRaytrace(world, eye, direction, dist);

            if (hit == null) {
                anyVisible = true;
                break;
            }

            if (nearestHit == null || hit.distance < nearestHit.distance) {
                nearestHit = hit;
            }
        }

        if (anyVisible || nearestHit == null) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - BUFFER_DECREMENT);
            return;
        }

        // ── All probes blocked — solid wall between attacker & target ─
        wallHitBuffer = Math.min(BUFFER_MAX, wallHitBuffer + BUFFER_INCREMENT);

        if (wallHitBuffer >= BUFFER_THRESHOLD) {
            Vector3i hitPos = nearestHit.position;
            String matName = nearestHit.material != null ? nearestHit.material.name() : "UNKNOWN";
            double distToTarget = eye.distance(new Vector(tx, ty + targetHeight * 0.5D, tz));

            fail("Attacking player through block",
                    "target " + MsgType.MAIN_THEME_COLOR.getMessage() + target.getName()
                            + "\nhitBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + matName
                            + "\nhitLocation " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + new Vector(hitPos.getX(), hitPos.getY(), hitPos.getZ())
                            + "\nblockDistance " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + String.format("%.2f", nearestHit.distance)
                            + "\ntargetDistance " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + String.format("%.2f", distToTarget)
                            + "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + attackerRotation.getYaw()
                            + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + attackerRotation.getPitch()
                            + "\nping " + MsgType.MAIN_THEME_COLOR.getMessage() + ping
                            + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + wallHitBuffer);

            wallHitBuffer = BUFFER_RESET_AFTER_FLAG;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  STEP RAY-TRACE — same approach as InteractB's fallback raytrace
    //  in GeneralEventListener, but uses ChunkCache instead of
    //  World#getBlockAt.
    //
    //  Walks small steps along the direction vector. At each step,
    //  queries the block at that position from ChunkCache. The first
    //  solid, non-ignored block is returned.
    // ══════════════════════════════════════════════════════════════════
    private BlockHit stepRaytrace(World world, Vector eye, Vector direction, double maxDistance) {
        if (world == null) return null;

        ChunkCache cache = ChunkCache.get();
        double step = 0.1D;

        int lastBlockX = Integer.MIN_VALUE;
        int lastBlockY = Integer.MIN_VALUE;
        int lastBlockZ = Integer.MIN_VALUE;

        for (double travelled = 0.0D; travelled <= maxDistance; travelled += step) {
            double x = eye.getX() + direction.getX() * travelled;
            double y = eye.getY() + direction.getY() * travelled;
            double z = eye.getZ() + direction.getZ() * travelled;

            int bx = floor(x);
            int by = floor(y);
            int bz = floor(z);

            // Skip if we're still in the same block as the last step.
            if (bx == lastBlockX && by == lastBlockY && bz == lastBlockZ) {
                continue;
            }
            lastBlockX = bx;
            lastBlockY = by;
            lastBlockZ = bz;

            Material mat = cache.getBlock(world, bx, by, bz);

            // Uncached chunk → treat as solid (prevents bypass via uncached areas).
            if (mat == null) {
                mat = Material.OBSIDIAN;
            }

            // Skip air and non-solid/ignored blocks.
            if (isIgnoredRayBlock(mat)) {
                continue;
            }

            // Skip blocks the client has legitimately broken or
            // that are pending a server update.
            BlockKey key = BlockKey.of(world, bx, by, bz);
            if (isClientPredictedAir(key) || isServerUpdateInFlight(key)) {
                continue;
            }

            // We have a solid block — check collision bounds for precision.
            WrappedBlockState state = cache.getBlockState(world, bx, by, bz);
            List<PEMaterials.CollisionBounds> boundsList = state != null
                    ? PEMaterials.getCollisionBounds(state, bx, by, bz)
                    : PEMaterials.getCollisionBounds(mat, bx, by, bz);

            if (boundsList == null || boundsList.isEmpty()) {
                continue;
            }

            // Verify the ray point is actually inside one of the
            // collision bounds (handles partial blocks like slabs, stairs).
            boolean insideBounds = false;
            for (PEMaterials.CollisionBounds bounds : boundsList) {
                if (x >= bounds.minX && x <= bounds.maxX
                        && y >= bounds.minY && y <= bounds.maxY
                        && z >= bounds.minZ && z <= bounds.maxZ) {
                    insideBounds = true;
                    break;
                }
            }

            if (!insideBounds) {
                continue;
            }

            // First solid block hit.
            return new BlockHit(mat, new Vector3i(bx, by, bz), travelled);
        }

        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Block classification — same as InteractB's isIgnoredRayBlock
    // ══════════════════════════════════════════════════════════════════
    private boolean isIgnoredRayBlock(Material mat) {
        if (mat == null || mat == Material.AIR) {
            return true;
        }

        String name = mat.name();

        // Air variants.
        if (name.equals("CAVE_AIR") || name.equals("VOID_AIR")) {
            return true;
        }

        // Non-physical blocks.
        if (name.equals("LIGHT") || name.equals("STRUCTURE_VOID")) {
            return true;
        }

        // Liquids.
        if (name.contains("WATER") || name.contains("LAVA")
                || name.equals("BUBBLE_COLUMN")) {
            return true;
        }

        // Vegetation, decorations, and other pass-through blocks.
        return name.contains("TALL_GRASS")
                || name.endsWith("_GRASS")
                || name.contains("FLOWER")
                || name.contains("SAPLING")
                || name.contains("MUSHROOM")
                || name.contains("TORCH")
                || name.contains("BUTTON")
                || name.contains("PRESSURE_PLATE")
                || name.contains("SIGN")
                || name.contains("BANNER")
                || name.contains("CARPET")
                || name.contains("DEAD_BUSH")
                || name.contains("FERN")
                || name.equals("SUGAR_CANE")
                || name.equals("VINE")
                || name.equals("LILY_PAD")
                || name.equals("SNOW")
                || name.contains("CORAL_FAN")
                || name.contains("CORAL_WALL_FAN")
                || name.contains("REDSTONE_WIRE")
                || name.equals("REDSTONE")
                || name.equals("TRIPWIRE")
                || name.equals("TRIPWIRE_HOOK")
                || name.equals("STRING");
    }

    // ══════════════════════════════════════════════════════════════════
    //  Utility methods
    // ══════════════════════════════════════════════════════════════════
    private Player resolveTarget(int entityId) {
        try {
            UUID targetId = profile.getCombatData().getTrackedEntities().get(entityId);
            Player tracked = targetId == null ? null : PlatformBackend.get().getServer().getPlayer(targetId);
            if (tracked != null) return tracked;
        } catch (Throwable ignored) {}

        for (Player player : PlatformBackend.get().getServer().getOnlinePlayers()) {
            if (player.getEntityId() == entityId) return player;
        }
        return null;
    }

    private boolean isExempt(Player target) {
        Player attacker = profile.getPlayer();

        if (attacker == null || target == null
                || !attacker.isOnline() || !target.isOnline()
                || attacker == target
                || profile.shouldCancel()
                || profile.isBedrockPlayer()
                || attacker.isDead() || target.isDead()
                || attacker.isSleeping()
                || attacker.isInsideVehicle() || target.isInsideVehicle()
                || attacker.getGameMode() == GameMode.CREATIVE
                || attacker.getGameMode() == GameMode.SPECTATOR
                || target.getGameMode() == GameMode.CREATIVE
                || target.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }

        return profile.getTick() < 20 || profile.isExempt().isTeleports();
    }

    // ── Block-history helpers ───────────────────────────────────────

    private boolean isClientPredictedAir(BlockKey key) {
        return isUnexpired(predictedClientAir, key);
    }

    private boolean isServerUpdateInFlight(BlockKey key) {
        return isUnexpired(pendingServerUpdates, key);
    }

    private boolean isUnexpired(Map<BlockKey, Long> map, BlockKey key) {
        Long expiresAt = map.get(key);
        if (expiresAt == null) return false;
        if (expiresAt < System.currentTimeMillis()) {
            map.remove(key);
            return false;
        }
        return true;
    }

    private void putBounded(Map<BlockKey, Long> map, BlockKey key, long expiresAt) {
        if (map.size() >= MAX_TRACKED_BLOCKS && !map.containsKey(key)) {
            BlockKey oldest = map.keySet().iterator().next();
            map.remove(oldest);
        }
        map.put(key, expiresAt);
    }

    private void removeExpiredBlockHistory(long now) {
        predictedClientAir.entrySet().removeIf(entry -> entry.getValue() < now);
        pendingServerUpdates.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private long getClientBreakGraceMillis() {
        return Math.max(125L, Math.min(2_500L, getPingMillis() + 125L));
    }

    private long getServerUpdateGraceMillis() {
        return Math.max(75L, Math.min(1_500L, (getPingMillis() / 2L) + 75L));
    }

    private int getPingMillis() {
        if (profile.getConnectionData() == null) return 0;

        ConnectionData connection = profile.getConnectionData();
        int ping = 0;

        try { ping = Math.max(ping, connection.getTransPing()); } catch (Throwable ignored) {}
        try { ping = Math.max(ping, connection.getPing()); } catch (Throwable ignored) {}
        try { ping = Math.max(ping, connection.getClientTickTrans() * 50); } catch (Throwable ignored) {}

        return Math.min(2_000, ping);
    }

    // ── Geometry helpers ────────────────────────────────────────────

    private double getEyeHeight(Player player) {
        if (player == null) return 1.62D;
        try {
            return player.getEyeHeight();
        } catch (Throwable ignored) {
            return player.isSneaking() ? 1.54D : 1.62D;
        }
    }

    private double getTargetHeight(Player target) {
        try {
            if (target.isGliding()) return 0.6D;
        } catch (Throwable ignored) {}
        try {
            Object swimming = target.getClass().getMethod("isSwimming").invoke(target);
            if (swimming instanceof Boolean && (Boolean) swimming) return 0.6D;
        } catch (Throwable ignored) {}
        return target.isSneaking() ? 1.5D : 1.8D;
    }

    private Vector getDirection(float yaw, float pitch) {
        double yawRad   = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double horiz    = Math.cos(pitchRad);

        Vector dir = new Vector(
                -horiz * Math.sin(yawRad),
                -Math.sin(pitchRad),
                horiz * Math.cos(yawRad)
        );
        return dir.lengthSquared() <= 1.0E-12D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : dir.normalize();
    }

    private boolean isValidBlockPosition(Vector3i position) {
        return position != null
                && !(position.getX() == -1 && position.getY() == -1 && position.getZ() == -1);
    }

    private int floor(double value) {
        return MathUtil.floor(value);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Data containers
    // ══════════════════════════════════════════════════════════════════

    private static class BlockHit {
        final Material material;
        final Vector3i position;
        final double distance;

        BlockHit(Material material, Vector3i position, double distance) {
            this.material = material;
            this.position = position;
            this.distance = distance;
        }
    }

    private static class BlockKey {
        final UUID world;
        final int x, y, z;

        private BlockKey(UUID world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static BlockKey of(Block block) {
            return of(block.getWorld(), block.getX(), block.getY(), block.getZ());
        }

        static BlockKey of(World world, int x, int y, int z) {
            return new BlockKey(world.getUID(), x, y, z);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof BlockKey other)) return false;
            return x == other.x && y == other.y && z == other.z && world.equals(other.world);
        }

        @Override
        public int hashCode() {
            int result = world.hashCode();
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
