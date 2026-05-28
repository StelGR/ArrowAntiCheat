package me.arrow.playerdata.processors.impl;

import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.PotionData;
import me.arrow.utils.custom.PotionType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// this is completely made by Chat GPT, cry all you want this is better than most simulations for the server side, the only issue it has, is if the piston moves the slime block while it pushes you, it has no world compensasion so there for it will not detect it as proper slime jump, otherwise it's perfect
// maybe vulcan can learn something from this, can't wait to see him sell this for 50$ and pull a verus

/**
 * Per-instanced slime-bounce tracker. Create one instance and call isBouncing(...) per packet for each player.
 * - Non-mutating: reads MovementData / PotionData only.
 * - Keeps an internal map of active BounceSessions keyed by player UUID (or fallback synthetic key).
 */
public class SlimeProcessor {

    Profile profile;

    public SlimeProcessor(Profile profile){
        this.profile = profile;
    }

    // ---- Tunables (adjust to taste) ----
    private final double BOUNCE_HEIGHT_RATIO = 0.60;      // vanilla-ish small-fall ratio
    private final double BOUNCE_VELOCITY_FACTOR = Math.sqrt(BOUNCE_HEIGHT_RATIO);
    private final double EPS_FALL = 0.03;                 // lastDeltaY < -EPS_FALL considered falling
    private final double EPS_UP = 0.01;                   // deltaY > EPS_UP considered moving up
    private final float MIN_MEANINGFUL_FALL = 0.01f;      // ignore tiny falls
    private final float MIN_FALLDIST_FOR_FALL = 0.2f;     // lastFallDistance > this => considered falling
    private final float MAX_JUMP_FALL_DISTANCE = 0.15f;   // small fall => probably normal jump
    private final double VELOCITY_ALLOWANCE = 0.02;       // tolerance for numeric noise in velocities
    private final double RISE_ALLOWANCE = 0.05;           // tolerance (meters/blocks) for predicted rise
    private final int MAX_SIM_TICKS = 200;                // safety cap for apex simulation

    // Vanilla-ish per-tick constants used for short simulation to predict apex / rise.
    // These are approximations (minecraft motionY changes per tick roughly by gravity and drag).
    private final double VANILLA_GRAVITY = 0.08;
    private final double VANILLA_DRAG = 0.98;

    // Per-instance sessions map (not static).
    private final Map<UUID, BounceSession> sessions = new ConcurrentHashMap<>();

    private static final class BounceSession {
        final double expectedBounceVel; // predicted initial upward velocity (positive)
        final double predictedMaxRise;  // predicted total rise (blocks/meters) from start
        int remainingTicks;             // predicted ticks until apex (or tolerance)
        double accumulatedRise;         // actual observed upward motion accumulated (sum of positive deltaY)
        boolean startedRising;          // we have observed upward deltaY at least once
        boolean pistonPush;

        BounceSession(double expectedBounceVel, double predictedMaxRise, int remainingTicks, boolean pistonPush) {
            this.expectedBounceVel = expectedBounceVel;
            this.predictedMaxRise = predictedMaxRise;
            this.remainingTicks = remainingTicks;
            this.accumulatedRise = 0.0;
            this.startedRising = false;
            this.pistonPush = pistonPush;
        }
    }

    /**
     * Call this every packet / movement update.
     * Returns true while the player is considered to be in a proper slime bounce (from the initial
     * rising tick until apex/stop-climbing), subject to the checks described above.
     *
     * This method does not mutate MovementData or PotionData.
     */
    public boolean isBouncing(MovementData md, PotionData pd) {
        if (md == null) return false;

        // Determine key (prefer player UUID)
        UUID uuid = profile.getUUID();
        // fallback synthetic UUID derived from hashcode to keep key stable across calls for same object
        UUID key = (uuid != null) ? uuid : syntheticUuidFromObject(md);

//        // quick: if player is sneaking now, cancel any session and return false (vanilla cancels bounce)
//        Player player = profile.getPlayer();
//        if (player != null && player.isSneaking()) {
//            sessions.remove(key);
//            return false;
//        }

        // read movement fields (non-mutating)
        double deltaY = md.getDeltaY();
        double lastDeltaY = md.getLastDeltaY();
        float lastFallDistance = md.getLastFallDistance();
        boolean lastOnGround = md.isLastOnGround();

        // If there is an active session, update it
        BounceSession session = sessions.get(key);
        if (session != null) {
            // Reject if player's actual upward motion ever exceeds expected bounce velocity + allowance
            if (!session.pistonPush) {
                if (deltaY - (session.expectedBounceVel + VELOCITY_ALLOWANCE) > 0.0) {
                    sessions.remove(key);
                    return false;
                }
            }

            // accumulate positive upward motion to track actual rise since session started
            if (deltaY > 0.0) {
                session.accumulatedRise += deltaY;
                session.startedRising = true;
            }

            // If accumulated rise exceeds predicted max rise + allowance => invalid bounce
            if (!session.pistonPush) {
                if (session.accumulatedRise - (session.predictedMaxRise + RISE_ALLOWANCE) > 0.0) {
                    sessions.remove(key);
                    return false;
                }
            }

            // If currently moving up, decrement remaining ticks (we assume per-packet == per-tick)
            if (deltaY > EPS_UP) {
                session.remainingTicks = Math.max(0, session.remainingTicks - 1);
                // still rising -> still bouncing (even if remainingTicks reaches 0 we keep one final tick tolerance)
                if (session.remainingTicks <= 0) {
                    // if the player still has positive deltaY, keep one last tick; otherwise finish
                    if (deltaY > EPS_UP) {
                        // let it remain true this tick, but expire next call if no upward motion
                        sessions.put(key, session);
                        return true;
                    } else {
                        sessions.remove(key);
                        return false;
                    }
                } else {
                    sessions.put(key, session);
                    return true;
                }
            }

            // Not currently moving up:
            // - if we had started rising earlier, allow the session to linger for remainingTicks (tolerance)
            if (session.startedRising && session.remainingTicks > 0) {
                session.remainingTicks = Math.max(0, session.remainingTicks - 1);
                if (session.remainingTicks <= 0) {
                    sessions.remove(key);
                    return false;
                } else {
                    sessions.put(key, session);
                    return true;
                }
            }

            // Otherwise, session finished
            sessions.remove(key);
            return false;
        }

        // No session: test for initial bounce tick using conservative rules
        // Must be on slime block now
        if (!md.isOnExtendedHitboxSlime()) {
            return false;
        }

        boolean wasFalling =
                lastDeltaY < -EPS_FALL
                        || lastFallDistance > MIN_FALLDIST_FOR_FALL;

        boolean pistonSlimePush = md.isNearPiston() && deltaY >= 0.75D;

        boolean nowMovingUp = deltaY > EPS_UP;

        if ((!wasFalling && !pistonSlimePush) || !nowMovingUp) {
            return false;
        }

        if (!pistonSlimePush && lastFallDistance <= MIN_MEANINGFUL_FALL) {
            return false;
        }

        // Exclude normal player jump started on ground (including tiny step-ups)
        if (!pistonSlimePush) {
            if (lastOnGround && lastFallDistance <= MAX_JUMP_FALL_DISTANCE) {
                return false;
            }
        }

        // exclude canonical jump initial (jump boost aware)
        int jumpLevel = 0;
        if (pd != null) {
            try { jumpLevel = pd.getPotionEffectLevel(PotionType.JUMP_BOOST); } catch (Exception ignored) {}
        }
        double expectedJumpInit = 0.42 + 0.1 * Math.max(0, jumpLevel);
        if (!pistonSlimePush) {
            if (lastOnGround && deltaY >= expectedJumpInit * 0.85) {
                return false;
            }
        }

        // compute expected bounce velocity from lastDeltaY (preferred) or approximate from lastFallDistance
        double expectedBounceVel;
        if (lastDeltaY < 0.0) {
            expectedBounceVel = -lastDeltaY * BOUNCE_VELOCITY_FACTOR;
        } else {
            // fallback conversion: v ~= sqrt(2 * g * h) with g scaled per second; used only rarely.
            final double gPerSec = VANILLA_GRAVITY * 20.0;
            double h = Math.max(0.0f, lastFallDistance);
            double v = Math.sqrt(2.0 * gPerSec * h);
            expectedBounceVel = v * BOUNCE_VELOCITY_FACTOR;
        }

        if (!(expectedBounceVel > 0.0)) return false;

        // Reject if actual upward motion already exceeds expected bounce + allowance
        if (deltaY - (expectedBounceVel + VELOCITY_ALLOWANCE) > 0.0) return false;

        // Predict apex ticks and predicted max rise (bounded loop; fast).
        SimulationResult sim = simulateApexAndRise(expectedBounceVel);
        int ticksToApex = sim.ticksToApex;
        double predictedRise = sim.predictedRise;

        // create session and mark startedRising
        // clamp ticks for safety
        if (ticksToApex < 1) ticksToApex = 1;
        if (ticksToApex > MAX_SIM_TICKS) ticksToApex = MAX_SIM_TICKS;

        BounceSession newSession = new BounceSession(
                expectedBounceVel,
                predictedRise,
                ticksToApex,
                pistonSlimePush
        );
        // we've observed first rising tick now: start accumulatedRise with this positive deltaY
        newSession.startedRising = true;
        newSession.accumulatedRise = Math.max(0.0, deltaY);

        sessions.put(key, newSession);
        return true;
    }

    /** Clears session for a specific player (useful on logout). */
    public void clearSession(UUID playerUuid) {
        if (playerUuid == null) return;
        sessions.remove(playerUuid);
    }

    /** Clear all sessions (useful for tests/world unload). */
    public void clearAll() {
        sessions.clear();
    }

    // ---- Helpers ----

    // Small result object for simulation
    private static final class SimulationResult {
        final int ticksToApex;
        final double predictedRise;
        SimulationResult(int ticksToApex, double predictedRise) {
            this.ticksToApex = ticksToApex;
            this.predictedRise = predictedRise;
        }
    }

    /**
     * Simulate per-tick motion to compute ticks until upward velocity becomes <= 0 and predicted rise.
     * Bounded by MAX_SIM_TICKS; uses VANILLA_GRAVITY and VANILLA_DRAG.
     */
    private SimulationResult simulateApexAndRise(double initialUpVel) {
        double v = initialUpVel;
        double rise = 0.0;
        int ticks = 0;
        int cap = Math.min(MAX_SIM_TICKS, 200);
        while (v > 0.0 && ticks < cap) {
            rise += v;                    // approximate per-tick displacement (motionY units)
            v = (v - VANILLA_GRAVITY) * VANILLA_DRAG;
            ticks++;
        }
        return new SimulationResult(ticks, rise);
    }

    // Create a synthetic stable UUID from an object's identity/hashcode for fallback keys.
    private static UUID syntheticUuidFromObject(Object o) {
        int h = System.identityHashCode(o);
        // combine with a constant so it's a valid UUID but stable for object identity during lifetime
        long msb = 0x2f4a5c3b00000000L | ((long) h & 0xffffffffL);
        long lsb = 0x7f3d9b2e00000000L | (((long) h << 16) & 0xffffffffL);
        return new UUID(msb, lsb);
    }
}
