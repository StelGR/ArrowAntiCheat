package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reconstructs where this profile's client rendered nearby player entities.
 * Server-side target movement history is not sufficient for reach: the client
 * sees quantized outgoing entity packets and interpolates each update over
 * three ticks. This tracker records that exact per-viewer stream.
 */
public class ReachEntityTracker implements Data {

    private static final int MAX_UPDATES = 140;
    private static final long MAX_UPDATE_AGE_MS = 6_000L;
    private static final long INTERPOLATION_TIME_MS = 150L;
    private static final double TRACK_DISTANCE_SQUARED = 24.0D * 24.0D;

    private final Profile profile;
    private final Map<Integer, TrackedEntity> entities = new ConcurrentHashMap<>();
    private final Map<Integer, Long> modernBoundaries = new ConcurrentHashMap<>();
    private final Map<Short, Long> legacyBoundaries = new ConcurrentHashMap<>();

    private volatile long lastConfirmedBoundary;

    public ReachEntityTracker(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        try {
            if (event.getPacketType().equals(PacketType.Play.Client.PONG)) {
                int id = new WrapperPlayClientPong(event).getId();
                Long boundary = modernBoundaries.remove(id);

                if (boundary != null) {
                    lastConfirmedBoundary = Math.max(lastConfirmedBoundary, boundary);
                }
            } else if (event.getPacketType().equals(PacketType.Play.Client.WINDOW_CONFIRMATION)) {
                short id = new WrapperPlayClientWindowConfirmation(event).getActionId();
                Long boundary = legacyBoundaries.remove(id);

                if (boundary != null) {
                    lastConfirmedBoundary = Math.max(lastConfirmedBoundary, boundary);
                }
            }

            if (isMovement(event)) {
                long receiveTime = normalizePacketTimestamp(event.getTimestamp());
                long estimatedClientBoundary = receiveTime - getPingMillis();
                long confirmedClientBoundary = Math.max(estimatedClientBoundary, lastConfirmedBoundary);

                for (TrackedEntity entity : entities.values()) {
                    entity.advanceClientTick(confirmedClientBoundary);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        final long timestamp = normalizePacketTimestamp(event.getTimestamp());

        try {
            if (event.getPacketType().equals(PacketType.Play.Server.PING)) {
                int id = new WrapperPlayServerPing(event).getId();
                modernBoundaries.putIfAbsent(id, timestamp);
                trimBoundaries();
                return;
            }

            if (event.getPacketType().equals(PacketType.Play.Server.WINDOW_CONFIRMATION)) {
                short id = new WrapperPlayServerWindowConfirmation(event).getActionId();
                legacyBoundaries.putIfAbsent(id, timestamp);
                trimBoundaries();
                return;
            }

            if (event.getPacketType().equals(PacketType.Play.Server.SPAWN_PLAYER)) {
                WrapperPlayServerSpawnPlayer wrapper = new WrapperPlayServerSpawnPlayer(event);
                trackSpawn(wrapper.getEntityId(), wrapper.getUUID(), wrapper.getPosition(), timestamp);
                return;
            }

            // Player spawning moved to the generic entity packet in 1.20.2.
            // Never wrap legacy generic spawns: some 1.8 death-drop object IDs
            // have no PacketEvents EntityType mapping and are then re-encoded
            // with a null type, disconnecting every receiving client.
            if (event.getPacketType().equals(PacketType.Play.Server.SPAWN_ENTITY)
                    && usesGenericPlayerSpawnPacket()) {
                WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);

                if (wrapper.getEntityType() == EntityTypes.PLAYER && wrapper.getUUID().isPresent()) {
                    trackSpawn(wrapper.getEntityId(), wrapper.getUUID().get(), wrapper.getPosition(), timestamp);
                }
                return;
            }

            if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_RELATIVE_MOVE)) {
                WrapperPlayServerEntityRelativeMove wrapper = new WrapperPlayServerEntityRelativeMove(event);
                moveRelative(wrapper.getEntityId(), wrapper.getDeltaX(), wrapper.getDeltaY(), wrapper.getDeltaZ(), timestamp);
                return;
            }

            if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION)) {
                WrapperPlayServerEntityRelativeMoveAndRotation wrapper = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                moveRelative(wrapper.getEntityId(), wrapper.getDeltaX(), wrapper.getDeltaY(), wrapper.getDeltaZ(), timestamp);
                return;
            }

            if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_TELEPORT)) {
                WrapperPlayServerEntityTeleport wrapper = new WrapperPlayServerEntityTeleport(event);
                teleport(wrapper.getEntityId(), wrapper.getPosition(), wrapper.getRelativeFlags(), timestamp);
                return;
            }

            if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_POSITION_SYNC)) {
                WrapperPlayServerEntityPositionSync wrapper = new WrapperPlayServerEntityPositionSync(event);
                EntityPositionData values = wrapper.getValues();

                if (values != null) {
                    teleport(wrapper.getId(), values.getPosition(), RelativeFlag.NONE, timestamp);
                }
                return;
            }

            if (event.getPacketType().equals(PacketType.Play.Server.DESTROY_ENTITIES)) {
                int[] ids = new WrapperPlayServerDestroyEntities(event).getEntityIds();

                for (int id : ids) {
                    entities.remove(id);
                }
            }
        } catch (Throwable ignored) {
            // Packet availability differs across the supported 1.7-modern range.
            // A malformed/unsupported wrapper must only disable this sample;
            // Reach A retains its target movement-history fallback.
        }
    }

    private boolean usesGenericPlayerSpawnPacket() {
        try {
            return PacketEvents.getAPI().getServerManager().getVersion()
                    .isNewerThanOrEquals(ServerVersion.V_1_20_2);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public RenderSnapshot getRenderSnapshot(int entityId,
                                            UUID expectedUuid,
                                            World world,
                                            long attackTimestamp,
                                            int pingMillis,
                                            int minimumUpdates) {
        TrackedEntity entity = entities.get(entityId);

        if (entity == null || expectedUuid == null || !expectedUuid.equals(entity.uuid)) {
            return null;
        }

        long attackTime = normalizePacketTimestamp(attackTimestamp);
        long estimatedRenderTime = attackTime - Math.max(0, Math.min(2_500, pingMillis));

        // An acknowledged transaction proves that every entity update before
        // the first copy of that transaction was already processed by the
        // client. It gives a reliable lower bound when RTT directionality or a
        // short ping spike makes the timestamp estimate too old.
        long renderTime = Math.max(estimatedRenderTime, lastConfirmedBoundary);
        renderTime = Math.min(renderTime, attackTime);

        return entity.snapshot(world, renderTime, Math.max(1, minimumUpdates));
    }

    private void trackSpawn(int entityId, UUID uuid, Vector3d position, long timestamp) {
        if (uuid == null || position == null || uuid.equals(profile.getUUID())) {
            return;
        }

        entities.put(entityId, new TrackedEntity(
                uuid,
                position.getX(),
                position.getY(),
                position.getZ(),
                timestamp
        ));
    }

    private void moveRelative(int entityId, double deltaX, double deltaY, double deltaZ, long timestamp) {
        TrackedEntity entity = entities.get(entityId);

        if (entity == null) {
            return;
        }

        entity.moveRelative(deltaX, deltaY, deltaZ, timestamp, isNearViewer(entity));
    }

    private void teleport(int entityId, Vector3d position, RelativeFlag flags, long timestamp) {
        TrackedEntity entity = entities.get(entityId);

        if (entity == null || position == null) {
            return;
        }

        entity.teleport(position, flags, timestamp, isNearViewer(entity, position));
    }

    private boolean isNearViewer(TrackedEntity entity) {
        return isNearViewer(entity, new Vector3d(entity.packetX, entity.packetY, entity.packetZ));
    }

    private boolean isNearViewer(TrackedEntity entity, Vector3d targetPosition) {
        if (profile.getMovementData() == null || profile.getMovementData().getLocation() == null) {
            return true;
        }

        CustomLocation viewer = profile.getMovementData().getLocation();
        double x = viewer.getX() - targetPosition.getX();
        double y = viewer.getY() - targetPosition.getY();
        double z = viewer.getZ() - targetPosition.getZ();

        return x * x + y * y + z * z <= TRACK_DISTANCE_SQUARED;
    }

    private void trimBoundaries() {
        if (modernBoundaries.size() > 32) {
            modernBoundaries.clear();
        }

        if (legacyBoundaries.size() > 32) {
            legacyBoundaries.clear();
        }
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private int getPingMillis() {
        int ping = 0;

        try {
            if (profile.getConnectionData() != null) {
                ping = Math.max(ping, profile.getConnectionData().getTransPing());
                ping = Math.max(ping, profile.getConnectionData().getPing());
            }
        } catch (Throwable ignored) {
        }

        try {
            ping = Math.max(ping, profile.getPing());
        } catch (Throwable ignored) {
        }

        return Math.max(0, Math.min(2_500, ping));
    }

    private static long normalizePacketTimestamp(long timestamp) {
        long nowMillis = System.currentTimeMillis();

        if (timestamp <= 0L) {
            return nowMillis;
        }

        if (Math.abs(nowMillis - timestamp) <= 60_000L) {
            return timestamp;
        }

        long nanoAge = System.nanoTime() - timestamp;

        if (nanoAge >= 0L && nanoAge <= TimeUnit.SECONDS.toNanos(60L)) {
            return nowMillis - TimeUnit.NANOSECONDS.toMillis(nanoAge);
        }

        return nowMillis;
    }

    public static final class RenderSnapshot {
        private final CustomLocation precise;
        private final List<CustomLocation> candidates;
        private final int updates;

        private RenderSnapshot(CustomLocation precise, List<CustomLocation> candidates, int updates) {
            this.precise = precise;
            this.candidates = candidates;
            this.updates = updates;
        }

        public CustomLocation getPrecise() {
            return precise;
        }

        public List<CustomLocation> getCandidates() {
            return candidates;
        }

        public int getUpdates() {
            return updates;
        }
    }

    private static final class TrackedEntity {
        private final UUID uuid;
        private final Deque<EntityUpdate> updates = new ArrayDeque<>();

        private double packetX;
        private double packetY;
        private double packetZ;
        private boolean historyStartsAtSpawn = true;

        private boolean renderInitialized;
        private double renderedX;
        private double renderedY;
        private double renderedZ;
        private double renderTargetX;
        private double renderTargetY;
        private double renderTargetZ;
        private int renderIncrements;

        private TrackedEntity(UUID uuid, double x, double y, double z, long timestamp) {
            this.uuid = uuid;
            this.packetX = x;
            this.packetY = y;
            this.packetZ = z;
            updates.addLast(new EntityUpdate(x, y, z, timestamp));
        }

        private synchronized void moveRelative(double x, double y, double z, long timestamp, boolean nearViewer) {
            packetX += x;
            packetY += y;
            packetZ += z;
            addUpdate(timestamp, nearViewer);
        }

        private synchronized void teleport(Vector3d position,
                                           RelativeFlag flags,
                                           long timestamp,
                                           boolean nearViewer) {
            RelativeFlag safeFlags = flags == null ? RelativeFlag.NONE : flags;
            packetX = safeFlags.has(RelativeFlag.X) ? packetX + position.getX() : position.getX();
            packetY = safeFlags.has(RelativeFlag.Y) ? packetY + position.getY() : position.getY();
            packetZ = safeFlags.has(RelativeFlag.Z) ? packetZ + position.getZ() : position.getZ();
            addUpdate(timestamp, nearViewer);
        }

        private void addUpdate(long timestamp, boolean nearViewer) {
            if (!nearViewer) {
                updates.clear();
                historyStartsAtSpawn = false;
                renderInitialized = false;
                renderIncrements = 0;
            }

            updates.addLast(new EntityUpdate(packetX, packetY, packetZ, timestamp));

            while (updates.size() > MAX_UPDATES) {
                updates.removeFirst();
            }

            while (updates.size() > 2 && timestamp - updates.peekFirst().timestamp > MAX_UPDATE_AGE_MS) {
                updates.removeFirst();
            }
        }

        /**
         * Advances the entity exactly once for one movement packet from the
         * viewing client. Vanilla moves a remotely rendered entity toward its
         * latest target in three client ticks; wall-clock interpolation alone
         * becomes inaccurate during W-taps and uneven packet timing.
         */
        private synchronized void advanceClientTick(long visibleBoundary) {
            EntityUpdate newestVisible = null;

            for (EntityUpdate update : updates) {
                if (update.applied) {
                    continue;
                }

                if (update.timestamp > visibleBoundary) {
                    break;
                }

                update.applied = true;
                newestVisible = update;
            }

            if (newestVisible != null) {
                if (!renderInitialized) {
                    renderedX = newestVisible.x;
                    renderedY = newestVisible.y;
                    renderedZ = newestVisible.z;
                    renderTargetX = newestVisible.x;
                    renderTargetY = newestVisible.y;
                    renderTargetZ = newestVisible.z;
                    renderInitialized = true;
                    renderIncrements = 0;
                } else {
                    renderTargetX = newestVisible.x;
                    renderTargetY = newestVisible.y;
                    renderTargetZ = newestVisible.z;
                    renderIncrements = 3;
                }
            }

            if (!renderInitialized || renderIncrements <= 0) {
                return;
            }

            renderedX += (renderTargetX - renderedX) / renderIncrements;
            renderedY += (renderTargetY - renderedY) / renderIncrements;
            renderedZ += (renderTargetZ - renderedZ) / renderIncrements;
            renderIncrements--;
        }

        private synchronized RenderSnapshot snapshot(World world, long renderTime, int minimumSamples) {
            if (world == null
                    || updates.isEmpty()
                    || !renderInitialized
                    || (!historyStartsAtSpawn && updates.size() < minimumSamples)) {
                return null;
            }

            List<EntityUpdate> history = new ArrayList<>(updates);
            CustomLocation precise = location(world, renderedX, renderedY, renderedZ, renderTime);

            int sampleCount = Math.max(1, minimumSamples);
            List<CustomLocation> candidates = new ArrayList<>(sampleCount);
            candidates.add(precise);

            if (sampleCount > 1) {
                // More configured samples increase resolution inside the same
                // fixed 25 ms uncertainty window. They do not widen the window
                // or grant additional reach leniency.
                int pairs = Math.max(1, (sampleCount - 1) / 2);

                for (int pair = 1; candidates.size() < sampleCount; pair++) {
                    long offset = Math.round(25.0D * Math.min(pair, pairs) / pairs);
                    addCandidate(candidates, render(history, world, renderTime - offset));

                    if (candidates.size() < sampleCount) {
                        addCandidate(candidates, render(history, world, renderTime + offset));
                    }
                }
            }

            return new RenderSnapshot(precise, candidates, history.size());
        }

        private static CustomLocation render(List<EntityUpdate> history, World world, long renderTime) {
            if (history.isEmpty()) {
                return null;
            }

            EntityUpdate first = history.get(0);
            double renderedX = first.x;
            double renderedY = first.y;
            double renderedZ = first.z;
            double targetX = first.x;
            double targetY = first.y;
            double targetZ = first.z;
            long targetTime = first.timestamp;

            if (renderTime <= targetTime) {
                return location(world, renderedX, renderedY, renderedZ, renderTime);
            }

            for (int i = 1; i < history.size(); i++) {
                EntityUpdate next = history.get(i);

                if (next.timestamp > renderTime) {
                    break;
                }

                double amount = interpolationAmount(next.timestamp - targetTime);
                renderedX += (targetX - renderedX) * amount;
                renderedY += (targetY - renderedY) * amount;
                renderedZ += (targetZ - renderedZ) * amount;

                targetX = next.x;
                targetY = next.y;
                targetZ = next.z;
                targetTime = next.timestamp;
            }

            double amount = interpolationAmount(renderTime - targetTime);
            renderedX += (targetX - renderedX) * amount;
            renderedY += (targetY - renderedY) * amount;
            renderedZ += (targetZ - renderedZ) * amount;

            return location(world, renderedX, renderedY, renderedZ, renderTime);
        }

        private static double interpolationAmount(long elapsed) {
            return Math.max(0.0D, Math.min(1.0D, elapsed / (double) INTERPOLATION_TIME_MS));
        }

        private static CustomLocation location(World world, double x, double y, double z, long timestamp) {
            return new CustomLocation(world, x, y, z, 0.0F, 0.0F, timestamp);
        }

        private static void addCandidate(List<CustomLocation> candidates, CustomLocation location) {
            if (location != null) {
                candidates.add(location);
            }
        }
    }

    private static final class EntityUpdate {
        private final double x;
        private final double y;
        private final double z;
        private final long timestamp;
        private boolean applied;

        private EntityUpdate(double x, double y, double z, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timestamp = timestamp;
        }
    }
}
