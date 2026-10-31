package me.arrow.playerdata.processors.impl;

import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.processors.Processor;
import me.arrow.tasks.TickTask;
import me.arrow.utils.MathUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.custom.materials.PEMaterials;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import static me.arrow.utils.customutils.OtherUtility.setbackDebug;

public class SetbackProcessor implements Processor {

    private final SampleList<CustomLocation> solidGroundHistory = new SampleList<>(20, true);

    @Getter
    @Setter
    private CustomLocation lastSolidGroundLocation;

    @Getter
    @Setter
    private CustomLocation lastServerGroundLocation;

    @Getter
    @Setter
    private CustomLocation lastSafeGroundLocation;

    @Getter
    @Setter
    private CustomLocation lastSetbackTarget;

    final Profile profile;
    int lastSetbackTicks = -1;
    int lastStoredLocationTicks = -1;
    boolean setbackDone;

    @Getter
    private boolean setbackAwaitingMatch;

    @Getter
    @Setter
    public int flags = 0;

    public SetbackProcessor(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void process() {
        // Safe ground recording is performed post-check in recordSafeGroundPostCheck()
    }

    /**
     * Safely evaluates and records genuine solid ground locations AFTER all checks have run
     * on the incoming flying packet. Cheated packets (flagged, teleport-clashing, or debounced)
     * and packets touching ghostblocks or mid-air positions are strictly rejected.
     */
    public void recordSafeGroundPostCheck() {
        if (profile.isFlaggedThisPacket()) {
            return;
        }

        if (this.setbackAwaitingMatch) {
            return;
        }

        if (profile.getTeleportData() != null
                && (profile.getTeleportData().isTeleporting() || profile.getTeleportData().hasPendingTeleports())) {
            return;
        }

        MovementData movementData = profile.getMovementData();
        if (movementData == null) {
            return;
        }

        CustomLocation location = movementData.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        // Must be genuinely standing on real solid server ground (never on ghostblocks or in mid-air)
        if (!isStandingOnRealSolidGround(location)) {
            return;
        }

        // Anti-spoof distance check: reject anomalous single-tick teleport leaps
        if (movementData.getLastLocation() != null
                && movementData.getLastLocation().getWorld() != null
                && movementData.getLastLocation().getWorld().equals(location.getWorld())) {
            double tickDistSq = movementData.getLastLocation().distanceSquared(location);
            if (tickDistSq > 36.0) {
                return;
            }
        }

        // Verified genuine solid ground!
        this.lastSolidGroundLocation = location.clone();
        this.lastServerGroundLocation = location.clone();
        this.lastSafeGroundLocation = location.clone();
        this.solidGroundHistory.add(location.clone());
        movementData.setLastSetBackLocation(location.clone());
        this.setbackDone = false;
        this.lastStoredLocationTicks = TickTask.getCurrentTick();
    }

    /**
     * Verifies whether the player is genuinely standing on real, solid server ground:
     * - Server physics confirms not in air (customAirTicks == 0, customInAir == false)
     * - Not on any ghost block (profile.isOnGhostBlock() == false, sinceOnGhostBlock >= 3)
     * - Block directly beneath feet is solid or has collision in ChunkCache
     * - Feet are resting on the block's top surface or bounding box
     * - Headroom above feet is clear and passable (not suffocating inside an occluding full block)
     */
    public boolean isStandingOnRealSolidGround(CustomLocation loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }

        MovementData movementData = profile.getMovementData();
        if (movementData == null) {
            return false;
        }

        // 1. Anticheat collision flags: server physics must confirm player is not in air
        if (movementData.isCustomInAir() || movementData.getCustomAirTicks() > 0) {
            return false;
        }

        if (movementData.isNearBoat() || movementData.isNearClimbable()) {
            return false;
        }

        // 2. Ghostblock checks: strictly reject if standing on a ghost block
        if (profile.isOnGhostBlock()) {
            return false;
        }

        if (profile.getBlockProcessor() != null && profile.getBlockProcessor().isOnGhostBlock()) {
            return false;
        }

        if (profile.getClientWorldTracker() != null
                && profile.getClientWorldTracker().getCollisionResult().onGhostBlock) {
            return false;
        }

        if (movementData.getSinceOnGhostBlock() < 3) {
            return false;
        }

        // 3. Ground block check directly beneath the player's feet
        World world = loc.getWorld();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y - 0.01D);
        int blockZ = (int) Math.floor(z);

        Material mat = ChunkCache.get().getBlock(world, blockX, blockY, blockZ);
        Material matBelow = ChunkCache.get().getBlock(world, blockX, blockY - 1, blockZ);

        boolean groundFound = isMaterialSolidGround(mat) || isMaterialSolidGround(matBelow);

        if (!groundFound) {
            // Check bounding box footprint corners (±0.25) in case standing on an edge/perimeter
            double[][] corners = {{-0.25, -0.25}, {-0.25, 0.25}, {0.25, -0.25}, {0.25, 0.25}};
            for (double[] c : corners) {
                int cx = (int) Math.floor(x + c[0]);
                int cz = (int) Math.floor(z + c[1]);
                Material cMat = ChunkCache.get().getBlock(world, cx, blockY, cz);
                Material cMatBelow = ChunkCache.get().getBlock(world, cx, blockY - 1, cz);
                if (isMaterialSolidGround(cMat) || isMaterialSolidGround(cMatBelow)) {
                    groundFound = true;
                    mat = cMat;
                    blockX = cx;
                    blockZ = cz;
                    break;
                }
            }
        }

        // Also check if collision processor already verified exact ground support
        if (!groundFound && movementData.getNearbyBlocksResult() != null
                && (movementData.getNearbyBlocksResult().hasExactGroundSupport()
                || movementData.getNearbyBlocksResult().hasLandingGroundSupport())) {
            groundFound = true;
        }

        // If ChunkCache is still caching chunks around this area (mat is null), trust collision result
        if (!groundFound && mat == null && !movementData.isCustomInAir() && movementData.getCustomAirTicks() == 0) {
            groundFound = true;
        }

        if (!groundFound) {
            return false;
        }

        if (mat == Material.LAVA || mat == Material.FIRE || mat == Material.CACTUS) {
            return false;
        }

        // 4. Headroom check: ensure player's torso is not suffocating inside a full occluding block
        if (!profile.isCrawling() && !profile.isSneaking()) {
            int torsoY = (int) Math.floor(y + 0.6D);
            Material torsoMat = ChunkCache.get().getBlock(world, blockX, torsoY, blockZ);
            return torsoMat == null || !torsoMat.isOccluding();
        }

        return true;
    }

    public static boolean isMaterialSolidGround(Material mat) {
        if (mat == null || mat == Material.AIR) {
            return false;
        }
        if (mat == Material.LAVA || mat == Material.FIRE || mat == Material.CACTUS) {
            return false;
        }
        if (mat.isSolid()) {
            return true;
        }
        return PEMaterials.hasPotentialCollision(mat);
    }

    public void causeSetBack(String reason) {
        setback(reason);
    }

    public void setback(String reason) {
        try {
            Player player = profile.getPlayer();
            if (player == null || !player.isOnline()) {
                return;
            }

            int currentTick = TickTask.getCurrentTick();

            // 1. Rate-limiting: prevent spamming duplicate setbacks within 5 ticks
            if (this.lastSetbackTicks != -1 && MathUtils.elapsedTicks(this.lastSetbackTicks) < 5) {
                return;
            }

            // 2. Debounce: if a setback is already dispatched and awaiting client landing, don't spam duplicate teleports
            if (this.setbackAwaitingMatch && MathUtils.elapsedTicks(this.lastSetbackTicks) < 20) {
                return;
            }

            // 3. Teleport clash protection: if there is an external server/plugin teleport pending (e.g. /tp, warp, ender pearl),
            // let that legitimate teleport proceed instead of conflicting with old coordinates
            if (profile.getTeleportData() != null && profile.getTeleportData().hasPendingExternalTeleports()) {
                setbackDebug(profile, "&eSetback suppressed: external server teleport pending");
                return;
            }

            CustomLocation target = getCustomLocation(player);
            if (target == null) {
                return;
            }

            this.lastSetbackTicks = currentTick;
            this.setbackAwaitingMatch = true;
            this.lastSetbackTarget = target.clone();
            this.setbackDone = true;

            teleportSetback(player, target);

            setbackDebug(
                    profile,
                    "&c" + reason + " &7setback -> &6"
                            + target.getBlockX() + ", "
                            + target.getBlockY() + ", "
                            + target.getBlockZ()
            );

        } catch (Exception e) {
            setbackDebug(profile, "&cSetback failed: &7" + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onSetbackLanded() {
        this.setbackAwaitingMatch = false;
        if (this.lastSetbackTarget != null) {
            this.lastSolidGroundLocation = this.lastSetbackTarget.clone();
            this.lastSafeGroundLocation = this.lastSetbackTarget.clone();
            this.lastServerGroundLocation = this.lastSetbackTarget.clone();
            this.solidGroundHistory.add(this.lastSetbackTarget.clone());
        }
    }

    public void onSetbackTimeout() {
        this.setbackAwaitingMatch = false;
    }

    public void onExternalTeleportLanded() {
        this.setbackAwaitingMatch = false;
        this.solidGroundHistory.clear();
        this.lastSolidGroundLocation = null;
        this.lastSafeGroundLocation = null;
        this.lastServerGroundLocation = null;
    }

    /**
     * Determines the setback target location:
     * - If the player is airborne (jumping, falling, on a ghostblock, or in the air):
     *   ALWAYS sets back to the last solid ground the player was on before leaving the floor!
     * - If the player is on the ground (running, speeding, or abusing packet-teleport):
     *   Rolls them back slightly to the previous solid ground.
     * - If no prior ground location exists (e.g. joined in mid-air):
     *   Forces directly to the solid floor beneath them (or spawn location if over void).
     */
    private CustomLocation getCustomLocation(Player player) {
        MovementData movementData = profile.getMovementData();
        CustomLocation target = null;

        boolean isAirborne = movementData == null
                || movementData.isCustomInAir()
                || movementData.getCustomAirTicks() > 0
                || profile.isOnGhostBlock()
                || movementData.getSinceOnGhostBlock() < 3;

        if (isAirborne) {
            // Not on solid ground (airborne / jumping / on ghostblock):
            // ALWAYS set back to the last solid ground the player was on before leaving the floor!
            if (this.lastSolidGroundLocation != null
                    && this.lastSolidGroundLocation.getWorld() != null
                    && this.lastSolidGroundLocation.getWorld().equals(player.getWorld())) {
                target = this.lastSolidGroundLocation.clone();
            } else if (!this.solidGroundHistory.isEmpty()) {
                for (int i = this.solidGroundHistory.size() - 1; i >= 0; i--) {
                    CustomLocation loc = this.solidGroundHistory.get(i);
                    if (loc != null && loc.getWorld() != null && loc.getWorld().equals(player.getWorld())) {
                        target = loc.clone();
                        break;
                    }
                }
            }
        } else {
            // On ground (e.g. speed check or packet-teleport abuse):
            // Roll back slightly (up to 5 solid ground samples, ~0.25s) to cancel illegal movement
            if (!this.solidGroundHistory.isEmpty()) {
                int rollbackIndex = Math.max(0, this.solidGroundHistory.size() - 5);
                target = this.solidGroundHistory.get(rollbackIndex).clone();
            } else if (this.lastSolidGroundLocation != null
                    && this.lastSolidGroundLocation.getWorld() != null
                    && this.lastSolidGroundLocation.getWorld().equals(player.getWorld())) {
                target = this.lastSolidGroundLocation.clone();
            }
        }

        // Secondary fallbacks from other tracking data if target is still unset:
        if (target == null && this.lastSafeGroundLocation != null
                && this.lastSafeGroundLocation.getWorld() != null
                && this.lastSafeGroundLocation.getWorld().equals(player.getWorld())) {
            target = this.lastSafeGroundLocation.clone();
        }

        if (target == null && movementData != null && movementData.getLastGroundLocation() != null
                && movementData.getLastGroundLocation().getWorld() != null
                && movementData.getLastGroundLocation().getWorld().equals(player.getWorld())) {
            target = movementData.getLastGroundLocation().clone();
        }

        if (target == null && movementData != null && movementData.getLastSetBackLocation() != null
                && movementData.getLastSetBackLocation().getWorld() != null
                && movementData.getLastSetBackLocation().getWorld().equals(player.getWorld())) {
            target = movementData.getLastSetBackLocation().clone();
        }

        // If target is completely unset or confirmed floating (e.g. joined in mid-air): force downwards to floor
        if (target == null || isFloatingOrInvalid(target)) {
            CustomLocation fallbackLoc = (target != null)
                    ? target
                    : ((movementData != null && movementData.getLastLocation() != null)
                            ? movementData.getLastLocation()
                            : new CustomLocation(player.getLocation()));
            CustomLocation floorLoc = forceToFloor(fallbackLoc);
            if (floorLoc != null) {
                target = floorLoc;
            }
        }

        // Ultimate safety check: world spawn
        if (target == null || target.getWorld() == null || !target.getWorld().equals(player.getWorld())) {
            target = new CustomLocation(player.getWorld().getSpawnLocation());
        }

        return target;
    }

    private boolean isFloatingOrInvalid(CustomLocation loc) {
        if (loc == null || loc.getWorld() == null) return true;
        World world = loc.getWorld();
        int blockX = loc.getBlockX();
        int blockY = (int) Math.floor(loc.getY() - 0.01D);
        int blockZ = loc.getBlockZ();

        Material mat = ChunkCache.get().getBlock(world, blockX, blockY, blockZ);
        Material matBelow = ChunkCache.get().getBlock(world, blockX, blockY - 1, blockZ);

        if (isMaterialSolidGround(mat) || isMaterialSolidGround(matBelow)) {
            return false;
        }

        // Check bounding box corners (±0.25)
        double[][] corners = {{-0.25, -0.25}, {-0.25, 0.25}, {0.25, -0.25}, {0.25, 0.25}};
        for (double[] c : corners) {
            int cx = (int) Math.floor(loc.getX() + c[0]);
            int cz = (int) Math.floor(loc.getZ() + c[1]);
            Material cMat = ChunkCache.get().getBlock(world, cx, blockY, cz);
            Material cMatBelow = ChunkCache.get().getBlock(world, cx, blockY - 1, cz);
            if (isMaterialSolidGround(cMat) || isMaterialSolidGround(cMatBelow)) {
                return false;
            }
        }

        // If ChunkCache chunk is unpopulated / null, do NOT assume it's invalid
        if (mat == null) {
            return false;
        }

        return true;
    }

    private CustomLocation forceToFloor(CustomLocation loc) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        int blockX = loc.getBlockX();
        int startY = loc.getBlockY();
        int blockZ = loc.getBlockZ();

        int minY = -64;
        try {
            minY = world.getMinHeight();
        } catch (Throwable ignored) {
            minY = 0;
        }

        // Scan downwards up to 30 blocks
        int maxScan = Math.max(minY, startY - 30);
        for (int y = startY; y >= maxScan; y--) {
            Material mat = ChunkCache.get().getBlock(world, blockX, y, blockZ);
            if (isMaterialSolidGround(mat)) {
                return new CustomLocation(world, blockX + 0.5, y + 1.0, blockZ + 0.5, loc.getYaw(), loc.getPitch());
            }
        }

        return null;
    }

    private void teleportSetback(Player player, CustomLocation location) {
        if (player == null || location == null) {
            return;
        }

        if (TaskUtils.isFoliaServer()) {
            tryTeleportAsync(player, location.toBukkit());
            return;
        }

        TaskUtils.task(() -> {
            if (player.isOnline()) {
                player.teleport(location.toBukkit(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        });
    }

    private void tryTeleportAsync(Player player, Location location) {
        if (player == null || location == null || !player.isOnline()) {
            return;
        }

        try {
            // Paper/Folia: Entity#teleportAsync(Location, TeleportCause)
            player.getClass()
                    .getMethod("teleportAsync", Location.class, PlayerTeleportEvent.TeleportCause.class)
                    .invoke(player, location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            return;
        } catch (Throwable ignored) {
        }

        try {
            // Older Paper style: Entity#teleportAsync(Location)
            player.getClass()
                    .getMethod("teleportAsync", Location.class)
                    .invoke(player, location);
        } catch (Throwable ignored) {
            setbackDebug(profile, "&cSetback failed: &7teleportAsync is not available on this server/API");
        }
    }
}
