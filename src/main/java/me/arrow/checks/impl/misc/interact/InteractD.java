package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
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
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.custom.materials.PEMaterials;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * InteractD – Detects attacks through solid blocks (wall-hitting).
 *
 * <p>Fully lag-compensated: uses rewound target positions and the client's
 * predicted block-break state. All block lookups go through {@link ChunkCache}
 * exclusively – no live {@code World#getBlockAt} calls.
 *
 * <p><b>Design overview:</b>
 * <ol>
 *   <li>On each attack packet, build a small set of attacker eye positions
 *       (current + last) and target positions (current + ping-rewound).</li>
 *   <li>For every (eye, target-snapshot) pair, cast direct rays toward the
 *       target's eye, center, and feet.</li>
 *   <li>If <em>any</em> ray to <em>any</em> snapshot is clear (no blocking
 *       solid block), the attack is considered legitimate.</li>
 *   <li>Only when <b>every</b> ray to <b>every</b> snapshot is blocked by a
 *       solid block does the buffer increase and eventually flag.</li>
 * </ol>
 *
 * <p><b>Lag compensation for block breaks:</b> When the client sends
 * {@code FINISHED_DIGGING}, the broken block position is remembered as
 * "predicted client air" for a grace period proportional to the client's ping.
 * Similarly, when the server sends {@code BLOCK_CHANGE} or
 * {@code MULTI_BLOCK_CHANGE}, those positions are remembered as
 * "server update in flight". Blocks at those positions are treated as air
 * during ray-traces.
 *
 * <p><b>Uncached blocks:</b> If {@code ChunkCache.getBlock} returns {@code null}
 * (chunk not yet cached), the block is treated as solid ({@code OBSIDIAN}) to
 * prevent bypasses via uncached chunks.
 */
@Experimental
public class InteractD extends Check {

    // ──────────────────────────────────────────────────────────────────
    //  Constants
    // ──────────────────────────────────────────────────────────────────
    private static final double MAX_RAY_DISTANCE = 6.0D;
    private static final int    MAX_TARGET_HISTORY = 6;
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

        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            return;
        }

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        Player target = resolveTarget(packet.getEntityId());
        if (target != null) {
            handleAttack(target);
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

        // The client believes it broke this block — grace it for (ping + 125)ms.
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

        // Ensure both relevant chunks are in the cache.
        ChunkCache.get().ensurePlayerChunkLoaded(attackerLocation);
        ChunkCache.get().ensurePlayerChunkLoaded(targetProfile.getMovementData().getLocation());

        // ── Build attacker eye positions ────────────────────────────
        Player attacker = profile.getPlayer();
        List<Vector> attackerEyes = buildAttackerEyes(attackerMovement, attacker);

        // ── Build target snapshots (current + rewound) ──────────────
        List<TargetSnapshot> targetSnapshots = buildTargetSnapshots(target, targetProfile, ping);

        // ── Probe every (eye, snapshot) pair ────────────────────────
        // If ANY pair has a clear line of sight → legitimate attack.
        // Only if ALL pairs are blocked → flag.
        BlockHit bestBlockedHit = null;
        double bestBlockedEntityDist = Double.MAX_VALUE;
        long bestBlockedRewind = 0L;
        boolean allBlocked = true;

        for (Vector eye : attackerEyes) {
            for (TargetSnapshot snapshot : targetSnapshots) {
                double height = getTargetHeight(target);
                double tx = snapshot.location.getX();
                double ty = snapshot.location.getY();
                double tz = snapshot.location.getZ();

                // Probe three points on the target: eye level, center, feet.
                Vector[] probes = {
                    new Vector(tx, ty + height * 0.85D, tz),
                    new Vector(tx, ty + height * 0.5D,  tz),
                    new Vector(tx, ty + 0.2D,           tz)
                };

                for (Vector probe : probes) {
                    Vector diff = probe.clone().subtract(eye);
                    double dist = diff.length();

                    if (dist <= 1.0E-5D) {
                        // Attacker inside target — trivially visible.
                        allBlocked = false;
                        break;
                    }
                    if (dist > MAX_RAY_DISTANCE) {
                        continue;
                    }

                    Vector direction = diff.clone().normalize();
                    BlockHit hit = traceBlocks(world, eye, direction, dist);

                    if (hit == null) {
                        // Clear line of sight through this probe — attack is legitimate.
                        allBlocked = false;
                        break;
                    }

                    // This probe is blocked. Track the nearest blocking block.
                    if (hit.distance < bestBlockedEntityDist) {
                        bestBlockedHit = hit;
                        bestBlockedEntityDist = hit.distance;
                        bestBlockedRewind = snapshot.rewindMillis;
                    }
                }

                if (!allBlocked) break;
            }
            if (!allBlocked) break;
        }

        // ── Also check the look-direction ray ───────────────────────
        // The attacker's actual look direction might not point at any probe
        // point (e.g., killaura sending packets with wrong rotations). We
        // check whether the look direction intersects the target hitbox AND
        // is blocked by a solid wall.
        if (allBlocked) {
            for (Vector eye : attackerEyes) {
                Vector lookDir = getDirection(attackerRotation.getYaw(), attackerRotation.getPitch());

                for (TargetSnapshot snapshot : targetSnapshots) {
                    double horizExpand = getTargetHorizontalExpand(ping);
                    double height = getTargetHeight(target);
                    double tx = snapshot.location.getX();
                    double ty = snapshot.location.getY();
                    double tz = snapshot.location.getZ();

                    EntityBounds targetBox = new EntityBounds(
                            tx - 0.3D - horizExpand,
                            ty - 0.05D,
                            tz - 0.3D - horizExpand,
                            tx + 0.3D + horizExpand,
                            ty + height + 0.05D,
                            tz + 0.3D + horizExpand
                    );

                    double entityDist = getRayBoxEntryDistance(eye, lookDir, targetBox, MAX_RAY_DISTANCE);
                    if (entityDist < 0.0D) continue;

                    BlockHit hit = traceBlocks(world, eye, lookDir, entityDist);
                    if (hit == null) {
                        // Look direction reaches the target box unobstructed.
                        allBlocked = false;
                        break;
                    }

                    if (hit.distance < bestBlockedEntityDist) {
                        bestBlockedHit = hit;
                        bestBlockedEntityDist = hit.distance;
                        bestBlockedRewind = snapshot.rewindMillis;
                    }
                }
                if (!allBlocked) break;
            }

            // Also check the previous tick's rotation if it changed.
            if (allBlocked
                    && (Math.abs(attackerRotation.getYaw() - attackerRotation.getLastYaw()) > 1.0E-4F
                    || Math.abs(attackerRotation.getPitch() - attackerRotation.getLastPitch()) > 1.0E-4F)) {

                Vector lastLookDir = getDirection(attackerRotation.getLastYaw(), attackerRotation.getLastPitch());

                for (Vector eye : attackerEyes) {
                    for (TargetSnapshot snapshot : targetSnapshots) {
                        double horizExpand = getTargetHorizontalExpand(ping);
                        double height = getTargetHeight(target);
                        double tx = snapshot.location.getX();
                        double ty = snapshot.location.getY();
                        double tz = snapshot.location.getZ();

                        EntityBounds targetBox = new EntityBounds(
                                tx - 0.3D - horizExpand,
                                ty - 0.05D,
                                tz - 0.3D - horizExpand,
                                tx + 0.3D + horizExpand,
                                ty + height + 0.05D,
                                tz + 0.3D + horizExpand
                        );

                        double entityDist = getRayBoxEntryDistance(eye, lastLookDir, targetBox, MAX_RAY_DISTANCE);
                        if (entityDist < 0.0D) continue;

                        BlockHit hit = traceBlocks(world, eye, lastLookDir, entityDist);
                        if (hit == null) {
                            allBlocked = false;
                            break;
                        }
                    }
                    if (!allBlocked) break;
                }
            }
        }

        // ── Decision ────────────────────────────────────────────────
        if (!allBlocked) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - BUFFER_DECREMENT);
            return;
        }

        if (bestBlockedHit == null) {
            // No probes were in range — can't determine, decay buffer.
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - 0.2D);
            return;
        }

        wallHitBuffer = Math.min(BUFFER_MAX, wallHitBuffer + BUFFER_INCREMENT);

        if (wallHitBuffer >= BUFFER_THRESHOLD) {
            Vector3i hitPos = bestBlockedHit.position;
            String matName = bestBlockedHit.material != null ? bestBlockedHit.material.name() : "UNKNOWN";

            fail("Attacking player through block",
                    "target " + MsgType.MAIN_THEME_COLOR.getMessage() + target.getName()
                            + "\nhitBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + matName
                            + "\nhitLocation " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + new Vector(hitPos.getX(), hitPos.getY(), hitPos.getZ())
                            + "\nblockDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + bestBlockedEntityDist
                            + "\nrewind " + MsgType.MAIN_THEME_COLOR.getMessage() + bestBlockedRewind
                            + "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + attackerRotation.getYaw()
                            + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + attackerRotation.getPitch()
                            + "\nping " + MsgType.MAIN_THEME_COLOR.getMessage() + ping
                            + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + wallHitBuffer);

            wallHitBuffer = BUFFER_RESET_AFTER_FLAG;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  RAY-TRACE: walk blocks from start along direction up to maxDist
    // ══════════════════════════════════════════════════════════════════
    private BlockHit traceBlocks(World world, Vector start, Vector direction, double maxDistance) {
        if (world == null) return null;

        Vector end = start.clone().add(direction.clone().multiply(maxDistance));

        int minX = floor(Math.min(start.getX(), end.getX()));
        int maxX = floor(Math.max(start.getX(), end.getX()));
        int minY = floor(Math.min(start.getY(), end.getY()) - 0.5D);
        int maxY = floor(Math.max(start.getY(), end.getY()));
        int minZ = floor(Math.min(start.getZ(), end.getZ()));
        int maxZ = floor(Math.max(start.getZ(), end.getZ()));

        Material nearestMaterial = null;
        Vector3i nearestPosition = null;
        double nearestDistance = Double.MAX_VALUE;

        ChunkCache cache = ChunkCache.get();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material mat = cache.getBlock(world, x, y, z);

                    // ── KEY: uncached chunk → treat as solid ────────
                    // ChunkCache returns null when the chunk hasn't been
                    // loaded into the cache yet. Treating this as air would
                    // let attackers bypass the check by attacking toward
                    // uncached chunks. Instead, we use a full-cube solid
                    // placeholder so the ray always stops.
                    if (mat == null) {
                        mat = Material.OBSIDIAN;
                    }

                    if (mat == Material.AIR) {
                        continue;
                    }

                    BlockKey key = BlockKey.of(world, x, y, z);

                    // Skip blocks the client legitimately broke or
                    // that the server just changed (in-flight update).
                    if (isClientPredictedAir(key) || isServerUpdateInFlight(key)) {
                        continue;
                    }

                    // Get collision bounds from state (precise) or material (fallback).
                    WrappedBlockState state = cache.getBlockState(world, x, y, z);
                    List<PEMaterials.CollisionBounds> boundsList = state != null
                            ? PEMaterials.getCollisionBounds(state, x, y, z)
                            : PEMaterials.getCollisionBounds(mat, x, y, z);

                    if (boundsList == null || boundsList.isEmpty()) {
                        continue;
                    }

                    Vector3i blockPos = new Vector3i(x, y, z);

                    for (PEMaterials.CollisionBounds bounds : boundsList) {
                        // Check if the eye is inside this collision box.
                        if (start.getX() >= bounds.minX && start.getX() <= bounds.maxX
                                && start.getY() >= bounds.minY && start.getY() <= bounds.maxY
                                && start.getZ() >= bounds.minZ && start.getZ() <= bounds.maxZ) {
                            return new BlockHit(mat, blockPos, 0.0D);
                        }

                        double distance = getRayBoxEntryDistance(start, direction, bounds, maxDistance);

                        if (distance < 0.0D
                                || distance + 1.0E-4D >= maxDistance
                                || distance >= nearestDistance) {
                            continue;
                        }

                        nearestDistance = distance;
                        nearestMaterial = mat;
                        nearestPosition = blockPos;
                    }
                }
            }
        }

        return nearestPosition == null ? null : new BlockHit(nearestMaterial, nearestPosition, nearestDistance);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Snapshot builders
    // ══════════════════════════════════════════════════════════════════
    private List<Vector> buildAttackerEyes(MovementData movement, Player player) {
        List<Vector> eyes = new ArrayList<>(3);
        CustomLocation current = movement.getLocation();
        double eyeH = getEyeHeight(player);

        eyes.add(new Vector(current.getX(), current.getY() + eyeH, current.getZ()));

        CustomLocation last = movement.getLastLocation();
        if (last != null) {
            Vector lastEye = new Vector(last.getX(), last.getY() + eyeH, last.getZ());
            if (!isDuplicate(eyes, lastEye)) {
                eyes.add(lastEye);
            }
        }

        // Underblock case: test with previous-tick Y for head-hit situations.
        if (movement.isUnderblock()
                && (Math.abs(movement.getDeltaY()) > 1.0E-6D
                || Math.abs(movement.getLastDeltaY()) > 1.0E-6D)) {
            CustomLocation lastLast = movement.getLastLastLocation();
            if (lastLast != null) {
                Vector llEye = new Vector(current.getX(), lastLast.getY() + eyeH, current.getZ());
                if (!isDuplicate(eyes, llEye)) {
                    eyes.add(llEye);
                }
            }
        }

        return eyes;
    }

    private boolean isDuplicate(List<Vector> existing, Vector candidate) {
        for (Vector v : existing) {
            if (Math.abs(v.getX() - candidate.getX()) < 1.0E-6D
                    && Math.abs(v.getY() - candidate.getY()) < 1.0E-6D
                    && Math.abs(v.getZ() - candidate.getZ()) < 1.0E-6D) {
                return true;
            }
        }
        return false;
    }

    private List<TargetSnapshot> buildTargetSnapshots(Player target, Profile targetProfile, int pingMillis) {
        List<TargetSnapshot> result = new ArrayList<>();
        MovementData movement = targetProfile.getMovementData();

        result.add(new TargetSnapshot(movement.getLocation(), 0L));

        SampleList<CustomLocation> history = movement.getPastLocations();
        if (history == null || history.isEmpty()) return result;

        List<CustomLocation> samples;
        try {
            samples = new ArrayList<>(history);
        } catch (Throwable ignored) {
            return result;
        }

        long now = System.currentTimeMillis();
        long expectedRewind = pingMillis + 50L;
        long window = Math.min(250L, 100L + (pingMillis / 5L));
        int added = 0;

        for (int i = samples.size() - 1; i >= 0 && added < MAX_TARGET_HISTORY; i--) {
            CustomLocation sample = samples.get(i);
            if (sample == null || sample.getWorld() == null
                    || !sample.getWorld().getUID().equals(target.getWorld().getUID())) {
                continue;
            }

            long age = Math.max(0L, now - sample.getTimeStamp());
            if (age > expectedRewind + window) break;

            if (Math.abs(age - expectedRewind) <= window) {
                result.add(new TargetSnapshot(sample, age));
                added++;
            }
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Ray-box intersection
    // ══════════════════════════════════════════════════════════════════
    private double getRayBoxEntryDistance(Vector origin, Vector direction,
                                          PEMaterials.CollisionBounds bounds, double maxDistance) {
        return getRayBoxEntryDistance(origin, direction,
                new EntityBounds(bounds.minX, bounds.minY, bounds.minZ,
                                 bounds.maxX, bounds.maxY, bounds.maxZ),
                maxDistance);
    }

    private double getRayBoxEntryDistance(Vector origin, Vector direction,
                                          EntityBounds bounds, double maxDistance) {
        double[] range = {0.0D, maxDistance};

        if (!clipAxis(origin.getX(), direction.getX(), bounds.minX, bounds.maxX, range)
                || !clipAxis(origin.getY(), direction.getY(), bounds.minY, bounds.maxY, range)
                || !clipAxis(origin.getZ(), direction.getZ(), bounds.minZ, bounds.maxZ, range)) {
            return -1.0D;
        }

        return range[0] <= maxDistance ? range[0] : -1.0D;
    }

    private boolean clipAxis(double origin, double direction, double min, double max, double[] range) {
        if (Math.abs(direction) < 1.0E-9D) {
            return origin >= min && origin <= max;
        }

        double first  = (min - origin) / direction;
        double second = (max - origin) / direction;

        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }

        range[0] = Math.max(range[0], first);
        range[1] = Math.min(range[1], second);

        return range[0] <= range[1] && range[1] >= 0.0D;
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

    private double getTargetHorizontalExpand(int ping) {
        double base = profile.getVersion() != null
                && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                ? 0.10D : 0.08D;
        return base + Math.min(0.05D, ping * 0.00004D);
    }

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

    private static class EntityBounds {
        double minX, minY, minZ, maxX, maxY, maxZ;

        EntityBounds(double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    private static final class TargetSnapshot {
        final CustomLocation location;
        final long rewindMillis;

        TargetSnapshot(CustomLocation location, long rewindMillis) {
            this.location = location;
            this.rewindMillis = rewindMillis;
        }
    }

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
