package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.checks.impl.movement.speed.SpeedMath.MovementMath;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.ReflectionUtils;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.customutils.OtherUtility;
import me.arrow.utils.minecraft.MathHelper;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.util.Vector;

// this is a very decent check, it accounts for acceleration and deceleration, the acceleration part is based off of OpenKarhu's speed b
// it does have alot of improvements though, but it has some issues with modified attribute speed, remember the goal of the anticheat is to work on ALL minecraft versions.

@Experimental
public class SpeedB extends Check {
    public SpeedB(Profile profile) {
        super(profile, CheckType.SPEED, "B", "Checks for deceleration/acceleration");
    }

    float lastDeltaYaw;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {

        if (!OtherUtility.isFlying(event.getPacketType())) return;

        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();
        RotationData rotationData = profile.getRotationData();

        double deltaX = movementData.getDeltaX();
        double deltaZ = movementData.getDeltaZ();
        double deltaY = movementData.getDeltaY();
        double deltaXZ = movementData.getDeltaXZ();
        double lastDeltaXZ = movementData.getLastDeltaXZ();

        if (exempt("cancelled", profile.shouldCancel())) { resetMovement(deltaX, deltaZ); return; }
        if (exempt("dead", profile.getPlayer().isDead())) { resetMovement(deltaX, deltaZ); return; }
        if (exempt("notRespawned", !profile.isExempt().isRespawned())) { resetMovement(deltaX, deltaZ); return; }
        if (exempt("teleports", movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4))) { resetMovement(deltaX, deltaZ); return; }
        if (exempt("vehicle", profile.isExempt().vehicle())) { resetMovement(deltaX, deltaZ); return; }

        if (exempt("reelingIn", profile.getExempt().isReelingIn())) { lastMove = new Vector(deltaX, 0.0, deltaZ); return; }

        if (exempt("pistonUpdate", profile.getActionData().hasRecentPistonUpdate(5 + (profile.getConnectionData().getClientTickTrans() * 2)))) { lastMove = new Vector(deltaX, 0.0, deltaZ); return; }

        boolean serverGround = movementData.isServerGround();
        boolean clientGround = movementData.isOnGround();
        float movingTicks = movementData.getMovingTicks();
        boolean sprinting = actionData.isSprinting();

        double velocityH = profile.getVelocityData().getTotalHorizontalVelocity();
        float deltaYaw = rotationData.getDeltaYaw();
        double mdAccel = movementData.getAccelXZ();
        double accel = Math.abs(deltaXZ - lastDeltaXZ);


        runPrediction(movementData, actionData);

        int ghostLiquidWebTicks = Math.min(
                profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
        );

        if (exempt("ghostLiquidWeb", ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 4))) {
            lastMove = new Vector(deltaX, 0.0, deltaZ);
            return;
        }

        calculateDeceleration(movementData, deltaXZ, lastDeltaXZ, deltaYaw, accel, mdAccel);

        calculateAcceleration(movementData, actionData, deltaX, deltaY, deltaZ, deltaXZ, clientGround, serverGround, movingTicks, velocityH, sprinting);

        this.lastDeltaYaw = deltaYaw;
    }

    private double bucketVl, sprintVl;

    String leniencyReason;

    public void runPrediction(MovementData movementData, ActionData actionData) {

        MovementMath sim = profile.getSimulation();
        // Match Karhu's NMS value parser: this threshold includes the current
        // sprint state exactly once, while MovementMath itself brute-forces
        // sprint from a speed value with that modifier removed.
        double attributeSpeed = ReflectionUtils.getPlayerMovementSpeedWithoutSprint(profile.getPlayer());
        if (actionData.isSprinting()) {
            attributeSpeed *= 1.0D + 0.3F;
        }
        if (!Double.isFinite(attributeSpeed) || attributeSpeed <= 0.0D) {
            attributeSpeed = 0.1D;
        }

        // Speed C's entry conditions, translated only to Arrow's shared data.
        if (exempt("belowAttributeSpeed", movementData.getDeltaXZ() < attributeSpeed)) { resetPredictionBuffers(); return; }
        if (exempt("lowLastDeltaXZ", movementData.getLastDeltaXZ() < offsetMove() + 0.01)) { resetPredictionBuffers(); return; }
        if (exempt("nearClimbable", movementData.isNearClimbable())) { resetPredictionBuffers(); return; }
        if (exempt("cancelled", profile.shouldCancel())) { resetPredictionBuffers(); return; }
        if (exempt("velocity", profile.getVelocityData().isTakingVelocity())) { resetPredictionBuffers(); return; }
        if (exempt("recentPiston", movementData.getSinceNearPistonTicks() <= 3)) { resetPredictionBuffers(); return; }
        if (exempt("recentFlight", profile.getLastFlightToggleTimer().hasNotPassed(30))) { resetPredictionBuffers(); return; }
        if (exempt("recentGliding", movementData.getSinceGlidingTicks() <= 15)) { resetPredictionBuffers(); return; }
        if (exempt("recentRiptiding", movementData.getSinceRiptidingTicks() < 15)) { resetPredictionBuffers(); return; }
        if (exempt("nearBed", movementData.isNearBed())) { resetPredictionBuffers(); return; }
        if (exempt("recentCollision", movementData.getSinceCollideTicks() <= 2)) { resetPredictionBuffers(); return; }
        if (exempt("recentGhostBlock", movementData.getSinceOnGhostBlock() < 2)) { resetPredictionBuffers(); return; }

        leniencyReason = "default";

        final double leniency = getLeniency(0.003D, movementData, actionData);
        double predicted = sim.getOutputXZ();

        double diff = movementData.getDeltaXZ() - predicted;

        verbose(this.getClass().getSimpleName(), getBuffer(), 2, "Verbose" + "\npredicted " + predicted
                + "\nlowest " + sim.getLowestMatch()
                + "\nattribute " + sim.getAttributeSpeed()
                + "\ndiff " + diff
                + "\nstrafe " + sim.getMoveStrafe()
                + "\nforward " + sim.getMoveForward()
                + "\nonGround " + movementData.isOnGround()
                + "\nlastOnGround " + movementData.isLastOnGround()
                + "\nlastLastOnGround " + movementData.isLastLastOnGround()
                + "\nclientAirTicks " + movementData.getClientAirTicks()
                + "\njumped " + sim.isJumped()
                + "\nleniency " + leniency
                + "\nleniencyReason " + leniencyReason
                + "\nsprinting " + sim.isSprinting()
                + "\ndeltaXZ " + movementData.getDeltaXZ()
                + "\nvel " + profile.getVelocityData().getTotalVelocity()
                + "\nuseItem " + sim.isUseItem()
                + "\nmoveTicks " + movementData.getMovingTicks()
                + "\nscenarios " + sim.getScenarioAmount());

//        if (sprintVl < 3) {
//            return;
//        }

        if (sim.getLowestMatch() > leniency && Config.Setting.SIMULATION_MODE.getBoolean()) {
            if (increaseBuffer() > 6) {
                fail("Prediction", "Failed Prediction", "predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + predicted
                        + "\nlowest " + MsgType.MAIN_THEME_COLOR.getMessage() + sim.getLowestMatch()
                        + "\nattribute " + MsgType.MAIN_THEME_COLOR.getMessage() + sim.getAttributeSpeed()
                        + "\ndiff " + MsgType.MAIN_THEME_COLOR.getMessage() + diff
                        + "\nleniency " + MsgType.MAIN_THEME_COLOR.getMessage() + leniency
                        + "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sim.isSprinting()
                        + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ()
                        + "\nvel " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalVelocity()
                        + "\nuseItem " + MsgType.MAIN_THEME_COLOR.getMessage() + sim.isUseItem()
                        + "\nmoveTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getMovingTicks()
                        + "\nscenarios " + MsgType.MAIN_THEME_COLOR.getMessage() + sim.getScenarioAmount());

                setBuffer(6);
            }
        } else {
            decreaseBufferBy(0.075D);
        }

        bucketVl = Math.max(0.0D, bucketVl - 0.2D);
        sprintVl = Math.max(0.0D, sprintVl - 0.3D);
    }


    private double getLeniency(double threshold, MovementData movementData, ActionData actionData) {
        double leniency = threshold;

        boolean entity = movementData.getSinceCollideTicks() <= 2;
        boolean waterPush = movementData.getSinceNearWaterTicks() <= 4;
        int lastInLiquid = movementData.getSinceInsideWaterTicks();
        boolean modernMovement = profile.getVersion().isNewerThan(ClientVersion.V_1_12_2);
        boolean onWater = movementData.isInsideWater() || movementData.isOnTopOfWater();

        if (movementData.getMovingTicks() <= 3) {
            leniency += (offsetMove() + clamp()) * 2;
            leniencyReason += ", lowMovingTicks";
        }

        if (movementData.isUnderblock()) {
            leniency += 0.04;
            leniencyReason += ", underblock";
        }

        if (!movementData.isOnGround() && movementData.isLastOnGround() && movementData.getClientAirTicks() == 1) {
            leniency += 0.04;
            leniencyReason += ", justJumped";
        }

        if (movementData.isNearWall()) {
            leniency += 0.04;
            leniencyReason += ", nearWall";
        }

        if (profile.getSimulation().getEdgeSneakTick() > 0
                && profile.getTick() - profile.getSimulation().getEdgeSneakTick() <= 3) {
            leniency += 0.15D;
            leniencyReason += ", edgeSneak";
        }
        if (movementData.getSinceSoulTicks() <= 3) {
            leniency += 0.05;
            leniencyReason += ", soulTicks";
        }
        if (movementData.getSinceSlimeTicks() <= 3) {
            leniency += 0.05;
            leniencyReason += ", slimeTicks";
        }

        if (movementData.getNearbyBlocksResult() != null
                && movementData.getNearbyBlocksResult().getBlockTypes().stream().anyMatch(material -> MaterialType.isMaterial(material.name(), MaterialType.BERRIES))) {
            leniency += 0.05;
            leniencyReason += ", berries";
        }
        if (movementData.isOnHoney() || (movementData.getSinceHoneyTicks() <= 3)) {
            leniency += 0.05;
            leniencyReason += ", honey/Ticks";
        }

        if (waterPush) {
            leniency += 0.02D;
            leniencyReason += ", waterPush";
        }

        if (actionData.getLastBlockPlaceAttemptTicks() <= profile.getConnectionData().getClientTickTrans() + 3) {
            leniency += 0.12D;
            leniencyReason += ", bucketPlace";
            ++bucketVl;
        }

        if (modernMovement && !movementData.isWasInWater() && movementData.isWasWasInWater() && !waterPush) {
            leniency += 0.05D;
            leniencyReason += ", leavingWater2";
        }
        if (modernMovement && !onWater && movementData.isWasInWater() && !waterPush) {
            leniency += 0.005D;
            leniencyReason += ", leavingWater";
        }
        if (modernMovement && movementData.getSinceInsideWaterTicks() <= 1) {
            leniency += 0.02D;
            leniencyReason += ", recentWater";
        }
        if (!movementData.isWasInWater()
                && lastInLiquid > 2
                && lastInLiquid <= 5 + Math.min(15, profile.getConnectionData().getClientTickTrans())) {
            leniency += 0.05D;
            leniencyReason += ", postWater";
        }

        if (movementData.getLastFrictionFactorUpdateTicks() <= profile.getConnectionData().getClientTickTrans() + 1) {
            leniency += 0.2D;
            leniencyReason += ", frictionUncertain";
        }

        if (profile.getActionData().getSinceLastSprintingTicks() > 0 && profile.getActionData().getSinceLastSprintingTicks() < 8) {
            ++sprintVl;
        }

        int ghostLiquidWebTicks = Math.min(
                profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
        );

        if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 4)) {
            leniency += 0.1;
            leniencyReason += ", ghostLiquid/Bucket";
        }

        if (movementData.getServerGroundTicks() < 5) {
            leniency += 0.05;
        }

        if (movementData.isNearWebs()) {
            leniency += 0.25;
            leniencyReason += ", webs";
        }
        if (movementData.getSincePowderSnowTicks() <= 3) {
            leniency += 0.25; 
            leniencyReason += ", powderSnowTicks";
        }
        if (entity) {
            leniency += 0.055D;
            leniencyReason += ", entityPush";
        }

        return leniency;
    }

    public double offsetMove() {
        return profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_18_2) ? 0.0002 : 0.03;
    }

    public double clamp() {
        return profile.getVersion().getProtocolVersion() > 47 ? 0.003D : 0.005D;
    }

    private static final float[][] KEY_COMBOS = {
            {1.0F, -1.0F},
            {1.0F,  0.0F},
            {1.0F,  1.0F},
            {0.0F, -1.0F},
            {0.0F,  0.0F},
            {0.0F,  1.0F},
            {-1.0F, -1.0F},
            {-1.0F,  0.0F},
            {-1.0F,  1.0F}
    };

    private static final boolean[] BOOLEANS_REVERSED = new boolean[]{false, true};

    private Vector lastMove = new Vector(0.0, 0.0, 0.0);
    private double shitZeroPointThree;
    private double holdVelocity;
    private double vlBuffer;

    private void decrease(double amount) {
        this.vlBuffer = Math.max(0.0D, this.vlBuffer - amount);
    }

    public void calculateAcceleration(MovementData movementData,
                                      ActionData actionData,
                                      double deltaX,
                                      double deltaY,
                                      double deltaZ,
                                      double deltaXZ,
                                      boolean clientGround,
                                      boolean serverGround,
                                      float movingTicks,
                                      double velocityH,
                                      boolean sprinting) {

        long profiler = Profiler.start();

        try {

            boolean velocity = profile.getVelocityData().isTakingVelocity() || profile.getVelocityData().getVelocityTicks() <= 1;
            String invalidReason = getInvalidReason(velocity);
            boolean valid = invalidReason == null;
            exempt(invalidReason, !valid);

            boolean recentAttack = profile.getCombatData().getAttackedTicks() <= 3
                    && profile.getCombatData().getTarget() != -696969;
            int placementSyncTicks = 4 + profile.getConnectionData().getClientTickTrans();
            boolean recentUnderPlace = actionData.getLastBlockPlaceAttemptTicks() <= placementSyncTicks
                    || actionData.hasRecentConfirmedUnderPlace(placementSyncTicks + 4);

            // Attacking changes the client's sprint/momentum state.  It is not
            // acceleration evidence, and a buffer collected before the attack
            // must not be allowed to turn this state transition into a flag.
            // A confirmed support block placed beneath the player also changes
            // client friction before the server/cache snapshot can be trusted.
            if (recentAttack || recentUnderPlace) {
                resetMovement(deltaX, deltaZ);
                return;
            }

            float movementSpeed = (float) ReflectionUtils.getPlayerMovementSpeed(profile.getPlayer());
            if (!Float.isFinite(movementSpeed) || movementSpeed <= 0.0F) {
                movementSpeed = 0.1F;
            }
            float movementSpeedSP = movementSpeed + movementSpeed * 0.3F;

            float friction = 0.91F;
            float force = 0.02F;
            float forceSprint = 0.026F;

            Vector move = new Vector(deltaX, 0.0, deltaZ);
            // Keep the same frame selection as Karhu: fromFrom chooses the
            // previous block carry multiplier; from chooses ground input force.
            float lastTickFriction = movementData.getLastFrictionFactor() * 0.91F;
            float carryFriction = movementData.isLastLastOnGround() ? lastTickFriction : 0.91F;
            Vector compLastMove = this.lastMove.clone().multiply(carryFriction);

            Vector plainComp = compLastMove.clone();

            float yaw = profile.getRotationData().getYaw();

            boolean jumpAdded = movementData.isLastOnGround() && !clientGround && deltaY >= 0.0;
            if (jumpAdded) {
                float yawRad = yaw * (float) (Math.PI / 180.0);
                compLastMove.add(new Vector((double) -sin(yawRad) * 0.2, 0.0, (double) cos(yawRad) * 0.2));
            }

            if (movementData.isLastOnGround()) {
                // Karhu's getCurrentFriction(): raw block slipperiness, not
                // the 0.91 velocity carry multiplier above.
                float rawFriction = movementData.getFrictionFactor();
                friction = rawFriction;
                force = movementSpeed * 0.16277136F / (rawFriction * rawFriction * rawFriction);
                forceSprint = movementSpeedSP * 0.16277136F / (rawFriction * rawFriction * rawFriction);
            }

            boolean serverSprinting = profile.getPlayer().isSprinting();
            boolean sprintStateMismatch = sprinting
                    && !serverSprinting
                    && Math.abs(movementSpeed - 0.1F) <= 0.001F;
            float clientSprintForce = sprintStateMismatch ? forceSprint * 1.3F : forceSprint;

            double threshold = movingTicks <= 3.0F ? 0.0325 : 0.0105;

            if (deltaXZ < 0.25D && movingTicks <= 2.0F && movementData.isLastOnGround()) {
                threshold += 0.2D;
            }

            if (movementData.getSinceCollideTicks() <= 10) {
                threshold += 0.1D;
            }

            if (movementData.getMovingUnderblockTicks() > 0) {
                threshold += 0.05D;
            }

            if (movementData.isNearWebs()) {
                threshold += 0.1D;
            }

            if (movementData.isNearWall()) {
                threshold += 0.08D;
            }

            if (movementData.getServerGroundTicks() < 5) {
                threshold += 0.05D;
            }

            if (movementData.getMovingOnHoneyTicks() > 0) {
                threshold += 0.15D;
            }

            if (movementData.getSinceTeleportTicks() <= 2 + (profile.getConnectionData().getClientTickTrans() * 4)) {
                threshold += 0.3D;
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 4)) {
                threshold += 0.2D;
            }

            if (movementData.isNearWall()) {
                threshold += 0.06D;
            }

            if (movementData.getLastNearEdgeTicks() <= 3 && actionData.getSinceSneakingTicks() <= 3) {
                if (velocity) {
                    threshold += profile.getVelocityData().getTotalHorizontalVelocity() + 0.5D;
                } else {
                    threshold += 0.5D;
                }
            }

            if (profile.getVelocityData().getVelocityTicks() <= 3 && !movementData.isLastOnGround()) {
                threshold += this.holdVelocity * 2.5 + 0.6D;
            }

            double tMult = 1.0001;

            if (movementData.getSincePredictUpwardsTicks() < 10
                    || movementData.getSincePredictDownwardsTicks() < 10
                    || movementData.getSincePredictUpwardsTicksWithoutMaterial() < 15
                    || movementData.getSincePredictDownwardsTicksWithoutMaterial() < 15) {
                threshold += 0.35D;
            }

            Vector subtracted = move.clone().subtract(compLastMove);
            double bestNormal = Math.min(this.getBest(subtracted, false, forceSprint, true, yaw), this.getBest(subtracted, false, force, false, yaw));
            double bestBlocking = Math.min(this.getBest(subtracted, true, forceSprint, true, yaw), this.getBest(subtracted, true, force, false, yaw));

            if (sprintStateMismatch) {
                bestNormal = Math.min(bestNormal, this.getBest(subtracted, false, clientSprintForce, true, yaw));
                bestBlocking = Math.min(bestBlocking, this.getBest(subtracted, true, clientSprintForce, true, yaw));
            }

            Vector subtractedPlain = move.clone().subtract(plainComp);
            double bestNormal2 = Math.min(this.getBest(subtractedPlain, false, forceSprint, true, yaw), this.getBest(subtractedPlain, false, force, false, yaw));
            double bestBlocking2 = Math.min(this.getBest(subtractedPlain, true, forceSprint, true, yaw), this.getBest(subtractedPlain, true, force, false, yaw));

            if (sprintStateMismatch) {
                bestNormal2 = Math.min(bestNormal2, this.getBest(subtractedPlain, false, clientSprintForce, true, yaw));
                bestBlocking2 = Math.min(bestBlocking2, this.getBest(subtractedPlain, true, clientSprintForce, true, yaw));
            }

            boolean pass1Exceeded = bestNormal > threshold * tMult && bestBlocking > threshold * tMult;
            boolean pass2Exceeded = pass1Exceeded && (bestNormal2 > threshold * tMult && bestBlocking2 > threshold * tMult);

            double closest = Math.min(Math.min(bestNormal, bestNormal2), Math.min(bestBlocking, bestBlocking2));
            // Keep the source bounds in the correct order.  The previous
            // min/max order forced every bad frame to add exactly 4.0.
            double bufferAddition = Math.min(7.5D, Math.max(1.0D, closest * 50.0D));
            // Karhu: 50 when near-sneak with small bestNormal, 35 otherwise
            int required = bestNormal < 0.06 && actionData.getSinceSneakingTicks() <= 3 ? 50 : 35;

            MovementPredictionUtil.DirectionalMovement strafeDir =
                    MovementPredictionUtil.predictDirectionalMovement(deltaX, deltaZ, yaw);

            MovementPredictionUtil.DirectionalMovement inputDir =
                    MovementPredictionUtil.predictDirectionalMovement(subtracted.getX(), subtracted.getZ(), yaw);

            if (valid && deltaXZ > 0.2D) {
                if (pass1Exceeded) {
                    if (pass2Exceeded) {
                        if (movingTicks <= 1.0F) {
                            if (++this.shitZeroPointThree > 3.0) {
                                this.decrease(0.005);
                            }
                            // Karhu updates lastMove on the first move tick,
                            // but still evaluates this frame below.
                            this.lastMove = new Vector(deltaX, 0.0, deltaZ);
                        } else {
                            this.shitZeroPointThree = Math.min(0.0, this.shitZeroPointThree - 0.1);
                        }

                        if ((this.vlBuffer += bufferAddition) >= (double) required) {
                            fail("Invalid acceleration", "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nprediction " + MsgType.MAIN_THEME_COLOR.getMessage() + closest
                                    + "\nfriction " + MsgType.MAIN_THEME_COLOR.getMessage() + friction
                                    + "\ncarryFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + carryFriction
                                    + "\ncurrentBlockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getFrictionFactor()
                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                    + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                    + "\nvelocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityH);

                            // Do not re-arm an already flagged local buffer at
                            // required - 5; one more imperfect combat frame
                            // would then immediately flag again.
                            this.vlBuffer = 20.0D;
                        }
                    } else {
                        // pass1 true, pass2 false → small decrease (Karhu)
                        this.decrease(0.1);
                    }
                } else {
                    // A matching prediction must clear stale evidence.  This
                    // is also how Karhu handles a failed first comparison.
                    this.decrease(0.1);
                }
            } else if (valid) {
                this.decrease(0.01);
            } else {
                this.decrease(0.005);
            }

            verbose(this.getClass().getSimpleName(), this.vlBuffer, required, "Verbose (Accel)"
                    + "\ndeltaXZ " + deltaXZ
                    + "\ndeltaY " + deltaY
                    + "\ndeltaX " + deltaX
                    + "\ndeltaZ " + deltaZ
                    + "\nyaw " + yaw
                    + "\nsector " + strafeDir.getSector()
                    + "\nangle " + strafeDir.getSignedAngle()
                    + "\nabsAngle " + strafeDir.getAbsoluteAngle()
                    + "\ndot " + strafeDir.getDot()
                    + "\ncross " + strafeDir.getCross()
                    + "\nforwardStrafe " + strafeDir.isForwardStrafe()
                    + "\ninputSector " + inputDir.getSector()
                    + "\ninputAngle " + inputDir.getSignedAngle()
                    + "\ninputDot " + inputDir.getDot()
                    + "\npredictedN1 " + bestNormal
                    + "\npredictedB1 " + bestBlocking
                    + "\npredictedN2 " + bestNormal2
                    + "\npredictedB2 " + bestBlocking2
                    + "\nclosest " + closest
                    + "\naddition " + bufferAddition
                    + "\nthreshold " + threshold
                    + "\nbuffer " + this.vlBuffer
                    + "\nrequired " + required
                    + "\nmoveSpeed " + movementSpeed
                    + "\nforce " + force
                    + "\nclientSprintForce " + clientSprintForce
                    + "\nfriction " + friction
                    + "\nclientGround " + clientGround
                    + "\nlastOnGround " + movementData.isLastOnGround()
                    + "\nlastLastOnGround " + movementData.isLastLastOnGround()
                    + "\nserverGround " + serverGround
                    + "\nsprinting " + sprinting
                    + "\nserverSprinting " + serverSprinting
                    + "\nsprintStateMismatch " + sprintStateMismatch
                    + "\njumpAdded " + jumpAdded
                    + "\nvalid " + valid
                    + "\ninvalid " + (invalidReason != null ? invalidReason : "none")
                    + "\nvelocity " + velocity
                    + "\nvelH " + velocityH
                    + "\nmoveTicks " + movingTicks
                    + "\nshit03 " + this.shitZeroPointThree);

            this.lastMove = new Vector(deltaX, 0.0, deltaZ);

            if (velocity) {
                this.holdVelocity = profile.getVelocityData().getTotalHorizontalVelocity();
            }
        } finally {
            Profiler.stop("Speed B (Accel)", profiler);
        }
    }


    public void calculateDeceleration(MovementData movementData, double deltaXZ, double lastDeltaXZ, double deltaYaw, double accel, double mdAccel) {

        long profiler = Profiler.start();

        try {
            boolean exempt = movementData.isNearWater()
                    || movementData.isNearLava()
                    || movementData.isNearWebs()
                    || movementData.isNearClimbable()
                    || profile.isBouncingOnSlime();

            double squaredAccel = accel * 100;

            if (deltaYaw > 1.5f
                    && deltaYaw != lastDeltaYaw
                    && deltaXZ > 0.15D
                    && squaredAccel < 1.0E-5
                    && !exempt) {
                if (increaseBuffer() > 1) {
                    String verboseTitle = "Invalid deceleration";
                    String verbose = "deltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYaw
                            + "\nlastDeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaYaw
                            + "\naccel " + MsgType.MAIN_THEME_COLOR.getMessage() + accel
                            + "\nmdAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + mdAccel
                            + "\nmdAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + mdAccel
                            + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                            + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaXZ;

                    fail(verboseTitle, verbose);
                }
            } else {
                decreaseBufferBy(0.025);
            }
        } finally {
            Profiler.stop("Speed B (Decel)", profiler);
        }
    }


    private String getInvalidReason(boolean velocity) {
        MovementData movementData = profile.getMovementData();

        if (velocity) return "velocity";
        if (profile.getPlayer().getGameMode() == org.bukkit.GameMode.SPECTATOR) return "spectator";
        if (profile.getPlayer().isFlying()) return "flying";
        if (!profile.isExempt().isRespawned()) return "notRespawned";
        if (profile.isExempt().vehicle() || movementData.isNearBoat() || movementData.isOnBoat()) return "vehicle";
        if (movementData.getSinceTeleportTicks() <= 2 + (profile.getConnectionData().getClientTickTrans() * 4)) return "teleport";
        if (movementData.getSinceOnGhostBlock() <= 2) return "ghostBlock";
        if (movementData.getSinceGlidingTicks() < 30) return "gliding";
        if (movementData.getSinceRiptidingTicks() < 30) return "riptiding";
        if (movementData.isInsideLiquid() || movementData.getSinceInsideWaterTicks() <= 2) return "liquid";
        if (movementData.isNearBed()) return "bed";
        if (movementData.getSinceSlimeTicks() <= 3) return "slime";
        if (movementData.getSinceSoulTicks() <= 3) return "soulSand";
        if (movementData.isNearClimbable()) return "climbable";
        if (movementData.getSinceNearPistonTicks() <= 3) return "piston";
        return null;
    }

    private boolean checkValid(boolean velocity) {
        String reason = getInvalidReason(velocity);
        exempt(reason, reason != null);
        return reason == null;
    }

    private void resetMovement(double deltaX, double deltaZ) {
        vlBuffer = 0.0D;
        lastMove = new Vector(deltaX, 0.0D, deltaZ);
    }

    private void resetPredictionBuffers() {
        decreaseBufferBy(0.005D);
        bucketVl = Math.max(0.0D, bucketVl - 0.2D);
        sprintVl = Math.max(0.0D, sprintVl - 0.3D);
    }

    private double getBest(Vector move, boolean blocking, float friction, boolean sprint, float yaw) {
        double lowestMatch = Double.MAX_VALUE;

        for (float[] floats : KEY_COMBOS) {
            for (boolean sneaking : BOOLEANS_REVERSED) {
                float strafe = floats[0];
                float forward = floats[1];
                Vector moveFlying = this.moveFlying(strafe, forward, blocking, sneaking, friction, yaw);
                double diffX = Math.abs(move.getX() - moveFlying.getX());
                double diffZ = Math.abs(move.getZ() - moveFlying.getZ());
                double[] diffXZ = new double[]{diffX, diffZ};
                lowestMatch = Math.min(lowestMatch, hypot(diffXZ));
            }
        }

        return lowestMatch;
    }

    private double getBest(Vector move, boolean blocking, float friction, boolean sprint) {
        return getBest(move, blocking, friction, sprint, profile.getRotationData().getYaw());
    }

    public Vector moveFlying(float strafe, float forward, boolean blocking, boolean sneaking, float friction, float yaw) {
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
        float f = strafe * strafe + forward * forward;
        if (f >= 1.0E-4F) {
            f = sqrt_float(f);
            if (f < 1.0F) {
                f = 1.0F;
            }

            f = friction / f;
            strafe *= f;
            forward *= f;
            float yawRad = yaw * (float) Math.PI / 180.0F;
            float f1 = sin(yawRad);
            float f2 = cos(yawRad);
            float xAdd = strafe * f2 - forward * f1;
            float zAdd = forward * f2 + strafe * f1;
            return new Vector(xAdd, 0.0F, zAdd);
        } else {
            return new Vector(0, 0, 0);
        }
    }

    public Vector moveFlying(float strafe, float forward, boolean blocking, boolean sneaking, float friction) {
        return moveFlying(strafe, forward, blocking, sneaking, friction, profile.getRotationData().getYaw());
    }


    public static float sin(float value) {
        return MathHelper.sin(value);
    }

    public static float cos(float value) {
        return MathHelper.cos(value);
    }

    public static float sin(boolean fastMath, float value) {
        return MathHelper.sin(value, fastMath);
    }

    public static float cos(boolean fastMath, float value) {
        return MathHelper.cos(value, fastMath);
    }


    public static double hypot(double... value) {
        double total = 0.0;

        for (double val : value) {
            total += val * val;
        }

        return FastMath.sqrt(total);
    }

    public static float sqrt_float(float value) {
        return MathHelper.sqrt_float(value);
    }
}

