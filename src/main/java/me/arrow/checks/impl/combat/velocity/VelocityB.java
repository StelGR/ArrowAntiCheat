package me.arrow.checks.impl.combat.velocity;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import org.bukkit.util.Vector;

import java.util.Locale;

// another AI velocity check, i can't really make my own so im using concept AI checks, they are terrible but the point is the idea
// not the quality, this is obviously never going to be used in production, the goal is meant to be just all in one
// horizontal velocity check, for delayed, 0, and reduced, it obviously falses everything under the sun, so if you can improve it
// go ahead and be my guest.

/**
 * VelocityB – Horizontal knockback check.
 *
 * After a transaction-confirmed velocity packet, the player's movement in
 * the knockback direction should reflect at least a proportional share of
 * the received horizontal impulse.  Cheats that absorb or zero-out horizontal
 * knockback (e.g. W-tap, anti-kb modules) will be caught by this check.
 *
 * Core idea:
 *  - Record the knockback vector (X, Z) direction at confirmation time.
 *  - Each subsequent movement packet, project the player's (deltaX, deltaZ)
 *    onto that direction to get the "accepted component".
 *  - Track the best (max) accepted component over the tracking window.
 *  - If bestAccepted < expectedH * minRatio at the end of the window, flag.
 *  - Additionally detect zero-response and delayed-response patterns.
 */
@Experimental
public class VelocityB extends Check {

    // Air friction per tick (same as Minecraft client)
    private static final double AIR_FRICTION = 0.91D;

    // Minimum / maximum horizontal impulse we bother tracking
    private static final double MIN_TRACK_H = 0.045D;
    private static final double MAX_TRACK_H = 4.0D;

    // Ratio of expected horizontal impulse the player must reach
    private static final double MIN_RATIO = 0.70D;

    // Buffers and their caps
    private static final double REDUCED_BUFFER_MAX = 3.0D;
    private static final double ZERO_BUFFER_MAX    = 2.0D;
    private static final double DELAY_BUFFER_MAX   = 2.0D;

    // Cumulative cross-hit miss buffer
    private double cumulativeMissBuffer;
    private static final double CUMULATIVE_MISS_MAX = 4.0D;

    // --- Tracking state ---
    private boolean tracking;
    private boolean acceptedAnyMotion;

    /** Unit vector (X, Z) of the knockback direction. */
    private double knockDirX;
    private double knockDirZ;
    private boolean hasDirection; // false if the knockback was axis-less (unlikely)

    private double initialH;   // confirmed horizontal impulse magnitude
    private double expectedH;  // expected accepted component this tick

    private double bestAccepted; // best (deltaX, deltaZ) · knockDir seen so far
    private double lastStartedH;

    private int trackedTicks;
    private int startVelocityTicks;
    private int allowedDelayTicks;

    private double reducedBuffer;
    private double zeroBuffer;
    private double delayedBuffer;

    public VelocityB(Profile profile) {
        super(profile, CheckType.VELOCITY, "B", "Checks horizontal knockback");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    // ------------------------------------------------------------------
    // Main entry
    // ------------------------------------------------------------------

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isMovement(event)) return;

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.getMovementData() == null
                || profile.getVelocityData() == null
                || profile.getBlockProcessor().isUnderGhostBlock()
                || profile.getBlockProcessor().getLastGhostLiquidWebTick()
                < 10 + profile.getConnectionData().getClientTickTrans()) {
            resetTracking();
            return;
        }

        MovementData movementData = profile.getMovementData();
        VelocityData velocityData = profile.getVelocityData();

        if (isExempt(movementData)) {
            resetTracking();
            decayBuffers(0.20D);
            cumulativeMissBuffer -= Math.min(cumulativeMissBuffer, 0.10D);
            return;
        }

        double[] confirmed = getConfirmedHorizontal(velocityData);
        double velocityH = confirmed[0]; // magnitude
        double velX      = confirmed[1]; // raw X
        double velZ      = confirmed[2]; // raw Z
        int velocityTicks = velocityData.getVelocityTicks();

        if (shouldStartTracking(velocityH, velocityTicks)) {
            startTracking(velocityH, velX, velZ, velocityTicks);
        }

        if (!tracking) {
            decayBuffers(0.03D);
            cumulativeMissBuffer -= Math.min(cumulativeMissBuffer, 0.02D);
            return;
        }

        runHorizontalCheck(movementData, velocityData);
    }

    // ------------------------------------------------------------------
    // Core check logic
    // ------------------------------------------------------------------

    private void runHorizontalCheck(MovementData movementData, VelocityData velocityData) {
        double deltaX  = movementData.getDeltaX();
        double deltaZ  = movementData.getDeltaZ();
        double deltaXZ = movementData.getDeltaXZ();

        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaZ)) {
            resetTracking();
            return;
        }

        trackedTicks++;

        boolean grounded = isGrounded(movementData);

        // Project the player's movement onto the knockback direction.
        // If we don't have a direction (pure-Y packet), fall back to raw magnitude.
        double accepted;
        if (hasDirection) {
            accepted = deltaX * knockDirX + deltaZ * knockDirZ;
        } else {
            accepted = deltaXZ;
        }

        // The expected component decays each tick by friction
        double expected = expectedH;

        // Guard ratio stability
        double ratio;
        if (expected > 0.05D) {
            ratio = accepted / expected;
        } else {
            ratio = accepted >= 0.0D ? 1.0D : 0.0D;
        }

        double allowed  = getAllowedOffset(expected, grounded);
        double minRatio = getEffectiveMinRatio(grounded);

        bestAccepted = Math.max(bestAccepted, accepted);

        if (accepted > getAcceptedThreshold(expected)) {
            acceptedAnyMotion = true;
        }

        boolean waitingResponse  = !acceptedAnyMotion && trackedTicks <= allowedDelayTicks;
        boolean zeroResponse     = expected > 0.08D && !waitingResponse && accepted <= getAllowedZeroThreshold();

        // If the player already demonstrated they took the knockback (bestAccepted >= initialH * minRatio),
        // natural deceleration due to ground friction / input on later ticks is NOT cheating.
        boolean alreadyProvedAcceptance = acceptedAnyMotion && bestAccepted >= initialH * MIN_RATIO;

        boolean reducedResponse  = expected > 0.05D
                && !waitingResponse
                && !alreadyProvedAcceptance
                && ratio < minRatio
                && (expected - accepted) > allowed;
        boolean delayedResponse  = !acceptedAnyMotion
                && trackedTicks > allowedDelayTicks
                && bestAccepted < Math.max(0.020D, initialH * 0.20D);

        // --- Buffer updates ---
        if (waitingResponse) {
            reducedBuffer -= Math.min(reducedBuffer, 0.30D);
            zeroBuffer    -= Math.min(zeroBuffer,    0.30D);
        } else if (reducedResponse) {
            double add = 0.65D;
            if (ratio < 0.85D) add += 0.50D;
            if (ratio < 0.65D) add += 0.70D;
            if (ratio < 0.45D) add += 0.90D;
            if (grounded && trackedTicks > 2) add *= 0.75D; // ground friction complicates things
            reducedBuffer += add;
        } else {
            // Decay faster once the player has already proved they accepted the knockback
            double decayRate = alreadyProvedAcceptance ? 0.55D : 0.20D;
            reducedBuffer -= Math.min(reducedBuffer, decayRate);
        }

        if (zeroResponse) {
            zeroBuffer += 1.15D;
        } else {
            zeroBuffer -= Math.min(zeroBuffer, 0.20D);
        }

        if (delayedResponse) {
            delayedBuffer += 1.10D;
        } else {
            delayedBuffer -= Math.min(delayedBuffer, 0.15D);
        }

        verbose(this.getClass().getSimpleName(), Math.max(ratio, 0.0D), 1.0D,
                MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (VelocityH)"
                        + "\n * deltaX " + MsgType.MAIN_THEME_COLOR.getMessage() + format(deltaX)
                        + "\n * deltaZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(deltaZ)
                        + "\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(deltaXZ)
                        + "\n * accepted " + MsgType.MAIN_THEME_COLOR.getMessage() + format(accepted)
                        + "\n * expected " + MsgType.MAIN_THEME_COLOR.getMessage() + format(expected)
                        + "\n * initialH " + MsgType.MAIN_THEME_COLOR.getMessage() + format(initialH)
                        + "\n * ratio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(ratio)
                        + "\n * minRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(minRatio)
                        + "\n * allowed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowed)
                        + "\n * bestAccepted " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestAccepted)
                        + "\n * knockDirX " + MsgType.MAIN_THEME_COLOR.getMessage() + format(knockDirX)
                        + "\n * knockDirZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(knockDirZ)
                        + "\n * hasDirection " + MsgType.MAIN_THEME_COLOR.getMessage() + hasDirection
                        + "\n * trackedTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + trackedTicks
                        + "\n * allowedDelay " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedDelayTicks
                        + "\n * waiting " + MsgType.MAIN_THEME_COLOR.getMessage() + waitingResponse
                        + "\n * alreadyProved " + MsgType.MAIN_THEME_COLOR.getMessage() + alreadyProvedAcceptance
                        + "\n * zero " + MsgType.MAIN_THEME_COLOR.getMessage() + zeroResponse
                        + "\n * reduced " + MsgType.MAIN_THEME_COLOR.getMessage() + reducedResponse
                        + "\n * delayed " + MsgType.MAIN_THEME_COLOR.getMessage() + delayedResponse
                        + "\n * grounded " + MsgType.MAIN_THEME_COLOR.getMessage() + grounded
                        + "\n * reducedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(reducedBuffer)
                        + "\n * zeroBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(zeroBuffer)
                        + "\n * delayedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(delayedBuffer)
                        + "\n * cumulativeMiss " + MsgType.MAIN_THEME_COLOR.getMessage() + format(cumulativeMissBuffer));

        // --- Flag checks ---
        if (zeroBuffer > ZERO_BUFFER_MAX) {
            cumulativeMissBuffer += 0.65D;
            failAndReset("Zero horizontal velocity", movementData, velocityData,
                    accepted, expected, allowed, ratio, minRatio, grounded);
            return;
        }

        if (delayedBuffer > DELAY_BUFFER_MAX) {
            cumulativeMissBuffer += 0.65D;
            failAndReset("Delayed horizontal velocity", movementData, velocityData,
                    accepted, expected, allowed, ratio, minRatio, grounded);
            return;
        }

        if (reducedBuffer > REDUCED_BUFFER_MAX) {
            cumulativeMissBuffer += 0.65D;
            failAndReset("Reduced horizontal velocity", movementData, velocityData,
                    accepted, expected, allowed, ratio, minRatio, grounded);
            return;
        }

        // Cumulative cross-hit detection
        if (cumulativeMissBuffer > CUMULATIVE_MISS_MAX) {
            cumulativeMissBuffer *= 0.35D;
            fail("Habitual horizontal velocity ignore",
                    "cumulativeMiss " + MsgType.MAIN_THEME_COLOR.getMessage() + format(cumulativeMissBuffer)
                            + "\ninitialH " + MsgType.MAIN_THEME_COLOR.getMessage() + format(initialH)
                            + "\nbestAccepted " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestAccepted));
            resetTracking();
            return;
        }

        // Advance expected with air friction (conservative – use air friction even on ground
        // so we are never stricter than reality; ground friction is faster decay).
        expectedH = nextHorizontal(expectedH, grounded, movementData);

        int maxTicks = Math.max(10, allowedDelayTicks + 5);

        if (trackedTicks > maxTicks || expectedH < 0.008D) {
            if (!acceptedAnyMotion && trackedTicks >= 3) {
                cumulativeMissBuffer += 0.30D;
            } else {
                cumulativeMissBuffer -= Math.min(cumulativeMissBuffer, 0.20D);
            }
            resetTracking();
        }
    }

    // ------------------------------------------------------------------
    // Tracking lifecycle
    // ------------------------------------------------------------------

    private boolean shouldStartTracking(double velocityH, int velocityTicks) {
        if (velocityH < MIN_TRACK_H || velocityH > MAX_TRACK_H) return false;

        int maxStartTicks = Math.max(2, Math.min(6, 2 + getPingTicks()));
        if (velocityTicks > maxStartTicks) return false;

        if (!tracking) return true;

        return velocityTicks <= 1 && Math.abs(velocityH - lastStartedH) > 1.0E-4D;
    }

    private void startTracking(double velocityH, double velX, double velZ, int velocityTicks) {
        tracking = true;
        acceptedAnyMotion = false;

        initialH    = velocityH;
        expectedH   = velocityH;
        bestAccepted = 0.0D;
        lastStartedH = velocityH;

        // Build unit direction from the velocity vector's XZ component
        double mag = Math.hypot(velX, velZ);
        if (mag > 1.0E-6D) {
            knockDirX    = velX / mag;
            knockDirZ    = velZ / mag;
            hasDirection = true;
        } else {
            knockDirX    = 0.0D;
            knockDirZ    = 0.0D;
            hasDirection = false;
        }

        trackedTicks      = 0;
        startVelocityTicks = velocityTicks;
        allowedDelayTicks  = getAllowedDelayTicks();

        // Carry over 40% of buffers so rapid re-hits don't give a free reset
        reducedBuffer *= 0.40D;
        zeroBuffer    *= 0.40D;
        delayedBuffer *= 0.40D;
    }

    // ------------------------------------------------------------------
    // Physics helpers
    // ------------------------------------------------------------------

    private double nextHorizontal(double h, boolean grounded, MovementData movementData) {
        double friction = grounded
                ? movementData.getFrictionFactor()
                : AIR_FRICTION;
        double next = h * friction;
        return next < 0.004D ? 0.0D : next;
    }

    // ------------------------------------------------------------------
    // Threshold helpers
    // ------------------------------------------------------------------

    private double getAllowedOffset(double expected, boolean grounded) {
        double allowed = 0.010D;
        allowed += Math.min(0.020D, expected * 0.035D);
        allowed += Math.min(0.020D, getPingTicks() * 0.002D);
        if (grounded) allowed += 0.015D; // ground friction variance
        if (profile.isBedrockPlayer()) allowed += 0.030D;
        return Math.min(0.060D, allowed);
    }

    private double getEffectiveMinRatio(boolean grounded) {
        // First tick needs a slightly stricter ratio since expected is highest then
        double ratio = trackedTicks <= 1 ? 0.925D : MIN_RATIO;
        if (grounded) ratio -= 0.08D;                     // ground absorption varies
        if (profile.isBedrockPlayer()) ratio -= 0.06D;
        return Math.max(0.55D, ratio);
    }

    /** Minimum accepted component to count as "motionAccepted". */
    private double getAcceptedThreshold(double expected) {
        return Math.max(0.030D, expected * 0.20D);
    }

    /** If accepted <= this value, treat it as a zero-response tick. */
    private double getAllowedZeroThreshold() {
        double base = 0.003D;
        base += Math.min(0.008D, getPingTicks() * 0.0005D);
        if (profile.isBedrockPlayer()) base += 0.005D;
        return base;
    }

    private int getAllowedDelayTicks() {
        return Math.max(3, Math.min(12, 2 + (getPingTicks() * 2)));
    }

    private int getPingTicks() {
        int ticks = 0;
        try { ticks = Math.max(ticks, profile.getConnectionData().getClientTickTrans()); }
        catch (Throwable ignored) {}
        try { ticks = Math.max(ticks, (int) Math.ceil(profile.getConnectionData().getTransPing() / 50.0D)); }
        catch (Throwable ignored) {}
        try { ticks = Math.max(ticks, (int) Math.ceil(profile.getConnectionData().getPing() / 50.0D)); }
        catch (Throwable ignored) {}
        try { ticks = Math.max(ticks, (int) Math.ceil(profile.getConnectionData().getAverageTransactionPing() / 50.0D)); }
        catch (Throwable ignored) {}
        return Math.min(12, ticks);
    }

    // ------------------------------------------------------------------
    // Confirmed horizontal velocity
    // ------------------------------------------------------------------

    /**
     * Returns double[3]: { magnitude, rawX, rawZ }
     * All values come from the transaction-confirmed velocity state.
     */
    private double[] getConfirmedHorizontal(VelocityData velocityData) {
        double h    = 0.0D;
        double velX = 0.0D;
        double velZ = 0.0D;

        try {
            double candidate = velocityData.getVelocityH();
            if (candidate > h) {
                h = candidate;
                Vector v = velocityData.getVelocity();
                if (v != null) { velX = v.getX(); velZ = v.getZ(); }
            }
        } catch (Throwable ignored) {}

        try {
            double candidate = velocityData.getVelocityHfvc();
            if (candidate > h) {
                h = candidate;
                Vector v = velocityData.getVelocityfvc();
                if (v != null) { velX = v.getX(); velZ = v.getZ(); }
            }
        } catch (Throwable ignored) {}

        try {
            double candidate = velocityData.getVelocityHSustain();
            if (candidate > h) {
                h = candidate;
                Vector v = velocityData.getVelocitySustain();
                if (v != null) { velX = v.getX(); velZ = v.getZ(); }
            }
        } catch (Throwable ignored) {}

        // Also check explosion knockback (horizontal component)
        try {
            Vector expl = velocityData.getExplosionKnockback();
            if (expl != null) {
                double explH = Math.hypot(expl.getX(), expl.getZ());
                if (explH > h) {
                    h    = explH;
                    velX = expl.getX();
                    velZ = expl.getZ();
                }
            }
        } catch (Throwable ignored) {}

        return new double[]{ h, velX, velZ };
    }

    // ------------------------------------------------------------------
    // Exemptions
    // ------------------------------------------------------------------

    private boolean isExempt(MovementData data) {
        if (profile.shouldCancel()
                || profile.isExempt().isTeleports()
                || profile.isExempt().vehicle()
                || profile.getExempt().isDead()
                || !profile.getExempt().isRespawned()
                || profile.getPlayer().isInsideVehicle()
                || profile.getMovementData().getSinceOnGhostBlock()
                <= 10 + profile.getConnectionData().getClientTickTrans()) {
            return true;
        }

        return data.isNearWater()
                || data.isNearLava()
                || data.isNearWebs()
                || data.isNearClimbable()
                || data.isOnSlime()
                || data.isOnHoney()
                || data.isInsideWater()
                || data.isOnTopOfWater()
                || data.isBottomOfWater()
                || data.isNearBoat()
                || data.isOnBoat()
                || data.isNearGhast()
                || data.isNearShulker()
                || data.isNearShulkerBox()
                || data.isNearBuggyBlock()
                || data.isIntersecting()
                || data.isRiptiding()
                || data.isUnderblock()
                || data.getMovingUnderblockTicks() > 0.0F
                || data.getSinceExplosionTicks() <= 8 + profile.getConnectionData().getClientTickTrans()
                || data.getSinceGlidingTicks() <= 20 + profile.getConnectionData().getClientTickTrans()
                || data.getSinceRiptidingTicks() <= 10 + profile.getConnectionData().getClientTickTrans()
                || data.getSincePowderSnowTicks() <= 10;
    }

    private boolean isGrounded(MovementData data) {
        return data.isOnGround()
                || data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround();
    }

    // ------------------------------------------------------------------
    // Fail + reset helpers
    // ------------------------------------------------------------------

    private void failAndReset(String reason,
                              MovementData movementData,
                              VelocityData velocityData,
                              double accepted,
                              double expected,
                              double allowed,
                              double ratio,
                              double minRatio,
                              boolean grounded) {
        fail(reason,
                "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(movementData.getDeltaXZ())
                        + "\naccepted " + MsgType.MAIN_THEME_COLOR.getMessage() + format(accepted)
                        + "\nexpected " + MsgType.MAIN_THEME_COLOR.getMessage() + format(expected)
                        + "\ninitialH " + MsgType.MAIN_THEME_COLOR.getMessage() + format(initialH)
                        + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowed)
                        + "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(ratio)
                        + "\nminRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(minRatio)
                        + "\nbestAccepted " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestAccepted)
                        + "\nknockDirX " + MsgType.MAIN_THEME_COLOR.getMessage() + format(knockDirX)
                        + "\nknockDirZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(knockDirZ)
                        + "\nhasDirection " + MsgType.MAIN_THEME_COLOR.getMessage() + hasDirection
                        + "\ntrackedTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + trackedTicks
                        + "\nstartVelocityTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + startVelocityTicks
                        + "\nallowedDelay " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedDelayTicks
                        + "\ngrounded " + MsgType.MAIN_THEME_COLOR.getMessage() + grounded
                        + "\nvelocityTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityData.getVelocityTicks()
                        + "\nreducedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(reducedBuffer)
                        + "\nzeroBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(zeroBuffer)
                        + "\ndelayedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(delayedBuffer)
                        + "\ncumulativeMiss " + MsgType.MAIN_THEME_COLOR.getMessage() + format(cumulativeMissBuffer));

        reducedBuffer *= 0.25D;
        zeroBuffer    *= 0.25D;
        delayedBuffer *= 0.25D;

        resetTracking();
    }

    private void decayBuffers(double amount) {
        reducedBuffer -= Math.min(reducedBuffer, amount);
        zeroBuffer    -= Math.min(zeroBuffer,    amount);
        delayedBuffer -= Math.min(delayedBuffer, amount);
    }

    private void resetTracking() {
        tracking         = false;
        acceptedAnyMotion = false;
        hasDirection     = false;
        knockDirX        = 0.0D;
        knockDirZ        = 0.0D;
        initialH         = 0.0D;
        expectedH        = 0.0D;
        bestAccepted     = 0.0D;
        trackedTicks     = 0;
        startVelocityTicks = 0;
        allowedDelayTicks  = 0;
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }
}

