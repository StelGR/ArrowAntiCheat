package me.arrow.utils;

import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.entity.Player;

/**
 * A simple movement utility class
 * NOTE: This is not perfect, It's made in case you want to make simple
 * Limit checks, There's lots of things that are wrong in here.
 */
public final class MoveUtils {

    //---------------------------------------------------------------------------------------
    public static final float MAXIMUM_PITCH = 90.0F;
    //---------------------------------------------------------------------------------------
    public static final float FRICTION = .91F;
    public static final float FRICTION_FACTOR = .6F;
    public static final double WATER_FRICTION = .800000011920929D;
    public static final double MOTION_Y_FRICTION = .9800000190734863D;
    public static final double JAVA_JUMP_MOTION = .41999998688697815D;

    public static final double LAND_GROUND_MOTION = -.07840000152587834D;
    public static final float JUMP_MOVEMENT_FACTOR = 0.026F;
    //---------------------------------------------------------------------------------------
    /**
     * Assuming they're moving forward and no acceleration is applied.
     */
    public static final float BASE_AIR_SPEED = .3565F;
    /**
     * Assuming they're moving sideways
     */
    public static final float BASE_GROUND_SPEED = .2867F;
    //---------------------------------------------------------------------------------------
    /**
     * 1.9+ Clients last tick motion before it resets to 0 due to .003D
     */
    public static final double RESET_MOTION = .003016261509046103D;
    //---------------------------------------------------------------------------------------

    private MoveUtils() {

    }

    /**
     * Conservative, realistic base air speed derived from ground base speed,
     * sprint state, and expected air-control. This is intended to approximate
     * the *maximum plausible* horizontal speed a player can produce while airborne,
     * taking into account potion/walk-speed modifiers (via MathUtil.getBaseSpeed).
     *
     * Rationale:
     * - Use MathUtil.getBaseSpeed(player) as baseline (already accounts for speed potions/walkSpeed).
     * - Sprinting gives an empirical multiplier (~1.3 on ground). In air the sprint benefit is slightly
     *   reduced for control but still relevant for initial velocities after jump.
     * - Air-control is better modeled as an additive acceleration over ticks rather than a single
     *   absolute cap; we only need a conservative expected maximum, so we scale baseline.
     *
     * This method aims to be conservative (avoid false positives) while giving the prediction
     * a dynamic base rather than a fixed constant.
     */
    public static float getBaseAirSpeed(final Profile profile) {

        if (profile == null || profile.getPlayer() == null) {
            return BASE_AIR_SPEED;
        }

        Player player = profile.getPlayer();

        // Baseline from ground-logic (includes potion speed levels, walk-speed changes, etc.)
        float baseGround = getBaseGroundSpeed(profile); // MathUtil.getBaseSpeed(player)

        // Sprint multiplier: players sprint on ground produce higher initial horizontal impulse when jumping.
        boolean sprinting = false;
        try {
            sprinting = profile.getActionData() != null && profile.getActionData().isSprinting();
        } catch (Exception ignored) { /* defensive */ }

        final float SPRINT_MULT = sprinting ? 1.28f : 1.0f; // conservative sprint boost

        // Jump movement factor increases ability to alter horizontal speed during airborne state.
        // We combine a fraction of JUMP_MOVEMENT_FACTOR effect with baseGround to estimate air controllability.
        // This is empirical: baseGround * (1 + JUMP_MOVEMENT_FACTOR * 6) yields sensible values.
        float airFromGround = baseGround * (1.0f + (JUMP_MOVEMENT_FACTOR * 6.0f));

        // Compose final base air speed. Ensure never below the conservative constant.
        float computed = Math.max(BASE_AIR_SPEED, airFromGround) * SPRINT_MULT;

        // Account for slowness potion (if present) in a simple way - MathUtil already adjusts baseGround
        // but if walkSpeed below default we clamp accordingly.
        float walkSpeed = player.getWalkSpeed();
        // The vanilla default walkSpeed is 0.2f; deviations reduce/increase control. We modestly incorporate this.
        float walkSpeedFactor = 1.0f + ((walkSpeed - 0.2f) * 1.6f);
        computed *= walkSpeedFactor;

        // Defensive clamping — keep computed in a reasonable band
        computed = Math.max(0.08f, Math.min(computed, 1.6f));

        return computed;
    }

    public static float getBaseGroundSpeed(final Profile profile) {

        //Your own method here
        return MathUtil.getBaseSpeed(profile.getPlayer());
    }

    public static float getCustomSpeed(final Profile profile) {

        //Your own method here
        return getBaseGroundSpeed(profile);
    }



    public static double getJumpMotion(Profile profile) {
        if (profile != null && profile.isBedrockPlayer() && profile.getMovementData() != null) {
            double motion = profile.getMovementData().getBEDROCK_JUMP_MOTION();

            if (profile.getPotionData() != null && profile.getPotionData().isHasJump()) {
                int level = profile.getPotionData().getJumpAmplifier();
                if (level >= 0) {
                    motion += level * 0.1D;
                }
            }

            return motion;
        }

        float motion = 0.42F;

        if (profile != null && profile.getPotionData() != null && profile.getPotionData().isHasJump()) {
            int level = profile.getPotionData().getJumpAmplifier();
            if (level >= 0) {
                motion += level * 0.1F;
            }
        }

        return motion;
    }
}
