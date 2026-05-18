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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

// the worst velocity check i've ever seen in my life
// although , i did not code it, it was a test from GPT 5 to get an idea of velocity checks, still hard to understand
// how to make accurate velocity on modern, but i am not this kind of dev, only really understand movement
// check velocity B for more info

// updated from claude

@Experimental
public class VelocityA extends Check {

    private static final double GRAVITY = 0.08D;
    private static final double DRAG = 0.9800000190734863D;

    private static final double MIN_TRACK_Y = 0.045D;
    private static final double MAX_TRACK_Y = 3.5D;

    private static final double OPEN_AIR_MIN_RATIO = 0.935D;

    private static final double REDUCED_BUFFER_MAX = 3.0D;
    private static final double ZERO_BUFFER_MAX = 1.75D;
    private static final double DELAY_BUFFER_MAX = 1.75D;

    // Cumulative miss counter – persists across velocity events to detect habitual ignoring
    private double cumulativeMissBuffer;
    private static final double CUMULATIVE_MISS_MAX = 3.5D;

    private boolean tracking;
    private boolean acceptedAnyMotion;
    // Whether the player was airborne when this tracking session started
    private boolean wasAirborneAtStart;

    private double initialY;
    private double expectedY;
    private double bestDeltaY;
    private double bestRatio;
    private double lastStartedY;

    private int trackedTicks;
    private int startVelocityTicks;
    private int allowedDelayTicks;

    private double reducedBuffer;
    private double zeroBuffer;
    private double delayedBuffer;

    public VelocityA(Profile profile) {
        super(profile, CheckType.VELOCITY, "A", "Checks vertical knockback");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    private static final Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    private static Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        addCauseIfPresent(set, "VOID");
        addCauseIfPresent(set, "POISON");
        addCauseIfPresent(set, "WITHER");
        addCauseIfPresent(set, "FALL");
        addCauseIfPresent(set, "MAGIC");
        addCauseIfPresent(set, "FIRE");
        addCauseIfPresent(set, "FIRE_TICK");
        addCauseIfPresent(set, "CAMPFIRE");
        addCauseIfPresent(set, "SUFFOCATION");
        addCauseIfPresent(set, "LIGHTNING");
        addCauseIfPresent(set, "CONTACT");
        addCauseIfPresent(set, "THORNS");
        addCauseIfPresent(set, "FLY_INTO_WALL");
        addCauseIfPresent(set, "CRAMMING");
        addCauseIfPresent(set, "WORLD_BORDER");
        return set;
    }

    private static void addCauseIfPresent(Set<EntityDamageEvent.DamageCause> set, String name) {
        try {
            set.add(EntityDamageEvent.DamageCause.valueOf(name));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isMovement(event)) {
            return;
        }



        MovementData movementData = profile.getMovementData();
        VelocityData velocityData = profile.getVelocityData();

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.getMovementData() == null
                || profile.getVelocityData() == null
                || movementData.isMovingUp()
                || movementData.getSinceMovingUpTicks() < 5
                || profile.getBlockProcessor().isUnderGhostBlock()
                || profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
            resetTracking();
            return;
        }

        if (isExempt(movementData)) {
            resetTracking();
            decayBuffers(0.20D);
            cumulativeMissBuffer -= Math.min(cumulativeMissBuffer, 0.10D);
            return;
        }

        double velocityY = getConfirmedVelocityY(velocityData);
        int velocityTicks = velocityData.getVelocityTicks();

        if (shouldStartTracking(velocityY, velocityTicks)) {
            startTracking(velocityY, velocityTicks, movementData);
        }

        if (!tracking) {
            decayBuffers(0.03D);
            cumulativeMissBuffer -= Math.min(cumulativeMissBuffer, 0.02D);
            return;
        }

        runVelocityCheck(movementData, velocityData);
    }

    private void runVelocityCheck(MovementData movementData, VelocityData velocityData) {
        if (isUnderBlock(movementData)) {
            resetTracking();
            decayBuffers(0.25D);
            return;
        }

        double deltaY = movementData.getDeltaY();
        double lastDeltaY = movementData.getLastDeltaY();

        if (!Double.isFinite(deltaY) || !Double.isFinite(lastDeltaY)) {
            resetTracking();
            return;
        }

        trackedTicks++;

        boolean recentUnderPlace = hasRecentUnderPlace();
        boolean grounded = isGrounded(movementData);
        // isMidAirJumpMotion is now much more conservative – only first 2 ticks of an airborne start
        boolean midAirJumpMotion = isMidAirJumpMotion(movementData, deltaY, lastDeltaY);

        double expected = expectedY;
        double vanillaAirExpected = predictVanillaAirY(lastDeltaY);
        double offset = expected - deltaY;

        // Guard ratio when expected is very small to avoid division instability
        double ratio;
        if (expected > 0.05D) {
            ratio = deltaY / expected;
        } else {
            ratio = deltaY >= 0.0D ? 1.0D : 0.0D;
        }

        double allowed = getAllowedOffset(expected, deltaY, recentUnderPlace, grounded, midAirJumpMotion);
        double minRatio = getMinRatio(recentUnderPlace, grounded, midAirJumpMotion);

        bestDeltaY = Math.max(bestDeltaY, deltaY);
        bestRatio = Math.max(bestRatio, ratio);

        if (deltaY > getAcceptedMotion(expected, midAirJumpMotion)) {
            acceptedAnyMotion = true;
        }

        boolean zeroMotion = Math.abs(deltaY) <= getZeroEpsilon();
        boolean expectedPositive = expected > 0.025D;

        boolean waitingForVelocityResponse = !acceptedAnyMotion && trackedTicks <= allowedDelayTicks;

        boolean validJumpMotion = midAirJumpMotion
                && deltaY >= vanillaAirExpected - getJumpMotionTolerance(movementData)
                && deltaY > -0.035D;

        // If the player already proved they accepted the velocity (bestRatio >= minRatio),
        // do not treat later decayed ticks as "reduced" – natural drift in the trajectory
        // after the peak is not evidence of cheating.
        boolean alreadyProvedAcceptance = acceptedAnyMotion && bestRatio >= minRatio;

        boolean reduced = expectedPositive
                && !waitingForVelocityResponse
                && !validJumpMotion
                && !alreadyProvedAcceptance
                && ratio < minRatio
                && offset > allowed;

        boolean zero = expected > 0.08D
                && !waitingForVelocityResponse
                && zeroMotion
                && !midAirJumpMotion;

        boolean delayed = !acceptedAnyMotion
                && trackedTicks > allowedDelayTicks
                && bestDeltaY < Math.max(0.025D, initialY * 0.25D);

        if (waitingForVelocityResponse) {
            reducedBuffer -= Math.min(reducedBuffer, 0.30D);
            zeroBuffer -= Math.min(zeroBuffer, 0.30D);
        } else if (reduced) {
            double add = 0.75D;

            if (ratio < 0.90D) add += 0.45D;
            if (ratio < 0.75D) add += 0.65D;
            if (ratio < 0.50D) add += 0.85D;

            if (recentUnderPlace) add *= 0.70D;
            if (grounded && trackedTicks > 2) add *= 0.80D;
            if (midAirJumpMotion) add *= 0.50D; // still lenient, but less than before

            reducedBuffer += add;
        } else {
            // Decay rate is higher once the player has already proved they accepted the velocity
            double decayRate = alreadyProvedAcceptance ? 0.55D : (validJumpMotion ? 0.35D : 0.18D);
            reducedBuffer -= Math.min(reducedBuffer, decayRate);
        }

        if (zero) {
            zeroBuffer += 1.20D;
        } else {
            zeroBuffer -= Math.min(zeroBuffer, 0.20D);
        }

        if (delayed) {
            delayedBuffer += 1.10D;
        } else {
            delayedBuffer -= Math.min(delayedBuffer, 0.16D);
        }

        verbose(this.getClass().getSimpleName(), bestRatio, 1.0D,
                MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (VelocityY)"
                        + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(deltaY)
                        + "\n * lastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(lastDeltaY)
                        + "\n * initialY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(initialY)
                        + "\n * expectedY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(expected)
                        + "\n * vanillaAirExpected " + MsgType.MAIN_THEME_COLOR.getMessage() + format(vanillaAirExpected)
                        + "\n * offset " + MsgType.MAIN_THEME_COLOR.getMessage() + format(offset)
                        + "\n * allowed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowed)
                        + "\n * ratio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(ratio)
                        + "\n * minRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(minRatio)
                        + "\n * bestDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestDeltaY)
                        + "\n * bestRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestRatio)
                        + "\n * velocityTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityData.getVelocityTicks()
                        + "\n * trackedTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + trackedTicks
                        + "\n * allowedDelay " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedDelayTicks
                        + "\n * waitingResponse " + MsgType.MAIN_THEME_COLOR.getMessage() + waitingForVelocityResponse
                        + "\n * recentUnderPlace " + MsgType.MAIN_THEME_COLOR.getMessage() + recentUnderPlace
                        + "\n * grounded " + MsgType.MAIN_THEME_COLOR.getMessage() + grounded
                        + "\n * midAirJumpMotion " + MsgType.MAIN_THEME_COLOR.getMessage() + midAirJumpMotion
                        + "\n * wasAirborneAtStart " + MsgType.MAIN_THEME_COLOR.getMessage() + wasAirborneAtStart
                        + "\n * validJumpMotion " + MsgType.MAIN_THEME_COLOR.getMessage() + validJumpMotion
                        + "\n * alreadyProved " + MsgType.MAIN_THEME_COLOR.getMessage() + alreadyProvedAcceptance
                        + "\n * reduced " + MsgType.MAIN_THEME_COLOR.getMessage() + reduced
                        + "\n * zero " + MsgType.MAIN_THEME_COLOR.getMessage() + zero
                        + "\n * delayed " + MsgType.MAIN_THEME_COLOR.getMessage() + delayed
                        + "\n * reducedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(reducedBuffer)
                        + "\n * zeroBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(zeroBuffer)
                        + "\n * delayedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(delayedBuffer)
                        + "\n * cumulativeMiss " + MsgType.MAIN_THEME_COLOR.getMessage() + format(cumulativeMissBuffer));

        if (zeroBuffer > ZERO_BUFFER_MAX) {
            cumulativeMissBuffer += 0.60D;
            failAndReset("Zero vertical velocity", movementData, velocityData, expected, offset, allowed, ratio, minRatio, recentUnderPlace, grounded, midAirJumpMotion);
            return;
        }

        if (delayedBuffer > DELAY_BUFFER_MAX) {
            cumulativeMissBuffer += 0.60D;
            failAndReset("Delayed vertical velocity", movementData, velocityData, expected, offset, allowed, ratio, minRatio, recentUnderPlace, grounded, midAirJumpMotion);
            return;
        }

        double requiredReducedBuffer = REDUCED_BUFFER_MAX;

        if (recentUnderPlace) requiredReducedBuffer += 0.75D;
        if (midAirJumpMotion) requiredReducedBuffer += 1.25D; // was 2.0 – tighter now

        if (reducedBuffer > requiredReducedBuffer) {
            cumulativeMissBuffer += 0.60D;
            failAndReset("Reduced vertical velocity", movementData, velocityData, expected, offset, allowed, ratio, minRatio, recentUnderPlace, grounded, midAirJumpMotion);
            return;
        }

        // Cumulative cross-hit detection: if player has consistently missed many velocity events
        if (cumulativeMissBuffer > CUMULATIVE_MISS_MAX) {
            cumulativeMissBuffer *= 0.35D;
            fail("Habitual velocity ignore",
                    "cumulativeMiss " + MsgType.MAIN_THEME_COLOR.getMessage() + format(cumulativeMissBuffer)
                            + "\ninitialY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(initialY)
                            + "\nbestDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestDeltaY)
                            + "\nbestRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestRatio));
            resetTracking();
            return;
        }

        expectedY = nextVertical(expectedY);

        int maxTicks = Math.max(10, allowedDelayTicks + 5);

        if (trackedTicks > maxTicks || expectedY <= 0.0D) {
            // If the player never accepted any motion over the full tracking window, note it cumulatively
            if (!acceptedAnyMotion && trackedTicks >= 3) {
                cumulativeMissBuffer += 0.30D;
            } else {
                // Accepted velocity – reward with cumulative decay
                cumulativeMissBuffer -= Math.min(cumulativeMissBuffer, 0.20D);
            }
            resetTracking();
        }
    }

    private boolean shouldStartTracking(double velocityY, int velocityTicks) {
        if (velocityY < MIN_TRACK_Y || velocityY > MAX_TRACK_Y) {
            return false;
        }

        int maxStartTicks = Math.max(2, Math.min(6, 2 + getPingTicks()));

        if (velocityTicks > maxStartTicks) {
            return false;
        }

        if (!tracking) {
            return true;
        }

        return velocityTicks <= 1 && Math.abs(velocityY - lastStartedY) > 1.0E-4D;
    }

    private void startTracking(double velocityY, int velocityTicks, MovementData movementData) {
        tracking = true;
        acceptedAnyMotion = false;
        wasAirborneAtStart = movementData.isCustomInAir() && !isGrounded(movementData);

        initialY = velocityY;
        expectedY = velocityY;
        bestDeltaY = 0.0D;
        bestRatio = 0.0D;
        lastStartedY = velocityY;

        trackedTicks = 0;
        startVelocityTicks = velocityTicks;
        allowedDelayTicks = getAllowedDelayTicks();

        // Carry over 40% of existing buffers so re-hits don't give a free reset
        reducedBuffer *= 0.40D;
        zeroBuffer *= 0.40D;
        delayedBuffer *= 0.40D;
    }

    /**
     * Simplified velocity Y confirmation using existing helper methods.
     */
    private double getConfirmedVelocityY(VelocityData velocityData) {
        double y = 0.0D;

        try {
            y = Math.max(y, velocityData.getVelocityV());
        } catch (Throwable ignored) {
        }

        try {
            y = Math.max(y, velocityData.getVelocityVSustain());
        } catch (Throwable ignored) {
        }

        try {
            y = Math.max(y, velocityData.getVelocityVfvc());
        } catch (Throwable ignored) {
        }

        try {
            Vector velocity = velocityData.getVelocity();
            if (velocity != null) y = Math.max(y, velocity.getY());
        } catch (Throwable ignored) {
        }

        try {
            Vector sustain = velocityData.getVelocitySustain();
            if (sustain != null) y = Math.max(y, sustain.getY());
        } catch (Throwable ignored) {
        }

        try {
            Vector fvc = velocityData.getVelocityfvc();
            if (fvc != null) y = Math.max(y, fvc.getY());
        } catch (Throwable ignored) {
        }

        return y;
    }

    private double nextVertical(double motion) {
        double next = (motion - GRAVITY) * DRAG;

        if (Math.abs(next) < 0.003D) {
            return 0.0D;
        }

        return Math.max(0.0D, next);
    }

    private double getAllowedOffset(double expected,
                                    double deltaY,
                                    boolean recentUnderPlace,
                                    boolean grounded,
                                    boolean midAirJumpMotion) {
        double allowed = 0.0065D;

        allowed += Math.min(0.012D, expected * 0.020D);
        allowed += Math.min(0.006D, Math.abs(deltaY) * 0.015D);
        allowed += Math.min(0.018D, getPingTicks() * 0.0015D);

        if (recentUnderPlace) allowed += 0.020D;
        if (grounded && trackedTicks > 1) allowed += 0.010D;
        if (midAirJumpMotion) allowed += 0.055D; // was 0.070 – slightly tighter
        if (profile.isBedrockPlayer()) allowed += 0.025D;

        return Math.min(midAirJumpMotion ? 0.110D : 0.035D, allowed);
    }

    private double getMinRatio(boolean recentUnderPlace, boolean grounded, boolean midAirJumpMotion) {
        if (midAirJumpMotion) {
            // Was 0.45 – now 0.55 so it can still catch blatant cheats even mid-air
            return 0.55D;
        }

        double ratio;

        if (trackedTicks <= 1) {
            ratio = OPEN_AIR_MIN_RATIO;
        } else if (trackedTicks == 2) {
            ratio = 0.900D;
        } else {
            ratio = 0.825D;
        }

        if (recentUnderPlace) ratio -= 0.08D;
        if (grounded && trackedTicks > 1) ratio -= 0.04D;
        if (profile.isBedrockPlayer()) ratio -= 0.06D;

        return Math.max(0.70D, ratio);
    }

    private double getAcceptedMotion(double expected, boolean midAirJumpMotion) {
        if (midAirJumpMotion) {
            return Math.max(0.015D, expected * 0.08D);
        }

        return Math.max(0.040D, expected * 0.25D);
    }

    private double getZeroEpsilon() {
        return 0.0025D;
    }

    private int getAllowedDelayTicks() {
        return Math.max(3, Math.min(12, 2 + (getPingTicks() * 2)));
    }

    private int getPingTicks() {
        int ticks = 0;

        try {
            ticks = Math.max(ticks, profile.getConnectionData().getClientTickTrans());
        } catch (Throwable ignored) {
        }

        try {
            ticks = Math.max(ticks, (int) Math.ceil(profile.getConnectionData().getTransPing() / 50.0D));
        } catch (Throwable ignored) {
        }

        try {
            ticks = Math.max(ticks, (int) Math.ceil(profile.getConnectionData().getPing() / 50.0D));
        } catch (Throwable ignored) {
        }

        try {
            ticks = Math.max(ticks, (int) Math.ceil(profile.getConnectionData().getAverageTransactionPing() / 50.0D));
        } catch (Throwable ignored) {
        }

        return Math.min(12, ticks);
    }

    private boolean isExempt(MovementData data) {
        if (profile.shouldCancel()
                || profile.isExempt().isTeleports()
                || profile.isExempt().vehicle()
                || profile.getExempt().isDead()
                || !profile.getExempt().isRespawned()
                || profile.getPlayer().isInsideVehicle()
                || profile.getMovementData().getSinceOnGhostBlock() <= 10 + profile.getConnectionData().getClientTickTrans()) {
            return true;
        }

        if (isUnderBlock(data)) {
            return true;
        }

        if (profile.getPlayer().getLastDamageCause() != null) {
            EntityDamageEvent.DamageCause cause = profile.getPlayer().getLastDamageCause().getCause();

            if (IGNORED_CAUSES.contains(cause)) {
                return true;
            }
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

    private boolean isUnderBlock(MovementData data) {
        return data.isUnderblock()
                || data.getMovingUnderblockTicks() > 0.0F;
    }

    private boolean hasRecentUnderPlace() {
        try {
            return profile.getActionData() != null
                    && profile.getActionData().hasRecentConfirmedUnderPlace(3 + getPingTicks());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void failAndReset(String reason,
                              MovementData movementData,
                              VelocityData velocityData,
                              double expected,
                              double offset,
                              double allowed,
                              double ratio,
                              double minRatio,
                              boolean recentUnderPlace,
                              boolean grounded,
                              boolean midAirJumpMotion) {
        fail(reason,
                "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(movementData.getDeltaY())
                        + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(movementData.getLastDeltaY())
                        + "\ninitialY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(initialY)
                        + "\nexpectedY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(expected)
                        + "\noffset " + MsgType.MAIN_THEME_COLOR.getMessage() + format(offset)
                        + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowed)
                        + "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(ratio)
                        + "\nminRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(minRatio)
                        + "\nbestDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestDeltaY)
                        + "\nbestRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestRatio)
                        + "\nvelocityTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityData.getVelocityTicks()
                        + "\nstartVelocityTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + startVelocityTicks
                        + "\ntrackedTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + trackedTicks
                        + "\nallowedDelay " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedDelayTicks
                        + "\nwasAirborneAtStart " + MsgType.MAIN_THEME_COLOR.getMessage() + wasAirborneAtStart
                        + "\nrecentUnderPlace " + MsgType.MAIN_THEME_COLOR.getMessage() + recentUnderPlace
                        + "\ngrounded " + MsgType.MAIN_THEME_COLOR.getMessage() + grounded
                        + "\nmidAirJumpMotion " + MsgType.MAIN_THEME_COLOR.getMessage() + midAirJumpMotion
                        + "\nreducedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(reducedBuffer)
                        + "\nzeroBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(zeroBuffer)
                        + "\ndelayedBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(delayedBuffer)
                        + "\ncumulativeMiss " + MsgType.MAIN_THEME_COLOR.getMessage() + format(cumulativeMissBuffer));

        reducedBuffer *= 0.25D;
        zeroBuffer *= 0.25D;
        delayedBuffer *= 0.25D;

        resetTracking();
    }

    private void decayBuffers(double amount) {
        reducedBuffer -= Math.min(reducedBuffer, amount);
        zeroBuffer -= Math.min(zeroBuffer, amount);
        delayedBuffer -= Math.min(delayedBuffer, amount);
    }

    private void resetTracking() {
        tracking = false;
        acceptedAnyMotion = false;
        wasAirborneAtStart = false;

        initialY = 0.0D;
        expectedY = 0.0D;
        bestDeltaY = 0.0D;
        bestRatio = 0.0D;

        trackedTicks = 0;
        startVelocityTicks = 0;
        allowedDelayTicks = 0;
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    /**
     * True ONLY when the player received velocity while they were genuinely mid-jump,
     * and we are still in the first few ticks where the observed motion may differ from
     * the velocity Y due to lag. This is now deliberately narrow to prevent the previous
     * issue where ANY upward air-motion triggered lenient mid-air thresholds.
     */
    private boolean isMidAirJumpMotion(MovementData data, double deltaY, double lastDeltaY) {
        // Must have been airborne when tracking started (player was jumping before being hit)
        if (!wasAirborneAtStart) {
            return false;
        }

        if (isGrounded(data)) {
            return false;
        }

        if (isUnderBlock(data)) {
            return false;
        }

        // Only applies during the initial response window (first 2 ticks after delay)
        // After that the client must show velocity-based motion regardless
        int graceTicks = Math.max(2, 1 + getPingTicks() / 3);
        if (trackedTicks > graceTicks) {
            return false;
        }

        double jumpMotion;
        try {
            jumpMotion = me.arrow.utils.MoveUtils.getJumpMotion(profile);
        } catch (Throwable ignored) {
            jumpMotion = 0.42D;
        }

        // Player's pre-hit deltaY was consistent with a natural jump upward arc
        boolean wasJumping = lastDeltaY > 0.05D || Math.abs(lastDeltaY - jumpMotion) <= 0.10D;

        return wasJumping;
    }

    private double predictVanillaAirY(double lastDeltaY) {
        double predicted = (lastDeltaY - GRAVITY) * DRAG;

        if (Math.abs(predicted) < 0.003D) {
            predicted = 0.0D;
        }

        return predicted;
    }

    private double getJumpMotionTolerance(MovementData data) {
        double tolerance = 0.045D;
        tolerance += Math.min(0.045D, getPingTicks() * 0.003D);

        if (data.getCustomAirTicks() <= 3) {
            tolerance += 0.040D;
        }

        if (profile.isBedrockPlayer()) {
            tolerance += 0.030D;
        }

        return Math.min(0.14D, tolerance);
    }
}