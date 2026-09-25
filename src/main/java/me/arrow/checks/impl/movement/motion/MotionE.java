package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.Arrow;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Detects a player remaining exactly level while moving across the real water
 * surface. The surface is resolved from the packet-driven chunk cache, rather
 * than from the broad nearby-material scan, so a wall/floor adjacent to water
 * cannot make a water walker disappear from the check.
 */
@Experimental
public class MotionE extends Check {

    private static final int REQUIRED_SAMPLES = 12;
    private static final int REQUIRED_STATIONARY_SAMPLES = 10;
    private static final double SURFACE_TOLERANCE = 0.035D;
    private static final double ZERO_VERTICAL_TOLERANCE = 1.0E-5D;
    private static final double MIN_HORIZONTAL_SPEED = 0.05D;

    private final SampleList<WaterSample> samples = new SampleList<>(REQUIRED_SAMPLES, true);
    private WaterMode sampledMode;

    public MotionE(Profile profile) {
        super(profile, CheckType.MOTION, "E", "Checks for Jesus hacks");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) {
            return;
        }

        long profiler = Profiler.start();
        try {
            MovementData movement = profile.getMovementData();
            if (movement == null || movement.getLocation() == null) {
                clearSamples();
                return;
            }

            if (exempt("cancelled", profile.shouldCancel())) return;
            if (exempt("underBlock", movement.isUnderblock())) return;
            if (exempt("onBoat", movement.isOnBoat())) return;
            if (exempt("nearBoat", movement.isNearBoat())) return;
            if (exempt("nearBubble", movement.isNearBubble())) return;
            int clientTickTrans = profile.getConnectionData() == null
                    ? 0 : profile.getConnectionData().getClientTickTrans();
            int underPlaceTicks = profile.getActionData() == null
                    ? Integer.MAX_VALUE : profile.getActionData().getLastConfirmedUnderPlaceTicks();

            if (exempt("teleports", movement.getSinceTeleportTicks()
                    < 5 + (clientTickTrans * 4))) return;
            if (exempt("swimming", isSwimming())) return;
            if (exempt("riptiding", movement.getSinceRiptidingTicks() < 5)) return;
            if (exempt("underPlace", underPlaceTicks < 5)) return;
            if (exempt("nearBuggyBlock", movement.isNearBuggyBlock())) return;
            if (exempt("gliding", movement.isGlidingOrRecentlyGlided(30))) return;

            WaterSurface surface = findWaterSurface(movement.getLocation());
            WaterVolume submerged = findSubmergedWater(movement.getLocation());
            boolean immersed = movement.isInsideWater() || movement.isInsideLiquid();
            boolean bottomWater = movement.isBottomOfWater();
            WaterMode mode = surface != null && !immersed && !bottomWater
                    ? WaterMode.SURFACE
                    : submerged != null && !bottomWater ? WaterMode.SUBMERGED : null;

            if (mode == null) {
                clearSamples();
                decreaseBufferBy(0.25D);
                return;
            }

            double deltaY = movement.getDeltaY();
            double deltaXZ = movement.getDeltaXZ();
            boolean stationary = Math.abs(deltaY) <= ZERO_VERTICAL_TOLERANCE
                    && deltaXZ >= MIN_HORIZONTAL_SPEED;

            /* A legitimate bob, jump, fall, or stop breaks the evidence run.
             * This prevents samples from being accumulated across a normal
             * transition into/out of water. */
            if (!stationary) {
                clearSamples();
                decreaseBufferBy(0.10D);
                return;
            }

            if (sampledMode != null && sampledMode != mode) {
                clearSamples();
            }
            sampledMode = mode;

            samples.add(new WaterSample(mode, deltaY, deltaXZ, movement.isCustomInAir(),
                    movement.isServerGround(), movement.isOnGround()));

            if (!samples.isCollected()) {
                return;
            }

            int stationarySamples = 0;
            int airborneSamples = 0;
            int offGroundSamples = 0;
            for (WaterSample sample : samples) {
                if (sample.mode == mode
                        && Math.abs(sample.deltaY) <= ZERO_VERTICAL_TOLERANCE
                        && sample.deltaXZ >= MIN_HORIZONTAL_SPEED) {
                    stationarySamples++;
                }
                if (sample.inAir) {
                    airborneSamples++;
                }
                if (!sample.serverGround && !sample.clientGround) {
                    offGroundSamples++;
                }
            }

            boolean surfaceEvidence = mode == WaterMode.SURFACE
                    && airborneSamples >= REQUIRED_STATIONARY_SAMPLES;
            boolean submergedEvidence = mode == WaterMode.SUBMERGED
                    && offGroundSamples >= REQUIRED_STATIONARY_SAMPLES;

            if (stationarySamples >= REQUIRED_STATIONARY_SAMPLES
                    && (surfaceEvidence || submergedEvidence)) {
                fail("Walking On Water?",
                        "mode " + MsgType.MAIN_THEME_COLOR.getMessage() + mode
                                + "\nwater " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + (mode == WaterMode.SURFACE ? surface.location() : submerged.location())
                                + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + stationarySamples + "/" + samples.size()
                                + "\nairSamples " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + airborneSamples + "/" + samples.size()
                                + "\noffGroundSamples " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + offGroundSamples + "/" + samples.size()
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movement.isServerGround()
                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movement.isOnGround()
                                + "\ninsideWater " + MsgType.MAIN_THEME_COLOR.getMessage() + immersed
                                + "\nbottomWater " + MsgType.MAIN_THEME_COLOR.getMessage() + bottomWater);
                samples.clear();
            }
        } finally {
            Profiler.stop("Motion E", profiler);
        }
    }

    private boolean isSwimming() {
        try {
            return Arrow.getInstance().getNmsManager().getNmsInstance().isSwimming(profile.getPlayer());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolves a liquid block whose top is at the player's feet. The overlap
     * test uses the real player footprint, allowing edge-of-water walking but
     * never treating water beside a wall as support. A player at the bottom of
     * a pool cannot satisfy this surface-height condition.
     */
    private WaterSurface findWaterSurface(CustomLocation location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        World world = location.getWorld();
        int waterY = floor(location.getY() - 0.01D);
        double playerMinX = location.getX() - 0.30D;
        double playerMaxX = location.getX() + 0.30D;
        double playerMinZ = location.getZ() - 0.30D;
        double playerMaxZ = location.getZ() + 0.30D;
        int minX = floor(playerMinX);
        int maxX = floor(playerMaxX);
        int minZ = floor(playerMinZ);
        int maxZ = floor(playerMaxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!ChunkCache.get().isChunkLoaded(world, x >> 4, z >> 4)) {
                    return null;
                }

                Material material = ChunkCache.get().getBlock(world, x, waterY, z);
                if (!isWater(material)) {
                    continue;
                }

                if (Math.abs(location.getY() - (waterY + 1.0D)) <= SURFACE_TOLERANCE) {
                    return new WaterSurface(x, waterY, z);
                }
            }
        }

        return null;
    }

    /**
     * Requires water at both feet and torso level in the same cached column.
     * This keeps the submerged mode out of shallow water and away from the
     * liquid-bottom case; it only observes players actually suspended inside
     * a water volume.
     */
    private WaterVolume findSubmergedWater(CustomLocation location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        World world = location.getWorld();
        int feetY = floor(location.getY() + 0.05D);
        int torsoY = floor(location.getY() + 1.0D);
        double playerMinX = location.getX() - 0.30D;
        double playerMaxX = location.getX() + 0.30D;
        double playerMinZ = location.getZ() - 0.30D;
        double playerMaxZ = location.getZ() + 0.30D;

        for (int x = floor(playerMinX); x <= floor(playerMaxX); x++) {
            for (int z = floor(playerMinZ); z <= floor(playerMaxZ); z++) {
                if (!ChunkCache.get().isChunkLoaded(world, x >> 4, z >> 4)) {
                    return null;
                }

                Material feet = ChunkCache.get().getBlock(world, x, feetY, z);
                Material torso = ChunkCache.get().getBlock(world, x, torsoY, z);
                if (isWater(feet) && isWater(torso)) {
                    return new WaterVolume(x, feetY, z, torsoY);
                }
            }
        }

        return null;
    }

    private boolean isWater(Material material) {
        if (material == null) return false;

        String name = material.name();
        return name.equals("WATER")
                || name.equals("STATIONARY_WATER")
                || name.equals("LEGACY_WATER")
                || name.equals("LEGACY_STATIONARY_WATER");
    }

    private int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private void clearSamples() {
        sampledMode = null;
        if (!samples.isEmpty()) {
            samples.clear();
        }
    }

    private enum WaterMode {
        SURFACE,
        SUBMERGED
    }

    private static final class WaterSample {
        private final WaterMode mode;
        private final double deltaY;
        private final double deltaXZ;
        private final boolean inAir;
        private final boolean serverGround;
        private final boolean clientGround;

        private WaterSample(WaterMode mode, double deltaY, double deltaXZ, boolean inAir,
                            boolean serverGround, boolean clientGround) {
            this.mode = mode;
            this.deltaY = deltaY;
            this.deltaXZ = deltaXZ;
            this.inAir = inAir;
            this.serverGround = serverGround;
            this.clientGround = clientGround;
        }
    }

    private static final class WaterSurface {
        private final int x;
        private final int y;
        private final int z;

        private WaterSurface(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private String location() {
            return x + ", " + y + ", " + z;
        }
    }

    private static final class WaterVolume {
        private final int x;
        private final int feetY;
        private final int z;
        private final int torsoY;

        private WaterVolume(int x, int feetY, int z, int torsoY) {
            this.x = x;
            this.feetY = feetY;
            this.z = z;
            this.torsoY = torsoY;
        }

        private String location() {
            return x + ", " + feetY + " -> " + torsoY + ", " + z;
        }
    }
}
