package me.arrow.playerdata.data.impl.worldcomp;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.customutils.EventTimer;
import me.arrow.utils.customutils.EvictingList;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// this is a GPT improved processor from MrPlugin, it syncs ghost blocks (does not work on 1.8)
// and properly accounts for world guard blocks, so it really helps fix alot of bugs with ghost blocks
// such as the piston glitch i found where if you place a block by spamming on a piston that's moving with 1 extra tick delay
// you can make any block become a ghost block
// this will sync any ghost blocks near you, but it is probably gonna cause lag on slower servers with 80+ players
// but this is really really good and works almost flawlessly on 1.21.11

@Getter
public class BlockProcessor implements Data {

    static volatile Method cachedGetBlockDataMethod;
    static volatile Method cachedModernSendBlockChangeMethod;
    static volatile Method cachedLegacySendBlockChangeMethod;
    static volatile Method cachedLegacyGetDataMethod;
    static volatile Method cachedLegacyGetStateMethod;
    static volatile Method cachedLegacyGetRawDataMethod;

    static volatile boolean modernGetBlockDataLookupDone;
    static volatile boolean modernSendBlockChangeLookupDone;
    static volatile boolean legacySendBlockChangeLookupDone;
    static volatile boolean legacyGetDataLookupDone;
    static volatile boolean legacyGetStateLookupDone;
    static volatile boolean legacyGetRawDataLookupDone;

    static final int CLEARED_GHOST_CONTEXT_TICK = 100;
    static final int PLACE_CONFIRMATION_GRACE_TICKS = 4;
    static final int PHYSICS_PLACE_CONFIRMATION_GRACE_TICKS = 10;
    int RECENT_PLACE_BLOCK_CHANGE_GRACE_TICKS = 8;
    static final double CANCELLED_PHYSICS_GHOST_RANGE_XZ = 3.0D;
    static final double CANCELLED_PHYSICS_GHOST_RANGE_Y = 3.0D;

    static final int MAX_GHOST_BLOCK_TICKS = 20 * 8;
    static final int MAX_CANCELLED_PLACE_GHOST_TICKS = 20;
    static final int MAX_CLICK_INTERACTION_GHOST_TICKS = 10;

    static final int GHOST_SYNC_COOLDOWN_TICKS = 8;
    static final int GHOST_INTERACTION_AREA_SYNC_COOLDOWN_TICKS = 4;
    static final int AREA_SYNC_COOLDOWN_TICKS = 1;
    static final int MAX_AREA_SYNC_BLOCKS = 32;
    static final int MAX_SYNC_BLOCKS_PER_FLUSH = 4;
    static final int SELF_SYNC_IGNORE_TICKS = 3;
    static final int CANCELLED_PHYSICS_CONTEXT_TICKS = 40;


    int lastGhostInteractionAreaSyncTick = 100;
    int lastAreaSyncTick = 100;

    final Profile data;
    final EventTimer lastConfirmedBlockPlaceTimer;
    final EventTimer lastConfirmedCancelPlaceTimer;
    final EventTimer lastPlacementPacket;

    final Deque<GhostBlock> ghostBlocks = new EvictingList<>(200);

    final Object ghostBlockLock = new Object();
    final Map<Long, Integer> selfSyncedBlocks = new ConcurrentHashMap<>();
    final Object syncQueueLock = new Object();
    final Map<Long, SyncBlock> queuedSyncBlocks = new LinkedHashMap<>();
    volatile boolean syncFlushQueued;

    Material materialPlaced;
    int blockUpdateTicks;
    boolean hasPlacedBlock = false, recentC2SPacket = false;
    Vector currentBlockCords;
    Material blockPlaceMaterial;
    Material main;
    //    Material lastBlockChangeMaterial, lastBlockChangeMultiMaterial;
    int placeTicks;
    int lastWebUpdateTick;
    int face;
    int lastGhostBlockTick = 100;
    int lastGhostLiquidWebTick = 100;
    int lastPendingPhysicsPlaceTick = 100;
    int recentPlacePacketTicks = 100;
    Vector recentPlaceVector;
    Material recentPlaceMaterial;
    Vector lastClickedBlockVector;
    Material lastClickedBlockMaterial;
    int lastPlacementFace = -1;

    boolean pendingVineLadderWallPlace;
    int pendingVineLadderWallTick;
    Vector pendingVineLadderWallVector;
    Material pendingVineLadderWallMaterial;

    int cancelledPhysicsContextTicks;
    Vector cancelledPhysicsContextVector;
    Material cancelledPhysicsContextMaterial;

    boolean nearGhostBlock;
    boolean onGhostBlock;
    boolean insideGhostBlock;
    boolean underGhostBlock;
    boolean interactingGhostBlock;

    @Setter
    boolean autoCorrectGhostBlocks = true;

    double distanceFromUpdate, distanceFromUpdateMulti;

    Material lastAttemptedPlaceMaterial;
    int pendingPlacementTicks;

    public BlockProcessor(Profile user) {
        // Creates the per-player block processor and initializes placement confirmation timers.
        this.data = user;
        this.lastConfirmedBlockPlaceTimer = new EventTimer(20, user);
        this.lastConfirmedCancelPlaceTimer = new EventTimer(20, user);
        this.lastPlacementPacket = new EventTimer(20, user);
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        // Handles client packets relevant to block placement and movement ticking.
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            handleBlockPlacePacket(event);
        }

        if (isMovement(event)) {
            handleMovementPacket(event);
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        // Handles server block update packets so stale ghost entries can be cleared or repaired.
        if (event.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleMultiBlockChange(event);
        }

        if (event.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleBlockChange(event);
        }
    }

    void handleBlockPlacePacket(PacketReceiveEvent event) {
        // Stores a possible client-side placement attempt while ignoring pure right-click interactions.
        WrapperPlayClientPlayerBlockPlacement wrapped = new WrapperPlayClientPlayerBlockPlacement(event);

        int x = wrapped.getBlockPosition().getX();
        int y = wrapped.getBlockPosition().getY();
        int z = wrapped.getBlockPosition().getZ();

        if (x == -1 && y == -1 && z == -1) {
            return;
        }

        this.lastPlacementPacket.reset();

        int faceValue;

        try {
            faceValue = wrapped.getFace().getFaceValue();
        } catch (Throwable ignored) {
            faceValue = -1;
        }

        if (faceValue < 0 || faceValue > 5) {
            return;
        }

        Material clickedServerMaterial = getServerMaterial(x, y, z);
        Material rawAttemptedMaterial = resolveRawAttemptedMaterial(wrapped);
        Material attemptedMaterial = resolveAttemptedMaterial(wrapped);

        Vector clickedVector = new Vector(x, y, z);
        Vector placedVector = resolvePlacedVector(clickedVector, clickedServerMaterial, attemptedMaterial, faceValue);

        this.lastClickedBlockVector = clickedVector;
        this.lastClickedBlockMaterial = clickedServerMaterial;
        this.lastPlacementFace = faceValue;

        boolean knownGhostNearInteraction =
                isKnownGhostNear(clickedVector, 2.25D)
                        || isKnownGhostNear(placedVector, 2.25D)
                        || isPlayerNearAnyGhost();

        if (knownGhostNearInteraction && autoCorrectGhostBlocks) {
            syncGhostInteractionArea(clickedVector, placedVector);
        }

        if (rawAttemptedMaterial == null || attemptedMaterial == null) {
            clearPendingPlacement();
            return;
        }

        if (isIgnoredNoHitboxGhostMaterial(rawAttemptedMaterial)) {
            clearPendingPlacement();
            syncSmallInteractionArea(clickedVector, placedVector);
            return;
        }

        if (isInteractionOnlyClick(clickedServerMaterial, rawAttemptedMaterial, faceValue)) {
            clearPendingPlacement();
            syncSmallInteractionArea(clickedVector, placedVector);
            return;
        }

        if (!isPhysicsPlacementNearPlayer(clickedVector, placedVector)
                && isCancelledPhysicsContextMaterial(attemptedMaterial)) {
            clearPendingPlacement();
            return;
        }

        if (isVineOrLadderPlacement(attemptedMaterial) && faceValue == 1) {
            clearPendingPlacement();
            this.pendingVineLadderWallPlace = true;
            this.pendingVineLadderWallTick = 0;
            this.pendingVineLadderWallVector = placedVector;
            this.pendingVineLadderWallMaterial = attemptedMaterial;
            this.lastPendingPhysicsPlaceTick = 0;
            return;
        }

        this.face = faceValue;
        this.materialPlaced = clickedServerMaterial;
        this.currentBlockCords = placedVector;
        this.blockPlaceMaterial = attemptedMaterial;
        this.lastAttemptedPlaceMaterial = attemptedMaterial;
        this.pendingPlacementTicks = 0;
        this.placeTicks++;

        if (isCancelledPhysicsContextMaterial(attemptedMaterial)
                && isPhysicsPlacementNearPlayer(clickedVector, placedVector)) {
            // Start the simple WorldGuard/protection physics grace only after interaction-only clicks
            // have been filtered out. Lever/button/chest clicks must not reach this point.
            markPendingPhysicsPlacementContext(placedVector, attemptedMaterial);
        }

        this.hasPlacedBlock = this.recentC2SPacket;
        this.recentPlacePacketTicks = 0;
        this.recentPlaceVector = placedVector;
        this.recentPlaceMaterial = attemptedMaterial;
    }


    void handleMovementPacket(PacketReceiveEvent event) {
        // Advances timers, refreshes ghost contact state, confirms pending placements, and drains queued sync packets.
        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);

        this.blockUpdateTicks++;
        this.lastWebUpdateTick++;
        this.lastGhostLiquidWebTick++;
        this.lastPendingPhysicsPlaceTick++;
        this.lastGhostBlockTick++;
        this.lastGhostInteractionAreaSyncTick++;
        this.lastAreaSyncTick++;
        this.recentPlacePacketTicks++;

        ageGhostBlocks();
        ageSelfSyncedBlocks();
        scheduleSyncFlush();
        handlePendingVineLadderWallPlace();
        refreshCancelledPhysicsPlacementContext();

        this.recentC2SPacket = flying.hasRotationChanged() && !flying.hasPositionChanged();

        updateGhostBlockContact();

        keepPendingPlacementPhysicsExemption();

        if (autoCorrectGhostBlocks
                && this.interactingGhostBlock
                && this.lastGhostInteractionAreaSyncTick > GHOST_INTERACTION_AREA_SYNC_COOLDOWN_TICKS) {
            syncRealBlocksAroundPlayer(1, 1, 1);
            this.lastGhostInteractionAreaSyncTick = 0;
        }

        if (this.currentBlockCords != null && this.blockPlaceMaterial != null) {
            this.pendingPlacementTicks++;

            TaskUtils.player(data.getPlayer(), () -> {
                if (this.currentBlockCords == null || this.blockPlaceMaterial == null) {
                    return;
                }

                Vector placeVector = this.currentBlockCords;
                Vector clickedVector = this.lastClickedBlockVector;
                Material attempted = this.blockPlaceMaterial;
                Material confirmedMaterial = findConfirmedPlacedMaterial(placeVector, clickedVector, attempted);

                if (confirmedMaterial != null) {
                    this.main = confirmedMaterial;

                    clearConfirmedPlacementGhostContext(placeVector, attempted, confirmedMaterial);

                    this.blockPlaceMaterial = null;
                    return;
                }

                int requiredGrace = isCancelledPhysicsContextMaterial(attempted)
                        ? PHYSICS_PLACE_CONFIRMATION_GRACE_TICKS
                        : PLACE_CONFIRMATION_GRACE_TICKS;

                if (this.pendingPlacementTicks < requiredGrace) {
                    return;
                }

                this.blockPlaceMaterial = null;
            });
        }

        if (this.main != null) {
            Material confirmedServerMaterial = this.main;
            Material attempted = this.lastAttemptedPlaceMaterial;
            Vector vector = this.currentBlockCords;

            clearConfirmedPlacementGhostContext(vector, attempted, confirmedServerMaterial);

            this.lastConfirmedBlockPlaceTimer.reset();
            this.placeTicks = 0;
            this.pendingPlacementTicks = 0;
            this.main = null;
            this.lastAttemptedPlaceMaterial = null;
            this.currentBlockCords = null;
            return;
        }

        if (this.lastAttemptedPlaceMaterial != null
                && this.currentBlockCords != null
                && this.blockPlaceMaterial == null) {

            Material attempted = this.lastAttemptedPlaceMaterial;
            Vector vector = this.currentBlockCords;
            Material confirmedMaterial = findConfirmedPlacedMaterial(vector, this.lastClickedBlockVector, attempted);

            if (confirmedMaterial != null) {
                clearConfirmedPlacementGhostContext(vector, attempted, confirmedMaterial);

                this.lastConfirmedBlockPlaceTimer.reset();
                this.placeTicks = 0;
                this.pendingPlacementTicks = 0;
                this.main = null;
                this.lastAttemptedPlaceMaterial = null;
                this.currentBlockCords = null;
                return;
            }

            Material serverMaterial = getServerMaterial(vector);

            if (!isSamePlacedMaterial(serverMaterial, attempted)) {
                this.lastConfirmedCancelPlaceTimer.reset();

                boolean physicsContext = isCancelledPhysicsContextMaterial(attempted);
                boolean nearCancelledPlacement = isPlayerNearPlacement(vector, CANCELLED_PHYSICS_GHOST_RANGE_XZ, CANCELLED_PHYSICS_GHOST_RANGE_Y);

                if (!isTrackableGhostMaterial(attempted) && !physicsContext) {
                    if (autoCorrectGhostBlocks && nearCancelledPlacement) {
                        syncCancelledPlacementArea(vector);
                    }

                    this.lastAttemptedPlaceMaterial = null;
                    this.currentBlockCords = null;
                    this.pendingPlacementTicks = 0;
                    return;
                }

                if (nearCancelledPlacement || !physicsContext) {
                    addGhostBlock(vector, attempted, "placement-cancelled-or-mismatch", MAX_CANCELLED_PLACE_GHOST_TICKS);
                }

                boolean on = isPlayerStandingOnGhost(vector);
                boolean inside = isPlayerInsideGhost(vector);
                boolean under = isPlayerUnderGhost(vector);
                boolean contact = on || inside || under;

                if (physicsContext && nearCancelledPlacement) {
                    beginCancelledPhysicsPlacementContext(vector, attempted);
                }

                if (contact) {
                    this.lastGhostBlockTick = 0;
                    this.nearGhostBlock = true;
                    this.interactingGhostBlock = true;
                    this.onGhostBlock = this.onGhostBlock || on;
                    this.insideGhostBlock = this.insideGhostBlock || inside;
                    this.underGhostBlock = this.underGhostBlock || under;

                    if (physicsContext) {
                        this.lastGhostLiquidWebTick = 0;
                    }
                }

                if (autoCorrectGhostBlocks && nearCancelledPlacement) {
                    syncCancelledPlacementArea(vector);
                }
            }

            this.lastAttemptedPlaceMaterial = null;
            this.currentBlockCords = null;
            this.pendingPlacementTicks = 0;
        }
    }


    void keepPendingPlacementPhysicsExemption() {
        // Keeps Fly/Gravity checks exempt while a nearby physics placement is waiting for server confirmation.
        if (this.currentBlockCords == null || this.blockPlaceMaterial == null) {
            return;
        }

        Material attempted = this.blockPlaceMaterial;

        if (!isCancelledPhysicsContextMaterial(attempted)) {
            return;
        }

        if (!isPlayerNearPlacement(this.currentBlockCords, CANCELLED_PHYSICS_GHOST_RANGE_XZ, CANCELLED_PHYSICS_GHOST_RANGE_Y)) {
            return;
        }

        markPendingPhysicsPlacementContext(this.currentBlockCords, attempted);
    }

    void markPendingPhysicsPlacementContext(Vector vector, Material material) {
        // Physics placements are client-predicted immediately, so movement checks must exempt while confirmation is pending.
        this.lastPendingPhysicsPlaceTick = 0;
        this.lastGhostLiquidWebTick = 0;
        this.lastGhostBlockTick = 0;

        if (vector != null) {
            this.nearGhostBlock = true;
            this.interactingGhostBlock = true;
        }
    }

    void beginCancelledPhysicsPlacementContext(Vector vector, Material material) {
        // A nearby physics placement was not accepted by the server, so keep liquid/web-style exemptions alive for a short fixed grace.
        this.cancelledPhysicsContextTicks = CANCELLED_PHYSICS_CONTEXT_TICKS;
        this.cancelledPhysicsContextVector = vector != null ? vector.clone() : null;
        this.cancelledPhysicsContextMaterial = material;
        this.lastGhostLiquidWebTick = 0;
        this.lastPendingPhysicsPlaceTick = 0;
        this.lastGhostBlockTick = 0;
        this.nearGhostBlock = true;
        this.interactingGhostBlock = true;
    }

    void refreshCancelledPhysicsPlacementContext() {
        // Keeps cancelled water/lava/web/vine/etc. placements exempt even if the player is only affected by client-side fluid physics.
        if (this.cancelledPhysicsContextTicks <= 0) {
            return;
        }

        this.cancelledPhysicsContextTicks--;

        if (this.cancelledPhysicsContextVector != null
                && !isPlayerNearPlacement(this.cancelledPhysicsContextVector, CANCELLED_PHYSICS_GHOST_RANGE_XZ + 0.75D, CANCELLED_PHYSICS_GHOST_RANGE_Y + 1.0D)) {
            return;
        }

        this.lastGhostLiquidWebTick = 0;
        this.lastPendingPhysicsPlaceTick = 0;
        this.lastGhostBlockTick = 0;
        this.nearGhostBlock = true;
        this.interactingGhostBlock = true;
    }

    boolean isPhysicsPlacementNearPlayer(Vector clickedVector, Vector placedVector) {
        // Uses both clicked and predicted placed coordinates, because buckets/vines can affect the client from either point.
        return isPlayerNearPlacement(placedVector, CANCELLED_PHYSICS_GHOST_RANGE_XZ, CANCELLED_PHYSICS_GHOST_RANGE_Y)
                || isPlayerNearPlacement(clickedVector, CANCELLED_PHYSICS_GHOST_RANGE_XZ, CANCELLED_PHYSICS_GHOST_RANGE_Y);
    }

    void clearConfirmedPlacementGhostContext(Vector vector, Material attemptedMaterial, Material serverMaterial) {
        // Clears ghost state when the server really accepted the placement.
        if (vector != null) {
            removeGhostBlock(vector);
        }

        this.cancelledPhysicsContextTicks = 0;
        this.cancelledPhysicsContextVector = null;
        this.cancelledPhysicsContextMaterial = null;

        boolean physics =
                isPhysicsPlacementMaterial(attemptedMaterial)
                        || isPhysicsPlacementMaterial(serverMaterial)
                        || isLiquidMaterial(attemptedMaterial)
                        || isLiquidMaterial(serverMaterial);

        if (!physics) {
            return;
        }

        this.lastGhostLiquidWebTick = CLEARED_GHOST_CONTEXT_TICK;
        this.lastPendingPhysicsPlaceTick = CLEARED_GHOST_CONTEXT_TICK;
        this.lastGhostBlockTick = CLEARED_GHOST_CONTEXT_TICK;

        if (!hasActiveGhostContact()) {
            this.nearGhostBlock = false;
            this.onGhostBlock = false;
            this.insideGhostBlock = false;
            this.underGhostBlock = false;
            this.interactingGhostBlock = false;
        }
    }

    boolean isPhysicsPlacementMaterial(Material material) {
        // Returns true for materials/items that can alter movement physics when client-predicted.
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.contains("WATER")
                || name.contains("LAVA")
                || name.contains("WATER_BUCKET")
                || name.contains("LAVA_BUCKET")
                || name.contains("POWDER_SNOW_BUCKET")
                || name.contains("WEB")
                || name.contains("COBWEB")
                || name.contains("HONEY")
                || name.contains("SLIME")
                || name.contains("POWDER_SNOW")
                || name.contains("BUBBLE")
                || name.contains("ICE")
                || name.contains("SOUL_SAND")
                || name.contains("LADDER")
                || name.contains("VINE")
                || name.contains("VINES")
                || name.contains("CAVE_VINES")
                || name.contains("WEEPING_VINES")
                || name.contains("TWISTING_VINES")
                || name.contains("GLOW_BERRIES")
                || name.contains("SCAFFOLDING")
                || name.contains("COD_BUCKET")
                || name.contains("SALMON_BUCKET")
                || name.contains("TROPICAL_FISH_BUCKET")
                || name.contains("PUFFERFISH_BUCKET")
                || name.contains("AXOLOTL_BUCKET")
                || name.contains("TADPOLE_BUCKET");
    }

    boolean hasActiveGhostContact() {
        // Checks whether any stored ghostblock is still touching the player hitbox.
        synchronized (ghostBlockLock) {
            if (ghostBlocks.isEmpty()) {
                return false;
            }

            for (GhostBlock ghost : ghostBlocks) {
                if (ghost == null || ghost.position == null) {
                    continue;
                }

                if (isPlayerStandingOnGhost(ghost.position)
                        || isPlayerInsideGhost(ghost.position)
                        || isPlayerUnderGhost(ghost.position)) {
                    return true;
                }
            }
        }

        return false;
    }
    Material findConfirmedPlacedMaterial(Vector placeVector, Vector clickedVector, Material attempted) {
        // Looks at the exact predicted placement targets to see whether the server accepted it.
        if (attempted == null) {
            return null;
        }

        Material direct = getServerMaterial(placeVector);

        if (isSamePlacedMaterial(direct, attempted)) {
            return direct;
        }

        Material clicked = getServerMaterial(clickedVector);

        if (isSamePlacedMaterial(clicked, attempted)) {
            return clicked;
        }

        /*
         * Do not loose-scan around water/lava/web/vine/honey/etc. cancelled placement
         * attempts. WorldGuard/protection cancels leave the target as air, and a nearby
         * existing water/vine/honey block must not be mistaken as confirmation.
         */
        isCancelledPhysicsContextMaterial(attempted);

        return null;
    }

    void handleMultiBlockChange(PacketSendEvent event) {
        // Processes multi-block server updates and queues tiny repairs for relevant nearby changes.
        WrapperPlayServerMultiBlockChange multiBlockChange = new WrapperPlayServerMultiBlockChange(event);

        CustomLocation current = data.getMovementData().getLocation();

        if (current == null) {
            return;
        }

        int repaired = 0;

        for (WrapperPlayServerMultiBlockChange.EncodedBlock blockData : multiBlockChange.getBlocks()) {
            if (repaired >= 8) {
                return;
            }

            int x = blockData.getX();
            int y = blockData.getY();
            int z = blockData.getZ();

            if (consumeSelfSyncedBlock(x, y, z)) {
                continue;
            }

            double dx = (x + 0.5D) - current.getX();
            double dy = (y + 0.5D) - current.getY();
            double dz = (z + 0.5D) - current.getZ();
            double distanceXZ = (dx * dx) + (dz * dz);
            Vector vector = new Vector(x, y, z);

            removeGhostBlock(vector);

            Material packetMaterial = null;

            try {
                StateType type = blockData.getBlockState(data.getVersion()).getType();
                packetMaterial = materialFromStateName(type.getName());
            } catch (Throwable ignored) {
            }

            if (packetMaterial != null && distanceXZ < 3.0D && isWebMaterial(packetMaterial)) {
                this.lastWebUpdateTick = 0;
            }

            confirmPendingVineLadderWallPlace(packetMaterial, vector, distanceXZ, dy);

            if (shouldRepairServerBlockUpdate(packetMaterial, distanceXZ, dy)) {
                syncBlockUpdatePatch(x, y, z, packetMaterial);
                repaired++;
            }
        }
    }

    void handleBlockChange(PacketSendEvent event) {
        // Processes single server block updates and queues a tiny repair only when it matters.
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(event);

        CustomLocation current = data.getMovementData().getLocation();

        if (current == null) {
            return;
        }

        int x = blockChange.getBlockPosition().getX();
        int y = blockChange.getBlockPosition().getY();
        int z = blockChange.getBlockPosition().getZ();

        if (consumeSelfSyncedBlock(x, y, z)) {
            return;
        }

        double dx = (x + 0.5D) - current.getX();
        double dy = (y + 0.5D) - current.getY();
        double dz = (z + 0.5D) - current.getZ();
        double distanceXZ = (dx * dx) + (dz * dz);
        Vector vector = new Vector(x, y, z);

        removeGhostBlock(vector);

        Material packetMaterial = null;

        try {
            StateType type = blockChange.getBlockState().getType();
            packetMaterial = materialFromStateName(type.getName());
        } catch (Throwable ignored) {
        }

        if (packetMaterial != null && distanceXZ < 3.0D && isWebMaterial(packetMaterial)) {
            this.lastWebUpdateTick = 0;
        }

        confirmPendingVineLadderWallPlace(packetMaterial, vector, distanceXZ, dy);

        if (shouldRepairServerBlockUpdate(packetMaterial, distanceXZ, dy)) {
            syncBlockUpdatePatch(x, y, z, packetMaterial);
        }
    }

    boolean shouldRepairServerBlockUpdate(Material packetMaterial, double distanceXZ, double deltaY) {
        // Filters server block updates so harmless interactions do not trigger repairs.
        if (distanceXZ > 36.0D || Math.abs(deltaY) > 4.0D) {
            return false;
        }

        if (packetMaterial == null || isAirLike(packetMaterial)) {
            return true;
        }

        if (isInteractiveBlock(packetMaterial) || isIgnoredNoHitboxGhostMaterial(packetMaterial)) {
            return false;
        }

        return shouldSyncServerUpdate(packetMaterial)
                || isPhysicsGhostMaterial(packetMaterial)
                || isPistonRelated(packetMaterial);
    }

    void syncBlockUpdatePatch(int x, int y, int z, Material packetMaterial) {
        // Queues the smallest useful repair patch around a server-side block update.
        if (!autoCorrectGhostBlocks) {
            return;
        }

        boolean wide = packetMaterial == null
                || isAirLike(packetMaterial)
                || isPistonRelated(packetMaterial)
                || isPhysicsGhostMaterial(packetMaterial);

        if (wide) {
            syncRealBlocksInBoxAsync(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1, 27, true);
            return;
        }

        syncRealBlocksInBoxAsync(x, y, z, x, y, z, 1, true);
    }

    boolean tryModernSendBlockChange(Player player, Block block, Location location) {
        // Uses cached reflection to call the modern Location + BlockData sendBlockChange method.
        try {
            Method getBlockData = cachedGetBlockDataMethod;

            if (getBlockData == null && !modernGetBlockDataLookupDone) {
                try {
                    getBlockData = block.getClass().getMethod("getBlockData");
                    cachedGetBlockDataMethod = getBlockData;
                } catch (Throwable ignored) {
                }

                modernGetBlockDataLookupDone = true;
            }

            if (getBlockData == null) {
                return false;
            }

            Object blockData = getBlockData.invoke(block);

            if (blockData == null) {
                return false;
            }

            Method sendBlockChange = cachedModernSendBlockChangeMethod;

            if (sendBlockChange == null && !modernSendBlockChangeLookupDone) {
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
                    sendBlockChange = method;
                    break;
                }

                modernSendBlockChangeLookupDone = true;
            }

            if (sendBlockChange == null) {
                return false;
            }

            markSelfSyncedBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            sendBlockChange.invoke(player, location, blockData);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    void tryLegacySendBlockChange(Player player, Block block, Location location) {
        // Uses cached reflection to call the old Location + Material + data sendBlockChange method.
        try {
            Method sendBlockChange = cachedLegacySendBlockChangeMethod;

            if (sendBlockChange == null && !legacySendBlockChangeLookupDone) {
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
                    sendBlockChange = method;
                    break;
                }

                legacySendBlockChangeLookupDone = true;
            }

            if (sendBlockChange == null) {
                return;
            }

            markSelfSyncedBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            sendBlockChange.invoke(player, location, block.getType(), getLegacyBlockData(block));
        } catch (Throwable ignored) {
        }
    }

    byte getLegacyBlockData(Block block) {
        // Reads legacy block metadata for 1.8-style sendBlockChange support.
        try {
            Method getData = cachedLegacyGetDataMethod;

            if (getData == null && !legacyGetDataLookupDone) {
                try {
                    getData = block.getClass().getMethod("getData");
                    cachedLegacyGetDataMethod = getData;
                } catch (Throwable ignored) {
                }

                legacyGetDataLookupDone = true;
            }

            if (getData != null) {
                Object value = getData.invoke(block);

                if (value instanceof Number) {
                    return ((Number) value).byteValue();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Method getState = cachedLegacyGetStateMethod;

            if (getState == null && !legacyGetStateLookupDone) {
                try {
                    getState = block.getClass().getMethod("getState");
                    cachedLegacyGetStateMethod = getState;
                } catch (Throwable ignored) {
                }

                legacyGetStateLookupDone = true;
            }

            if (getState == null) {
                return 0;
            }

            Object state = getState.invoke(block);

            if (state == null) {
                return 0;
            }

            Method getRawData = cachedLegacyGetRawDataMethod;

            if (getRawData == null && !legacyGetRawDataLookupDone) {
                try {
                    getRawData = state.getClass().getMethod("getRawData");
                    cachedLegacyGetRawDataMethod = getRawData;
                } catch (Throwable ignored) {
                }

                legacyGetRawDataLookupDone = true;
            }

            if (getRawData == null) {
                return 0;
            }

            Object value = getRawData.invoke(state);

            if (value instanceof Number) {
                return ((Number) value).byteValue();
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    void updateGhostBlockContact() {
        // Updates near/on/inside/under ghostblock flags and queues small repairs on contact.
        this.nearGhostBlock = false;
        this.onGhostBlock = false;
        this.insideGhostBlock = false;
        this.underGhostBlock = false;
        this.interactingGhostBlock = false;

        CustomLocation location = data.getMovementData().getLocation();

        if (location == null) {
            return;
        }

        synchronized (ghostBlockLock) {
            if (ghostBlocks.isEmpty()) {
                return;
            }

            for (GhostBlock ghost : ghostBlocks) {
                boolean near = isPlayerCloseTo(ghost.position, 2.25D, 2.75D);
                boolean on = isPlayerStandingOnGhost(ghost.position);
                boolean inside = isPlayerInsideGhost(ghost.position);
                boolean under = isPlayerUnderGhost(ghost.position);

                boolean contact = on || inside || under;

                if (near) {
                    this.nearGhostBlock = true;

                    /*
                     * Nearby ghosts should be synced, but they must not refresh
                     * lastGhostBlockTick / lastGhostLiquidWebTick.
                     */
                    if (autoCorrectGhostBlocks && ghost.syncCooldownTicks <= 0) {
                        syncRealBlockToClient(ghost.position);

                        ghost.syncCooldownTicks = GHOST_SYNC_COOLDOWN_TICKS;
                    }
                }

                if (contact) {
                    this.nearGhostBlock = true;
                    this.interactingGhostBlock = true;
                    this.lastGhostBlockTick = 0;

                    ghost.interactionTicks++;

                    if (isPhysicsGhostMaterial(ghost.material)) {
                        this.lastGhostLiquidWebTick = 0;
                    }

                    if (autoCorrectGhostBlocks && ghost.syncCooldownTicks <= 0) {
                        syncRealBlockToClient(ghost.position);
                        syncRealBlockToClient(ghost.position.clone().add(new Vector(0, 1, 0)));
                        syncRealBlockToClient(ghost.position.clone().add(new Vector(0, -1, 0)));


                        ghost.syncCooldownTicks = GHOST_SYNC_COOLDOWN_TICKS;
                    }
                }

                if (on) {
                    this.onGhostBlock = true;
                }

                if (inside) {
                    this.insideGhostBlock = true;
                }

                if (under) {
                    this.underGhostBlock = true;
                }
            }
        }
    }

    void addGhostBlock(Vector position, Material material, String reason) {
        // Stores a client-only/cancelled-placement block that may affect movement.
        addGhostBlock(position, material, reason, resolveGhostMaxTicks(reason));
    }

    void addGhostBlock(Vector position, Material material, String reason, int maxTicks) {
        // Stores a client-only/cancelled-placement block that may affect movement.
        if (position == null) {
            return;
        }

        int x = position.getBlockX();
        int y = position.getBlockY();
        int z = position.getBlockZ();

        synchronized (ghostBlockLock) {
            Iterator<GhostBlock> iterator = ghostBlocks.iterator();

            while (iterator.hasNext()) {
                GhostBlock ghost = iterator.next();

                if (sameBlock(ghost.position, x, y, z)) {
                    iterator.remove();
                    break;
                }
            }

            ghostBlocks.add(new GhostBlock(new Vector(x, y, z), material, reason, Math.max(1, maxTicks)));
        }
    }

    int resolveGhostMaxTicks(String reason) {
        // Chooses how long a stored ghostblock should remain tracked.
        if (reason == null) {
            return MAX_GHOST_BLOCK_TICKS;
        }

        if (reason.contains("placement-cancelled")) {
            return MAX_CANCELLED_PLACE_GHOST_TICKS;
        }

        if (reason.contains("client-interacted")) {
            return MAX_CLICK_INTERACTION_GHOST_TICKS;
        }

        return MAX_GHOST_BLOCK_TICKS;
    }

    void removeGhostBlock(Vector position) {
        // Removes stored ghostblocks at the supplied block coordinates.
        if (position == null) {
            return;
        }

        int x = position.getBlockX();
        int y = position.getBlockY();
        int z = position.getBlockZ();

        synchronized (ghostBlockLock) {
            Iterator<GhostBlock> iterator = ghostBlocks.iterator();

            while (iterator.hasNext()) {
                GhostBlock ghost = iterator.next();

                if (sameBlock(ghost.position, x, y, z)) {
                    iterator.remove();
                }
            }
        }
    }
    Material materialFromStateName(String stateName) {
        // Converts a PacketEvents state name into a Bukkit Material when possible.
        if (stateName == null || stateName.isEmpty()) {
            return null;
        }

        return matchMaterialCompat(stateName);
    }

    boolean isWebMaterial(Material material) {
        // Detects web/cobweb-style materials for web update timers.
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.equals("COBWEB")
                || name.contains("WEB");
    }

    void ageGhostBlocks() {
        // Ages stored ghostblocks and removes stale or server-confirmed entries.
        synchronized (ghostBlockLock) {
            if (ghostBlocks.isEmpty()) {
                return;
            }

            Iterator<GhostBlock> iterator = ghostBlocks.iterator();

            while (iterator.hasNext()) {
                GhostBlock ghost = iterator.next();

                ghost.ticks++;

                if (ghost.syncCooldownTicks > 0) {
                    ghost.syncCooldownTicks--;
                }

                Material serverMaterial = getServerMaterial(ghost.position);

                /*
                 * If the server now actually has the attempted material, it is no longer
                 * a ghostblock.
                 */
                if (isSamePlacedMaterial(serverMaterial, ghost.material)) {
                    iterator.remove();
                    continue;
                }

                /*
                 * If this was a WG/cancelled placement and the player is no longer in
                 * actual contact with it, clear it quickly. It only exists to sync the
                 * client correction, not to poison the area.
                 */
                boolean cancelledPlacement =
                        ghost.reason != null
                                && (ghost.reason.contains("placement-cancelled")
                                || ghost.reason.contains("client-interacted-server-air"));

                boolean contact =
                        isPlayerStandingOnGhost(ghost.position)
                                || isPlayerInsideGhost(ghost.position)
                                || isPlayerUnderGhost(ghost.position);

                if (cancelledPlacement && !contact && ghost.ticks > ghost.maxTicks) {
                    iterator.remove();
                    continue;
                }

                if (!contact && isAirLike(serverMaterial) && ghost.interactionTicks <= 0 && ghost.ticks > ghost.maxTicks) {
                    iterator.remove();
                    continue;
                }

                if (ghost.ticks > ghost.maxTicks) {
                    iterator.remove();
                }
            }
        }
    }

    boolean isPlayerStandingOnGhost(Vector block) {
        // Checks whether the player feet are standing on a stored ghost block.
        CustomLocation loc = data.getMovementData().getLocation();

        if (loc == null || block == null) {
            return false;
        }

        double playerMinX = loc.getX() - 0.3001D;
        double playerMaxX = loc.getX() + 0.3001D;
        double playerMinZ = loc.getZ() - 0.3001D;
        double playerMaxZ = loc.getZ() + 0.3001D;

        double blockMinX = block.getBlockX();
        double blockMaxX = block.getBlockX() + 1.0D;
        double blockMinZ = block.getBlockZ();
        double blockMaxZ = block.getBlockZ() + 1.0D;

        boolean horizontalOverlap =
                playerMaxX > blockMinX + 0.001D
                        && playerMinX < blockMaxX - 0.001D
                        && playerMaxZ > blockMinZ + 0.001D
                        && playerMinZ < blockMaxZ - 0.001D;

        if (!horizontalOverlap) {
            return false;
        }

        double feetY = loc.getY();
        double blockTop = block.getBlockY() + 1.0D;

        return feetY >= blockTop - 0.075D
                && feetY <= blockTop + 0.075D;
    }

    boolean isPlayerInsideGhost(Vector block) {
        // Checks whether the player body intersects a stored ghost block.
        CustomLocation loc = data.getMovementData().getLocation();

        if (loc == null || block == null) {
            return false;
        }

        double playerMinX = loc.getX() - 0.3001D;
        double playerMaxX = loc.getX() + 0.3001D;
        double playerMinY = loc.getY() + 0.001D;
        double playerMaxY = loc.getY() + 1.799D;
        double playerMinZ = loc.getZ() - 0.3001D;
        double playerMaxZ = loc.getZ() + 0.3001D;

        double blockMinX = block.getBlockX();
        double blockMaxX = block.getBlockX() + 1.0D;
        double blockMinY = block.getBlockY();
        double blockMaxY = block.getBlockY() + 1.0D;
        double blockMinZ = block.getBlockZ();
        double blockMaxZ = block.getBlockZ() + 1.0D;

        return playerMaxX > blockMinX + 0.001D
                && playerMinX < blockMaxX - 0.001D
                && playerMaxY > blockMinY + 0.001D
                && playerMinY < blockMaxY - 0.001D
                && playerMaxZ > blockMinZ + 0.001D
                && playerMinZ < blockMaxZ - 0.001D;
    }

    boolean isPlayerUnderGhost(Vector block) {
        // Checks whether the player head is touching the underside of a stored ghost block.
        CustomLocation loc = data.getMovementData().getLocation();

        if (loc == null || block == null) {
            return false;
        }

        double playerMinX = loc.getX() - 0.3001D;
        double playerMaxX = loc.getX() + 0.3001D;
        double playerMinZ = loc.getZ() - 0.3001D;
        double playerMaxZ = loc.getZ() + 0.3001D;

        double blockMinX = block.getBlockX();
        double blockMaxX = block.getBlockX() + 1.0D;
        double blockMinZ = block.getBlockZ();
        double blockMaxZ = block.getBlockZ() + 1.0D;

        boolean horizontalOverlap =
                playerMaxX > blockMinX + 0.001D
                        && playerMinX < blockMaxX - 0.001D
                        && playerMaxZ > blockMinZ + 0.001D
                        && playerMinZ < blockMaxZ - 0.001D;

        if (!horizontalOverlap) {
            return false;
        }

        double headY = loc.getY() + 1.8D;
        double blockBottom = block.getBlockY();

        return headY >= blockBottom - 0.075D
                && headY <= blockBottom + 0.125D;
    }

    boolean isPlayerCloseTo(Vector block, double horizontal, double vertical) {
        // Performs cheap block-distance proximity checks around the player.
        CustomLocation loc = data.getMovementData().getLocation();

        if (loc == null || block == null) {
            return false;
        }

        double px = loc.getX();
        double py = loc.getY();
        double pz = loc.getZ();

        double bx = block.getBlockX() + 0.5D;
        double by = block.getBlockY() + 0.5D;
        double bz = block.getBlockZ() + 0.5D;

        return Math.abs(px - bx) <= horizontal
                && Math.abs(pz - bz) <= horizontal
                && Math.abs(py - by) <= vertical;
    }

    boolean isSamePlacedMaterial(Material serverMaterial, Material attemptedMaterial) {
        // Compares attempted placement material/item against the real server material family.
        if (serverMaterial == null || attemptedMaterial == null) {
            return false;
        }

        if (serverMaterial == attemptedMaterial) {
            return true;
        }

        String server = serverMaterial.name();
        String attempted = attemptedMaterial.name();

        if (server.contains("WATER") && isWaterPlacementMaterial(attemptedMaterial)) {
            return true;
        }

        if (server.contains("LAVA") && isLavaPlacementMaterial(attemptedMaterial)) {
            return true;
        }

        if (server.contains("POWDER_SNOW") && (attempted.contains("POWDER_SNOW") || attempted.contains("POWDER_SNOW_BUCKET"))) {
            return true;
        }

        if ((server.equals("COBWEB") || server.equals("WEB"))
                && (attempted.equals("COBWEB") || attempted.equals("WEB"))) {
            return true;
        }

        if ((server.contains("CAVE_VINES") || server.contains("CAVE_VINES_PLANT"))
                && (attempted.contains("CAVE_VINES") || attempted.contains("GLOW_BERRIES"))) {
            return true;
        }

        if ((server.contains("TWISTING_VINES") || server.contains("TWISTING_VINES_PLANT"))
                && attempted.contains("TWISTING_VINES")) {
            return true;
        }

        if ((server.contains("WEEPING_VINES") || server.contains("WEEPING_VINES_PLANT"))
                && attempted.contains("WEEPING_VINES")) {
            return true;
        }

        if (server.contains("VINE") && attempted.contains("VINE")) {
            return true;
        }

        return server.equals(attempted);
    }

    boolean isPhysicsGhostMaterial(Material material) {
        // Detects blocks that can affect movement physics when desynced.
        if (material == null || material == Material.AIR) {
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
                || isClimbableGhostMaterial(material);
    }

    Material resolveAttemptedMaterial(WrapperPlayClientPlayerBlockPlacement wrapped) {
        // Resolves the attempted placement material from packet, main hand, or offhand.
        Material packetMaterial = resolvePacketMaterial(wrapped);

        if (isValidGhostAttemptMaterial(packetMaterial)) {
            return packetMaterial;
        }

        Material mainHand = getMainHandMaterial();

        if (isValidGhostAttemptMaterial(mainHand)) {
            return mainHand;
        }

        Material offHand = getOffHandMaterial();

        if (isValidGhostAttemptMaterial(offHand)) {
            return offHand;
        }

        return null;
    }

    Material resolvePacketMaterial(WrapperPlayClientPlayerBlockPlacement wrapped) {
        // Attempts to read the item material directly from the placement packet.
        try {
            if (wrapped.getItemStack().isPresent()) {
                Object type = wrapped.getItemStack().get().getType();

                Material material = matchMaterialCompat(type.toString());

                if (material != null) {
                    return material;
                }

                try {
                    Object name = type.getClass().getMethod("getName").invoke(type);

                    if (name != null) {
                        material = matchMaterialCompat(name.toString());

                        if (material != null) {
                            return material;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    Material getMainHandMaterial() {
        // Reads the player main-hand item across modern and legacy APIs.
        try {
            ItemStack stack = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(data.getPlayer());

            if (stack != null && stack.getType() != Material.AIR) {
                return stack.getType();
            }
        } catch (Throwable ignored) {
        }

        try {
            Object inventory = data.getPlayer().getInventory();
            Object stack = inventory.getClass().getMethod("getItemInMainHand").invoke(inventory);

            if (stack instanceof ItemStack item && item.getType() != Material.AIR) {
                return item.getType();
            }
        } catch (Throwable ignored) {
        }

        try {
            ItemStack stack = data.getPlayer().getItemInHand();

            if (stack.getType() != Material.AIR) {
                return stack.getType();
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    Material getOffHandMaterial() {
        // Reads the player offhand item where that API exists.
        try {
            ItemStack stack = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(data.getPlayer());

            if (stack != null && stack.getType() != Material.AIR) {
                return stack.getType();
            }
        } catch (Throwable ignored) {
        }

        try {
            Object inventory = data.getPlayer().getInventory();
            Object stack = inventory.getClass().getMethod("getItemInOffHand").invoke(inventory);

            if (stack instanceof ItemStack item && item.getType() != Material.AIR) {
                return item.getType();
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    Material matchMaterialCompat(String raw) {
        // Normalizes modern/legacy names into a Bukkit Material.
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

        if (name.contains("WATER")) {
            Material bucket = Material.matchMaterial("WATER_BUCKET");

            if (bucket != null) {
                return bucket;
            }
        }

        if (name.contains("LAVA")) {
            return Material.matchMaterial("LAVA_BUCKET");
        }

        return null;
    }

    boolean isValidGhostAttemptMaterial(Material material) {
        // Filters attempted items to only track real movement-relevant placement attempts.
        return isTrackableGhostMaterial(material) || isCancelledPhysicsContextMaterial(material);
    }

    Material resolveRawAttemptedMaterial(WrapperPlayClientPlayerBlockPlacement wrapped) {
        // Reads the raw held/packet item before collision/no-hitbox filtering.
        Material packetMaterial = resolvePacketMaterial(wrapped);

        if (packetMaterial != null && packetMaterial != Material.AIR) {
            return packetMaterial;
        }

        Material mainHand = getMainHandMaterial();

        if (mainHand != null && mainHand != Material.AIR) {
            return mainHand;
        }

        Material offHand = getOffHandMaterial();

        if (offHand != null && offHand != Material.AIR) {
            return offHand;
        }

        return null;
    }

    boolean isTrackableGhostMaterial(Material material) {
        // Returns true for block placements that can create collision-relevant ghosts.
        if (material == null || material == Material.AIR) {
            return false;
        }

        if (isIgnoredNoHitboxGhostMaterial(material)) {
            return false;
        }

        if (isCancelledPhysicsContextMaterial(material)) {
            return true;
        }

        String name = material.name();

        if (name.contains("RAIL")
                || name.contains("PISTON")
                || name.contains("PISTON_HEAD")
                || name.contains("PISTON_EXTENSION")
                || name.contains("MOVING_PISTON")) {
            return true;
        }

        return hasUsefulCollisionShape(material);
    }

    boolean isCancelledPhysicsContextMaterial(Material material) {
        // Returns true for cancelled placements that should reset liquid/web/physics timers.
        if (material == null || material == Material.AIR) {
            return false;
        }

        return isPhysicsGhostMaterial(material)
                || isWaterPlacementMaterial(material)
                || isLavaPlacementMaterial(material)
                || isAquaticBucket(material)
                || isPowderSnowPlacementMaterial(material)
                || isClimbableGhostMaterial(material);
    }

    boolean isWaterPlacementMaterial(Material material) {
        // Detects water and water-style bucket placement items.
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.contains("WATER")
                || isAquaticBucket(material);
    }

    boolean isLavaPlacementMaterial(Material material) {
        // Detects lava placement items.
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.contains("LAVA");
    }

    boolean isPowderSnowPlacementMaterial(Material material) {
        // Detects powder snow placement items.
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.contains("POWDER_SNOW");
    }

    boolean isAquaticBucket(Material material) {
        // Detects fish/axolotl/tadpole buckets as water-style physics placements.
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.contains("COD_BUCKET")
                || name.contains("SALMON_BUCKET")
                || name.contains("TROPICAL_FISH_BUCKET")
                || name.contains("PUFFERFISH_BUCKET")
                || name.contains("AXOLOTL_BUCKET")
                || name.contains("TADPOLE_BUCKET")
                || name.contains("FISH_BUCKET");
    }

    boolean isClimbableGhostMaterial(Material material) {
        // Detects ladders, vines, and scaffolding movement contexts.
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.contains("LADDER")
                || name.equals("VINE")
                || name.contains("VINES")
                || name.contains("CAVE_VINES")
                || name.contains("WEEPING_VINES")
                || name.contains("TWISTING_VINES")
                || name.contains("GLOW_BERRIES")
                || name.contains("SCAFFOLDING");
    }
    void handlePendingVineLadderWallPlace() {
        // Keeps a short pending physics exemption for vine/ladder top-face placements whose final attached block is yaw-dependent.
        if (!this.pendingVineLadderWallPlace) {
            return;
        }

        this.pendingVineLadderWallTick++;

        if (this.pendingVineLadderWallTick <= PHYSICS_PLACE_CONFIRMATION_GRACE_TICKS) {
            this.lastPendingPhysicsPlaceTick = 0;
            return;
        }

        this.pendingVineLadderWallPlace = false;
        this.pendingVineLadderWallTick = 0;
        this.pendingVineLadderWallVector = null;
        this.pendingVineLadderWallMaterial = null;
        this.lastPendingPhysicsPlaceTick = CLEARED_GHOST_CONTEXT_TICK;
    }

    void confirmPendingVineLadderWallPlace(Material packetMaterial, Vector vector, double distanceXZ, double deltaY) {
        // Clears the special vine/ladder pending state when a nearby server block update confirms it.
        if (!this.pendingVineLadderWallPlace) {
            return;
        }

        if (packetMaterial == null || !isVineOrLadderPlacement(packetMaterial)) {
            return;
        }

        if (distanceXZ > 16.0D || Math.abs(deltaY) > 4.0D) {
            return;
        }

        this.lastConfirmedBlockPlaceTimer.reset();
        this.placeTicks = 0;
        this.pendingVineLadderWallPlace = false;
        this.pendingVineLadderWallTick = 0;
        this.pendingVineLadderWallVector = null;
        this.pendingVineLadderWallMaterial = null;
        this.lastPendingPhysicsPlaceTick = CLEARED_GHOST_CONTEXT_TICK;
    }

    boolean isVineOrLadderPlacement(Material material) {
        // Detects vine/ladder-style placements that may attach to a different block than the clicked face predicts.
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.contains("LADDER")
                || name.equals("VINE")
                || name.contains("VINES")
                || name.contains("CAVE_VINES")
                || name.contains("WEEPING_VINES")
                || name.contains("TWISTING_VINES")
                || name.contains("GLOW_BERRIES");
    }

    boolean isPlayerNearPlacement(Vector block, double horizontal, double vertical) {
        // Limits cancelled-placement exemptions to blocks close to the player.
        CustomLocation loc = data.getMovementData().getLocation();

        if (loc == null || block == null) {
            return false;
        }

        double bx = block.getBlockX() + 0.5D;
        double by = block.getBlockY() + 0.5D;
        double bz = block.getBlockZ() + 0.5D;

        return Math.abs(loc.getX() - bx) <= horizontal
                && Math.abs(loc.getZ() - bz) <= horizontal
                && Math.abs(loc.getY() - by) <= vertical;
    }
    boolean hasUsefulCollisionShape(Material material) {
        // Cheaply filters materials that have useful collision for ghost tracking.
        if (material == null || material == Material.AIR) {
            return false;
        }

        if (isIgnoredNoHitboxGhostMaterial(material) || isInteractiveBlock(material)) {
            return false;
        }

        try {
            if (material.isSolid()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        String name = material.name();

        return name.contains("SLAB")
                || name.contains("STEP")
                || name.contains("STAIRS")
                || name.contains("FENCE")
                || name.contains("WALL")
                || name.contains("PANE")
                || name.contains("BARS")
                || name.contains("DOOR")
                || name.contains("TRAPDOOR")
                || name.contains("GATE")
                || name.contains("CHEST")
                || name.contains("BED")
                || name.contains("ANVIL")
                || name.contains("CACTUS")
                || name.contains("CARPET")
                || name.contains("SNOW")
                || name.contains("SCAFFOLDING")
                || name.contains("VINE")
                || name.contains("LADDER")
                || name.contains("SHULKER");
    }

    boolean isIgnoredNoHitboxGhostMaterial(Material material) {
        // Filters decorative/no-hitbox materials that should not cause ghostblock state.
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        if (isCancelledPhysicsContextMaterial(material) || isLiquidMaterial(material)) {
            return false;
        }

        if (name.contains("RAIL")) {
            return false;
        }

        return name.contains("SAPLING")
                || name.contains("FLOWER")
                || name.contains("TULIP")
                || name.contains("DANDELION")
                || name.contains("POPPY")
                || name.contains("ORCHID")
                || name.contains("ALLIUM")
                || name.contains("AZURE_BLUET")
                || name.contains("DAISY")
                || name.contains("CORNFLOWER")
                || name.contains("LILY_OF_THE_VALLEY")
                || name.contains("WITHER_ROSE")
                || name.contains("DEAD_BUSH")
                || name.contains("GRASS")
                || name.contains("FERN")
                || name.contains("SEAGRASS")
                || name.contains("KELP")
                || name.contains("MUSHROOM")
                || name.contains("ROOTS")
                || name.contains("SPROUTS")
                || name.contains("NETHER_WART")
                || name.contains("TORCH")
                || name.contains("BUTTON")
                || name.contains("LEVER")
                || name.contains("SIGN")
                || name.contains("BANNER")
                || name.contains("REDSTONE")
                || name.contains("TRIPWIRE")
                || name.contains("STRING");
    }

    void clearPendingPlacement() {
        // Clears all pending placement fields after success, cancel, or ignored interaction.
        this.currentBlockCords = null;
        this.blockPlaceMaterial = null;
        this.lastAttemptedPlaceMaterial = null;
        this.pendingPlacementTicks = 0;
        this.recentPlaceMaterial = null;
        this.recentPlaceVector = null;
        this.lastPendingPhysicsPlaceTick = CLEARED_GHOST_CONTEXT_TICK;
        this.lastClickedBlockVector = null;
        this.lastClickedBlockMaterial = null;
        this.lastPlacementFace = -1;
        this.cancelledPhysicsContextTicks = 0;
        this.cancelledPhysicsContextVector = null;
        this.cancelledPhysicsContextMaterial = null;
    }

    Vector getPlacedVector(int x, int y, int z, int faceValue) {
        // Converts clicked block + face into the predicted placed-block coordinate.
        if (faceValue == 1) {
            return new Vector(x, y + 1, z);
        }

        if (faceValue == 0) {
            return new Vector(x, y - 1, z);
        }

        if (faceValue == 4) {
            return new Vector(x - 1, y, z);
        }

        if (faceValue == 5) {
            return new Vector(x + 1, y, z);
        }

        if (faceValue == 2) {
            return new Vector(x, y, z - 1);
        }

        if (faceValue == 3) {
            return new Vector(x, y, z + 1);
        }

        return new Vector(x, y, z);
    }

    Material getServerMaterial(Vector vector) {
        // Reads the authoritative server material through the NMS abstraction.
        if (vector == null) {
            return null;
        }

        return getServerMaterial(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ());
    }

    Material getServerMaterial(int x, int y, int z) {
        Player player = data.getPlayer();

        if (player == null || !player.isOnline()) {
            return null;
        }

        World world;

        try {
            world = player.getWorld();
        } catch (Throwable ignored) {
            return null;
        }

        Location location = new Location(world, x, y, z);

        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(location)) {
            return null;
        }

        try {
            return Arrow.getInstance().getNmsManager().getNmsInstance2().getType(
                    world,
                    x,
                    y,
                    z
            );
        } catch (Throwable ignored) {
            return null;
        }
    }


    Vector resolvePlacedVector(Vector clickedVector, Material clickedMaterial, Material attemptedMaterial, int faceValue) {
        // Uses replaceable blocks when the client would place into the clicked block itself.
        if (clickedVector == null) {
            return null;
        }

        if (isReplaceablePlacementTarget(clickedMaterial, attemptedMaterial)) {
            return clickedVector.clone();
        }

        return getPlacedVector(clickedVector.getBlockX(), clickedVector.getBlockY(), clickedVector.getBlockZ(), faceValue);
    }

    boolean isInteractionOnlyClick(Material clickedMaterial, Material heldMaterial, int faceValue) {
        // Treats normal right-clicks on interactive blocks as interactions, not placement attempts.
        // This must run before physics placement context is started, otherwise clicking a lever/button
        // while holding water/cobweb/vines/etc. becomes a fake cancelled placement and poisons GroundC/Fly.
        if (clickedMaterial == null || heldMaterial == null || heldMaterial == Material.AIR) {
            return false;
        }

        if (data.getActionData() != null && data.getActionData().isSneaking()) {
            return false;
        }

        return isInteractiveBlock(clickedMaterial);
    }

    boolean isInteractiveBlock(Material material) {
        // Detects blocks whose right click usually opens/toggles/interacts instead of placing.
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.contains("BUTTON")
                || name.contains("LEVER")
                || name.contains("DOOR")
                || name.contains("TRAPDOOR")
                || name.contains("FENCE_GATE")
                || name.contains("CHEST")
                || name.contains("SHULKER_BOX")
                || name.contains("BARREL")
                || name.contains("FURNACE")
                || name.contains("BLAST_FURNACE")
                || name.contains("SMOKER")
                || name.contains("CRAFTING_TABLE")
                || name.contains("WORKBENCH")
                || name.contains("ANVIL")
                || name.contains("ENCHANTING_TABLE")
                || name.contains("ENCHANTMENT_TABLE")
                || name.contains("BREWING_STAND")
                || name.contains("BEACON")
                || name.contains("HOPPER")
                || name.contains("DROPPER")
                || name.contains("DISPENSER")
                || name.contains("JUKEBOX")
                || name.contains("LECTERN")
                || name.contains("LOOM")
                || name.contains("CARTOGRAPHY_TABLE")
                || name.contains("SMITHING_TABLE")
                || name.contains("GRINDSTONE")
                || name.contains("STONECUTTER")
                || name.contains("COMPOSTER")
                || name.contains("CAULDRON")
                || name.contains("BED")
                || name.contains("BELL")
                || name.contains("RESPAWN_ANCHOR");
    }

    boolean isReplaceablePlacementTarget(Material clickedMaterial, Material attemptedMaterial) {
        // Detects replaceable targets where the placement occurs inside the clicked block.
        if (clickedMaterial == null || clickedMaterial == Material.AIR) {
            return false;
        }

        if (attemptedMaterial == null || attemptedMaterial == Material.AIR) {
            return false;
        }

        String name = clickedMaterial.name();

        if (isLiquidMaterial(clickedMaterial)) {
            return false;
        }

        return name.contains("TALL_GRASS")
                || name.contains("LONG_GRASS")
                || name.contains("FERN")
                || name.contains("SEAGRASS")
                || name.contains("KELP")
                || name.contains("DEAD_BUSH")
                || name.contains("FLOWER")
                || name.contains("TULIP")
                || name.contains("DANDELION")
                || name.contains("POPPY")
                || name.contains("ORCHID")
                || name.contains("ALLIUM")
                || name.contains("AZURE_BLUET")
                || name.contains("DAISY")
                || name.contains("CORNFLOWER")
                || name.contains("LILY_OF_THE_VALLEY")
                || name.contains("WITHER_ROSE")
                || name.contains("MUSHROOM")
                || name.contains("ROOTS")
                || name.contains("SPROUTS")
                || name.contains("NETHER_WART")
                || name.contains("SNOW");
    }

    void syncSmallInteractionArea(Vector clickedVector, Vector placedVector) {
        // Queues one or two block corrections around ignored interactions.
        if (!autoCorrectGhostBlocks) {
            return;
        }

        if (clickedVector != null) {
            syncRealBlockToClient(clickedVector);
        }

        if (placedVector != null && !sameBlock(placedVector, clickedVector != null ? clickedVector.getBlockX() : Integer.MIN_VALUE, clickedVector != null ? clickedVector.getBlockY() : Integer.MIN_VALUE, clickedVector != null ? clickedVector.getBlockZ() : Integer.MIN_VALUE)) {
            syncRealBlockToClient(placedVector);
        }
    }

    boolean shouldSyncServerUpdate(Material material) {
        // Decides whether a server update is physics/piston-relevant enough to repair.
        if (material == null || material == Material.AIR) {
            return false;
        }

        if (isIgnoredNoHitboxGhostMaterial(material) || isInteractiveBlock(material)) {
            return false;
        }

        return isPhysicsGhostMaterial(material) || isPistonRelated(material);
    }

    boolean isPistonRelated(Material material) {
        // Detects piston blocks and moving piston state names.
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.contains("PISTON")
                || name.contains("MOVING_PISTON")
                || name.contains("PISTON_HEAD")
                || name.contains("PISTON_EXTENSION");
    }
    void syncRealBlockToClient(Vector vector) {
        // Queues a real server block to be resent to this player.
        enqueueRealBlockSync(vector, true);
    }

    void enqueueRealBlockSync(Vector vector, boolean removeStoredGhost) {
        // Adds a block coordinate to the deduplicated paced sync queue.
        if (vector == null) {
            return;
        }

        enqueueRealBlockSync(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ(), removeStoredGhost);
    }

    void enqueueRealBlockSync(int x, int y, int z, boolean removeStoredGhost) {
        // Adds a block coordinate to the deduplicated paced sync queue.
        if (!autoCorrectGhostBlocks || data.getPlayer() == null || !data.getPlayer().isOnline()) {
            return;
        }

        long key = blockKey(x, y, z);

        synchronized (syncQueueLock) {
            if (queuedSyncBlocks.size() >= MAX_AREA_SYNC_BLOCKS * 2 && !queuedSyncBlocks.containsKey(key)) {
                return;
            }

            queuedSyncBlocks.put(key, new SyncBlock(x, y, z, removeStoredGhost));
        }

        scheduleSyncFlush();
    }

    void scheduleSyncFlush() {
        if (syncFlushQueued) {
            return;
        }

        syncFlushQueued = true;

        Player player = data.getPlayer();

        if (player == null) {
            syncFlushQueued = false;
            return;
        }

        TaskUtils.player(player, () -> {
            try {
                if (player.isOnline()) {
                    flushQueuedSyncBlocks();
                }
            } finally {
                syncFlushQueued = false;
            }
        });
    }

    void flushQueuedSyncBlocks() {
        // Sends only a small number of queued block changes per flush to avoid transaction timeouts.
        Player player = data.getPlayer();

        if (player == null || !player.isOnline()) {
            clearSyncQueue();
            return;
        }

        World world = player.getWorld();

        int sent = 0;

        while (sent < MAX_SYNC_BLOCKS_PER_FLUSH) {
            SyncBlock syncBlock = pollQueuedSyncBlock();

            if (syncBlock == null) {
                return;
            }

            Block block = world.getBlockAt(syncBlock.x, syncBlock.y, syncBlock.z);
            sendBlockChangeCompat(player, block);

            if (syncBlock.removeStoredGhost) {
                removeGhostBlock(new Vector(syncBlock.x, syncBlock.y, syncBlock.z));
            }

            sent++;
        }
    }

    SyncBlock pollQueuedSyncBlock() {
        // Polls the oldest queued sync block while preserving deduplication.
        synchronized (syncQueueLock) {
            Iterator<Map.Entry<Long, SyncBlock>> iterator = queuedSyncBlocks.entrySet().iterator();

            if (!iterator.hasNext()) {
                return null;
            }

            Map.Entry<Long, SyncBlock> entry = iterator.next();
            SyncBlock syncBlock = entry.getValue();
            iterator.remove();
            return syncBlock;
        }
    }

    void clearSyncQueue() {
        // Clears queued repairs when the player/world is unavailable.
        synchronized (syncQueueLock) {
            queuedSyncBlocks.clear();
        }
    }

    void markSelfSyncedBlock(int x, int y, int z) {
        // Marks a block resend so our packet listener can ignore its echo.
        selfSyncedBlocks.put(blockKey(x, y, z), blockUpdateTicks);
    }

    boolean consumeSelfSyncedBlock(int x, int y, int z) {
        // Consumes self-sent block update echoes to prevent repair recursion.
        Long key = blockKey(x, y, z);
        Integer storedTick = selfSyncedBlocks.remove(key);

        return storedTick != null && blockUpdateTicks - storedTick <= SELF_SYNC_IGNORE_TICKS;
    }

    void ageSelfSyncedBlocks() {
        // Expires self-sent markers after a few movement ticks.
        selfSyncedBlocks.entrySet().removeIf(entry -> blockUpdateTicks - entry.getValue() > SELF_SYNC_IGNORE_TICKS);
    }

    long blockKey(int x, int y, int z) {
        // Builds a stable packed key for block coordinates.
        long key = 1469598103934665603L;
        key = (key ^ x) * 1099511628211L;
        key = (key ^ y) * 1099511628211L;
        key = (key ^ z) * 1099511628211L;
        return key;
    }

    void sendBlockChangeCompat(Player player, Block block) {
        // Sends the authoritative block state using modern or legacy Bukkit APIs.
        if (player == null || block == null || !player.isOnline()) {
            return;
        }

        Location location = block.getLocation();

        if (location.getWorld() == null) {
            return;
        } else {
            player.getWorld();
        }

        if (!location.getWorld().getName().equals(player.getWorld().getName())) {
            return;
        }

        if (tryModernSendBlockChange(player, block, location)) {
            return;
        }

        tryLegacySendBlockChange(player, block, location);
    }

    boolean sameBlock(Vector vector, int x, int y, int z) {
        // Compares a vector against integer block coordinates.
        return vector != null
                && vector.getBlockX() == x
                && vector.getBlockY() == y
                && vector.getBlockZ() == z;
    }

    boolean isMovement(PacketReceiveEvent event) {
        // Checks whether a packet is a movement/flying packet.
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    boolean isAirLike(Material material) {
        // Treats all modern air variants as air.
        if (material == null) {
            return true;
        }

        if (material == Material.AIR) {
            return true;
        }

        String name = material.name();
        return name.equals("CAVE_AIR") || name.equals("VOID_AIR");
    }

    boolean isKnownGhostNear(Vector vector, double range) {
        // Checks whether a point is close to any stored ghostblock.
        if (vector == null) {
            return false;
        }

        double max = range * range;

        synchronized (ghostBlockLock) {
            if (ghostBlocks.isEmpty()) {
                return false;
            }

            for (GhostBlock ghost : ghostBlocks) {
                if (ghost.position == null) {
                    continue;
                }

                double dx = (ghost.position.getBlockX() + 0.5D) - (vector.getBlockX() + 0.5D);
                double dy = (ghost.position.getBlockY() + 0.5D) - (vector.getBlockY() + 0.5D);
                double dz = (ghost.position.getBlockZ() + 0.5D) - (vector.getBlockZ() + 0.5D);

                if ((dx * dx) + (dy * dy) + (dz * dz) <= max) {
                    return true;
                }
            }
        }

        return false;
    }

    boolean isPlayerNearAnyGhost() {
        // Checks whether the player is close to or touching any stored ghostblock.
        synchronized (ghostBlockLock) {
            if (ghostBlocks.isEmpty()) {
                return false;
            }

            for (GhostBlock ghost : ghostBlocks) {
                if (isPlayerCloseTo(ghost.position, 2.75D, 3.0D)
                        || isPlayerStandingOnGhost(ghost.position)
                        || isPlayerInsideGhost(ghost.position)
                        || isPlayerUnderGhost(ghost.position)) {
                    return true;
                }
            }
        }

        return false;
    }

    void syncGhostInteractionArea(Vector clickedVector, Vector placedVector) {
        // Queues corrections around an existing ghostblock interaction.
        if (!autoCorrectGhostBlocks) {
            return;
        }

        if (clickedVector != null) {
            syncRealBlockToClient(clickedVector);
            syncRealBlockToClient(clickedVector.clone().add(new Vector(0, 1, 0)));
            syncRealBlockToClient(clickedVector.clone().add(new Vector(0, -1, 0)));
        }

        if (placedVector != null) {
            syncRealBlockToClient(placedVector);
            syncRealBlockToClient(placedVector.clone().add(new Vector(0, 1, 0)));
            syncRealBlockToClient(placedVector.clone().add(new Vector(0, -1, 0)));
        }

        synchronized (ghostBlockLock) {
            for (GhostBlock ghost : ghostBlocks) {
                if (ghost.position == null) {
                    continue;
                }

                boolean closeToClick = clickedVector != null && isVectorNear(ghost.position, clickedVector, 3.0D);
                boolean closeToPlace = placedVector != null && isVectorNear(ghost.position, placedVector, 3.0D);
                boolean closeToPlayer = isPlayerCloseTo(ghost.position, 3.0D, 3.5D);

                if (closeToClick || closeToPlace || closeToPlayer) {
                    syncRealBlockToClient(ghost.position);
                    syncRealBlockToClient(ghost.position.clone().add(new Vector(0, 1, 0)));
                    syncRealBlockToClient(ghost.position.clone().add(new Vector(0, -1, 0)));

                    ghost.syncCooldownTicks = GHOST_SYNC_COOLDOWN_TICKS;
                }
            }
        }

        this.lastGhostInteractionAreaSyncTick = 0;
    }

    boolean isVectorNear(Vector a, Vector b, double range) {
        // Cheap squared-distance comparison between block vectors.
        if (a == null || b == null) {
            return false;
        }

        double dx = (a.getBlockX() + 0.5D) - (b.getBlockX() + 0.5D);
        double dy = (a.getBlockY() + 0.5D) - (b.getBlockY() + 0.5D);
        double dz = (a.getBlockZ() + 0.5D) - (b.getBlockZ() + 0.5D);

        return (dx * dx) + (dy * dy) + (dz * dz) <= range * range;
    }

    void syncRealBlocksAroundPlayer(int radiusXZ, int down, int up) {
        // Queues a tiny repair area around the player.
        if (!autoCorrectGhostBlocks || data.getPlayer() == null || !data.getPlayer().isOnline()) {
            return;
        }

        CustomLocation loc = data.getMovementData().getLocation();

        if (loc == null) {
            return;
        }

        int baseX = (int) Math.floor(loc.getX());
        int baseY = (int) Math.floor(loc.getY());
        int baseZ = (int) Math.floor(loc.getZ());

        syncRealBlocksInBoxAsync(
                baseX - radiusXZ,
                baseY - down,
                baseZ - radiusXZ,
                baseX + radiusXZ,
                baseY + up,
                baseZ + radiusXZ,
                128
        );
    }
    boolean isLiquidMaterial(Material material) {
        // Detects water/lava materials.
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.contains("WATER")
                || name.contains("LAVA");
    }
    void syncCancelledPlacementArea(Vector vector) {
        // Queues a compact correction area for confirmed cancelled placements.
        if (!autoCorrectGhostBlocks) {
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        if (vector != null) {
            minX = Math.min(minX, vector.getBlockX() - 2);
            minY = Math.min(minY, vector.getBlockY() - 2);
            minZ = Math.min(minZ, vector.getBlockZ() - 2);
            maxX = Math.max(maxX, vector.getBlockX() + 2);
            maxY = Math.max(maxY, vector.getBlockY() + 2);
            maxZ = Math.max(maxZ, vector.getBlockZ() + 2);
        }

        if (this.lastClickedBlockVector != null) {
            minX = Math.min(minX, this.lastClickedBlockVector.getBlockX() - 2);
            minY = Math.min(minY, this.lastClickedBlockVector.getBlockY() - 2);
            minZ = Math.min(minZ, this.lastClickedBlockVector.getBlockZ() - 2);
            maxX = Math.max(maxX, this.lastClickedBlockVector.getBlockX() + 2);
            maxY = Math.max(maxY, this.lastClickedBlockVector.getBlockY() + 2);
            maxZ = Math.max(maxZ, this.lastClickedBlockVector.getBlockZ() + 2);
        }

        CustomLocation loc = data.getMovementData().getLocation();

        if (loc != null) {
            int px = (int) Math.floor(loc.getX());
            int py = (int) Math.floor(loc.getY());
            int pz = (int) Math.floor(loc.getZ());

            minX = Math.min(minX, px - 2);
            minY = Math.min(minY, py - 2);
            minZ = Math.min(minZ, pz - 2);
            maxX = Math.max(maxX, px + 2);
            maxY = Math.max(maxY, py + 3);
            maxZ = Math.max(maxZ, pz + 2);
        }

        if (minX == Integer.MAX_VALUE) {
            return;
        }

        syncRealBlocksInBoxAsync(minX, minY, minZ, maxX, maxY, maxZ, MAX_AREA_SYNC_BLOCKS, true);
    }
    void syncRealBlocksInBoxAsync(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int maxBlocks) {
        // Queues a bounded box of real server blocks instead of sending them all immediately.
        syncRealBlocksInBoxAsync(minX, minY, minZ, maxX, maxY, maxZ, maxBlocks, false);
    }

    void syncRealBlocksInBoxAsync(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int maxBlocks, boolean force) {
        // Queues a bounded box of real server blocks instead of sending them all immediately.
        if (!autoCorrectGhostBlocks || data.getPlayer() == null || !data.getPlayer().isOnline()) {
            return;
        }

        if (!force && this.lastAreaSyncTick < AREA_SYNC_COOLDOWN_TICKS) {
            return;
        }

        this.lastAreaSyncTick = 0;

        int fMinX = Math.min(minX, maxX);
        int fMinY = Math.min(minY, maxY);
        int fMinZ = Math.min(minZ, maxZ);
        int fMaxX = Math.max(minX, maxX);
        int fMaxY = Math.max(minY, maxY);
        int fMaxZ = Math.max(minZ, maxZ);
        int limit = Math.max(1, Math.min(maxBlocks, MAX_AREA_SYNC_BLOCKS));
        int queued = 0;

        CustomLocation loc = data.getMovementData() != null ? data.getMovementData().getLocation() : null;
        int centerX = loc != null ? (int) Math.floor(loc.getX()) : (fMinX + fMaxX) >> 1;
        int centerY = loc != null ? (int) Math.floor(loc.getY() + 0.9D) : (fMinY + fMaxY) >> 1;
        int centerZ = loc != null ? (int) Math.floor(loc.getZ()) : (fMinZ + fMaxZ) >> 1;

        if (centerX >= fMinX && centerX <= fMaxX
                && centerY >= fMinY && centerY <= fMaxY
                && centerZ >= fMinZ && centerZ <= fMaxZ) {
            enqueueRealBlockSync(centerX, centerY, centerZ, true);
            queued++;
        }

        for (int y = fMinY; y <= fMaxY; y++) {
            for (int x = fMinX; x <= fMaxX; x++) {
                for (int z = fMinZ; z <= fMaxZ; z++) {
                    if (queued >= limit) {
                        scheduleSyncFlush();
                        return;
                    }

                    if (x == centerX && y == centerY && z == centerZ) {
                        continue;
                    }

                    enqueueRealBlockSync(x, y, z, true);
                    queued++;
                }
            }
        }

        scheduleSyncFlush();
    }

    void syncRealBlockToClient(int x, int y, int z) {
        // Queues a real server block to be resent to this player.
        syncRealBlockToClient(new Vector(x, y, z));
    }

    static class SyncBlock {

        int x;
        int y;
        int z;
        boolean removeStoredGhost;

        SyncBlock(int x, int y, int z, boolean removeStoredGhost) {
            // Stores one queued block correction.
            this.x = x;
            this.y = y;
            this.z = z;
            this.removeStoredGhost = removeStoredGhost;
        }
    }

    static class GhostBlock {

        Vector position;
        Material material;
        String reason;
        int maxTicks;

        int ticks;
        int interactionTicks;
        int syncCooldownTicks;

        GhostBlock(Vector position, Material material, String reason, int maxTicks) {
            // Stores one tracked ghostblock candidate.
            this.position = position;
            this.material = material;
            this.reason = reason;
            this.maxTicks = maxTicks;
        }
    }
}