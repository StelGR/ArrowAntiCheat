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
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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

@Experimental
public class InteractD extends Check {

    private static final double MAX_RAY_DISTANCE = 6.0D;
    private static final int MAX_TARGET_HISTORY = 40;
    private static final int MAX_TRACKED_BLOCKS = 32;

    /*
     * Both maps are the world as this specific client can currently perceive
     * it. No Bukkit interaction/damage event is used by this check.
     */
    private final Map<BlockKey, Long> predictedClientAir = new LinkedHashMap<>();
    private final Map<BlockKey, Long> pendingServerUpdates = new LinkedHashMap<>();

    private double wallHitBuffer;

    public InteractD(Profile profile) {
        super(profile, CheckType.INTERACT, "D", "Checks for attacking players through walls");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
            rememberServerUpdate(packet.getBlockPosition());
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : packet.getBlocks()) {
                rememberServerUpdate(new Vector3i(block.getX(), block.getY(), block.getZ()));
            }
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (isMovement(event)) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - 0.025D);
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

    private void handleDigging(PacketReceiveEvent event) {
        Player player = profile.getPlayer();

        if (player == null) {
            return;
        } else {
            player.getWorld();
        }

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

        if (!isValidBlockPosition(position)) {
            return;
        }

        BlockKey key = BlockKey.of(player.getWorld(), position.getX(), position.getY(), position.getZ());

        if (action == DiggingAction.CANCELLED_DIGGING) {
            predictedClientAir.remove(key);
            return;
        }

        long now = System.currentTimeMillis();
        putBounded(predictedClientAir, key, now + getClientBreakGraceMillis());
    }

    private void rememberServerUpdate(Vector3i position) {
        Player player = profile.getPlayer();

        if (player == null || !isValidBlockPosition(position)) {
            return;
        }

        long now = System.currentTimeMillis();
        BlockKey key = BlockKey.of(player.getWorld(), position.getX(), position.getY(), position.getZ());

        putBounded(pendingServerUpdates, key, now + getServerUpdateGraceMillis());
    }

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

        if (world == null || !world.getUID().equals(target.getWorld().getUID())) {
            return;
        }

        int ping = getPingMillis();

        if (ping > 1_750) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - 0.5D);
            return;
        }

        Vector direction = getDirection(attackerRotation.getYaw(), attackerRotation.getPitch());
        List<AttackerSnapshot> attackers = getAttackerSnapshots(attackerMovement, profile.getPlayer());

        List<TargetSnapshot> targets = getTargetSnapshots(target, targetProfile, ping);
        RayResult closestBlocked = null;
        boolean intersectedTarget = false;

        for (AttackerSnapshot attacker : attackers) {
            for (TargetSnapshot snapshot : targets) {
                RayResult result = traceTarget(
                        world,
                        attacker.eye,
                        direction,
                        attacker.body,
                        target,
                        snapshot,
                        ping
                );

                if (!result.intersectedTarget) {
                    continue;
                }

                intersectedTarget = true;

                if (!result.blocked) {
                    wallHitBuffer = Math.max(0.0D, wallHitBuffer - 0.6D);
                    return;
                }

                if (closestBlocked == null || result.blockDistance < closestBlocked.blockDistance) {
                    closestBlocked = result;
                }
            }
        }

        /*
         * A rotation that does not intersect any compensated target hitbox is
         * Reach/Aim territory. It is not safe evidence of a through-wall hit.
         */
        if (!intersectedTarget || closestBlocked.hitBlock == null) {
            wallHitBuffer = Math.max(0.0D, wallHitBuffer - 0.2D);
            return;
        }

        wallHitBuffer = Math.min(4.0D, wallHitBuffer + 1.0D);

        if (wallHitBuffer > 1.25D) {
            Block hitBlock = closestBlocked.hitBlock;

            fail("Attacking player through block",
                    "target " + MsgType.MAIN_THEME_COLOR.getMessage() + target.getName()
                            + "\nhitBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + hitBlock.getType().name()
                            + "\nhitLocation " + MsgType.MAIN_THEME_COLOR.getMessage() + new Vector(hitBlock.getX(), hitBlock.getY(), hitBlock.getZ())
                            + "\nentityDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + closestBlocked.entityDistance
                            + "\nblockDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + closestBlocked.blockDistance
                            + "\nrewind " + MsgType.MAIN_THEME_COLOR.getMessage() + closestBlocked.rewindMillis
                            + "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + attackerRotation.getYaw()
                            + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + attackerRotation.getPitch()
                            + "\nping " + MsgType.MAIN_THEME_COLOR.getMessage() + ping
                            + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + wallHitBuffer);

            wallHitBuffer = 1.0D;
        }
    }

    private RayResult traceTarget(World world,
                                  Vector eye,
                                  Vector direction,
                                  EntityBounds attackerBody,
                                  Player target,
                                  TargetSnapshot snapshot,
                                  int ping) {
        double horizontalExpand = getTargetHorizontalExpand(ping);
        double verticalExpand = 0.03D;
        double height = getTargetHeight(target);
        double x = snapshot.location.getX();
        double y = snapshot.location.getY();
        double z = snapshot.location.getZ();

        EntityBounds targetBody = new EntityBounds(
                x - 0.3D,
                y,
                z - 0.3D,
                x + 0.3D,
                y + height,
                z + 0.3D
        );
        EntityBounds targetBox = new EntityBounds(
                x - 0.3D - horizontalExpand,
                y - verticalExpand,
                z - 0.3D - horizontalExpand,
                x + 0.3D + horizontalExpand,
                y + height + verticalExpand,
                z + 0.3D + horizontalExpand
        );

        double entityDistance = getRayBoxEntryDistance(eye, direction, targetBox, MAX_RAY_DISTANCE);

        if (entityDistance < 0.0D) {
            return RayResult.noIntersection(snapshot.rewindMillis);
        }

        BlockHit blockHit = traceBlocks(world, eye, direction, entityDistance, attackerBody, targetBody);

        if (blockHit == null) {
            return RayResult.visible(entityDistance, snapshot.rewindMillis);
        }

        return RayResult.blocked(blockHit.block, blockHit.distance, entityDistance, snapshot.rewindMillis);
    }

    private BlockHit traceBlocks(World world,
                                 Vector start,
                                 Vector direction,
                                 double entityDistance,
                                 EntityBounds attackerBody,
                                 EntityBounds targetBody) {
        Vector end = start.clone().add(direction.clone().multiply(entityDistance));
        int minX = floor(Math.min(start.getX(), end.getX()));
        int maxX = floor(Math.max(start.getX(), end.getX()));
        int minY = floor(Math.min(start.getY(), end.getY()));
        int maxY = floor(Math.max(start.getY(), end.getY()));
        int minZ = floor(Math.min(start.getZ(), end.getZ()));
        int maxZ = floor(Math.max(start.getZ(), end.getZ()));

        Block nearestBlock = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!CollisionUtils.isChunkLoaded(new Location(world, x, y, z))) continue;

                    Block block = world.getBlockAt(x, y, z);
                    BlockKey key = BlockKey.of(block);

                    if (isClientPredictedAir(key) || isServerUpdateInFlight(key)) {
                        continue;
                    }

                    for (PEMaterials.CollisionBounds bounds : PEMaterials.getCollisionBounds(block)) {
                        /*
                         * A shape already intersecting either player's body is
                         * not a separating wall. This covers legitimate combat
                         * while clipped into thin shapes such as iron bars.
                         */
                        if (overlaps(attackerBody, bounds) || overlaps(targetBody, bounds)) {
                            continue;
                        }

                        double distance = getRayBoxEntryDistance(start, direction, bounds, entityDistance);

                        if (distance <= 1.0E-5D
                                || distance + 1.0E-4D >= entityDistance
                                || distance >= nearestDistance) {
                            continue;
                        }

                        nearestDistance = distance;
                        nearestBlock = block;
                    }
                }
            }
        }

        return nearestBlock == null ? null : new BlockHit(nearestBlock, nearestDistance);
    }

    private EntityBounds createPlayerBody(CustomLocation location, Player player) {
        double height = getTargetHeight(player);

        return new EntityBounds(
                location.getX() - 0.3D,
                location.getY(),
                location.getZ() - 0.3D,
                location.getX() + 0.3D,
                location.getY() + height,
                location.getZ() + 0.3D
        );
    }

    private List<AttackerSnapshot> getAttackerSnapshots(MovementData movement, Player player) {
        CustomLocation current = movement.getLocation();
        List<AttackerSnapshot> result = new ArrayList<>(3);

        addAttackerSnapshot(result, current, current.getY(), player);

        /*
         * While jumping into a low ceiling, the attack and movement packets can
         * disagree by one vertical state. Test the two immediately preceding eye
         * heights only in that confined vertical-motion case. X/Z stay current,
         * so this cannot compensate an attacker horizontally through a wall.
         */
        if (movement.isUnderblock()
                && (Math.abs(movement.getDeltaY()) > 1.0E-6D
                || Math.abs(movement.getLastDeltaY()) > 1.0E-6D)) {
            CustomLocation last = movement.getLastLocation();
            CustomLocation lastLast = movement.getLastLastLocation();

            if (last != null) {
                addAttackerSnapshot(result, current, last.getY(), player);
            }

            if (lastLast != null) {
                addAttackerSnapshot(result, current, lastLast.getY(), player);
            }
        }

        return result;
    }

    private void addAttackerSnapshot(List<AttackerSnapshot> snapshots,
                                     CustomLocation current,
                                     double feetY,
                                     Player player) {
        for (AttackerSnapshot snapshot : snapshots) {
            if (Math.abs(snapshot.feetY - feetY) <= 1.0E-6D) {
                return;
            }
        }

        CustomLocation bodyLocation = current.clone();
        bodyLocation.setY(feetY);
        snapshots.add(new AttackerSnapshot(
                feetY,
                new Vector(current.getX(), feetY + getEyeHeight(player), current.getZ()),
                createPlayerBody(bodyLocation, player)
        ));
    }

    private boolean overlaps(EntityBounds body, PEMaterials.CollisionBounds block) {
        double epsilon = 1.0E-4D;

        return block.maxX > body.minX + epsilon
                && block.minX < body.maxX - epsilon
                && block.maxY > body.minY + epsilon
                && block.minY < body.maxY - epsilon
                && block.maxZ > body.minZ + epsilon
                && block.minZ < body.maxZ - epsilon;
    }

    private double getRayBoxEntryDistance(Vector origin,
                                          Vector direction,
                                          PEMaterials.CollisionBounds bounds,
                                          double maxDistance) {
        return getRayBoxEntryDistance(
                origin,
                direction,
                new EntityBounds(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ),
                maxDistance
        );
    }

    private double getRayBoxEntryDistance(Vector origin,
                                          Vector direction,
                                          EntityBounds bounds,
                                          double maxDistance) {
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

        double first = (min - origin) / direction;
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

    private List<TargetSnapshot> getTargetSnapshots(Player target, Profile targetProfile, int pingMillis) {
        List<TargetSnapshot> result = new ArrayList<>();
        MovementData movement = targetProfile.getMovementData();

        result.add(new TargetSnapshot(movement.getLocation(), 0L));

        SampleList<CustomLocation> history = movement.getPastLocations();

        if (history == null || history.isEmpty()) {
            return result;
        }

        List<CustomLocation> samples;

        try {
            samples = new ArrayList<>(history);
        } catch (Throwable ignored) {
            samples = Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        long expectedRewind = pingMillis + 50L;
        long window = Math.min(250L, 100L + (pingMillis / 5L));
        int added = 0;

        for (int i = samples.size() - 1; i >= 0 && added < MAX_TARGET_HISTORY; i--) {
            CustomLocation sample = samples.get(i);

            if (sample == null
                    || sample.getWorld() == null
                    || !sample.getWorld().getUID().equals(target.getWorld().getUID())) {
                continue;
            }

            long age = Math.max(0L, now - sample.getTimeStamp());

            if (age > expectedRewind + window) {
                break;
            }

            if (Math.abs(age - expectedRewind) <= window) {
                result.add(new TargetSnapshot(sample, age));
                added++;
            }
        }

        return result;
    }

    private Player resolveTarget(int entityId) {
        try {
            UUID targetId = profile.getCombatData().getTrackedEntities().get(entityId);
            Player tracked = targetId == null ? null : Bukkit.getPlayer(targetId);

            if (tracked != null) {
                return tracked;
            }
        } catch (Throwable ignored) {
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getEntityId() == entityId) {
                return player;
            }
        }

        return null;
    }

    private boolean isExempt(Player target) {
        Player attacker = profile.getPlayer();

        if (attacker == null
                || target == null
                || !attacker.isOnline()
                || !target.isOnline()
                || attacker == target
                || profile.shouldCancel()
                || profile.isBedrockPlayer()
                || attacker.isDead()
                || target.isDead()
                || attacker.isSleeping()
                || attacker.isInsideVehicle()
                || target.isInsideVehicle()
                || attacker.getGameMode() == GameMode.CREATIVE
                || attacker.getGameMode() == GameMode.SPECTATOR
                || target.getGameMode() == GameMode.CREATIVE
                || target.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }

        return profile.getTick() < 20 || profile.isExempt().isTeleports();
    }

    private boolean isClientPredictedAir(BlockKey key) {
        return isUnexpired(predictedClientAir, key);
    }

    private boolean isServerUpdateInFlight(BlockKey key) {
        return isUnexpired(pendingServerUpdates, key);
    }

    private boolean isUnexpired(Map<BlockKey, Long> history, BlockKey key) {
        Long expiresAt = history.get(key);

        if (expiresAt == null) {
            return false;
        }

        if (expiresAt < System.currentTimeMillis()) {
            history.remove(key);
            return false;
        }

        return true;
    }

    private void putBounded(Map<BlockKey, Long> history, BlockKey key, long expiresAt) {
        if (history.size() >= MAX_TRACKED_BLOCKS && !history.containsKey(key)) {
            BlockKey oldest = history.keySet().iterator().next();
            history.remove(oldest);
        }

        history.put(key, expiresAt);
    }

    private void removeExpiredBlockHistory(long now) {
        predictedClientAir.entrySet().removeIf(entry -> entry.getValue() < now);
        pendingServerUpdates.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private long getClientBreakGraceMillis() {
        /* Same-packet-order grace remains even at 0 ms ping. */
        return Math.max(125L, Math.min(2_500L, getPingMillis() + 125L));
    }

    private long getServerUpdateGraceMillis() {
        return Math.max(75L, Math.min(1_500L, (getPingMillis() / 2L) + 75L));
    }

    private int getPingMillis() {
        if (profile.getConnectionData() == null) {
            return 0;
        }

        ConnectionData connection = profile.getConnectionData();
        int ping = 0;

        try {
            ping = Math.max(ping, connection.getTransPing());
        } catch (Throwable ignored) {
        }

        try {
            ping = Math.max(ping, connection.getPing());
        } catch (Throwable ignored) {
        }

        try {
            ping = Math.max(ping, connection.getClientTickTrans() * 50);
        } catch (Throwable ignored) {
        }

        return Math.min(2_000, ping);
    }

    private double getTargetHorizontalExpand(int ping) {
        double expand = profile.getVersion() != null
                && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                ? 0.10D
                : 0.03D;

        return expand + Math.min(0.05D, ping * 0.00004D);
    }

    private double getEyeHeight(Player player) {
        if (player == null) {
            return 1.62D;
        }

        try {
            return player.getEyeHeight();
        } catch (Throwable ignored) {
            return player.isSneaking() ? 1.54D : 1.62D;
        }
    }

    private double getTargetHeight(Player target) {
        try {
            if (target.isGliding()) {
                return 0.6D;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object swimming = target.getClass().getMethod("isSwimming").invoke(target);

            if (swimming instanceof Boolean && (Boolean) swimming) {
                return 0.6D;
            }
        } catch (Throwable ignored) {
        }

        return target.isSneaking() ? 1.5D : 1.8D;
    }

    private Vector getDirection(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);

        Vector direction = new Vector(
                -horizontal * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians)
        );

        return direction.lengthSquared() <= 1.0E-12D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : direction.normalize();
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean isValidBlockPosition(Vector3i position) {
        return position != null
                && !(position.getX() == -1 && position.getY() == -1 && position.getZ() == -1);
    }

    private int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static class EntityBounds {
        double minX, minY, minZ, maxX, maxY, maxZ;

        private EntityBounds(double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    private static final class TargetSnapshot {
        CustomLocation location;
        long rewindMillis;

        private TargetSnapshot(CustomLocation location, long rewindMillis) {
            this.location = location;
            this.rewindMillis = rewindMillis;
        }
    }

    private static final class AttackerSnapshot {
        double feetY;
        Vector eye;
        EntityBounds body;

        private AttackerSnapshot(double feetY, Vector eye, EntityBounds body) {
            this.feetY = feetY;
            this.eye = eye;
            this.body = body;
        }
    }

    private static class BlockHit {
        Block block;
        double distance;

        private BlockHit(Block block, double distance) {
            this.block = block;
            this.distance = distance;
        }
    }

    private static final class RayResult {
        boolean intersectedTarget;
        boolean blocked;
        Block hitBlock;
        double blockDistance;
        double entityDistance;
        long rewindMillis;

        private RayResult(boolean intersectedTarget,
                          boolean blocked,
                          Block hitBlock,
                          double blockDistance,
                          double entityDistance,
                          long rewindMillis) {
            this.intersectedTarget = intersectedTarget;
            this.blocked = blocked;
            this.hitBlock = hitBlock;
            this.blockDistance = blockDistance;
            this.entityDistance = entityDistance;
            this.rewindMillis = rewindMillis;
        }

        private static RayResult noIntersection(long rewindMillis) {
            return new RayResult(false, false, null, Double.MAX_VALUE, Double.MAX_VALUE, rewindMillis);
        }

        private static RayResult visible(double entityDistance, long rewindMillis) {
            return new RayResult(true, false, null, Double.MAX_VALUE, entityDistance, rewindMillis);
        }

        private static RayResult blocked(Block block,
                                         double blockDistance,
                                         double entityDistance,
                                         long rewindMillis) {
            return new RayResult(true, true, block, blockDistance, entityDistance, rewindMillis);
        }
    }

    private static class BlockKey {
        UUID world;
        int x, y, z;

        private BlockKey(UUID world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static BlockKey of(Block block) {
            return of(block.getWorld(), block.getX(), block.getY(), block.getZ());
        }

        private static BlockKey of(World world, int x, int y, int z) {
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
