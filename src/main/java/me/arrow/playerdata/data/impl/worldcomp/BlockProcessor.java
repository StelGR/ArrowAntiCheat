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
import me.arrow.utils.custom.MaterialType;
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

// this is a GPT improved processor from MrPlugin, it syncs ghost blocks (does not work on 1.8)
// and properly accounts for world guard blocks, so it really helps fix alot of bugs with ghost blocks
// such as the piston glitch i found where if you place a block by spamming on a piston that's moving with 1 extra tick delay
// you can make any block become a ghost block
// this will sync any ghost blocks near you, but it is probably gonna cause lag on slower servers with 80+ players
// but this is really really good and works almost flawlessly on 1.21.11

@Getter
public class BlockProcessor implements Data {

    private static final int CLEARED_GHOST_CONTEXT_TICK = 100;
    private static final int PLACE_CONFIRMATION_GRACE_TICKS = 4;
    private static final int PHYSICS_PLACE_CONFIRMATION_GRACE_TICKS = 10;
    private static final int RECENT_PLACE_BLOCK_CHANGE_GRACE_TICKS = 8;
    private static final double CANCELLED_PHYSICS_GHOST_RANGE_XZ = 2.0D;
    private static final double CANCELLED_PHYSICS_GHOST_RANGE_Y = 2.0D;

    private static final int MAX_GHOST_BLOCK_TICKS = 20 * 8;
    private static final int MAX_CANCELLED_PLACE_GHOST_TICKS = 20;
    private static final int MAX_CLICK_INTERACTION_GHOST_TICKS = 10;

    private static final int GHOST_SYNC_COOLDOWN_TICKS = 8;
    private static final int GHOST_INTERACTION_AREA_SYNC_COOLDOWN_TICKS = 4;

    private int lastGhostInteractionAreaSyncTick = 100;

    private final Profile data;
    private final EventTimer lastConfirmedBlockPlaceTimer;
    private final EventTimer lastConfirmedCancelPlaceTimer;
    private final EventTimer lastPlacementPacket;

    private final Deque<GhostBlock> ghostBlocks = new EvictingList<>(200);

    private final Object ghostBlockLock = new Object();

    private Material materialPlaced;
    private int blockUpdateTicks;
    private boolean hasPlacedBlock = false, recentC2SPacket = false;
    private Vector currentBlockCords;
    private Material blockPlaceMaterial;
    private Material main;
    //    private Material lastBlockChangeMaterial, lastBlockChangeMultiMaterial;
    private int placeTicks;
    private int lastWebUpdateTick;
    private int face;
    private int lastGhostBlockTick = 100;
    private int lastGhostLiquidWebTick = 100;
    private int lastPendingPhysicsPlaceTick = 100;
    private int recentPlacePacketTicks = 100;
    private Vector recentPlaceVector;
    private Material recentPlaceMaterial;
    private Vector lastClickedBlockVector;
    private Material lastClickedBlockMaterial;
    private int lastPlacementFace = -1;


    private boolean nearGhostBlock;
    private boolean onGhostBlock;
    private boolean insideGhostBlock;
    private boolean underGhostBlock;
    private boolean interactingGhostBlock;

    @Setter
    private boolean autoCorrectGhostBlocks = true;

    private double distanceFromUpdate, distanceFromUpdateMulti;

    private Material lastAttemptedPlaceMaterial;
    private int pendingPlacementTicks;

    public BlockProcessor(Profile user) {
        this.data = user;
        this.lastConfirmedBlockPlaceTimer = new EventTimer(20, user);
        this.lastConfirmedCancelPlaceTimer = new EventTimer(20, user);
        this.lastPlacementPacket = new EventTimer(20, user);
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            handleBlockPlacePacket(event);
        }

        if (isMovement(event)) {
            handleMovementPacket(event);
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleMultiBlockChange(event);
        }

        if (event.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleBlockChange(event);
        }
    }

    private void handleBlockPlacePacket(PacketReceiveEvent event) {
        WrapperPlayClientPlayerBlockPlacement wrapped = new WrapperPlayClientPlayerBlockPlacement(event);

        if (wrapped.getBlockPosition() == null || wrapped.getFace() == null) {
            return;
        }

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
        Vector placedVector = getPlacedVector(x, y, z, faceValue);

        this.lastClickedBlockVector = clickedVector;
        this.lastClickedBlockMaterial = clickedServerMaterial;
        this.lastPlacementFace = faceValue;

        boolean clickedAirLike = isAirLike(clickedServerMaterial);
        boolean knownGhostNearInteraction =
                isKnownGhostNear(clickedVector, 2.25D)
                        || isKnownGhostNear(placedVector, 2.25D)
                        || isPlayerNearAnyGhost();

        if (knownGhostNearInteraction && autoCorrectGhostBlocks) {
            syncGhostInteractionArea(clickedVector, placedVector);
        }

        if (isIgnoredNoHitboxGhostMaterial(rawAttemptedMaterial)) {
            clearPendingPlacement();

            if (autoCorrectGhostBlocks) {
                syncRealBlockToClient(clickedVector);
                syncRealBlockToClient(placedVector);
            }

            return;
        }

        if (clickedAirLike && attemptedMaterial == null && rawAttemptedMaterial == null) {
            addGhostBlock(clickedVector, Material.AIR, "client-interacted-server-air-without-place", MAX_CLICK_INTERACTION_GHOST_TICKS);

            if (autoCorrectGhostBlocks) {
                syncGhostInteractionArea(clickedVector, placedVector);
            }
        }

        this.face = faceValue;
        this.materialPlaced = clickedServerMaterial;
        this.currentBlockCords = placedVector;
        this.blockPlaceMaterial = attemptedMaterial;
        this.lastAttemptedPlaceMaterial = attemptedMaterial;
        this.pendingPlacementTicks = 0;
        this.placeTicks++;

        this.hasPlacedBlock = this.recentC2SPacket;
        this.recentPlacePacketTicks = 0;
        this.recentPlaceVector = placedVector;
        this.recentPlaceMaterial = attemptedMaterial;
    }

    private void handleMovementPacket(PacketReceiveEvent event) {
        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);

        this.blockUpdateTicks++;
        this.lastWebUpdateTick++;
        this.lastGhostLiquidWebTick++;
        this.lastPendingPhysicsPlaceTick++;
        this.lastGhostBlockTick++;
        this.lastGhostInteractionAreaSyncTick++;
        this.recentPlacePacketTicks++;

        ageGhostBlocks();

        this.recentC2SPacket = flying.hasRotationChanged() && !flying.hasPositionChanged();

        updateGhostBlockContact();

        keepPendingPlacementPhysicsExemption();

        if (autoCorrectGhostBlocks
                && this.interactingGhostBlock
                && this.lastGhostInteractionAreaSyncTick > GHOST_INTERACTION_AREA_SYNC_COOLDOWN_TICKS) {
            syncRealBlocksAroundPlayer(2, 2, 3);
            this.lastGhostInteractionAreaSyncTick = 0;
        }

        if (this.currentBlockCords != null && this.blockPlaceMaterial != null) {
            this.pendingPlacementTicks++;

            TaskUtils.task(() -> {
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
                        syncRealBlockToClient(vector);
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
                    this.lastGhostLiquidWebTick = 0;
                    this.lastGhostBlockTick = 0;
                    this.nearGhostBlock = true;
                    this.interactingGhostBlock = true;
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
                    syncRealBlockToClient(vector);
                }
            }

            this.lastAttemptedPlaceMaterial = null;
            this.currentBlockCords = null;
            this.pendingPlacementTicks = 0;
        }
    }


    private void keepPendingPlacementPhysicsExemption() {
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

        this.lastPendingPhysicsPlaceTick = 0;
    }

    private void clearConfirmedPlacementGhostContext(Vector vector, Material attemptedMaterial, Material serverMaterial) {
        if (vector != null) {
            removeGhostBlock(vector);
        }

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

    private boolean isPhysicsPlacementMaterial(Material material) {
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

    private boolean hasActiveGhostContact() {
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
    private Material findConfirmedPlacedMaterial(Vector placeVector, Vector clickedVector, Material attempted) {
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

        if (!isCancelledPhysicsContextMaterial(attempted)) {
            return null;
        }

        if (placeVector != null) {
            for (int x = placeVector.getBlockX() - 1; x <= placeVector.getBlockX() + 1; x++) {
                for (int y = placeVector.getBlockY() - 1; y <= placeVector.getBlockY() + 1; y++) {
                    for (int z = placeVector.getBlockZ() - 1; z <= placeVector.getBlockZ() + 1; z++) {
                        Material material = getServerMaterial(x, y, z);

                        if (isSamePlacedMaterial(material, attempted)) {
                            return material;
                        }
                    }
                }
            }
        }

        if (clickedVector != null) {
            for (int x = clickedVector.getBlockX() - 1; x <= clickedVector.getBlockX() + 1; x++) {
                for (int y = clickedVector.getBlockY() - 1; y <= clickedVector.getBlockY() + 1; y++) {
                    for (int z = clickedVector.getBlockZ() - 1; z <= clickedVector.getBlockZ() + 1; z++) {
                        Material material = getServerMaterial(x, y, z);

                        if (isSamePlacedMaterial(material, attempted)) {
                            return material;
                        }
                    }
                }
            }
        }

        return null;
    }

    private void handleMultiBlockChange(PacketSendEvent event) {
        WrapperPlayServerMultiBlockChange multiBlockChange = new WrapperPlayServerMultiBlockChange(event);

        CustomLocation current = data.getMovementData().getLocation();

        if (current == null || multiBlockChange.getBlocks() == null) {
            return;
        }

        for (WrapperPlayServerMultiBlockChange.EncodedBlock blockData : multiBlockChange.getBlocks()) {
            CustomLocation blockLocation = new CustomLocation(
                    data.getPlayer().getWorld(),
                    blockData.getX(),
                    blockData.getY(),
                    blockData.getZ()
            );

            this.distanceFromUpdateMulti = blockLocation.distanceSquaredXZ(current);

            TaskUtils.task(() -> {
                Material serverMaterial = getServerMaterial(blockData.getX(), blockData.getY(), blockData.getZ());

                if (serverMaterial == null) {
                    return;
                }

                if (distanceFromUpdateMulti < 3.0D && serverMaterial.name().equals(MaterialType.WEB.name())) {
                    this.lastWebUpdateTick = 0;
                }

                Vector vector = new Vector(blockData.getX(), blockData.getY(), blockData.getZ());

                try {
                    StateType type = blockData.getBlockState(data.getVersion()).getType();
                    String stateName = type != null ? type.getName() : null;

                    Material clientMaterial = materialFromStateName(stateName);

                    boolean serverAir = isAirLike(serverMaterial);
                    boolean clientAir = isStateAirLike(stateName);

                    if (!clientAir) {

                        if (!isTrackableGhostMaterial(clientMaterial)) {
                            removeGhostBlock(vector);

                            if (autoCorrectGhostBlocks && serverAir && isPlayerCloseTo(vector, 3.0D, 3.0D)) {
                                syncRealBlockToClient(vector);
                            }

                            return;
                        }

                        if (isRecentOwnPlacement(vector, clientMaterial)) {
                            removeGhostBlock(vector);
                            return;
                        }

                        if (isLiquidMaterial(clientMaterial) || isLiquidMaterial(serverMaterial)) {
                            removeGhostBlock(vector);
                            return;
                        }

                        if (!serverAir) {
                            removeGhostBlock(vector);
                            return;
                        }

                        addGhostBlock(
                                vector,
                                clientMaterial != null ? clientMaterial : Material.AIR,
                                "multi-block-client-server-mismatch",
                                Math.min(MAX_GHOST_BLOCK_TICKS, 20)
                        );

                        if (autoCorrectGhostBlocks && isPlayerCloseTo(vector, 3.0D, 3.0D)) {
                            syncRealBlockToClient(vector);
                        }

                        return;
                    }

                    removeGhostBlock(vector);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void handleBlockChange(PacketSendEvent event) {
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(event);

        CustomLocation current = data.getMovementData().getLocation();

        if (current == null) {
            return;
        }

        int x = blockChange.getBlockPosition().getX();
        int y = blockChange.getBlockPosition().getY();
        int z = blockChange.getBlockPosition().getZ();

        CustomLocation blockLocation = new CustomLocation(data.getPlayer().getWorld(), x, y, z);
        this.distanceFromUpdate = blockLocation.distanceSquaredXZ(current);

        TaskUtils.task(() -> {
            Material serverMaterial = getServerMaterial(x, y, z);

            if (serverMaterial == null) {
                return;
            }

            if (distanceFromUpdate < 3.0D && isWebMaterial(serverMaterial)) {
                this.lastWebUpdateTick = 0;
            }

            Vector vector = new Vector(x, y, z);

            try {
                StateType type = blockChange.getBlockState().getType();
                String stateName = type != null ? type.getName() : null;

                Material clientMaterial = materialFromStateName(stateName);

                boolean serverAir = isAirLike(serverMaterial);
                boolean clientAir = isStateAirLike(stateName);

                /*
                 * A normal BLOCK_CHANGE packet is the server intentionally syncing this block
                 * to the client. Do not instantly call it a ghost just because our Bukkit/NMS
                 * world lookup is one tick behind.
                 */
                if (!clientAir) {
                    if (!isTrackableGhostMaterial(clientMaterial)) {
                        removeGhostBlock(vector);

                        if (autoCorrectGhostBlocks && serverAir && isPlayerCloseTo(vector, 3.0D, 3.0D)) {
                            syncRealBlockToClient(vector);
                        }

                        return;
                    }

                    if (isRecentOwnPlacement(vector, clientMaterial)) {
                        removeGhostBlock(vector);
                        return;
                    }

                    if (isLiquidMaterial(clientMaterial) || isLiquidMaterial(serverMaterial)) {
                        removeGhostBlock(vector);
                        return;
                    }

                    if (!serverAir) {
                        removeGhostBlock(vector);
                        return;
                    }

                    /*
                     * If this is a non-liquid server-sent fake block, only keep it as a short
                     * sync candidate. Do not make it a physics ghost unless contact confirms it.
                     */
                    addGhostBlock(
                            vector,
                            clientMaterial != null ? clientMaterial : Material.AIR,
                            "block-change-client-server-mismatch",
                            Math.min(MAX_GHOST_BLOCK_TICKS, 20)
                    );

                    if (autoCorrectGhostBlocks && isPlayerCloseTo(vector, 3.0D, 3.0D)) {
                        syncRealBlockToClient(vector);
                    }

                    return;
                }

                removeGhostBlock(vector);
            } catch (Throwable ignored) {
            }
        });
    }

    private boolean tryModernSendBlockChange(Player player, Block block, Location location) {
        try {
            Method getBlockData = block.getClass().getMethod("getBlockData");
            Object blockData = getBlockData.invoke(block);

            if (blockData == null) {
                return false;
            }

            Method sendBlockChange = player.getClass().getMethod(
                    "sendBlockChange",
                    Location.class,
                    blockData.getClass().getInterfaces().length > 0 ? blockData.getClass().getInterfaces()[0] : blockData.getClass()
            );

            sendBlockChange.invoke(player, location, blockData);
            return true;
        } catch (Throwable ignored) {
        }

        try {
            Object blockData = block.getClass().getMethod("getBlockData").invoke(block);

            if (blockData == null) {
                return false;
            }

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

                method.invoke(player, location, blockData);
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private void tryLegacySendBlockChange(Player player, Block block, Location location) {
        try {
            Material material = block.getType();
            byte data = getLegacyBlockData(block);

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

                method.invoke(player, location, material, data);
                return;
            }
        } catch (Throwable ignored) {
        }

    }

    private byte getLegacyBlockData(Block block) {
        try {
            Method getData = block.getClass().getMethod("getData");
            Object value = getData.invoke(block);

            if (value instanceof Number) {
                return ((Number) value).byteValue();
            }
        } catch (Throwable ignored) {
        }

        try {
            Method getState = block.getClass().getMethod("getState");
            Object state = getState.invoke(block);

            if (state != null) {
                Method getRawData = state.getClass().getMethod("getRawData");
                Object value = getRawData.invoke(state);

                if (value instanceof Number) {
                    return ((Number) value).byteValue();
                }
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    private void updateGhostBlockContact() {
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

                        if (this.lastGhostInteractionAreaSyncTick > GHOST_INTERACTION_AREA_SYNC_COOLDOWN_TICKS) {
                            syncRealBlocksAroundPlayer(1, 1, 2);
                            this.lastGhostInteractionAreaSyncTick = 0;
                        }

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

    private void addGhostBlock(Vector position, Material material, String reason) {
        addGhostBlock(position, material, reason, resolveGhostMaxTicks(reason));
    }

    private void addGhostBlock(Vector position, Material material, String reason, int maxTicks) {
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

    private int resolveGhostMaxTicks(String reason) {
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

    private void removeGhostBlock(Vector position) {
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

    private boolean isStateAirLike(String stateName) {
        if (stateName == null || stateName.isEmpty()) {
            return true;
        }

        String name = stateName
                .replace("minecraft:", "")
                .toUpperCase();

        return name.equals("AIR")
                || name.equals("CAVE_AIR")
                || name.equals("VOID_AIR");
    }

    private Material materialFromStateName(String stateName) {
        if (stateName == null || stateName.isEmpty()) {
            return null;
        }

        return matchMaterialCompat(stateName);
    }

    private boolean isWebMaterial(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.equals("WEB")
                || name.equals("COBWEB")
                || name.contains("WEB");
    }

    private void ageGhostBlocks() {
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

    private boolean isPlayerStandingOnGhost(Vector block) {
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

    private boolean isPlayerInsideGhost(Vector block) {
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

    private boolean isPlayerUnderGhost(Vector block) {
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

    private boolean isPlayerCloseTo(Vector block, double horizontal, double vertical) {
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

    private boolean isSamePlacedMaterial(Material serverMaterial, Material attemptedMaterial) {
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

    private boolean isPhysicsGhostMaterial(Material material) {
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

    private Material resolveAttemptedMaterial(WrapperPlayClientPlayerBlockPlacement wrapped) {
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

    private Material resolvePacketMaterial(WrapperPlayClientPlayerBlockPlacement wrapped) {
        try {
            if (wrapped.getItemStack().isPresent()) {
                Object type = wrapped.getItemStack().get().getType();

                if (type != null) {
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
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private Material getMainHandMaterial() {
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

            if (stack != null && stack.getType() != Material.AIR) {
                return stack.getType();
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private Material getOffHandMaterial() {
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

        if (name.equals("WATER")) {
            Material bucket = Material.matchMaterial("WATER_BUCKET");

            if (bucket != null) {
                return bucket;
            }
        }

        if (name.equals("LAVA")) {
            Material bucket = Material.matchMaterial("LAVA_BUCKET");

            if (bucket != null) {
                return bucket;
            }
        }

        return null;
    }

    private boolean isValidGhostAttemptMaterial(Material material) {
        return isTrackableGhostMaterial(material) || isCancelledPhysicsContextMaterial(material);
    }

    private Material resolveRawAttemptedMaterial(WrapperPlayClientPlayerBlockPlacement wrapped) {
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

    private boolean isTrackableGhostMaterial(Material material) {
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

    private boolean isCancelledPhysicsContextMaterial(Material material) {
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

    private boolean isWaterPlacementMaterial(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.contains("WATER")
                || isAquaticBucket(material);
    }

    private boolean isLavaPlacementMaterial(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.contains("LAVA");
    }

    private boolean isPowderSnowPlacementMaterial(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.contains("POWDER_SNOW");
    }

    private boolean isAquaticBucket(Material material) {
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

    private boolean isClimbableGhostMaterial(Material material) {
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

    private boolean canClientPredictPlacement(Material attempted, Material clickedMaterial, int faceValue) {
        if (attempted == null) {
            return false;
        }

        String name = attempted.name();

        if (isWaterPlacementMaterial(attempted)
                || isLavaPlacementMaterial(attempted)
                || isPowderSnowPlacementMaterial(attempted)
                || name.contains("WEB")
                || name.contains("COBWEB")
                || name.contains("HONEY")
                || name.contains("SLIME")
                || name.contains("ICE")
                || name.contains("SOUL_SAND")) {
            return true;
        }

        if (name.contains("LADDER")) {
            return faceValue >= 2 && faceValue <= 5;
        }

        if (name.equals("VINE") || name.contains("VINES")) {
            if (name.contains("TWISTING_VINES")) {
                return faceValue == 1;
            }

            if (name.contains("WEEPING_VINES") || name.contains("CAVE_VINES") || name.contains("GLOW_BERRIES")) {
                return faceValue == 0;
            }

            return faceValue >= 2 && faceValue <= 5;
        }

        if (name.contains("SCAFFOLDING")) {
            return true;
        }

        return true;
    }


    private boolean isPlayerNearPlacement(Vector block, double horizontal, double vertical) {
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

    private boolean isPlayerNearPlacementXZ(Vector block, double horizontal) {
        CustomLocation loc = data.getMovementData().getLocation();

        if (loc == null || block == null) {
            return false;
        }

        double bx = block.getBlockX() + 0.5D;
        double bz = block.getBlockZ() + 0.5D;

        return Math.abs(loc.getX() - bx) <= horizontal
                && Math.abs(loc.getZ() - bz) <= horizontal;
    }

    private boolean hasUsefulCollisionShape(Material material) {
        if (material == null || material == Material.AIR) {
            return false;
        }

        try {
            Method method = material.getClass().getMethod("isCollidable");
            Object result = method.invoke(material);

            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        } catch (Throwable ignored) {
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

    private boolean isIgnoredNoHitboxGhostMaterial(Material material) {
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

    private void clearPendingPlacement() {
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
    }

    private Vector getPlacedVector(int x, int y, int z, int faceValue) {
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

    private Material getServerMaterial(Vector vector) {
        if (vector == null) {
            return null;
        }

        return getServerMaterial(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ());
    }

    private Material getServerMaterial(int x, int y, int z) {
        try {
            return Arrow.getInstance().getNmsManager().getNmsInstance2().getType(
                    data.getPlayer().getWorld(),
                    x,
                    y,
                    z
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void syncRealBlockToClient(Vector vector) {
        if (!autoCorrectGhostBlocks || vector == null || data.getPlayer() == null || !data.getPlayer().isOnline()) {
            return;
        }

        int x = vector.getBlockX();
        int y = vector.getBlockY();
        int z = vector.getBlockZ();

        TaskUtils.task(() -> {
            Player player = data.getPlayer();

            if (player == null || !player.isOnline()) {
                return;
            }

            World world = player.getWorld();

            if (world == null) {
                return;
            }

            Block block = world.getBlockAt(x, y, z);
            sendBlockChangeCompat(player, block);
        });
    }

    private void sendBlockChangeCompat(Player player, Block block) {
        if (player == null || block == null || !player.isOnline()) {
            return;
        }

        Location location = block.getLocation();

        if (location.getWorld() == null || player.getWorld() == null) {
            return;
        }

        if (!location.getWorld().getName().equals(player.getWorld().getName())) {
            return;
        }

        if (tryModernSendBlockChange(player, block, location)) {
            return;
        }

        tryLegacySendBlockChange(player, block, location);
    }

    private boolean sameBlock(Vector vector, int x, int y, int z) {
        return vector != null
                && vector.getBlockX() == x
                && vector.getBlockY() == y
                && vector.getBlockZ() == z;
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
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

    private boolean isKnownGhostNear(Vector vector, double range) {
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

    private boolean isPlayerNearAnyGhost() {
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

    private void syncGhostInteractionArea(Vector clickedVector, Vector placedVector) {
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

        syncRealBlocksAroundPlayer(2, 2, 3);
        this.lastGhostInteractionAreaSyncTick = 0;
    }

    private boolean isVectorNear(Vector a, Vector b, double range) {
        if (a == null || b == null) {
            return false;
        }

        double dx = (a.getBlockX() + 0.5D) - (b.getBlockX() + 0.5D);
        double dy = (a.getBlockY() + 0.5D) - (b.getBlockY() + 0.5D);
        double dz = (a.getBlockZ() + 0.5D) - (b.getBlockZ() + 0.5D);

        return (dx * dx) + (dy * dy) + (dz * dz) <= range * range;
    }

    private void syncRealBlocksAroundPlayer(int radiusXZ, int down, int up) {
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

        for (int x = baseX - radiusXZ; x <= baseX + radiusXZ; x++) {
            for (int y = baseY - down; y <= baseY + up; y++) {
                for (int z = baseZ - radiusXZ; z <= baseZ + radiusXZ; z++) {
                    syncRealBlockToClient(x, y, z);
                }
            }
        }
    }

    private boolean isRecentOwnPlacement(Vector vector, Material clientMaterial) {
        if (vector == null) {
            return false;
        }

        if (recentPlacePacketTicks > RECENT_PLACE_BLOCK_CHANGE_GRACE_TICKS) {
            return false;
        }

        if (recentPlaceVector == null) {
            return false;
        }

        boolean sameOrNear =
                sameBlock(recentPlaceVector, vector.getBlockX(), vector.getBlockY(), vector.getBlockZ())
                        || isVectorNear(recentPlaceVector, vector, 2.0D);

        if (!sameOrNear) {
            return false;
        }

        if (recentPlaceMaterial == null || clientMaterial == null) {
            return true;
        }

        return isSamePlacedMaterial(clientMaterial, recentPlaceMaterial)
                || isSamePlacedMaterial(recentPlaceMaterial, clientMaterial)
                || sameLiquidFamily(clientMaterial, recentPlaceMaterial);
    }

    private boolean isLiquidMaterial(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.contains("WATER")
                || name.contains("LAVA");
    }

    private boolean sameLiquidFamily(Material a, Material b) {
        if (a == null || b == null) {
            return false;
        }

        String aa = a.name();
        String bb = b.name();

        boolean waterA = aa.contains("WATER") || isAquaticBucket(a);
        boolean waterB = bb.contains("WATER") || isAquaticBucket(b);

        boolean lavaA = aa.contains("LAVA");
        boolean lavaB = bb.contains("LAVA");

        return (waterA && waterB) || (lavaA && lavaB);
    }

    private void syncRealBlockToClient(int x, int y, int z) {
        syncRealBlockToClient(new Vector(x, y, z));
    }

    private static final class GhostBlock {

        private final Vector position;
        private final Material material;
        private final String reason;
        private final int maxTicks;

        private int ticks;
        private int interactionTicks;
        private int syncCooldownTicks;

        private GhostBlock(Vector position, Material material, String reason, int maxTicks) {
            this.position = position;
            this.material = material;
            this.reason = reason;
            this.maxTicks = maxTicks;
        }
    }
}