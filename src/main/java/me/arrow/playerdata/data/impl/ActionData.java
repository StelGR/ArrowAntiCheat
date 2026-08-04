package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.nms.NmsInstance;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.MiscUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.desync.Desync;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;

// this is basic action data, for sprinting, but it also detects if you place a block under you
// this is used to account for block placement gravity in Fly A, it's not perfect though as you can clip inside the block and
// bypass fly A and still tower fast upwards, good enough though for now

@Getter
public class ActionData implements Data {

    Profile profile;

    GameMode gameMode;

    @Setter
    @Getter
    boolean allowFlight, sneaking, sprinting, lastSprinting, lastLastSprinting, inInventory;

    Desync desync;

    ItemStack itemInMainHand = MiscUtils.EMPTY_ITEM, itemInOffHand = MiscUtils.EMPTY_ITEM;

    int lastAllowFlightTicks, lastSleepingTicks, lastRidingTicks, sinceLastSprintingTicks, sinceSneakingTicks;

    private static final int PLACE_CONFIRM_STABLE_TICKS = 2;

    private final ArrayDeque<PendingUnderPlace> pendingUnderPlaces = new ArrayDeque<>();

    private int lastBlockPlaceAttemptTicks = 1000;
    private int lastConfirmedUnderPlaceTicks = 1000;
    private int lastConfirmedUnderPlaceX;
    private int lastConfirmedUnderPlaceY;
    private int lastConfirmedUnderPlaceZ;
    private double lastConfirmedUnderPlaceTopY;
    private Material lastConfirmedUnderPlaceType = Material.AIR;
    private String lastConfirmedUnderPlaceWorldName;

    // ---- Confirmed under-break tracking ----
    private final ArrayDeque<PendingUnderBreak> pendingUnderBreaks = new ArrayDeque<>();

    private int lastBreakAttemptTicks = 1000;
    private int lastConfirmedUnderBreakTicks = 1000;
    private int lastConfirmedUnderBreakX;
    private int lastConfirmedUnderBreakY;
    private int lastConfirmedUnderBreakZ;
    private Material lastConfirmedUnderBreakOldType = Material.AIR;

    private final ArrayDeque<PendingBlockUpdateUnder> pendingBlockUpdatesUnder = new ArrayDeque<>();

    private int sinceBlockUpdateUnderTicks = 1000;
    private int sincePistonUpdateTicks = 1000;
    private int lastBlockUpdateUnderX;
    private int lastBlockUpdateUnderY;
    private int lastBlockUpdateUnderZ;
    private Material lastBlockUpdateUnderOldType = Material.AIR;
    private Material lastBlockUpdateUnderNewType = Material.AIR;
    private int lastPistonUpdateX;
    private int lastPistonUpdateY;
    private int lastPistonUpdateZ;
    private Material lastPistonUpdateType = Material.AIR;

    @Getter
    @Setter
    private int duplicatePOSLOOKPacketTicks = 100;

    private volatile boolean actionTickQueued;
    private final AtomicInteger queuedActionTicks = new AtomicInteger();

    public ActionData(Profile profile) {

        this.profile = profile;

        this.desync = new Desync(profile);

        //Initialize

        Player player = profile.getPlayer();

        this.gameMode = player.getGameMode();

        this.allowFlight = Arrow.getInstance().getNmsManager().getNmsInstance().getAllowFlight(player);

        this.lastAllowFlightTicks = this.allowFlight ? 0 : 100;


    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(ENTITY_ACTION)) {
            WrapperPlayClientEntityAction entityAction = new WrapperPlayClientEntityAction(event);

            if (entityAction.getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {

                sprinting = true;
            } else if (entityAction.getAction() == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                sprinting = false;
            }

            if (entityAction.getAction() == WrapperPlayClientEntityAction.Action.START_SNEAKING) {
                sneaking = true;
            } else if (entityAction.getAction() == WrapperPlayClientEntityAction.Action.STOP_SNEAKING) {
                sneaking = false;
            }
        }
        if (event.getPacketType().equals(PLAYER_BLOCK_PLACEMENT)) {
            handleBlockPlace(event);
        }
        if (event.getPacketType().equals(PLAYER_FLYING)
                || event.getPacketType().equals(PLAYER_POSITION)
                || event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PLAYER_ROTATION)) {
            lastLastSprinting = lastSprinting;
            lastSprinting = sprinting;

            if (!isSprinting()) sinceLastSprintingTicks++;
            else sinceLastSprintingTicks = 0;

            if (!isSneaking()) sinceSneakingTicks++;
            else sinceSneakingTicks = 0;

            tickAndConfirmActionPredictionsSafely();
        }
        if (event.getPacketType().equals(CLOSE_WINDOW)) {
            inInventory = false;
        }

        if (event.getPacketType().equals(PLAYER_DIGGING)) {
            handleBlockBreak(event);
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.BLOCK_CHANGE)) {
            handleServerBlockChange(event);
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Server.MULTI_BLOCK_CHANGE)) {
            handleServerMultiBlockChange(event);
        }
    }

    private void runPlayerWorldTask(Player player, Runnable runnable) {
        if (player == null || runnable == null) {
            return;
        }

        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(player)) {
            TaskUtils.player(player, runnable);
            return;
        }

        runnable.run();
    }

    private void tickAndConfirmActionPredictionsSafely() {
        Player player = profile.getPlayer();

        if (player == null) {
            return;
        }

        if (!TaskUtils.isFoliaServer() || TaskUtils.isOwnedByCurrentRegion(player)) {
            tickBlockPlacePrediction();
            confirmPendingUnderPlaces();

            tickBlockBreakPrediction();
            confirmPendingUnderBreaks();

            tickBlockUpdatePrediction();
            confirmPendingBlockUpdatesUnder();
            return;
        }

        queuedActionTicks.incrementAndGet();

        if (actionTickQueued) {
            return;
        }

        actionTickQueued = true;

        TaskUtils.player(player, () -> {
            try {
                int ticks = Math.max(1, queuedActionTicks.getAndSet(0));

                for (int i = 0; i < ticks; i++) {
                    tickBlockPlacePrediction();
                    tickBlockBreakPrediction();
                    tickBlockUpdatePrediction();
                }

                confirmPendingUnderPlaces();
                confirmPendingUnderBreaks();
                confirmPendingBlockUpdatesUnder();
            } finally {
                actionTickQueued = false;
            }
        });
    }

    public boolean hasRecentConfirmedUnderPlace(int ticks) {
        return lastConfirmedUnderPlaceTicks <= ticks
                && isLastConfirmedUnderPlaceStillValid();
    }

    public boolean hasRecentBlockUpdateUnder(int ticks) {
        return sinceBlockUpdateUnderTicks <= ticks;
    }

    public boolean hasRecentConfirmedBlockUpdateUnder(int ticks) {
        return hasRecentBlockUpdateUnder(ticks);
    }

    public boolean hasRecentPistonUpdate(int ticks) {
        return sincePistonUpdateTicks <= ticks;
    }

    private void handleServerBlockChange(PacketSendEvent event) {
        WrapperPlayServerBlockChange blockChange;

        try {
            blockChange = new WrapperPlayServerBlockChange(event);
        } catch (Throwable ignored) {
            return;
        }

        int x = blockChange.getBlockPosition().getX();
        int y = blockChange.getBlockPosition().getY();
        int z = blockChange.getBlockPosition().getZ();

        Material material = null;

        try {
            material = materialFromState(blockChange.getBlockState().getType());
        } catch (Throwable ignored) {
        }

        handleServerBlockUpdate(x, y, z, material);
    }

    private void handleServerMultiBlockChange(PacketSendEvent event) {
        WrapperPlayServerMultiBlockChange multiBlockChange;

        try {
            multiBlockChange = new WrapperPlayServerMultiBlockChange(event);
        } catch (Throwable ignored) {
            return;
        }

        for (WrapperPlayServerMultiBlockChange.EncodedBlock block : multiBlockChange.getBlocks()) {
            Material material = null;

            try {
                material = materialFromState(block.getBlockState(profile.getVersion()).getType());
            } catch (Throwable ignored) {
            }

            handleServerBlockUpdate(block.getX(), block.getY(), block.getZ(), material);
        }
    }

    private void handleServerBlockUpdate(int x, int y, int z, Material newType) {
        if (newType == null || profile.getMovementData() == null) {
            return;
        }

        if (isPendingUnderPlacePosition(x, y, z)) {
            return;
        }

        if (isCorrectiveUnderPlacePacket(x, y, z, newType)) {
            return;
        }

        if (!isPacketPositionInFootSupportArea(x, y, z, newType, 1.25D)) {
            return;
        }

        boolean newSupportMaterial = isSupportMaterial(newType);
        boolean pistonUpdate = isPistonRelated(newType) || profile.getMovementData().isNearPiston();
        boolean supportRemoved = !newSupportMaterial;

        if (!supportRemoved && !pistonUpdate) {
            return;
        }

        pendingBlockUpdatesUnder.add(new PendingBlockUpdateUnder(
                getPlayerWorldName(),
                x,
                y,
                z,
                getKnownOldSupportType(x, y, z),
                newType,
                supportRemoved,
                pistonUpdate
        ));

        while (pendingBlockUpdatesUnder.size() > 16) {
            pendingBlockUpdatesUnder.pollFirst();
        }
    }

    public int getBlockPlacePredictionTicks() {
        int transTicks = profile.getConnectionData().getClientTickTrans();
        int pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);

        return Math.max(3, Math.min(20, 3 + transTicks + pingTicks));
    }

    private void handleBlockPlace(PacketReceiveEvent event) {
        Player player = profile.getPlayer();

        if (player == null) {
            return;
        }

        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);

        Vector3i position = packet.getBlockPosition();

        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();

        if (x == -1 && y == -1 && z == -1) {
            return;
        }

        BlockFace face = readFace(packet);

        if (face == null) {
            return;
        }

        runPlayerWorldTask(player, () -> handleBlockPlaceWorld(player, x, y, z, face));
    }

    private void tickBlockPlacePrediction() {
        lastBlockPlaceAttemptTicks = increment(lastBlockPlaceAttemptTicks);
        lastConfirmedUnderPlaceTicks = increment(lastConfirmedUnderPlaceTicks);

        Iterator<PendingUnderPlace> iterator = pendingUnderPlaces.iterator();

        while (iterator.hasNext()) {
            PendingUnderPlace place = iterator.next();
            place.ageTicks++;

            if (place.ageTicks > getBlockPlacePredictionTicks()) {
                iterator.remove();
            }
        }
    }


    private void handleBlockPlaceWorld(Player player, int x, int y, int z, BlockFace face) {
        if (player == null || !player.isOnline()) {
            return;
        }

        ItemStack item = getHeldBlockItem();

        if (!isBlockItem(item)) {
            return;
        }

        World world = player.getWorld();

        Block clicked = world.getBlockAt(x, y, z);
        Block placed = clicked.getRelative(face);

        if (!isPotentialUnderPlacement(placed)) {
            return;
        }

        Material oldType = placed.getType();

        /*
         * Important:
         * Do not count spam clicks on already-existing support blocks.
         * If this is not replaceable before the click, it is not a new block placement.
         */
        if (!isReplaceable(oldType)) {
            return;
        }

        if (!canTrackUnderPlace(clicked, placed, item)) {
            return;
        }

        /*
         * Only now it is a real possible placement attempt.
         * Previously this was set before validation, so invalid spam could keep resetting it.
         */
        lastBlockPlaceAttemptTicks = 0;

        addOrKeepPendingUnderPlace(
                world.getName(),
                placed.getX(),
                placed.getY(),
                placed.getZ(),
                oldType
        );
    }


    private boolean canTrackUnderPlace(Block clicked, Block placed, ItemStack item) {
        if (clicked == null || placed == null || item == null) {
            return false;
        }

        if (!isBlockItem(item)) {
            return false;
        }

        /*
         * Must be placing against a real block.
         */
        if (isReplaceable(clicked.getType())) {
            return false;
        }

        /*
         * Must start from air/replaceable.
         * This prevents spam clicking existing blocks from resetting placement tracking.
         */
        return isReplaceable(placed.getType());
    }

    private void addOrKeepPendingUnderPlace(String worldName, int x, int y, int z, Material oldType) {
        if (oldType == null) {
            oldType = Material.AIR;
        }

        Iterator<PendingUnderPlace> iterator = pendingUnderPlaces.iterator();

        while (iterator.hasNext()) {
            PendingUnderPlace existing = iterator.next();

            if (!samePendingPlace(existing, worldName, x, y, z)) {
                continue;
            }

            /*
             * Existing pending started from air/replaceable.
             * Keep it. Do not reset age/stable ticks.
             *
             * This is the spam-click fix:
             * spamming the same under block should not replace/evict the original
             * valid pending placement before it confirms.
             */
            if (isReplaceable(existing.oldType)) {
                return;
            }

            /*
             * Existing pending is useless because it started from a solid block.
             * Remove it so a real replaceable -> solid transition can be tracked.
             */
            iterator.remove();
            break;
        }

        pendingUnderPlaces.add(new PendingUnderPlace(
                worldName,
                x,
                y,
                z,
                oldType
        ));

        /*
         * 3x3 under-foot support area is only 9 blocks.
         * Keep this high enough that spam cannot evict a valid pending before
         * PLACE_CONFIRM_STABLE_TICKS.
         */
        while (pendingUnderPlaces.size() > 32) {
            removeWorstPendingUnderPlace();
        }
    }

    private boolean samePendingPlace(PendingUnderPlace place, String worldName, int x, int y, int z) {
        if (place == null) {
            return false;
        }

        if (place.x != x || place.y != y || place.z != z) {
            return false;
        }

        if (place.worldName == null || worldName == null) {
            return true;
        }

        return place.worldName.equals(worldName);
    }

    private void removeWorstPendingUnderPlace() {
        PendingUnderPlace worst = null;

        for (PendingUnderPlace place : pendingUnderPlaces) {
            /*
             * Prefer removing invalid/noisy entries first.
             */
            if (!isReplaceable(place.oldType)) {
                worst = place;
                break;
            }

            if (worst == null || place.ageTicks > worst.ageTicks) {
                worst = place;
            }
        }

        if (worst != null) {
            pendingUnderPlaces.remove(worst);
        } else {
            pendingUnderPlaces.pollFirst();
        }
    }

    private boolean isLastConfirmedUnderPlaceStillValid() {
        Player player = profile.getPlayer();

        if (player == null || !player.isOnline() || profile.getMovementData() == null) {
            return false;
        }

        World world = player.getWorld();

        if (lastConfirmedUnderPlaceWorldName != null
                && !lastConfirmedUnderPlaceWorldName.equals(world.getName())) {
            return false;
        }

        Block block = world.getBlockAt(
                lastConfirmedUnderPlaceX,
                lastConfirmedUnderPlaceY,
                lastConfirmedUnderPlaceZ
        );

        Material actual = block.getType();

        /*
         * WorldGuard / cancelled placement / corrective packet case:
         * if the server did not really keep a support block here, this must not count.
         */
        if (!isSupportMaterial(actual)) {
            return false;
        }

        /*
         * Do not allow an old confirmed block far away from the player to keep
         * exempting gravity or jump checks.
         */
        return isPacketPositionInFootSupportArea(
                block.getX(),
                block.getY(),
                block.getZ(),
                actual,
                2.25D
        );
    }

    private int increment(int value) {
        return value >= 1000 ? 1000 : value + 1;
    }

    private void tickBlockUpdatePrediction() {
        sinceBlockUpdateUnderTicks = increment(sinceBlockUpdateUnderTicks);
        sincePistonUpdateTicks = increment(sincePistonUpdateTicks);

        Iterator<PendingBlockUpdateUnder> iterator = pendingBlockUpdatesUnder.iterator();

        while (iterator.hasNext()) {
            PendingBlockUpdateUnder update = iterator.next();
            update.ageTicks++;

            if (update.ageTicks > getBlockUpdateConfirmationTicks()) {
                iterator.remove();
            }
        }
    }

    private int getBlockUpdateConfirmationTicks() {
        int transTicks = 0;
        int pingTicks = 0;

        try { transTicks = Math.max(0, profile.getConnectionData().getClientTickTrans()); } catch (Throwable ignored) {}
        try { pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50); } catch (Throwable ignored) {}

        return Math.max(3, Math.min(20, 3 + transTicks + pingTicks));
    }

    private void confirmPendingBlockUpdatesUnder() {
        if (pendingBlockUpdatesUnder.isEmpty()) {
            return;
        }

        Player player = profile.getPlayer();

        if (player == null || !player.isOnline()) {
            pendingBlockUpdatesUnder.clear();
            return;
        }

        World world = player.getWorld();
        Iterator<PendingBlockUpdateUnder> iterator = pendingBlockUpdatesUnder.iterator();

        while (iterator.hasNext()) {
            PendingBlockUpdateUnder update = iterator.next();

            if (update.worldName != null && !update.worldName.equals(world.getName())) {
                iterator.remove();
                continue;
            }

            NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();

            Block block = world.getBlockAt(update.x, update.y, update.z);
            Material actualType = nms.getType(block);

            if (isCorrectiveUnderPlacePacket(update.x, update.y, update.z, actualType)) {
                iterator.remove();
                continue;
            }

            if (update.supportRemoved) {
                if (isSupportMaterial(actualType)) {
                    continue;
                }

                if (!isPacketPositionInFootSupportArea(update.x, update.y, update.z, update.oldType, 1.25D)) {
                    continue;
                }

                sinceBlockUpdateUnderTicks = 0;
                lastBlockUpdateUnderX = update.x;
                lastBlockUpdateUnderY = update.y;
                lastBlockUpdateUnderZ = update.z;
                lastBlockUpdateUnderOldType = update.oldType == null ? Material.AIR : update.oldType;
                lastBlockUpdateUnderNewType = actualType;
            }

            if (update.pistonUpdate && sameMaterialOrAirFamily(actualType, update.newType)) {
                Material supportReference = isSupportMaterial(actualType) ? actualType : update.oldType;

                if (!isPacketPositionInFootSupportArea(update.x, update.y, update.z, supportReference, 1.25D)) {
                    continue;
                }

                sincePistonUpdateTicks = 0;
                lastPistonUpdateX = update.x;
                lastPistonUpdateY = update.y;
                lastPistonUpdateZ = update.z;
                lastPistonUpdateType = actualType;
            }

            iterator.remove();
        }
    }

    private void confirmPendingUnderPlaces() {
        if (pendingUnderPlaces.isEmpty()) {
            return;
        }

        Player player = profile.getPlayer();

        if (player == null || !player.isOnline()) {
            pendingUnderPlaces.clear();
            return;
        }

        Iterator<PendingUnderPlace> iterator = pendingUnderPlaces.iterator();

        while (iterator.hasNext()) {
            PendingUnderPlace place = iterator.next();

            World world = player.getWorld();

            if (!world.getName().equals(place.worldName)) {
                iterator.remove();
                continue;
            }

            Block block = world.getBlockAt(place.x, place.y, place.z);

            if (!isConfirmedPlacedBlock(block, place.oldType)) {
                place.stableTicks = 0;
                place.stableType = Material.AIR;
                continue;
            }

            Material now = block.getType();

            if (place.stableType != now) {
                place.stableType = now;
                place.stableTicks = 1;
                continue;
            }

            place.stableTicks++;

            if (place.stableTicks < PLACE_CONFIRM_STABLE_TICKS) {
                continue;
            }

            lastConfirmedUnderPlaceTicks = 0;
            lastConfirmedUnderPlaceX = block.getX();
            lastConfirmedUnderPlaceY = block.getY();
            lastConfirmedUnderPlaceZ = block.getZ();
            lastConfirmedUnderPlaceTopY = block.getY() + getBlockTopHeight(now);
            lastConfirmedUnderPlaceType = now;
            lastConfirmedUnderPlaceWorldName = world.getName();

            iterator.remove();
        }
    }

    private boolean isConfirmedPlacedBlock(Block block, Material oldType) {
        if (block == null) {
            return false;
        }

        Material now = block.getType();

        /*
         * Only confirm replaceable -> support.
         * This prevents clicks on already-existing blocks from being treated as a
         * fresh under-place.
         */
        if (!isReplaceable(oldType)) {
            return false;
        }

        if (sameMaterialOrAirFamily(now, oldType) || now == oldType) {
            return false;
        }

        if (!isSupportMaterial(now)) {
            return false;
        }

        return now.isBlock();
    }

    private boolean isPotentialUnderPlacement(Block block) {
        if (block == null || profile.getMovementData() == null) {
            return false;
        }

        return isUnderLocation(block, profile.getMovementData().getLocation())
                || isUnderLocation(block, profile.getMovementData().getLastLocation())
                || isUnderLocation(block, profile.getMovementData().getLastLastLocation());
    }

    private boolean isUnderLocation(Block block, CustomLocation location) {
        return isInFootSupportArea(block, location, 2.25D);
    }

    private boolean isInFootSupportArea(Block block, CustomLocation location, double belowRange) {
        return isInFootSupportArea(block, location, belowRange, block == null ? null : block.getType());
    }

    private boolean isInFootSupportArea(Block block, CustomLocation location, double belowRange, Material supportType) {
        if (block == null || location == null) {
            return false;
        }

        if (location.getWorld() != null) {
            block.getWorld();
            if (!block.getWorld().equals(location.getWorld())) {
                return false;
            }
        }

        int dx = Math.abs(block.getX() - location.getBlockX());
        int dz = Math.abs(block.getZ() - location.getBlockZ());

        double topY = block.getY() + getBlockTopHeight(supportType);

        return dx <= 1
                && dz <= 1
                && topY <= location.getY() + 0.15D
                && topY >= location.getY() - belowRange;
    }

    private boolean isPacketPositionInFootSupportArea(int x, int y, int z, Material material, double belowRange) {
        return isPacketPositionInFootSupportArea(x, y, z, material, profile.getMovementData().getLocation(), belowRange)
                || isPacketPositionInFootSupportArea(x, y, z, material, profile.getMovementData().getLastLocation(), belowRange)
                || isPacketPositionInFootSupportArea(x, y, z, material, profile.getMovementData().getLastLastLocation(), belowRange);
    }

    private boolean isPacketPositionInFootSupportArea(int x, int y, int z, Material material, CustomLocation location, double belowRange) {
        if (location == null) {
            return false;
        }

        int dx = Math.abs(x - location.getBlockX());
        int dz = Math.abs(z - location.getBlockZ());
        if (dx > 1 || dz > 1) {
            return false;
        }

        if (isSupportMaterial(material)) {
            return isSupportTopNearFeet(y + getBlockTopHeight(material), location, belowRange);
        }

        return isSupportTopNearFeet(y + 1.0D, location, belowRange)
                || isSupportTopNearFeet(y + 0.5D, location, belowRange)
                || isSupportTopNearFeet(y + 0.1875D, location, belowRange)
                || isSupportTopNearFeet(y + 0.125D, location, belowRange)
                || isSupportTopNearFeet(y + 0.0625D, location, belowRange);
    }

    private boolean isSupportTopNearFeet(double topY, CustomLocation location, double belowRange) {
        return topY <= location.getY() + 0.15D
                && topY >= location.getY() - belowRange;
    }

    private boolean canPlaceThere(Block clicked, Block placed, ItemStack item) {
        if (clicked == null || placed == null || item == null) {
            return false;
        }

        if (!isBlockItem(item)) {
            return false;
        }

        if (isReplaceable(clicked.getType())) {
            return false;
        }

        if (!isReplaceable(placed.getType())) {
            return false;
        }

        return !wouldIntersectPlayer(placed);
    }

    private boolean wouldIntersectPlayer(Block block) {
        if (profile.getMovementData() == null) {
            return false;
        }

        return intersectsPlayer(block, profile.getMovementData().getLocation())
                || intersectsPlayer(block, profile.getMovementData().getLastLocation());
    }

    private boolean intersectsPlayer(Block block, CustomLocation location) {
        if (block == null || location == null) {
            return false;
        }

        double playerMinX = location.getX() - 0.30D;
        double playerMaxX = location.getX() + 0.30D;
        double playerMinY = location.getY();
        double playerMaxY = location.getY() + 1.80D;
        double playerMinZ = location.getZ() - 0.30D;
        double playerMaxZ = location.getZ() + 0.30D;

        double blockMinX = block.getX();
        double blockMaxX = block.getX() + 1.0D;
        double blockMinY = block.getY();
        double blockMaxY = block.getY() + getBlockTopHeight(block.getType());
        double blockMinZ = block.getZ();
        double blockMaxZ = block.getZ() + 1.0D;

        return blockMaxX > playerMinX
                && blockMinX < playerMaxX
                && blockMaxY > playerMinY
                && blockMinY < playerMaxY
                && blockMaxZ > playerMinZ
                && blockMinZ < playerMaxZ;
    }

    private double getBlockTopHeight(Material material) {
        if (material == null) {
            return 1.0D;
        }

        String name = material.name();

        if (name.contains("SLAB") && !name.contains("DOUBLE")) {
            return 0.5D;
        }

        if (name.contains("CARPET")) {
            return 0.0625D;
        }

        if (name.equals("SNOW")) {
            return 0.125D;
        }

        if (name.contains("TRAPDOOR")) {
            return 0.1875D;
        }

        return 1.0D;
    }

    private ItemStack getHeldBlockItem() {
        Player player = profile.getPlayer();

        ItemStack main = MiscUtils.EMPTY_ITEM;
        ItemStack off = MiscUtils.EMPTY_ITEM;

        try {
            Method method = player.getInventory().getClass().getMethod("getItemInMainHand");
            main = (ItemStack) method.invoke(player.getInventory());
        } catch (Throwable ignored) {
            try {
                main = player.getItemInHand();
            } catch (Throwable ignoredToo) {
            }
        }

        try {
            Method method = player.getInventory().getClass().getMethod("getItemInOffHand");
            off = (ItemStack) method.invoke(player.getInventory());
        } catch (Throwable ignored) {
        }

        itemInMainHand = main == null ? MiscUtils.EMPTY_ITEM : main;
        itemInOffHand = off == null ? MiscUtils.EMPTY_ITEM : off;

        if (isBlockItem(itemInMainHand)) {
            return itemInMainHand;
        }

        if (isBlockItem(itemInOffHand)) {
            return itemInOffHand;
        }

        return MiscUtils.EMPTY_ITEM;
    }

    private boolean isBlockItem(ItemStack item) {
        if (item == null) {
            return false;
        }

        Material material = item.getType();
        String name = material.name();

        return material.isBlock()
                && !name.equals("AIR")
                && !name.equals("CAVE_AIR")
                && !name.equals("VOID_AIR")
                && !name.contains("WATER")
                && !name.contains("LAVA")
                && !name.contains("FIRE");
    }

    private boolean isReplaceable(Material material) {
        if (material == null) {
            return true;
        }

        String name = material.name();

        if (name.equals("AIR")
                || name.equals("CAVE_AIR")
                || name.equals("VOID_AIR")
                || name.equals("WATER")
                || name.equals("STATIONARY_WATER")
                || name.equals("LAVA")
                || name.equals("STATIONARY_LAVA")
                || name.equals("FIRE")
                || name.equals("SOUL_FIRE")
                || name.equals("SNOW")
                || name.equals("TALL_GRASS")
                || name.equals("LONG_GRASS")
                || name.equals("DEAD_BUSH")
                || name.equals("FERN")
                || name.equals("LARGE_FERN")
                || name.equals("VINE")
                || name.equals("REDSTONE")
                || name.equals("TRIPWIRE")) {
            return true;
        }

        try {
            return !material.isSolid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isSupportMaterial(Material material) {
        if (material == null || !material.isBlock()) {
            return false;
        }

        String name = material.name();

        if (MaterialType.isMaterialEqual(name, MaterialType.AIR)
                || MaterialType.isMaterial(name, MaterialType.LIQUID)
                || MaterialType.isMaterialEqual(name, MaterialType.TRANSPARENT)) {
            return false;
        }

        if (isKnownThinSupport(name)) {
            return true;
        }

        try {
            if (PEMaterials.isNonFullCollision(material)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            return material.isSolid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isKnownThinSupport(String name) {
        if (name == null) {
            return false;
        }

        return name.equals("CARPET")
                || name.endsWith("_CARPET")
                || name.equals("SNOW")
                || name.equals("LILY_PAD")
                || name.equals("WATER_LILY")
                || name.equals("SCAFFOLDING")
                || name.equals("FARMLAND")
                || name.equals("SOUL_SAND")
                || name.equals("HONEY_BLOCK")
                || name.endsWith("_SLAB")
                || name.equals("STEP")
                || name.equals("WOODEN_SLAB");
    }

    private boolean isPistonRelated(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.contains("PISTON")
                || name.contains("MOVING_PISTON")
                || name.contains("PISTON_HEAD")
                || name.contains("PISTON_EXTENSION");
    }

    private Material materialFromState(StateType type) {
        if (type == null) {
            return null;
        }

        return materialFromStateName(type.getName());
    }

    private Material materialFromStateName(String stateName) {
        if (stateName == null) {
            return null;
        }

        String name = stateName.trim();

        int namespace = name.indexOf(':');
        if (namespace != -1) {
            name = name.substring(namespace + 1);
        }

        name = name.toUpperCase(Locale.ROOT).replace(' ', '_');

        Material material = Material.matchMaterial(name);

        if (material != null) {
            return material;
        }

        return Material.matchMaterial("LEGACY_" + name);
    }

    private String getPlayerWorldName() {
        Player player = profile.getPlayer();

        if (player == null) {
            return null;
        } else {
            player.getWorld();
        }

        return player.getWorld().getName();
    }

    private boolean sameMaterialOrAirFamily(Material first, Material second) {
        if (first == second) {
            return true;
        }

        if (first == null || second == null) {
            return false;
        }

        if (isAirLike(first) && isAirLike(second)) {
            return true;
        }

        return first.name().equals(second.name());
    }

    private boolean isAirLike(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.equals("AIR")
                || name.equals("CAVE_AIR")
                || name.equals("VOID_AIR")
                || name.equals("LEGACY_AIR");
    }

    private boolean isCorrectiveUnderPlacePacket(int x, int y, int z, Material material) {
        for (PendingUnderPlace place : pendingUnderPlaces) {
            if (place.x != x || place.y != y || place.z != z) {
                continue;
            }

            if (sameMaterialOrAirFamily(material, place.oldType)) {
                return true;
            }
        }

        return false;
    }

    private boolean isPendingUnderPlacePosition(int x, int y, int z) {
        for (PendingUnderPlace place : pendingUnderPlaces) {
            if (place.x == x && place.y == y && place.z == z) {
                return true;
            }
        }

        return false;
    }

    private Material getKnownOldSupportType(int x, int y, int z) {
        for (PendingUnderBreak pendingBreak : pendingUnderBreaks) {
            if (pendingBreak.x == x && pendingBreak.y == y && pendingBreak.z == z) {
                return pendingBreak.oldType;
            }
        }

        return Material.AIR;
    }

    private BlockFace readFace(WrapperPlayClientPlayerBlockPlacement packet) {
        Object raw = invoke(packet, "getFace");

        if (raw == null) {
            raw = invoke(packet, "getBlockFace");
        }

        if (raw == null) {
            raw = invoke(packet, "getDirection");
        }

        if (raw == null) {
            return null;
        }

        if (raw instanceof BlockFace) {
            return (BlockFace) raw;
        }

        String value = String.valueOf(raw).toUpperCase();

        if (value.contains("DOWN") || value.equals("0")) return BlockFace.DOWN;
        if (value.contains("UP") || value.equals("1")) return BlockFace.UP;
        if (value.contains("NORTH") || value.equals("2")) return BlockFace.NORTH;
        if (value.contains("SOUTH") || value.equals("3")) return BlockFace.SOUTH;
        if (value.contains("WEST") || value.equals("4")) return BlockFace.WEST;
        if (value.contains("EAST") || value.equals("5")) return BlockFace.EAST;

        return null;
    }

    private Object invoke(Object object, String methodName) {
        if (object == null || methodName == null) {
            return null;
        }

        try {
            Method method = object.getClass().getMethod(methodName);
            return method.invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }


    private static final class PendingUnderPlace {

        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final Material oldType;
        private int ageTicks;
        private int stableTicks;
        private Material stableType = Material.AIR;

        private PendingUnderPlace(String worldName, int x, int y, int z, Material oldType) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldType = oldType;
        }
    }

    private static final class PendingBlockUpdateUnder {

        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final Material oldType;
        private final Material newType;
        private final boolean supportRemoved;
        private final boolean pistonUpdate;
        private int ageTicks;

        private PendingBlockUpdateUnder(String worldName,
                                        int x,
                                        int y,
                                        int z,
                                        Material oldType,
                                        Material newType,
                                        boolean supportRemoved,
                                        boolean pistonUpdate) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldType = oldType;
            this.newType = newType;
            this.supportRemoved = supportRemoved;
            this.pistonUpdate = pistonUpdate;
        }
    }

    private void handleBlockBreak(PacketReceiveEvent event) {
        Player player = profile.getPlayer();

        if (player == null) {
            return;
        }

        WrapperPlayClientPlayerDigging packet;

        try {
            packet = new WrapperPlayClientPlayerDigging(event);
        } catch (Throwable ignored) {
            return;
        }

        DiggingAction action = packet.getAction();

        if (action != DiggingAction.START_DIGGING
                && action != DiggingAction.FINISHED_DIGGING) {
            return;
        }

        Vector3i pos = packet.getBlockPosition();

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        if (x == -1 && y == -1 && z == -1) {
            return;
        }

        runPlayerWorldTask(player, () -> handleBlockBreakWorld(player, x, y, z));
    }

    private void handleBlockBreakWorld(Player player, int x, int y, int z) {
        if (player == null || !player.isOnline()) {
            return;
        }

        World world = player.getWorld();

        Block target = world.getBlockAt(x, y, z);

        lastBreakAttemptTicks = 0;

        if (!isPotentialUnderBreak(target)) {
            return;
        }

        Material oldType = target.getType();

        if (!isSupportMaterial(oldType)) {
            return;
        }

        pendingUnderBreaks.add(new PendingUnderBreak(
                world.getName(),
                x,
                y,
                z,
                oldType
        ));

        while (pendingUnderBreaks.size() > 12) {
            pendingUnderBreaks.pollFirst();
        }
    }

    private void tickBlockBreakPrediction() {
        lastBreakAttemptTicks = increment(lastBreakAttemptTicks);
        lastConfirmedUnderBreakTicks = increment(lastConfirmedUnderBreakTicks);

        Iterator<PendingUnderBreak> it = pendingUnderBreaks.iterator();
        while (it.hasNext()) {
            PendingUnderBreak br = it.next();
            br.ageTicks++;

            // break confirmation should be quick but ping tolerant
            if (br.ageTicks > getBlockBreakPredictionTicks()) {
                it.remove();
            }
        }
    }

    private int getBlockBreakPredictionTicks() {
        // Similar to place, but a bit more tolerant (break animation + server update timing)
        int transTicks = 0;
        int pingTicks = 0;

        try { transTicks = Math.max(0, profile.getConnectionData().getClientTickTrans()); } catch (Throwable ignored) {}
        try { pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50); } catch (Throwable ignored) {}

        // tuned: break confirmation tends to arrive slightly later than place
        return Math.max(4, Math.min(30, 5 + transTicks + pingTicks));
    }

    private void confirmPendingUnderBreaks() {
        if (pendingUnderBreaks.isEmpty()) return;

        Player player = profile.getPlayer();
        if (player == null || !player.isOnline()) {
            pendingUnderBreaks.clear();
            return;
        }

        Iterator<PendingUnderBreak> it = pendingUnderBreaks.iterator();
        while (it.hasNext()) {
            PendingUnderBreak br = it.next();

            World world = player.getWorld();
            if (!world.getName().equals(br.worldName)) {
                it.remove();
                continue;
            }

            Block block = world.getBlockAt(br.x, br.y, br.z);
            Material now = block.getType();

            // Confirm break: support block changed into non-support/air-like.
            if (now == br.oldType) continue;
            if (isSupportMaterial(now)) continue;

            lastConfirmedUnderBreakTicks = 0;
            lastConfirmedUnderBreakX = br.x;
            lastConfirmedUnderBreakY = br.y;
            lastConfirmedUnderBreakZ = br.z;
            lastConfirmedUnderBreakOldType = br.oldType;

            it.remove();
        }
    }

    private boolean isPotentialUnderBreak(Block block) {
        if (block == null || profile.getMovementData() == null) return false;

        // We only care about blocks that support the player (below-ish),
        // not random breaks around them.
        return isUnderBreakLocation(block, profile.getMovementData().getLocation())
                || isUnderBreakLocation(block, profile.getMovementData().getLastLocation())
                || isUnderBreakLocation(block, profile.getMovementData().getLastLastLocation());
    }

    private boolean isUnderBreakLocation(Block block, CustomLocation location) {
        return isInFootSupportArea(block, location, 1.25D);
    }

    private static final class PendingUnderBreak {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final Material oldType;
        private int ageTicks;

        private PendingUnderBreak(String worldName, int x, int y, int z, Material oldType) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldType = oldType;
        }
    }

    public boolean hasRecentConfirmedUnderBreak(int ticks) {
        return lastConfirmedUnderBreakTicks <= ticks;
    }

    public boolean hasRecentUnderPlaceSupport(int ticks) {
        if (hasRecentConfirmedUnderPlace(ticks)) {
            return true;
        }

        return hasRecentPendingUnderPlaceSupport(ticks);
    }

    public boolean hasRecentTowerBlockPlace(int supportTicks, int attemptTicks) {
        return hasRecentUnderPlaceSupport(supportTicks)
                || hasRecentPendingUnderPlaceAttempt(attemptTicks);
    }

    public boolean hasRecentPendingUnderPlaceSupport(int ticks) {
        Player player = profile.getPlayer();

        if (player == null || !player.isOnline() || profile.getMovementData() == null) {
            return false;
        }

        World world = player.getWorld();

        for (PendingUnderPlace place : pendingUnderPlaces) {
            if (place.ageTicks > ticks) {
                continue;
            }

            if (place.worldName != null && !place.worldName.equals(world.getName())) {
                continue;
            }

            Block block = world.getBlockAt(place.x, place.y, place.z);
            Material actual = block.getType();

            /*
             * WorldGuard/cancelled place case:
             * if the server did not really keep a support block, do not count it.
             */
            if (!isSupportMaterial(actual)) {
                continue;
            }

            /*
             * Must be a real replaceable -> support change.
             */
            if (!isReplaceable(place.oldType)) {
                continue;
            }

            if (sameMaterialOrAirFamily(actual, place.oldType) || actual == place.oldType) {
                continue;
            }

            if (!isPacketPositionInFootSupportArea(place.x, place.y, place.z, actual, 2.25D)) {
                continue;
            }

            return true;
        }

        return false;
    }

    public boolean hasRecentPendingUnderPlaceAttempt(int ticks) {
        if (lastBlockPlaceAttemptTicks > ticks) {
            return false;
        }

        Player player = profile.getPlayer();

        if (player == null || !player.isOnline() || profile.getMovementData() == null) {
            return false;
        }

        World world = player.getWorld();

        for (PendingUnderPlace place : pendingUnderPlaces) {
            if (place.ageTicks > ticks) {
                continue;
            }

            if (place.worldName != null && !place.worldName.equals(world.getName())) {
                continue;
            }

            if (!isReplaceable(place.oldType)) {
                continue;
            }

            /*
             * This is intentionally only an attempt check.
             * Use it only for the first exact 1.8 tower tick, not as a general gravity exempt.
             */
            if (!isPacketPositionInFootSupportArea(place.x, place.y, place.z, Material.STONE, 2.25D)) {
                continue;
            }

            return true;
        }

        return false;
    }
}
