package me.arrow.checks.impl.combat.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;


@Experimental
public class VelocityA extends Check {

// this (probably) wont false in real combat, read velocity b comment for more info, but it's super basic, mainly tested only on 1.8

    public VelocityA(Profile profile) {
        super(profile, CheckType.VELOCITY, "A", "Checks vertical knockback");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    private double thresholdA, thresholdB, thresholdC, groundSpoofBuffer;
    private double explosionVerticalBuffer, fallVelocityBuffer, pendingExplosionVelocity, bestExplosionResponse;
    private int pendingExplosionTicks;
    private boolean pendingExplosionCollisionLimited;

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
        int transition = Math.max(0, profile.getConnectionData().getClientTickTrans());
        boolean verticalCollisionLimited = movementData.isMovingUp()
                || movementData.isNearWall()
                || movementData.isUnderblock()
                || movementData.getSincePredictUpwardsTicks() < 5;
        boolean explosionContext = pendingExplosionTicks > 0
                || velocityData.getExplosionVelocityPacketTicks() <= 3 + transition
                || velocityData.getExplosionVelocityTicks() <= 2;

        if (profile.getBlockProcessor().isUnderGhostBlock()
                || (verticalCollisionLimited && !explosionContext)) {
            clearPendingExplosion();
            return;
        }

        if (velocityData.getExplosionVelocityTicks() == 1) {
            armExplosionResponse(movementData, velocityData);
        }

        if (pendingExplosionTicks > 0 && verticalCollisionLimited) {
            pendingExplosionCollisionLimited = true;
        }

        observeExplosionResponse(movementData);

        /*
         * Normal entity velocity is absolute, while explosion knockback is
         * additive. Never run the absolute-velocity branches for an explosion.
         */
        if (velocityData.getEntityVelocityTicks() != 1 || explosionContext) {
            decayNormalBuffers(0.125D);
            return;
        }

        int damageWindow = 4 + (transition * 2);
        EntityDamageEvent.DamageCause lastDamageCause = profile.getDamageData().getLastCause(damageWindow);

        if (lastDamageCause == EntityDamageEvent.DamageCause.CONTACT) {
            decayNormalBuffers(0.5D);
            return;
        }

        if (lastDamageCause == EntityDamageEvent.DamageCause.FALL) {
            handleFallDamageVelocity(movementData);
            decayNormalBuffers(0.25D);
            return;
        }

        if (movementData.isNearWebs() ||
         movementData.getSinceGlidingTicks() < 30) return;

        int selfBowPunchLevel = lastDamageCause == EntityDamageEvent.DamageCause.PROJECTILE
                ? profile.getDamageData().getSelfBowPunchLevel(damageWindow)
                : -1;

        invalidVerticalA(movementData, velocityData);
        invalidVerticalB(movementData, velocityData, selfBowPunchLevel);
        invalidVerticalC(movementData, velocityData);
        spoofVelocity(movementData, velocityData);
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }


    public void invalidVerticalA(MovementData movementData, VelocityData velocityData) {
        double deltaY = movementData.getDeltaY();

        double velocity = velocityData.getVelocityVfvc();

        double ratio = deltaY / velocity;

        if (deltaY < 0.42f && velocity < 2 && velocity > 0.2) {
            if (velocityData.getEntityVelocityTicks() == 1
                    && !movementData.isOnGround() && movementData.isLastOnGround()) {

                if (ratio <= 0.99) {
                    thresholdA += ratio < 0.0D ? 3.0D : 1.0D;

                    if (thresholdA > 2.0D) {
                        fail("Invalid Vertical Velocity (1)", "velocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocity
                                + "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + ratio
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
                    }
                } else {
                    thresholdA -= Math.min(thresholdA, 0.5D);
                }
            }
        } else {
            thresholdA -= Math.min(thresholdA, 0.25D);
        }
    }


    public void invalidVerticalB(MovementData movementData,
                                 VelocityData velocityData,
                                 int selfBowPunchLevel) {
        double deltaY = movementData.getDeltaY();

        double velocity = velocityData.getVelocityVfvc();

        double ratio = deltaY / velocity;

        if (velocityData.getEntityVelocityTicks() == 1
                && Double.isFinite(ratio)
                && velocity > 0.05D
                && velocity < 4.0D) {
            double excess = deltaY - velocity;
            double tolerance = Math.max(0.04D, velocity * 0.08D);
            boolean vanillaJump = isVanillaJump(movementData);
            boolean selfBowBoost = selfBowPunchLevel >= 0;
            double maximumBowResponse = Math.max(velocity, getExpectedJumpMotion())
                    + 0.06D
                    + (selfBowPunchLevel * 0.02D);
            boolean amplified = selfBowBoost
                    ? deltaY > maximumBowResponse && deltaY - maximumBowResponse > 0.04D
                    : !vanillaJump && ratio > 1.08D && excess > tolerance;

            if (amplified) {
                thresholdB += ratio > 1.75D ? 2.0D : 1.0D;

                if (thresholdB > 1.0D) {
                    fail("Invalid Vertical Velocity (2)","deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + ratio
                            + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess
                            + "\nbowPunch " + MsgType.MAIN_THEME_COLOR.getMessage() + selfBowPunchLevel);
                }
            } else {
                thresholdB -= Math.min(thresholdB, 0.5D);
            }
        } else {
            thresholdB -= Math.min(thresholdB, 0.25D);
        }
    }

    private boolean isVanillaJump(MovementData movementData) {
        if (!movementData.isLastOnGround() || movementData.isOnGround() || movementData.getDeltaY() <= 0.0D) {
            return false;
        }

        double jumpMotion = getExpectedJumpMotion();

        return Math.abs(movementData.getDeltaY() - jumpMotion) <= 0.03125D;
    }

    private void handleFallDamageVelocity(MovementData movementData) {
        boolean jumpResponse = movementData.isLastOnGround()
                && !movementData.isOnGround()
                && movementData.getDeltaY() > 0.0D;

        /*
         * On 1.8, ordinary fall damage may produce no usable velocity response.
         * Only validate it when the movement itself confirms a jump response.
         */
        if (!jumpResponse) {
            fallVelocityBuffer = Math.max(0.0D, fallVelocityBuffer - 0.5D);
            return;
        }

        double expected = getExpectedJumpMotion();
        double response = movementData.getDeltaY();
        double ratio = response / expected;
        double difference = Math.abs(response - expected);
        boolean reduced = ratio < 0.75D && difference > 0.06D;
        boolean amplified = ratio > 1.20D && difference > 0.08D;

        if (reduced || amplified) {
            double deviation = reduced ? 1.0D - ratio : ratio - 1.0D;
            fallVelocityBuffer = Math.min(6.0D,
                    fallVelocityBuffer + Math.min(2.5D, Math.max(0.75D, deviation * 1.5D)));

            if (fallVelocityBuffer > 2.5D) {
                fail("Invalid Fall Damage Velocity",
                        "percent " + MsgType.MAIN_THEME_COLOR.getMessage() + (ratio * 100.0D)
                                + "\nmodification " + MsgType.MAIN_THEME_COLOR.getMessage() + (amplified ? "amplified" : "reduced")
                                + "\nexpected " + MsgType.MAIN_THEME_COLOR.getMessage() + expected
                                + "\nresponse " + MsgType.MAIN_THEME_COLOR.getMessage() + response);
            }
        } else {
            fallVelocityBuffer = Math.max(0.0D, fallVelocityBuffer - 1.0D);
        }
    }

    private double getExpectedJumpMotion() {
        double jumpMotion = 0.42D;

        if (profile.getPotionData() != null && profile.getPotionData().isHasJump()) {
            jumpMotion += profile.getPotionData().getJumpAmplifier() * 0.1D;
        }

        return jumpMotion;
    }

    public void invalidVerticalC(MovementData movementData, VelocityData velocityData) {

        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8) && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {

            if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                return;
            }
            double deltaY = movementData.getDeltaY();

            double velocity = velocityData.getVelocityVfvc();

            if (profile.getLastAttackByEntityTimer().hasNotPassed(20)
                    || profile.getLastShotByArrowTimer().hasNotPassed(20)) {

                if (velocityData.getEntityVelocityTicks() == 1
                        && movementData.isLastOnGround()) {

                    if ((deltaY / velocity) == 0.0) {
                        if (++thresholdC > 3.0D && velocity != -0.0783739241897089) {
                            fail("Invalid Vertical Velocity (3)", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nvelocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocity);
                        }
                    } else {
                        thresholdC -= Math.min(thresholdC, 0.125);
                    }
                }
            }
        }
    }

    public void spoofVelocity(MovementData movementData, VelocityData velocityData) {
        double deltaY = movementData.getDeltaY();
        double velocity = velocityData.getVelocityVfvc();

        if (velocityData.getEntityVelocityTicks() == 1) {
            if (deltaY < 0.42F && velocity < 2.0D && velocity > 0.2D) {
                if (movementData.isOnGround() && movementData.isLastOnGround()) {
                    if (++groundSpoofBuffer > 2.0D) {
                        fail("Spoofed ground velocity", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\nvelocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocity);
                    }
                } else {
                    groundSpoofBuffer -= Math.min(groundSpoofBuffer, 0.5D);
                }
            }
        }
    }

    private void armExplosionResponse(MovementData movementData, VelocityData velocityData) {
        int transition = Math.max(0, profile.getConnectionData().getClientTickTrans());
        boolean staggeredEntityResponse = velocityData.getEntityVelocityPacketTicks() <= 3 + transition
                && velocityData.getEntityVelocityTicks() != 1;

        if (staggeredEntityResponse) {
            clearPendingExplosion();
            return;
        }

        double explosionY = velocityData.getExplosionKnockbackfvc() == null
                ? 0.0D
                : velocityData.getExplosionKnockbackfvc().getY();

        if (!Double.isFinite(explosionY) || explosionY <= 0.1D) {
            clearPendingExplosion();
            return;
        }

        double baseY;

        if (velocityData.getEntityVelocityTicks() == 1) {
            baseY = velocityData.getVelocityVfvc();
        } else if (movementData.isLastOnGround() || movementData.isLastServerGround()) {
            baseY = 0.0D;
        } else {
            baseY = (movementData.getLastDeltaY() - 0.08D) * 0.98D;
        }

        double expected = baseY + explosionY;

        if (!Double.isFinite(expected) || expected <= 0.2D) {
            clearPendingExplosion();
            return;
        }

        pendingExplosionVelocity = expected;
        bestExplosionResponse = Double.NEGATIVE_INFINITY;
        pendingExplosionCollisionLimited = movementData.isNearWall()
                || movementData.isUnderblock()
                || movementData.isMovingUp()
                || movementData.getSincePredictUpwardsTicks() < 5;
        pendingExplosionTicks = 2 + Math.min(2, Math.max(0, profile.getConnectionData().getClientTickTrans()));
    }

    private void observeExplosionResponse(MovementData movementData) {
        if (pendingExplosionTicks <= 0 || pendingExplosionVelocity <= 0.0D) {
            return;
        }

        bestExplosionResponse = Math.max(bestExplosionResponse, movementData.getDeltaY());

        if (--pendingExplosionTicks > 0) {
            return;
        }

        double response = Math.max(0.0D, bestExplosionResponse);
        double ratio = response / pendingExplosionVelocity;
        double missing = Math.max(0.0D, pendingExplosionVelocity - response);
        double excess = Math.max(0.0D, response - pendingExplosionVelocity);
        boolean reduced = !pendingExplosionCollisionLimited && ratio < 0.75D && missing > 0.08D;
        boolean amplified = ratio > 1.20D && excess > 0.1D;

        if (reduced || amplified) {
            double deviation = reduced ? 1.0D - ratio : ratio - 1.0D;
            double evidence = Math.min(2.5D, Math.max(0.75D, deviation * 1.5D));

            if (ratio > 1.75D) {
                evidence = 3.0D;
            }

            explosionVerticalBuffer = Math.min(6.0D,
                    explosionVerticalBuffer + evidence);

            if (explosionVerticalBuffer > 2.5D) {
                fail("Invalid Vertical Velocity (Explosion)",
                        "percent " + MsgType.MAIN_THEME_COLOR.getMessage() + (ratio * 100.0D)
                                + "\nmodification " + MsgType.MAIN_THEME_COLOR.getMessage() + (amplified ? "amplified" : "reduced")
                                + "\nexpected " + MsgType.MAIN_THEME_COLOR.getMessage() + pendingExplosionVelocity
                                + "\nresponse " + MsgType.MAIN_THEME_COLOR.getMessage() + response
                                + "\nmissing " + MsgType.MAIN_THEME_COLOR.getMessage() + missing
                                + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess);
            }
        } else {
            explosionVerticalBuffer = Math.max(0.0D, explosionVerticalBuffer - 1.0D);
        }

        clearPendingExplosion();
    }

    private void clearPendingExplosion() {
        pendingExplosionVelocity = 0.0D;
        bestExplosionResponse = 0.0D;
        pendingExplosionTicks = 0;
        pendingExplosionCollisionLimited = false;
    }

    private void decayNormalBuffers(double amount) {
        thresholdA = Math.max(0.0D, thresholdA - amount);
        thresholdB = Math.max(0.0D, thresholdB - amount);
        thresholdC = Math.max(0.0D, thresholdC - amount);
        groundSpoofBuffer = Math.max(0.0D, groundSpoofBuffer - amount);
    }
}
