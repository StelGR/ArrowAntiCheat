package me.arrow.checks.impl.combat.velocity;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.materials.PEMaterials;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

import java.util.*;

@Experimental
public class VelocityB extends Check {

    private double horizontalBuffer;
    private double predictedX, predictedZ, allowance = 0.001D;
    private int velocityTicks, attackPackets, fittedAttackReductions, activeBowPunchLevel = -1;
    private int lastCheckedEntityVelocitySequence = -1;
    private boolean lastGround, activeExplosion, activeCollisionLimited;

    private static final float[][] KEY_COMBOS = {
            {1.0F, -1.0F},
            {1.0F, 0.0F},
            {1.0F, 1.0F},
            {0.0F, -1.0F},
            {0.0F, 0.0F},
            {0.0F, 1.0F},
            {-1.0F, -1.0F},
            {-1.0F, 0.0F},
            {-1.0F, 1.0F}
    };

    private static final boolean[] BOOLEANS = {true, false};

    public VelocityB(Profile profile) {
        super(profile, CheckType.VELOCITY, "B", "Checks horizontal knockback");
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
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                attackPackets++;
            }

            return;
        }

        if (!isMovement(event)) {
            return;
        }

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.getMovementData() == null
                || profile.getVelocityData() == null
                || profile.getBlockProcessor() == null
                || profile.getConnectionData() == null) {
            resetVelocityState();
            return;
        }

        MovementData movementData = profile.getMovementData();
        VelocityData velocityData = profile.getVelocityData();
        int transition = Math.max(0, profile.getConnectionData().getClientTickTrans());

        if (movementData.isNearWebs() ||
                movementData.getSinceGlidingTicks() < 30) return;

        if (profile.getBlockProcessor().isUnderGhostBlock()
                || profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + transition) {
            resetVelocityState();
            return;
        }
        int damageWindow = 4 + (transition * 2);

        if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, damageWindow)) {
            resetVelocityState();
            return;
        }

        ClientWorldTracker.CollisionResult clientWorld = profile.getClientWorldTracker().getCollisionResult();

        if (clientWorld.shouldExemptMovementChecks()) {
            resetVelocityState();
            return;
        }

        if (profile.getBlockProcessor().isCancelledBlockPlacementExempt(10 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            return;
        }

        if (movementData.isNearWebs()) return;

        handleHorizontalVelocity(movementData, velocityData);

    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private void handleHorizontalVelocity(MovementData movementData, VelocityData velocityData) {
        int entityTicks = velocityData.getEntityVelocityTicks();
        int explosionTicks = velocityData.getExplosionVelocityTicks();
        int confirmedTicks = Math.min(entityTicks, explosionTicks);
        boolean entityResponse = entityTicks == 1;
        boolean explosionResponse = explosionTicks == 1;
        int transition = Math.max(0, profile.getConnectionData().getClientTickTrans());
        int entityVelocitySequence = velocityData.getEntityVelocitySequence();
        boolean newEntityResponse = entityResponse
                && entityVelocitySequence != lastCheckedEntityVelocitySequence;
        boolean pendingEntityResponse = !entityResponse
                && !explosionResponse
                && entityVelocitySequence > 0
                && entityVelocitySequence != lastCheckedEntityVelocitySequence
                && velocityData.getEntityVelocityPacketTicks() >= getWithheldTransactionGraceTicks()
                && velocityData.getPendingEntityVelocity() != null
                && Math.hypot(velocityData.getPendingEntityVelocity().getX(), velocityData.getPendingEntityVelocity().getZ()) > getMoveOffset();
        int responseTicks = (newEntityResponse || pendingEntityResponse || explosionResponse) ? 1 : confirmedTicks;
        boolean waitingForExplosion = velocityData.getExplosionVelocityPacketTicks() <= 2 + transition
                && explosionTicks > 1;
        boolean waitingForEntityVelocity = velocityData.getEntityVelocityPacketTicks() <= 2 + transition
                && entityTicks > 1;
        int damageWindow = 4 + (transition * 2);
        EntityDamageEvent.DamageCause lastDamageCause = profile.getDamageData().getLastCause(damageWindow);
        boolean fallOrContactDamage = lastDamageCause == EntityDamageEvent.DamageCause.FALL
                || lastDamageCause == EntityDamageEvent.DamageCause.CONTACT;
        int selfBowPunchLevel = lastDamageCause == EntityDamageEvent.DamageCause.PROJECTILE
                ? profile.getDamageData().getSelfBowPunchLevel(damageWindow)
                : -1;

        if ((entityResponse && !explosionResponse && waitingForExplosion)
                || (explosionResponse && !entityResponse && waitingForEntityVelocity)) {
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        if (newEntityResponse || pendingEntityResponse || explosionResponse) {
            initializeResponse(movementData, velocityData, newEntityResponse, pendingEntityResponse, explosionResponse);

            if (newEntityResponse || pendingEntityResponse) {
                lastCheckedEntityVelocitySequence = entityVelocitySequence;
            }

            activeBowPunchLevel = (newEntityResponse || pendingEntityResponse) && !explosionResponse ? selfBowPunchLevel : -1;
        }

        if (activeExplosion && movementData.isNearWall()) {
            activeCollisionLimited = true;
        }

        if (!canCheckHorizontalVelocity(movementData, responseTicks)) {
            horizontalBuffer = Math.max(horizontalBuffer - 0.01D, 0.0D);
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        CollisionContext collisionContext = createCollisionContext(movementData, predictedX, predictedZ);

        if (collisionContext.uncertain) {
            horizontalBuffer = Math.max(horizontalBuffer - 0.05D, 0.0D);
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        /*
         * Vanilla combat can apply one sprint-hit slowdown before the next
         * movement packet. Modelling every attack packet as another 0.6x
         * reduction lets packet spam disguise major velocity reductions.
         */
        if (attackPackets >= 1 && isAttackSlowdownPossible()) {
            fitAttackSlowdown(movementData, collisionContext);
        } else {
            fittedAttackReductions = 0;
        }

        double carryFriction = lastGround ? getCarryFriction(movementData) : 0.91D;
        predictedX = Math.abs(predictedX) < getVelocityClamp() ? 0.0D : predictedX;
        predictedZ = Math.abs(predictedZ) < getVelocityClamp() ? 0.0D : predictedZ;

        double impulseX = predictedX;
        double impulseZ = predictedZ;
        double impulseHorizontal = Math.hypot(impulseX, impulseZ);

        /*
         * Below one normal movement input, horizontal response cannot be
         * separated reliably from the player's own movement. This covers
         * vertical-biased sources such as iron golems; VelocityA still checks
         * the meaningful vertical response from the same packet.
         */
        if (!activeExplosion
                && activeBowPunchLevel < 0
                && impulseHorizontal < getMinimumReliableEntityHorizontal()) {
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        HorizontalInput input = findClosestInput(movementData, predictedX, predictedZ, collisionContext);

        if (input == null) {
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        /*
         * findClosestInput already applies both the jump sprint impulse and
         * moveFlying. Re-applying them here made normal jump resets look like
         * reduced knockback.
         */
        predictedX = input.motionX;
        predictedZ = input.motionZ;
        activeCollisionLimited = input.collidedX || input.collidedZ;

        double clientHorizontal = movementData.getDeltaXZ();
        double serverHorizontal = Math.hypot(predictedX, predictedZ);

        if (serverHorizontal <= 0.025D && !activeExplosion) {
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        /*
         * Landing damage does not produce a stable horizontal response, and
         * cactus/contact damage produces none. VelocityA handles the observable
         * vertical jump response for fall damage instead.
         */
        if ((newEntityResponse || pendingEntityResponse) && !explosionResponse && fallOrContactDamage) {
            horizontalBuffer = Math.max(0.0D, horizontalBuffer - 0.5D);
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        double deficitX = predictedX - movementData.getDeltaX();
        double deficitZ = predictedZ - movementData.getDeltaZ();
        double directionalThreshold = activeBowPunchLevel >= 0 ? 0.01D : getMoveOffset();
        boolean directionalImpulse = impulseHorizontal > directionalThreshold;
        double projectedMissing = directionalImpulse
                ? ((deficitX * impulseX) + (deficitZ * impulseZ)) / impulseHorizontal
                : 0.0D;
        double ratio = directionalImpulse
                ? 1.0D - (projectedMissing / impulseHorizontal)
                : (serverHorizontal > 1.0E-6D ? clientHorizontal / serverHorizontal : 1.0D);
        double percent = ratio * 100.0D;
        double missing = Math.max(0.0D, projectedMissing);
        double excess = Math.max(0.0D, -projectedMissing);
        double requiredPercent = getRequiredPercent();
        boolean jumped = isJumpStart(movementData);
        boolean reversed = isReversed(movementData, predictedX, predictedZ);
        double predictionTolerance = getPredictionTolerance(impulseHorizontal);
        allowance = Math.max(allowance, predictionTolerance);
        double bowBoostAllowance = getBowBoostAllowance(activeBowPunchLevel);
        double amplificationAllowance = allowance + bowBoostAllowance;
        double maximumPercent = activeBowPunchLevel >= 0
                ? (1.0D + (amplificationAllowance / impulseHorizontal)) * 100.0D
                : (activeExplosion ? 108.0D : 103.0D);
        boolean reduced = directionalImpulse
                && percent < requiredPercent
                && missing > allowance
                && input.offset > allowance;
        boolean directSevereReduction = isDirectSevereEntityReduction(
                newEntityResponse || pendingEntityResponse,
                explosionResponse,
                movementData,
                velocityData,
                jumped,
                activeCollisionLimited
        );
        boolean amplified = directionalImpulse
                ? percent > maximumPercent
                && excess > amplificationAllowance
                && input.offset > amplificationAllowance
                : activeExplosion
                && clientHorizontal > serverHorizontal + Math.max(0.05D, allowance)
                && input.offset > Math.max(0.05D, allowance);
        boolean invalid = reduced || directSevereReduction || amplified;

        if (invalid) {
            double deviation = reduced
                    ? Math.max(0.0D, 1.0D - ratio)
                    : Math.max(0.0D, ratio - (maximumPercent / 100.0D));
            double evidence = Math.min(2.5D, Math.max(0.75D, 0.75D + (deviation * 2.0D)));

            if (ratio < -0.25D || ratio > 1.75D) {
                evidence = 3.25D;
            }

            // Even after the one permitted sprint-hit slowdown, receiving at
            // most 45% is a hard reduction and should be caught immediately.
            if ((reduced && ratio <= 0.45D) || directSevereReduction) {
                evidence = 2.5D;
            }

            horizontalBuffer = Math.min(15.0D, horizontalBuffer + evidence);

            if (horizontalBuffer > getMaxBuffer()) {
                fail("Invalid Horizontal Velocity",
                        "percent " + MsgType.MAIN_THEME_COLOR.getMessage() + percent
                                + "\nrequired " + MsgType.MAIN_THEME_COLOR.getMessage() + requiredPercent
                                + "\nmaximum " + MsgType.MAIN_THEME_COLOR.getMessage() + maximumPercent
                                + "\nmodification " + MsgType.MAIN_THEME_COLOR.getMessage() + (amplified ? "amplified" : (ratio < 0.0D ? "reversed" : "reduced"))
                                + "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + ratio
                                + "\nclient " + MsgType.MAIN_THEME_COLOR.getMessage() + clientHorizontal
                                + "\nserver " + MsgType.MAIN_THEME_COLOR.getMessage() + serverHorizontal
                                + "\nmissing " + MsgType.MAIN_THEME_COLOR.getMessage() + missing
                                + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess
                                + "\nallowance " + MsgType.MAIN_THEME_COLOR.getMessage() + allowance
                                + "\nmodelError " + MsgType.MAIN_THEME_COLOR.getMessage() + input.offset
                                + "\nresponse " + MsgType.MAIN_THEME_COLOR.getMessage() + (activeExplosion ? "explosion" : (pendingEntityResponse ? "pending-entity" : "entity"))
                                + "\nbowPunch " + MsgType.MAIN_THEME_COLOR.getMessage() + activeBowPunchLevel
                                + "\nkbX " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedX
                                + "\nkbZ " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedZ
                                + "\npredictedX " + MsgType.MAIN_THEME_COLOR.getMessage() + input.motionX
                                + "\npredictedZ " + MsgType.MAIN_THEME_COLOR.getMessage() + input.motionZ
                                + "\ninputOffset " + MsgType.MAIN_THEME_COLOR.getMessage() + input.offset
                                + "\nstrafe " + MsgType.MAIN_THEME_COLOR.getMessage() + input.strafe
                                + "\nforward " + MsgType.MAIN_THEME_COLOR.getMessage() + input.forward
                                + "\nfriction " + MsgType.MAIN_THEME_COLOR.getMessage() + input.inputFriction
                                + "\nattackReductions " + MsgType.MAIN_THEME_COLOR.getMessage() + fittedAttackReductions
                                + "\nattacks " + MsgType.MAIN_THEME_COLOR.getMessage() + attackPackets
                                + "\njump " + MsgType.MAIN_THEME_COLOR.getMessage() + jumped + " | " + input.jump
                                + "\nreverse " + MsgType.MAIN_THEME_COLOR.getMessage() + reversed
                                + "\ntick " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityTicks
                                + "\nvelocityTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + responseTicks
                                + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + horizontalBuffer);
            }

            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        /*
         * Do not let isolated model noise accumulate forever through normal
         * combat. Strong reductions still add multiple points per response and
         * remain detectable within the requested hit window.
         */
        horizontalBuffer = Math.max(horizontalBuffer - 0.25D, 0.0D);
        predictedX = input.carryX * carryFriction;
        predictedZ = input.carryZ * carryFriction;

        if (velocityTicks++ >= 8 || !hasHorizontalVelocity()) {
            resetVelocityState();
        }

        finishMovementTick(movementData);
    }

    private boolean canCheckHorizontalVelocity(MovementData movementData, int confirmedTicks) {
        return confirmedTicks >= 1
                && confirmedTicks <= (activeExplosion ? 8 : 4)
                && movementData != null
                && movementData.getLocation() != null
                && (activeExplosion || activeBowPunchLevel >= 0 || hasHorizontalVelocity())
                && (activeExplosion
                || activeBowPunchLevel >= 0
                || Math.hypot(predictedX, predictedZ) > getMoveOffset());
    }

    private void initializeResponse(MovementData movementData,
                                    VelocityData velocityData,
                                    boolean entityResponse,
                                    boolean pendingEntityResponse,
                                    boolean explosionResponse) {
        double responseX = 0.0D;
        double responseZ = 0.0D;

        if (entityResponse || pendingEntityResponse) {
            Vector velocity = entityResponse
                    ? velocityData.getVelocityfvc()
                    : velocityData.getPendingEntityVelocity();

            if (velocity != null) {
                responseX = velocity.getX();
                responseZ = velocity.getZ();
            }
        } else {
            double carry = lastGround ? getCarryFriction(movementData) : 0.91D;
            responseX = movementData.getLastDeltaX() * carry;
            responseZ = movementData.getLastDeltaZ() * carry;
        }

        if (explosionResponse) {
            Vector explosion = velocityData.getExplosionKnockbackfvc();

            if (explosion != null) {
                responseX += explosion.getX();
                responseZ += explosion.getZ();
            }
        }

        predictedX = responseX;
        predictedZ = responseZ;
        velocityTicks = 0;
        activeExplosion = explosionResponse;
        activeCollisionLimited = explosionResponse && movementData.isNearWall();
        allowance = getPredictionTolerance(Math.hypot(responseX, responseZ));
    }

    private void fitAttackSlowdown(MovementData movementData, CollisionContext collisionContext) {
        HorizontalInput baseInput = findClosestInput(movementData, predictedX, predictedZ, collisionContext);
        double bestOffset = baseInput == null
                ? Math.hypot(movementData.getDeltaX() - predictedX, movementData.getDeltaZ() - predictedZ)
                : baseInput.offset;
        double bestX = predictedX;
        double bestZ = predictedZ;
        int bestReductions = 0;

        double baseX = Math.abs(predictedX) < getVelocityClamp() ? 0.0D : predictedX;
        double baseZ = Math.abs(predictedZ) < getVelocityClamp() ? 0.0D : predictedZ;

        for (int i = 1; i <= 1; i++) {
            double reducedX = baseX * Math.pow(0.6D, i);
            double reducedZ = baseZ * Math.pow(0.6D, i);
            HorizontalInput input = findClosestInput(movementData, reducedX, reducedZ, collisionContext);

            if (input == null) {
                continue;
            }

            if (input.offset < bestOffset) {
                bestOffset = input.offset;
                bestX = reducedX;
                bestZ = reducedZ;
                bestReductions = i;
            }
        }

        predictedX = bestX;
        predictedZ = bestZ;
        fittedAttackReductions = bestReductions;
    }

    private HorizontalInput findClosestInput(MovementData movementData,
                                             double baseX,
                                             double baseZ,
                                             CollisionContext collisionContext) {
        HorizontalInput best = null;

        for (float[] keys : KEY_COMBOS) {
            for (boolean sprinting : BOOLEANS) {
                for (boolean blocking : BOOLEANS) {
                    for (boolean sneaking : BOOLEANS) {
                        for (boolean jumping : BOOLEANS) {
                            double motionX = baseX;
                            double motionZ = baseZ;
                            boolean appliedJump = jumping && sprinting && lastGround;

                            if (appliedJump) {
                                double[] jumped = applySprintJump(motionX, motionZ, movementData.getLocation().getYaw());
                                motionX = jumped[0];
                                motionZ = jumped[1];
                            }

                            float strafe = keys[0];
                            float forward = keys[1];

                            if (sneaking) {
                                strafe *= 0.3F;
                                forward *= 0.3F;
                            }

                            if (blocking) {
                                strafe *= 0.2F;
                                forward *= 0.2F;
                            }

                            strafe *= 0.98F;
                            forward *= 0.98F;

                            float inputFriction = getInputFriction(movementData, sprinting);
                            double[] moved = moveFlying(motionX, motionZ, strafe, forward, inputFriction, movementData.getLocation().getYaw());
                            double desiredX = moved[0];
                            double desiredZ = moved[1];

                            for (CollisionMotion collision : collisionContext.clip(desiredX, desiredZ)) {
                                double offset = Math.hypot(
                                        movementData.getDeltaX() - collision.x,
                                        movementData.getDeltaZ() - collision.z
                                );

                                if (best == null || offset < best.offset) {
                                    best = new HorizontalInput(
                                            collision.x,
                                            collision.z,
                                            collision.collidedX ? 0.0D : desiredX,
                                            collision.collidedZ ? 0.0D : desiredZ,
                                            collision.collidedX,
                                            collision.collidedZ,
                                            offset,
                                            strafe,
                                            forward,
                                            inputFriction,
                                            appliedJump
                                    );

                                    if (offset <= 0.001D) {
                                        return best;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    private double[] moveFlying(double motionX, double motionZ, float strafe, float forward, float friction, float yaw) {
        float f = (strafe * strafe) + (forward * forward);

        if (f >= 1.0E-4F) {
            f = (float) Math.sqrt(f);

            if (f < 1.0F) {
                f = 1.0F;
            }

            f = friction / f;
            strafe *= f;
            forward *= f;

            float radians = yaw * (float) Math.PI / 180.0F;
            float sin = (float) Math.sin(radians);
            float cos = (float) Math.cos(radians);

            motionX += strafe * cos - forward * sin;
            motionZ += forward * cos + strafe * sin;
        }

        return new double[]{motionX, motionZ};
    }

    private double[] applySprintJump(double motionX, double motionZ, float yaw) {
        float radians = yaw * (float) Math.PI / 180.0F;

        motionX -= Math.sin(radians) * 0.2D;
        motionZ += Math.cos(radians) * 0.2D;

        return new double[]{motionX, motionZ};
    }

    private CollisionContext createCollisionContext(MovementData movementData,
                                                    double baseX,
                                                    double baseZ) {
        if (movementData == null || movementData.getLastLocation() == null) {
            return CollisionContext.uncertain();
        }

        CustomLocation location = movementData.getLastLocation();
        World world = location.getWorld();

        if (world == null
                || !Double.isFinite(location.getX())
                || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())
                || Math.abs(movementData.getDeltaY()) > 3.0D) {
            return CollisionContext.uncertain();
        }

        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(profile.getPlayer())) {
            return CollisionContext.uncertain();
        }

        double halfWidth = 0.3D;
        double height = getPlayerCollisionHeight();
        PlayerBox playerBox = new PlayerBox(
                location.getX() - halfWidth,
                location.getY(),
                location.getZ() - halfWidth,
                location.getX() + halfWidth,
                location.getY() + height,
                location.getZ() + halfWidth
        );

        /* Vanilla resolves vertical movement before the horizontal axes. */
        playerBox.offset(0.0D, movementData.getDeltaY(), 0.0D);

        double horizontalReach = Math.max(Math.abs(baseX), Math.abs(baseZ)) + 0.35D;
        int minX = floor(playerBox.minX - horizontalReach - 1.0E-4D);
        int maxX = floor(playerBox.maxX + horizontalReach + 1.0E-4D);
        int minY = floor(playerBox.minY - 1.0E-4D);
        int maxY = floor(playerBox.maxY + 1.0E-4D);
        int minZ = floor(playerBox.minZ - horizontalReach - 1.0E-4D);
        int maxZ = floor(playerBox.maxZ + horizontalReach + 1.0E-4D);
        List<PEMaterials.CollisionBounds> boxes = new ArrayList<>();

        try {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        return CollisionContext.uncertain();
                    }

                    for (int y = minY; y <= maxY; y++) {
                        Block block = world.getBlockAt(x, y, z);

                        for (PEMaterials.CollisionBounds bounds : PEMaterials.getCollisionBounds(block)) {
                            if (playerBox.strictlyIntersects(bounds)) {
                                return CollisionContext.uncertain();
                            }

                            boxes.add(bounds);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            return CollisionContext.uncertain();
        }

        return new CollisionContext(playerBox, boxes, false);
    }

    private double getPlayerCollisionHeight() {
        if (profile.getPlayer() == null) {
            return 1.8D;
        }

        try {
            if (profile.getPlayer().isGliding()) {
                return 0.6D;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object swimming = profile.getPlayer().getClass().getMethod("isSwimming").invoke(profile.getPlayer());

            if (swimming instanceof Boolean && (Boolean) swimming) {
                return 0.6D;
            }
        } catch (Throwable ignored) {
        }

        return isModernClient() && profile.getPlayer().isSneaking() ? 1.5D : 1.8D;
    }

    private int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private boolean isReversed(MovementData movementData, double motionX, double motionZ) {
        boolean reversedX = Math.abs(motionX) > 0.025D && movementData.getDeltaX() / motionX < -0.05D;
        boolean reversedZ = Math.abs(motionZ) > 0.025D && movementData.getDeltaZ() / motionZ < -0.05D;

        return reversedX || reversedZ;
    }

    private boolean isJumpStart(MovementData movementData) {
        if (movementData == null || !movementData.isLastOnGround() || movementData.isOnGround() || movementData.getDeltaY() <= 0.0D) {
            return false;
        }

        double expected = 0.42D;

        if (profile.getPotionData() != null && profile.getPotionData().isHasJump()) {
            expected += profile.getPotionData().getJumpAmplifier() * 0.1D;
        }

        return Math.abs(movementData.getDeltaY() - expected) <= 0.045D;
    }

    private boolean isDirectSevereEntityReduction(boolean entityResponse,
                                                   boolean explosionResponse,
                                                   MovementData movementData,
                                                   VelocityData velocityData,
                                                   boolean jumped,
                                                   boolean collisionClipped) {
        if (!entityResponse || explosionResponse || jumped || collisionClipped || movementData == null || velocityData == null) {
            return false;
        }

        Vector rawVelocity = velocityData.getVelocityfvc();
        if (rawVelocity == null) {
            return false;
        }

        double rawX = rawVelocity.getX();
        double rawZ = rawVelocity.getZ();
        double rawHorizontal = Math.hypot(rawX, rawZ);

        /*
         * Small knockback can be substantially offset by normal input. For a
         * normal combat impulse, however, almost no movement along its vector
         * is conclusive even if attack packets were sent in the same window.
         */
        if (rawHorizontal < 0.30D) {
            return false;
        }

        double receivedProjection = ((movementData.getDeltaX() * rawX)
                + (movementData.getDeltaZ() * rawZ)) / rawHorizontal;

        return receivedProjection <= rawHorizontal * 0.20D;
    }

    private float getInputFriction(MovementData movementData, boolean sprinting) {
        if (lastGround) {
            double slipperiness = getBlockSlipperiness(movementData);
            double factor = 0.16277136D / (slipperiness * slipperiness * slipperiness);

            return (float) (MathUtil.getAttributeSpeed(profile, sprinting) * factor);
        }

        return sprinting ? 0.025999999F : 0.02F;
    }

    private double getCarryFriction(MovementData movementData) {
        double slipperiness = getBlockSlipperiness(movementData);

        // Every supported Java client applies 0.91 after block slipperiness.
        // Keeping it out of getInputFriction is important: ground acceleration
        // uses the raw slipperiness value before this end-of-tick drag.
        return slipperiness * 0.91D;
    }

    private double getBlockSlipperiness(MovementData movementData) {
        double slipperiness = movementData.getFrictionFactor();

        if (Double.isFinite(slipperiness) && slipperiness > 0.0D) {
            return Math.min(1.0D, slipperiness);
        }

        return 0.6D;
    }

    private double getRequiredPercent() {
        return 99.0D;
    }

    private boolean isAttackSlowdownPossible() {
        return profile.getActionData() != null
                && (profile.getActionData().isSprinting()
                || profile.getActionData().isLastSprinting()
                || profile.getActionData().isLastLastSprinting()
                || profile.getActionData().getSinceLastSprintingTicks() <= 2);
    }

    private double getMaxBuffer() {
        return isModernClient() ? 4.5D : 3.5D;
    }

    private double getVelocityClamp() {
        return isModernClient() ? 0.003D : 0.005D;
    }

    private double getMoveOffset() {
        return profile.getVersion() != null && profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_18_2) ? 0.015D : 0.03D;
    }

    private double getPredictionTolerance(double horizontal) {
        if (activeExplosion) {
            return Math.max(0.0125D, horizontal * 0.04D);
        }

        /*
         * The exact outgoing packet remains authoritative, including velocity
         * modified by the server. Modern combat has a small proportional
         * first-response uncertainty; a fixed 99% absolute gate made normal
         * 1.9+ PvP responses around 96-97% accumulate violations.
         */
        if (isModernClient()) {
            return 0.002D + (horizontal * 0.0425D);
        }

        /*
         * 1.8 has its own first-response/input-order precision loss. Recorded
         * vanilla samples can sit around 97.4% with ~0.023 blocks of minimum
         * model error, so scale the uncertainty from the authoritative packet
         * instead of treating it as modified knockback.
         */
        return 0.0035D + (horizontal * 0.025D);
    }

    private double getMinimumReliableEntityHorizontal() {
        return isModernClient() ? 0.075D : 0.06D;
    }

    private int getWithheldTransactionGraceTicks() {
        int ping = Math.max(0, profile.getConnectionData().getTransPing());
        int oneWayPingTicks = (int) Math.ceil(ping / 100.0D);

        return Math.max(2, Math.min(5, 2 + oneWayPingTicks));
    }

    private double getBowBoostAllowance(int punchLevel) {
        return switch (punchLevel) {
            case 0 -> 0.055D;
            case 1 -> 0.205D;
            case 2 -> 0.255D;
            default -> 0.0D;
        };
    }

    private boolean isModernClient() {
        return profile.getVersion() != null && !profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
    }

    private boolean hasHorizontalVelocity() {
        return (predictedX * predictedX) + (predictedZ * predictedZ) > 0.0001D;
    }

    private void resetVelocityState() {
        predictedX = 0.0D;
        predictedZ = 0.0D;
        velocityTicks = 0;
        allowance = 0.001D;
        attackPackets = 0;
        fittedAttackReductions = 0;
        activeExplosion = false;
        activeCollisionLimited = false;
        activeBowPunchLevel = -1;
    }

    private void finishMovementTick(MovementData movementData) {
        lastGround = movementData != null && movementData.isOnGround();
        attackPackets = 0;
        fittedAttackReductions = 0;
    }

    private static final class CollisionContext {
        private static final double EPSILON = 1.0E-7D;

        private final PlayerBox startBox;
        private final List<PEMaterials.CollisionBounds> boxes;
        private final boolean uncertain;

        private CollisionContext(PlayerBox startBox,
                                 List<PEMaterials.CollisionBounds> boxes,
                                 boolean uncertain) {
            this.startBox = startBox;
            this.boxes = boxes;
            this.uncertain = uncertain;
        }

        private static CollisionContext uncertain() {
            return new CollisionContext(null, Collections.emptyList(), true);
        }

        private List<CollisionMotion> clip(double desiredX, double desiredZ) {
            if (boxes.isEmpty()) {
                return Collections.singletonList(new CollisionMotion(desiredX, desiredZ, false, false));
            }

            CollisionMotion xThenZ = clipInOrder(desiredX, desiredZ, true);
            CollisionMotion zThenX = clipInOrder(desiredX, desiredZ, false);

            if (Math.abs(xThenZ.x - zThenX.x) <= EPSILON
                    && Math.abs(xThenZ.z - zThenX.z) <= EPSILON) {
                return Collections.singletonList(xThenZ);
            }

            List<CollisionMotion> possibilities = new ArrayList<>(2);
            possibilities.add(xThenZ);
            possibilities.add(zThenX);
            return possibilities;
        }

        private CollisionMotion clipInOrder(double desiredX, double desiredZ, boolean xFirst) {
            PlayerBox box = startBox.copy();
            double clippedX = desiredX;
            double clippedZ = desiredZ;

            if (xFirst) {
                clippedX = clipX(box, clippedX);
                box.offset(clippedX, 0.0D, 0.0D);
                clippedZ = clipZ(box, clippedZ);
            } else {
                clippedZ = clipZ(box, clippedZ);
                box.offset(0.0D, 0.0D, clippedZ);
                clippedX = clipX(box, clippedX);
            }

            return new CollisionMotion(
                    clippedX,
                    clippedZ,
                    Math.abs(clippedX - desiredX) > EPSILON,
                    Math.abs(clippedZ - desiredZ) > EPSILON
            );
        }

        private double clipX(PlayerBox player, double desired) {
            double result = desired;

            for (PEMaterials.CollisionBounds block : boxes) {
                if (!overlaps(player.minY, player.maxY, block.minY, block.maxY)
                        || !overlaps(player.minZ, player.maxZ, block.minZ, block.maxZ)) {
                    continue;
                }

                if (result > 0.0D && player.maxX <= block.minX + EPSILON) {
                    result = Math.min(result, block.minX - player.maxX);
                } else if (result < 0.0D && player.minX >= block.maxX - EPSILON) {
                    result = Math.max(result, block.maxX - player.minX);
                }
            }

            return Math.abs(result) <= EPSILON ? 0.0D : result;
        }

        private double clipZ(PlayerBox player, double desired) {
            double result = desired;

            for (PEMaterials.CollisionBounds block : boxes) {
                if (!overlaps(player.minY, player.maxY, block.minY, block.maxY)
                        || !overlaps(player.minX, player.maxX, block.minX, block.maxX)) {
                    continue;
                }

                if (result > 0.0D && player.maxZ <= block.minZ + EPSILON) {
                    result = Math.min(result, block.minZ - player.maxZ);
                } else if (result < 0.0D && player.minZ >= block.maxZ - EPSILON) {
                    result = Math.max(result, block.maxZ - player.minZ);
                }
            }

            return Math.abs(result) <= EPSILON ? 0.0D : result;
        }

        private static boolean overlaps(double minA, double maxA, double minB, double maxB) {
            return maxA > minB + EPSILON && minA < maxB - EPSILON;
        }
    }

    private static final class CollisionMotion {
        private final double x;
        private final double z;
        private final boolean collidedX;
        private final boolean collidedZ;

        private CollisionMotion(double x, double z, boolean collidedX, boolean collidedZ) {
            this.x = x;
            this.z = z;
            this.collidedX = collidedX;
            this.collidedZ = collidedZ;
        }
    }

    private static final class PlayerBox {
        private double minX, minY, minZ, maxX, maxY, maxZ;

        private PlayerBox(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        private PlayerBox copy() {
            return new PlayerBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private void offset(double x, double y, double z) {
            minX += x;
            maxX += x;
            minY += y;
            maxY += y;
            minZ += z;
            maxZ += z;
        }

        private boolean strictlyIntersects(PEMaterials.CollisionBounds block) {
            double epsilon = 1.0E-5D;

            return maxX > block.minX + epsilon
                    && minX < block.maxX - epsilon
                    && maxY > block.minY + epsilon
                    && minY < block.maxY - epsilon
                    && maxZ > block.minZ + epsilon
                    && minZ < block.maxZ - epsilon;
        }
    }

    private static class HorizontalInput {
        double motionX;
        double motionZ;
        double carryX;
        double carryZ;
        boolean collidedX;
        boolean collidedZ;
        double offset;
        float strafe;
        float forward;
        float inputFriction;
        boolean jump;

        private HorizontalInput(double motionX,
                                double motionZ,
                                double carryX,
                                double carryZ,
                                boolean collidedX,
                                boolean collidedZ,
                                double offset,
                                float strafe,
                                float forward,
                                float inputFriction,
                                boolean jump) {
            this.motionX = motionX;
            this.motionZ = motionZ;
            this.carryX = carryX;
            this.carryZ = carryZ;
            this.collidedX = collidedX;
            this.collidedZ = collidedZ;
            this.offset = offset;
            this.strafe = strafe;
            this.forward = forward;
            this.inputFriction = inputFriction;
            this.jump = jump;
        }
    }


}
