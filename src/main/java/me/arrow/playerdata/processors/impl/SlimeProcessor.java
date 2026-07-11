package me.arrow.playerdata.processors.impl;

import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.PotionData;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.MaterialType;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * Tracks a real fall-to-slime collision and validates the resulting ascent.
 * One processor instance belongs to one profile.
 */
public class SlimeProcessor {

    private static final double FALL_EPSILON = 0.03D;
    private static final double RISE_EPSILON = 0.01D;
    private static final double MIN_TRACKED_FALL = 0.10D;
    private static final double PLAYER_HALF_WIDTH = 0.300001D;
    private static final double VANILLA_GRAVITY = 0.08D;
    private static final double VANILLA_DRAG = 0.98D;
    private static final int MAX_APEX_TICKS = 200;

    private final Profile profile;

    private BounceSession session;
    private CustomLocation lastDescendingLocation;
    private double trackedFallDistance;
    private double lastDescendingDeltaY;
    private int descendingTicks;
    private int descentGraceTicks;

    public SlimeProcessor(Profile profile) {
        this.profile = profile;
    }

    /**
     * Returns true only for a position-tracked slime impact and its valid
     * upward phase. PotionData remains in the signature for compatibility.
     */
    public boolean isBouncing(MovementData movementData, PotionData ignoredPotionData) {
        if (movementData == null || movementData.getLocation() == null) {
            resetAll();
            return false;
        }

        double deltaY = movementData.getDeltaY();

        if (!Double.isFinite(deltaY)) {
            resetAll();
            return false;
        }

        if (session != null) {
            return updateBounce(deltaY);
        }

        if (deltaY < -FALL_EPSILON) {
            trackDescent(movementData, deltaY);
            return false;
        }

        if (deltaY > RISE_EPSILON && canStartBounce(movementData, deltaY)) {
            startBounce(deltaY);
            resetDescent();
            return true;
        }

        if (trackedFallDistance > 0.0D) {
            if (Math.abs(deltaY) <= RISE_EPSILON && descentGraceTicks++ < 1) {
                return false;
            }

            resetDescent();
        }

        return false;
    }

    private void trackDescent(MovementData movementData, double deltaY) {
        if (movementData.getLastDeltaY() >= -FALL_EPSILON || descentGraceTicks > 0) {
            resetDescent();
        }

        trackedFallDistance += -deltaY;
        lastDescendingDeltaY = deltaY;
        lastDescendingLocation = movementData.getLocation().clone();
        descendingTicks++;
        descentGraceTicks = 0;
    }

    private boolean canStartBounce(MovementData movementData, double deltaY) {
        if (descendingTicks <= 0
                || trackedFallDistance < MIN_TRACKED_FALL
                || lastDescendingDeltaY >= -FALL_EPSILON
                || lastDescendingLocation == null
                || !hasSweptSlimeContact(movementData)) {
            return false;
        }

        double incomingVelocity = Math.abs(nextVerticalVelocity(lastDescendingDeltaY));
        double launchAllowance = Math.max(0.35D, incomingVelocity * 0.10D);

        return deltaY <= incomingVelocity + launchAllowance;
    }

    private void startBounce(double firstRise) {
        double incomingVelocity = Math.abs(nextVerticalVelocity(lastDescendingDeltaY));
        double maximumLaunch = Math.max(firstRise, incomingVelocity) + Math.max(0.25D, incomingVelocity * 0.08D);
        SimulationResult simulation = simulateApexAndRise(maximumLaunch);

        session = new BounceSession(
                maximumLaunch,
                simulation.predictedRise + 0.75D,
                Math.min(MAX_APEX_TICKS, simulation.ticksToApex + 2),
                Math.max(0.0D, firstRise)
        );
    }

    private boolean updateBounce(double deltaY) {
        BounceSession active = session;

        if (deltaY <= RISE_EPSILON
                || active.remainingTicks <= 0
                || deltaY > active.maximumLaunchVelocity) {
            session = null;
            return false;
        }

        /*
         * The first bounce position packet contains a partial collision tick,
         * so use the following rising sample to calibrate the clean trajectory.
         */
        if (Double.isFinite(active.expectedNextVelocity)) {
            double tolerance = Math.max(0.085D, Math.abs(active.expectedNextVelocity) * 0.08D + 0.02D);

            if (Math.abs(deltaY - active.expectedNextVelocity) > tolerance) {
                session = null;
                return false;
            }
        }

        active.accumulatedRise += deltaY;

        if (active.accumulatedRise > active.maximumRise) {
            session = null;
            return false;
        }

        active.expectedNextVelocity = nextVerticalVelocity(deltaY);
        active.remainingTicks--;
        return true;
    }

    private boolean hasSweptSlimeContact(MovementData movementData) {
        if (movementData.isOnSlime()) {
            return true;
        }

        CustomLocation current = movementData.getLocation();
        CustomLocation from = lastDescendingLocation;

        if (from == null
                || current == null
                || from.getWorld() == null
                || current.getWorld() != from.getWorld()) {
            return false;
        }

        World world = from.getWorld();
        double projectedEndY = from.getY() + nextVerticalVelocity(lastDescendingDeltaY);
        double minimumSurfaceY = Math.min(projectedEndY, from.getY()) - 0.35D;
        double maximumSurfaceY = Math.max(projectedEndY, from.getY()) + 0.25D;
        int minimumBlockY = (int) Math.floor(minimumSurfaceY) - 1;
        int maximumBlockY = (int) Math.floor(maximumSurfaceY);

        // Player terminal velocity limits this to a handful of blocks, but cap
        // the scan defensively for modified or corrupt movement.
        minimumBlockY = Math.max(minimumBlockY, maximumBlockY - 8);

        for (int step = 0; step <= 4; step++) {
            double progress = step / 4.0D;
            double x = from.getX() + ((current.getX() - from.getX()) * progress);
            double z = from.getZ() + ((current.getZ() - from.getZ()) * progress);

            for (double offsetX : FOOTPRINT_OFFSETS) {
                for (double offsetZ : FOOTPRINT_OFFSETS) {
                    int blockX = (int) Math.floor(x + offsetX);
                    int blockZ = (int) Math.floor(z + offsetZ);

                    for (int blockY = minimumBlockY; blockY <= maximumBlockY; blockY++) {
                        Block block = world.getBlockAt(blockX, blockY, blockZ);

                        if (!MaterialType.isMaterial(block.getType().name(), MaterialType.SLIME)) {
                            continue;
                        }

                        double surfaceY = blockY + 1.0D;

                        if (surfaceY >= minimumSurfaceY && surfaceY <= maximumSurfaceY) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private double nextVerticalVelocity(double velocity) {
        return (velocity - VANILLA_GRAVITY) * VANILLA_DRAG;
    }

    private SimulationResult simulateApexAndRise(double initialVelocity) {
        double velocity = Math.max(0.0D, initialVelocity);
        double rise = 0.0D;
        int ticks = 0;

        while (velocity > 0.0D && ticks < MAX_APEX_TICKS) {
            rise += velocity;
            velocity = nextVerticalVelocity(velocity);
            ticks++;
        }

        return new SimulationResult(ticks, rise);
    }

    public void clearSession(UUID playerUuid) {
        if (playerUuid != null && (profile.getUUID() == null || playerUuid.equals(profile.getUUID()))) {
            resetAll();
        }
    }

    public void clearAll() {
        resetAll();
    }

    private void resetAll() {
        session = null;
        resetDescent();
    }

    private void resetDescent() {
        lastDescendingLocation = null;
        trackedFallDistance = 0.0D;
        lastDescendingDeltaY = 0.0D;
        descendingTicks = 0;
        descentGraceTicks = 0;
    }

    private static final double[] FOOTPRINT_OFFSETS = {
            -PLAYER_HALF_WIDTH,
            0.0D,
            PLAYER_HALF_WIDTH
    };

    private static final class BounceSession {
        private final double maximumLaunchVelocity;
        private final double maximumRise;
        private int remainingTicks;
        private double accumulatedRise;
        private double expectedNextVelocity = Double.NaN;

        private BounceSession(double maximumLaunchVelocity,
                              double maximumRise,
                              int remainingTicks,
                              double accumulatedRise) {
            this.maximumLaunchVelocity = maximumLaunchVelocity;
            this.maximumRise = maximumRise;
            this.remainingTicks = remainingTicks;
            this.accumulatedRise = accumulatedRise;
        }
    }

    private static final class SimulationResult {
        private final int ticksToApex;
        private final double predictedRise;

        private SimulationResult(int ticksToApex, double predictedRise) {
            this.ticksToApex = ticksToApex;
            this.predictedRise = predictedRise;
        }
    }
}
