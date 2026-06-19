package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.MiscUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.desync.Desync;
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

    private final ArrayDeque<PendingUnderPlace> pendingUnderPlaces = new ArrayDeque<>();

    private int lastBlockPlaceAttemptTicks = 1000;
    private int lastConfirmedUnderPlaceTicks = 1000;
    private int lastConfirmedUnderPlaceX;
    private int lastConfirmedUnderPlaceY;
    private int lastConfirmedUnderPlaceZ;
    private double lastConfirmedUnderPlaceTopY;
    private Material lastConfirmedUnderPlaceType = Material.AIR;

    // ---- Confirmed under-break tracking ----
    private final ArrayDeque<PendingUnderBreak> pendingUnderBreaks = new ArrayDeque<>();

    private int lastBreakAttemptTicks = 1000;
    private int lastConfirmedUnderBreakTicks = 1000;
    private int lastConfirmedUnderBreakX;
    private int lastConfirmedUnderBreakY;
    private int lastConfirmedUnderBreakZ;
    private Material lastConfirmedUnderBreakOldType = Material.AIR;

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
                }

                confirmPendingUnderPlaces();
                confirmPendingUnderBreaks();
            } finally {
                actionTickQueued = false;
            }
        });
    }

    public boolean hasRecentConfirmedUnderPlace(int ticks) {
        return lastConfirmedUnderPlaceTicks <= ticks;
    }

    public int getBlockPlacePredictionTicks() {
        int transTicks = 0;
        int pingTicks = 0;

        try {
            transTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());
        } catch (Throwable ignored) {
        }

        try {
            pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        } catch (Throwable ignored) {
        }

        return Math.max(3, Math.min(20, 3 + transTicks + pingTicks));
    }

    private void handleBlockPlace(PacketReceiveEvent event) {
        Player player = profile.getPlayer();

        if (player == null) {
            return;
        }

        WrapperPlayClientPlayerBlockPlacement packet;

        try {
            packet = new WrapperPlayClientPlayerBlockPlacement(event);
        } catch (Throwable ignored) {
            return;
        }

        Object position = packet.getBlockPosition();

        if (position == null) {
            return;
        }

        int x = getCoordinate(position, "getX");
        int y = getCoordinate(position, "getY");
        int z = getCoordinate(position, "getZ");

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

        if (world == null) {
            return;
        }

        Block clicked = world.getBlockAt(x, y, z);
        Block placed = clicked.getRelative(face);

        lastBlockPlaceAttemptTicks = 0;

        if (!isPotentialUnderPlacement(placed)) {
            return;
        }

        if (!canPlaceThere(clicked, placed, item)) {
            return;
        }

        pendingUnderPlaces.add(new PendingUnderPlace(
                world.getName(),
                placed.getX(),
                placed.getY(),
                placed.getZ(),
                placed.getType()
        ));

        while (pendingUnderPlaces.size() > 10) {
            pendingUnderPlaces.pollFirst();
        }
    }
    private int increment(int value) {
        return value >= 1000 ? 1000 : value + 1;
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

            if (world == null || !world.getName().equals(place.worldName)) {
                iterator.remove();
                continue;
            }

            Block block = world.getBlockAt(place.x, place.y, place.z);

            if (!isConfirmedPlacedBlock(block, place.oldType)) {
                continue;
            }

            lastConfirmedUnderPlaceTicks = 0;
            lastConfirmedUnderPlaceX = block.getX();
            lastConfirmedUnderPlaceY = block.getY();
            lastConfirmedUnderPlaceZ = block.getZ();
            lastConfirmedUnderPlaceTopY = block.getY() + getBlockTopHeight(block.getType());
            lastConfirmedUnderPlaceType = block.getType();

            iterator.remove();
        }
    }

    private boolean isConfirmedPlacedBlock(Block block, Material oldType) {
        if (block == null || block.getType() == null) {
            return false;
        }

        Material now = block.getType();

        if (now == oldType) {
            return false;
        }

        if (isReplaceable(now)) {
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
        if (block == null || location == null) {
            return false;
        }

        double blockCenterX = block.getX() + 0.5D;
        double blockCenterZ = block.getZ() + 0.5D;
        double dx = Math.abs(blockCenterX - location.getX());
        double dz = Math.abs(blockCenterZ - location.getZ());

        double topY = block.getY() + getBlockTopHeight(block.getType());

        return dx <= 0.95D
                && dz <= 0.95D
                && topY <= location.getY() + 0.15D
                && topY >= location.getY() - 2.25D;
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
        if (item == null || item.getType() == null) {
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

    private int getCoordinate(Object object, String methodName) {
        Object value = invoke(object, methodName);

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return 0;
    }

    private static final class PendingUnderPlace {

        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final Material oldType;
        private int ageTicks;

        private PendingUnderPlace(String worldName, int x, int y, int z, Material oldType) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldType = oldType;
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

        Object pos = packet.getBlockPosition();

        if (pos == null) {
            return;
        }

        int x = getCoordinate(pos, "getX");
        int y = getCoordinate(pos, "getY");
        int z = getCoordinate(pos, "getZ");

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

        if (world == null) {
            return;
        }

        Block target = world.getBlockAt(x, y, z);

        lastBreakAttemptTicks = 0;

        if (!isPotentialUnderBreak(target)) {
            return;
        }

        Material oldType = target.getType();

        if (oldType == null || isReplaceable(oldType)) {
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
            if (world == null || !world.getName().equals(br.worldName)) {
                it.remove();
                continue;
            }

            Block block = world.getBlockAt(br.x, br.y, br.z);
            Material now = block.getType();

            // Confirm break: block changed from old solid-ish type to replaceable/air-like
            if (now == br.oldType) continue;
            if (!isReplaceable(now)) continue;

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
        if (block == null || location == null) return false;

        double blockCenterX = block.getX() + 0.5D;
        double blockCenterZ = block.getZ() + 0.5D;

        double dx = Math.abs(blockCenterX - location.getX());
        double dz = Math.abs(blockCenterZ - location.getZ());

        // For breaks, we want "support blocks" under feet, not above.
        double blockTopY = block.getY() + getBlockTopHeight(block.getType());

        return dx <= 0.95D
                && dz <= 0.95D
                && blockTopY <= location.getY() + 0.15D
                && blockTopY >= location.getY() - 1.25D; // tighter than place (break should be directly under)
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
}