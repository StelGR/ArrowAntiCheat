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
        Material attemptedMaterial = resolveAttemptedMaterial(wrapped);

        Vector clickedVector = new Vector(x, y, z);
        Vector placedVector = getPlacedVector(x, y, z, faceValue);

        boolean clickedAirLike = isAirLike(clickedServerMaterial);
        boolean knownGhostNearInteraction =
                isKnownGhostNear(clickedVector, 2.25D)
                        || isKnownGhostNear(placedVector, 2.25D)
                        || isPlayerNearAnyGhost();

        /*
         * Important:
         * Do NOT create a new ghostblock just because the player right-clicked near
         * a previous ghostblock. That is what makes levers/buttons and old WG areas
         * re-trigger GroundC.
         */
        if (knownGhostNearInteraction && autoCorrectGhostBlocks) {
            syncGhostInteractionArea(clickedVector, placedVector);
        }

        /*
         * If the clicked block is air and the player is not actually trying to place
         * a block, they probably interacted with a client-only block. Track it briefly.
         *
         * If they ARE holding a block, wait for the delayed placement confirmation
         * below in handleMovementPacket. That path knows the intended placedVector.
         */
        if (clickedAirLike && attemptedMaterial == null) {
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
    }

    private void handleMovementPacket(PacketReceiveEvent event) {
        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);

        this.blockUpdateTicks++;
        this.lastWebUpdateTick++;
        this.lastGhostLiquidWebTick++;
        this.lastGhostBlockTick++;
        this.lastGhostInteractionAreaSyncTick++;

        ageGhostBlocks();

        this.recentC2SPacket = flying.hasRotationChanged() && !flying.hasPositionChanged();

        updateGhostBlockContact();

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
                Material attempted = this.blockPlaceMaterial;
                Material serverMaterial = getServerMaterial(placeVector);

                if (serverMaterial == null) {
                    return;
                }

                if (isSamePlacedMaterial(serverMaterial, attempted)) {
                    this.main = serverMaterial;
                }

                this.blockPlaceMaterial = null;
            });
        }

        if (this.main != null) {
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
            Material serverMaterial = getServerMaterial(vector);

            if (!isSamePlacedMaterial(serverMaterial, attempted)) {
                this.lastConfirmedCancelPlaceTimer.reset();

                addGhostBlock(vector, attempted, "placement-cancelled-or-mismatch", MAX_CANCELLED_PLACE_GHOST_TICKS);

                boolean on = isPlayerStandingOnGhost(vector);
                boolean inside = isPlayerInsideGhost(vector);
                boolean under = isPlayerUnderGhost(vector);
                boolean contact = on || inside || under;

                /*
                 * Near a cancelled placement = sync/exempt context only.
                 * Actual on/inside/under = GroundC-relevant ghost state.
                 */
                if (contact) {
                    this.lastGhostBlockTick = 0;
                    this.nearGhostBlock = true;
                    this.interactingGhostBlock = true;
                    this.onGhostBlock = this.onGhostBlock || on;
                    this.insideGhostBlock = this.insideGhostBlock || inside;
                    this.underGhostBlock = this.underGhostBlock || under;

                    if (isPhysicsGhostMaterial(attempted)) {
                        this.lastGhostLiquidWebTick = 0;
                    }
                }

                if (autoCorrectGhostBlocks && isPlayerCloseTo(vector, 3.0D, 3.0D)) {
                    syncRealBlockToClient(vector);
                }
            }

            this.lastAttemptedPlaceMaterial = null;
            this.currentBlockCords = null;
            this.pendingPlacementTicks = 0;
        }
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

                    boolean serverAir = isAirLike(serverMaterial);
                    boolean clientAir = isStateAirLike(stateName);

                    if (serverAir && !clientAir) {
                        Material clientMaterial = materialFromStateName(stateName);

                        addGhostBlock(
                                vector,
                                clientMaterial != null ? clientMaterial : Material.AIR,
                                "multi-block-client-server-mismatch",
                                MAX_GHOST_BLOCK_TICKS
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

                boolean serverAir = isAirLike(serverMaterial);
                boolean clientAir = isStateAirLike(stateName);

                /*
                 * Server says air, packet says non-air:
                 * client may see a block that server does not.
                 */
                if (serverAir && !clientAir) {
                    Material clientMaterial = materialFromStateName(stateName);

                    addGhostBlock(
                            vector,
                            clientMaterial != null ? clientMaterial : Material.AIR,
                            "block-change-client-server-mismatch",
                            MAX_GHOST_BLOCK_TICKS
                    );

                    if (autoCorrectGhostBlocks && isPlayerCloseTo(vector, 3.0D, 3.0D)) {
                        syncRealBlockToClient(vector);
                    }

                    return;
                }

                /*
                 * Server and client packet agree, so remove stale ghost history.
                 */
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

        if (attempted.contains(MaterialType.WATER.name()) || attempted.contains(MaterialType.WATER_BUCKET.name())) {
            return server.contains(MaterialType.WATER.name()) || server.contains(MaterialType.WATER_BUCKET.name());
        }

        if (attempted.contains(MaterialType.LAVA.name()) || attempted.contains(MaterialType.LAVA_BUCKET.name())) {
            return server.contains(MaterialType.LAVA.name()) || server.contains(MaterialType.LAVA_BUCKET.name());
        }

        return false;
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
                || name.contains("SOUL_SAND");
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
        if (material == null || material == Material.AIR) {
            return false;
        }

        if (material.isBlock()) {
            return true;
        }

        String name = material.name();

        return name.contains("WATER_BUCKET")
                || name.contains("LAVA_BUCKET")
                || name.contains("POWDER_SNOW_BUCKET")
                || name.contains("WATER")
                || name.contains("LAVA")
                || name.contains("WEB")
                || name.contains("COBWEB")
                || name.contains("HONEY")
                || name.contains("SLIME")
                || name.contains("ICE")
                || name.contains("SOUL_SAND");
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