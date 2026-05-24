package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

// these are my gravity checks, there's 4 of them, enjoy losing ur mind in here

public class FlyA extends Check {

    public FlyA(Profile profile) {
        super(profile, CheckType.FLY, "A", "Checks if player follows gravity");
    }

    private double bufferA, bufferB, bufferC;

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
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();

            if (movementData == null) {
                return;
            }

            if (movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isNearShulker()
                    || movementData.isNearShulkerBox()
                    || movementData.isNearLava()
                    || movementData.isNearWater()
                    || profile.getExempt().isVehicle()
                    || profile.shouldCancel()
                    || movementData.getSinceLevitationEffectTicks() < 10) {
                return;
            }

            ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

            if (world.shouldExemptMovementChecks()
                    || world.nextToGhostWall
                    || world.physicsMismatch
                    || world.onGhostBlock
                    || world.insideGhostBlock
                    || world.underGhostBlock) {
                return;
            }

            if (movementData.getSinceTeleportTicks() < 5) {
                return;
            }

            if (profile.getPlayer() != null && profile.getPlayer().getLastDamageCause() != null) {
                EntityDamageEvent.DamageCause cause = profile.getPlayer().getLastDamageCause().getCause();

                if (IGNORED_CAUSES.contains(cause)) {
                    Bukkit.getScheduler().runTaskLater(
                            Arrow.getInstance().getHost(),
                            () -> {
                                if (profile.getPlayer() != null) {
                                    profile.getPlayer().setLastDamageCause(null);
                                }
                            },
                            6L + (profile.getConnectionData().getClientTickTrans() * 2L)
                    );

                    lastOffset = 0.0D;
                    resetPlacedBlockGravityState();
                    return;
                }
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("Fly A: is Exempting (ghostblock liquid/web)");
                }

                bufferA = 0.0D;
                bufferB = 0.0D;
                bufferC = 0.0D;
                resetPlacedBlockGravityState();
                return;
            }

            if (profile.getBlockProcessor().isNearGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("Fly A: is Exempting (near Ghostblock)");
                }

                bufferA = 0.0D;
                bufferB = 0.0D;
                bufferC = 0.0D;
//                resetPlacedBlockGravityState();
                return;
            }

            if (profile.getBlockProcessor().isUnderGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("Fly A: is Exempting (under Ghostblock)");
                }

                bufferA = 0.0D;
                bufferB = 0.0D;
                bufferC = 0.0D;
//                resetPlacedBlockGravityState();
                return;
            }

            double deltaY = movementData.getDeltaY();
            double lastDeltaY = movementData.getLastDeltaY();
            boolean clientGround = movementData.isOnGround();

            GravityPredictionA(movementData, deltaY, clientGround);

            if (!profile.isBedrockPlayer()) {
                GravityPredictionB(movementData, deltaY);
            }

            if (!profile.isBedrockPlayer()) {
                GravityPredictionC(deltaY, lastDeltaY, movementData.isUnderblock(), movementData);
            }

            GravityPredictionD(movementData);
        }
    }

    public void GravityPredictionA(MovementData movementData, double deltaY, boolean onGround) {
        if (profile.shouldCancel()) { debugExempt("shouldCancel"); return; }
        if (profile.isExempt().isTeleports()) { debugExempt("teleport"); return; }
        if (!profile.isExempt().isRespawned()) { debugExempt("notRespawned"); return; }
        if (movementData.getSinceRiptidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()) { debugExempt("riptiding"); return; }
        if (profile.getVehicleData().getSinceVehicleTicks() < 10) { debugExempt("vehicleTicks"); return; }
        if (profile.getPlayer().isInsideVehicle()) { debugExempt("insideVehicle"); return; }

        int pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);

        if (profile.getLastBlockBreakTimer().hasNotPassed(10 + pingTicks)) {
            debugExempt("recentBlockBreak");
            return;
        }


        if (profile.isBouncingOnSlime()) { debugExempt("slimeBounce"); return; }
        if (movementData.isOnTopOfWater()) { debugExempt("onTopOfWater"); return; }
        if (movementData.isInsideWater()) { debugExempt("insideWater"); return; }
        if (movementData.isInsideLiquid()) { debugExempt("insideLiquid"); return; }
        if (movementData.isNearLava()) { debugExempt("nearLava"); return; }
        if (movementData.isNearWater()) { debugExempt("nearWater"); return; }
        if (movementData.isNearBuggyBlock()) { debugExempt("nearBuggyBlock"); return; }
        if (profile.getVelocityData().isTakingVelocity()) { debugExempt("isTakingVelocity"); return; }
        if (movementData.isNearWebs()) { debugExempt("nearWebs"); return; }
        if (movementData.isUnderblock()) { debugExempt("underblock"); return; }
        if (movementData.isNearBed()) { debugExempt("nearBed"); return; }
        if (movementData.isNearHoney()) { debugExempt("nearHoney"); return; }
        if (movementData.isNearDripLeaf()) { debugExempt("nearDripLeaf"); return; }
        if (profile.getLastBlockBreakTimer().hasNotPassed(12 + (profile.getConnectionData().getClientTickTrans() * 2))) { debugExempt("breakingBlock"); return; }
        if (movementData.isNearShulkerBox()) { debugExempt("nearShulkerBox"); return; }
        if (movementData.isNearShulker()) { debugExempt("nearShulker"); return; }
        if (movementData.getSinceOnGhostBlock() < 10 + profile.getConnectionData().getClientTickTrans()) { debugExempt("sinceGhostblock"); return; }
        if (movementData.getSinceMovingOnSlimeTicks() < 10) { debugExempt("movingOnSlime"); return; }
        if (movementData.isNearClimbable()) { debugExempt("nearClimbable"); return; }
        if (profile.getExempt().isReelingIn()) { debugExempt("reelingIn"); return; }
//        if (movementData.getSinceElytraEquipTicks() < 10) { debugExempt("Elytra Equip"); return; }

        if (movementData.getSincePowderSnowTicks() < 10) {
            debugExempt("Powder Snow");
            return;
        }

        if (movementData.isMovingUp()
                || movementData.isMovingDown()
                || movementData.getSincePredictUpwardsTicks() < 10
                || movementData.getSincePredictDownwardsTicks() < 10) {
            bufferA -= Math.min(bufferA, 0.75D);
            return;
        }

        double normalPrediction = getPrediction(profile, deltaY);
        PredictionResult predictionResult = selectGravityPrediction(movementData, normalPrediction, true);
        double prediction = predictionResult.prediction;

        double totalUp = Math.abs(deltaY - prediction);
        double max = computeAllowedDelta(profile, deltaY);

        double motion = MoveUtils.getJumpMotion(profile);
        if (deltaY == motion && movementData.getClientAirTicks() == 1) return;

        if (deltaY != 0.0D) {
            verbose(this.getClass().getSimpleName(), bufferA, 4,
                    MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (1)"
                            + "\n * motion " + MsgType.MAIN_THEME_COLOR.getMessage() + totalUp
                            + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * prediction " + MsgType.MAIN_THEME_COLOR.getMessage() + prediction
                            + "\n * normalPrediction " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPrediction
                            + "\n * predictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + predictionResult.type
                            + "\n * predictionABS " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.abs(prediction)
                            + "\n * ground " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isOnGround()
                            + "\n * lastGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastLastOnGround()
                            + "\n * inAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                            + "\n * sYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerYGround()
                            + "\n * sGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerGround()
                            + "\n * pYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPositionYGround());
        }

        boolean exempt = profile.getMovementData().getSinceGlidingTicks() < 20;

        if (!onGround && !exempt) {
            if (totalUp > max && Math.abs(prediction) > max) {
                int requiredBuffer = profile.getPotionData().isHasSlowFalling() ? 4 : 2;

                if (++bufferA > requiredBuffer) {
                    fail("Not following MCP Gravity (1)",
                            "motion " + MsgType.MAIN_THEME_COLOR.getMessage() + totalUp
                                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nprediction " + MsgType.MAIN_THEME_COLOR.getMessage() + prediction
                                    + "\nnormalPrediction " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPrediction
                                    + "\npredictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + predictionResult.type
                                    + "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isOnGround()
                                    + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                                    + "\nsYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerYGround()
                                    + "\nsGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerGround()
                                    + "\npYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPositionYGround());

                    bufferA = Math.min(requiredBuffer + 2, bufferA);
                }
            } else {
                bufferA -= Math.min(bufferA, 0.05D);
            }
        }
    }

    public void GravityPredictionB(MovementData movementData, double deltaY) {
        if (profile.shouldCancel()
                || movementData.isNearBed()
                || movementData.isNearWebs()
                || movementData.isNearShulker()
                || movementData.isNearShulkerBox()
                || movementData.isNearBuggyBlock()
                || profile.isBouncingOnSlime()
                || profile.isExempt().vehicle()
                || movementData.isRiptiding()
                || movementData.isInsideLiquid()
                || profile.isExempt().isTeleports()
                || !profile.isExempt().isRespawned()
                || profile.getVehicleData().getSinceVehicleTicks() < 5
                || profile.getLastBlockBreakTimer().hasNotPassed(5 + profile.getConnectionData().getClientTickTrans())) {
            return;
        }

        if (profile.getExempt().isReelingIn()) {
            debugExemptB("reelingIn");
            return;
        }

//        if (movementData.getSinceElytraEquipTicks() < 10) {
//            debugExemptB("Elytra Equip");
//            return;
//        }

        if (movementData.getSincePowderSnowTicks() < 10) {
            debugExemptB("Powder Snow");
            return;
        }

        if (movementData.isInsideWater()
                || movementData.isInsideLiquid()
                || movementData.isBottomOfWater()
                || movementData.isNearWater()
                || movementData.isNearClimbable()) {
            bufferB = 0.0D;
            return;
        }

        double lastDeltaY = movementData.getLastDeltaY();
        boolean isClientGround = movementData.isOnGround();
        boolean isServerGround = movementData.isServerGround();
        boolean isServerYGround = movementData.isServerYGround();

        boolean exempt = profile.getMovementData().getSinceGlidingTicks() < 15
                || Math.abs(deltaY - MoveUtils.getJumpMotion(profile)) <= 1.0E-9D;

        if (!isClientGround
                && profile.getVelocityData().getTotalVerticalVelocity() == 0.0D
                && profile.getVelocityData().getTotalHorizontalVelocity() == 0.0D) {

            double normalPrediction;

            if (profile.getPotionData().isHasLevitation()) {
                double amplifier = profile.getPotionData().getLevitationAmplifier();
                normalPrediction = (lastDeltaY * 0.8D) + (0.01D * amplifier);
            } else {
                normalPrediction = (lastDeltaY - 0.08D) * 0.9800000190734863D;
            }

            PredictionResult predictionResult = selectGravityPrediction(movementData, normalPrediction, true);
            double prediction = predictionResult.prediction;

            if (movementData.isMovingUp()
                    || movementData.isMovingDown()
                    || movementData.getSincePredictUpwardsTicks() < 10
                    || movementData.getSincePredictDownwardsTicks() < 10) {
                bufferB -= Math.min(bufferB, 0.25D);
                return;
            }

            if (deltaY != 0.0D) {
                verbose(this.getClass().getSimpleName(), deltaY, prediction,
                        MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (2)"
                                + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\n * prediction " + MsgType.MAIN_THEME_COLOR.getMessage() + prediction
                                + "\n * normalPrediction " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPrediction
                                + "\n * predictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + predictionResult.type
                                + "\n * predictionABS " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.abs(prediction)
                                + "\n * ground " + MsgType.MAIN_THEME_COLOR.getMessage() + isClientGround
                                + "\n * lastGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastLastOnGround()
                                + "\n * inAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                                + "\n * sYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + isServerYGround
                                + "\n * sGround " + MsgType.MAIN_THEME_COLOR.getMessage() + isServerGround
                                + "\n * pYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPositionYGround());
            }

            if (!(deltaY - prediction < 1.0E-13D) && lastDeltaY > 0.0D && deltaY != 0.0D && !exempt) {
                if (++bufferB > 5.0D) {
                    fail("Not following MCP Gravity (2)",
                            "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                                    + "\nprediction " + MsgType.MAIN_THEME_COLOR.getMessage() + prediction
                                    + "\nnormalPrediction " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPrediction
                                    + "\npredictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + predictionResult.type
                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + isClientGround
                                    + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + isServerGround
                                    + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + isServerYGround);
                    bufferB = Math.max(8, bufferB);
                }
            }
        } else {
            bufferB -= Math.min(bufferB, 0.025D);
        }
    }

    double lastOffset;

    public void GravityPredictionC(double deltaY, double lastDeltaY, boolean underBlock, MovementData md) {
        boolean inLiquid = md.isInsideWater() || md.isBottomOfWater() || md.isInsideLiquid() || md.isNearWater() || md.isNearLava();
        boolean onWeb = md.isNearWebs();
        boolean onLadder = md.isNearClimbable();
        boolean onIce = md.getMovingOnIceTicks() > 0;
        boolean onHoney = md.getMovingOnHoneyTicks() > 0;
        boolean onSlime = md.getMovingOnSlimeTicks() > 0;
        boolean nearBed = md.isNearBed();
        boolean clientGround = md.isOnGround();
        boolean serverGround = md.isServerGround();
        boolean isGliding = md.getSinceGlidingTicks() < 10;
        boolean hasVelocity = profile.getVelocityData().isTakingVelocity();

        if (onSlime) { debugExemptC("slime"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (onHoney) { debugExemptC("honey"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (onIce) { debugExemptC("ice"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (onLadder) { debugExemptC("ladder"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (onWeb) { debugExemptC("web"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (inLiquid) { debugExemptC("liquid"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (nearBed) { debugExemptC("bed"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (underBlock) { debugExemptC("underBlock"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (isGliding) { debugExemptC("gliding"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (profile.isBouncingOnSlime()) { debugExemptC("bouncingOnSlime"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (hasVelocity) { debugExemptC("hasVelocity"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.isRiptiding()) { debugExemptC("riptiding"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (profile.isExempt().isTeleports()) { debugExemptC("teleport"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.isNearContact()) { debugExemptC("contact"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.isNearWater()) { debugExemptC("nearWater"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.elytraMomentum() > 0) { debugExemptC("elytraMomentum"); lastOffset = 0.0D; bufferC = 0.0D; return; }

        if (md.getSincePredictDownwardsTicks() < 5) { debugExemptC("predictDownwards"); lastOffset = 0.0D; bufferC = 0.0D; return; }

        if (md.isNearHoney()) {
            debugExemptC("nearHoney");
            lastOffset = 0.0D;
            return;
        }

        if (md.isNearShulkerBox()) {
            debugExemptC("nearShulkerBox");
            lastOffset = 0.0D;
            return;
        }

        if (profile.shouldCancel()) {
            debugExemptC("shouldCancel");
            lastOffset = 0.0D;
            return;
        }

        double jumpAmplifier = profile.getPotionData().getJumpAmplifier();
        double jumpStart = MoveUtils.getJumpMotion(profile);

       // final double jumpStart = motion + (0.1D * jumpAmplifier);
        final double JUMP_TOL = 0.046D;

        if (md.isMovingUp()
                || md.isMovingDown()
                || md.getSincePredictUpwardsTicks() < 10
                || md.getSincePredictDownwardsTicks() < 10) {
            lastOffset = 0.0D;
            return;
        }

        if (profile.getPotionData().isHasLevitation() || profile.getPotionData().isHasSlowFalling()) {
            lastOffset = 0.0D;
            return;
        }

        if (deltaY == 0.0D || serverGround) {
            lastOffset = 0.0D;
            return;
        }

        if (md.getSincePowderSnowTicks() < 15) {
            lastOffset = 0.0D;
            return;
        }

        if (md.getSinceInsideWaterTicks() < 15) {
            lastOffset = 0.0D;
            return;
        }

        double horizontal = profile.getVelocityData().getTotalHorizontalVelocity();
        final double JUMP_START_TOL_BASE = 0.06D;
        final double JUMP_START_TOL_PER_AMP = 0.02D;
        double jumpTolDynamic = JUMP_START_TOL_BASE + (jumpAmplifier * JUMP_START_TOL_PER_AMP);
        final double HORIZONTAL_VEL_LIMIT = 1.0D;

        if (!clientGround && Math.abs(deltaY - jumpStart) <= jumpTolDynamic && horizontal <= HORIZONTAL_VEL_LIMIT) {
            lastOffset = 0.0D;
            return;
        }

        final double G = 0.08D;
        final double DRAG = 0.9800000190734863D;
        final double EPS = 1.0E-8D;

        double normalPred1 = (lastDeltaY - G) * DRAG;

        if (Math.abs(normalPred1) < 0.005D) {
            normalPred1 = 0.0D;
        }

        PredictionResult pred1Result = selectGravityPrediction(md, normalPred1, true);
        double pred1 = pred1Result.prediction;
        double off1 = Math.abs(deltaY - pred1);

        if (off1 < EPS) {
            lastOffset = off1;
            return;
        }

        double normalPred2 = (normalPred1 - G) * DRAG;

        if (Math.abs(normalPred2) < 0.005D) {
            normalPred2 = 0.0D;
        }

        PredictionResult pred2Result = selectGravityPrediction(md, normalPred2, true);
        double pred2 = pred2Result.prediction;
        double off2 = Math.abs(deltaY - pred2);

        if (off2 < EPS) {
            lastOffset = off2;
            return;
        }

        final double VANILLA_MICRO = 0.003016261509046103D;
        final double VANILLA_NEG = -0.0784000015258789D;
        final double TOL_NEG = 1.0E-6D;
        final double TOL_MICRO = 1.0E-12D;

        boolean microMatches = Math.abs(deltaY - VANILLA_MICRO) < TOL_MICRO
                && (Math.abs(pred1 - VANILLA_NEG) < TOL_NEG
                || Math.abs(pred2 - VANILLA_NEG) < TOL_NEG
                || Math.abs(pred1 - VANILLA_MICRO) < TOL_MICRO
                || Math.abs(pred2 - VANILLA_MICRO) < TOL_MICRO);

        if (microMatches && !clientGround && !serverGround) {
            lastOffset = Math.abs(deltaY - VANILLA_MICRO);
            return;
        }

        final double LASTDELTA_GROUND_EPS = 1.0E-3D;

        if (!clientGround
                && deltaY >= (jumpStart - JUMP_TOL)
                && deltaY <= (jumpStart + JUMP_TOL)
                && Math.abs(lastDeltaY) <= LASTDELTA_GROUND_EPS) {
            lastOffset = 0.0D;
            return;
        }

        final double LAND_NEG = -0.07840000152587834D;
        final double LAND_TOL = 1.0E-6D;
        final double FALL_THRESH = -0.15D;
        final double PRED_FALL_THRESH = -0.20D;
        final double FRACT_Y_TOL = 0.12D;

        if (Math.abs(deltaY - LAND_NEG) <= LAND_TOL
                && lastDeltaY < FALL_THRESH
                && pred1 < PRED_FALL_THRESH
                && pred2 < PRED_FALL_THRESH
                && !clientGround
                && !serverGround) {
            double y = getCurrentY(md);
            double frac = y - Math.floor(y + 1.0E-9D);

            if (frac < FRACT_Y_TOL || frac > (1.0D - FRACT_Y_TOL)) {
                lastOffset = Math.abs(deltaY - LAND_NEG);
                return;
            }
        }

        lastOffset = Math.min(off1, off2);

        if (lastOffset == 0.2268933260512424D
                && deltaY == -0.07840000152587834D
                && !clientGround
                && !serverGround) {
            return;
        }

        String predictionType = off1 <= off2 ? pred1Result.type : pred2Result.type;

        if (deltaY != 0.0D) {
            verbose(this.getClass().getSimpleName(), bufferC, 3,
                    "Verbose (3)"
                            + "\noffset " + MsgType.MAIN_THEME_COLOR.getMessage() + lastOffset
                            + "\nprediction1 " + MsgType.MAIN_THEME_COLOR.getMessage() + pred1
                            + "\nprediction2 " + MsgType.MAIN_THEME_COLOR.getMessage() + pred2
                            + "\nnormalPrediction1 " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPred1
                            + "\nnormalPrediction2 " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPred2
                            + "\npredictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + predictionType
                            + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                            + "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                            + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                            + "\ntook Damage? " + MsgType.MAIN_THEME_COLOR.getMessage() + (profile.getPlayer().getLastDamageCause() == null ? "No" : profile.getPlayer().getLastDamageCause().getCause()));
        }

        if (!clientGround) {
            if (++bufferC > 6.0D) {
                fail("Not following MCP Gravity (3)",
                        "offset " + MsgType.MAIN_THEME_COLOR.getMessage() + lastOffset
                                + "\nprediction1 " + MsgType.MAIN_THEME_COLOR.getMessage() + pred1
                                + "\nprediction2 " + MsgType.MAIN_THEME_COLOR.getMessage() + pred2
                                + "\nnormalPrediction1 " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPred1
                                + "\nnormalPrediction2 " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPred2
                                + "\npredictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + predictionType
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                                + "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\ntook Damage? " + MsgType.MAIN_THEME_COLOR.getMessage() + (profile.getPlayer().getLastDamageCause() == null ? "No" : profile.getPlayer().getLastDamageCause().getCause()));
                bufferC = Math.max(10, bufferC);
            }
        } else {
            bufferC -= Math.min(bufferC, 0.05D);
        }
    }

    private static final double GRAVITY = 0.08D;
    private static final double DRAG = 0.98D;

    private boolean trackingFall;
    private double predictedDY;
    private double predictedFallDist;
    private int predictedTicks;

    private double negGravStreak;

    public void GravityPredictionD(MovementData data) {
        double dy = data.getDeltaY();
        double lastDy = data.getLastDeltaY();
        double fallDist = data.getFallDistance();

        int transTicks = profile.getConnectionData().getClientTickTrans();
        int pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);

        if (!Double.isFinite(dy) || !Double.isFinite(lastDy)) {
            resetGravityD("invalidMotion");
            return;
        }

        if (profile.shouldCancel()) { resetGravityD("shouldCancel"); return; }
        if (profile.isBouncingOnSlime()) { resetGravityD("bouncingOnSlime"); return; }
        if (profile.isExempt().isTeleports()) { resetGravityD("teleport"); return; }
        if (profile.isExempt().vehicle()) { resetGravityD("vehicle"); return; }
        if (profile.getMovementData().getSinceOnGhostBlock() <= 10 + transTicks) { resetGravityD("ghostBlock"); return; }

       // if (data.getSincePredictDownwardsTicks() < 5) { resetGravityD("predictDownwards"); return; }
        if (data.isNearWater()) { resetGravityD("nearWater"); return; }
        if (data.isNearLava()) { resetGravityD("nearLava"); return; }
        if (data.isNearWebs()) { resetGravityD("nearWebs"); return; }
        if (data.isNearBoat()) { resetGravityD("nearBoat"); return; }
        if (data.isNearBed()) { resetGravityD("nearBed"); return; }
        if (data.isNearShulker()) { resetGravityD("nearShulker"); return; }
        if (data.isNearShulkerBox()) { resetGravityD("nearShulkerBox"); return; }
        if (data.isNearClimbable()) { resetGravityD("nearClimbable"); return; }
        if (data.isOnSlime()) { resetGravityD("onSlime"); return; }
        if (data.isNearContact()) { resetGravityD("nearContact"); return; }

        if (data.getSinceGlidingTicks() < 20 + transTicks) { resetGravityD("gliding"); return; }

        if (data.isOnHoney()) { resetGravityD("onHoney"); return; }
        if (data.isInsideWater()) { resetGravityD("insideWater"); return; }
        if (data.isOnTopOfWater()) { resetGravityD("onTopOfWater"); return; }
        if (data.isBottomOfWater()) { resetGravityD("bottomOfWater"); return; }
        if (data.isUnderblock()) { resetGravityD("underBlock"); return; }
        if (data.getMovingUnderblockTicks() > 0) { resetGravityD("movingUnderBlock"); return; }

        //if (data.isOnSlime()) { resetGravityD("onSlime"); return; }

        if (data.isMovingUp()) { resetGravityD("movingUp"); return; }
        if (data.isMovingDown()) { resetGravityD("movingDown"); return; }
        //if (data.getVerticalMove() == MovementPredictionUtil.VerticalMove.DOWN) { resetGravityD("verticalMoveDown"); return; }
        if (data.getSincePredictUpwardsTicks() < 8 + transTicks) { resetGravityD("predictUpwards"); return; }
        if (data.getSincePredictDownwardsTicks() < 8 + transTicks) { resetGravityD("predictDownwards"); return; }

        if (data.getSinceRiptidingTicks() < 10 + transTicks) { resetGravityD("riptiding"); return; }

        if (profile.getVelocityData().isTakingVelocity()) { resetGravityD("takingVelocity"); return; }
        if (Math.abs(profile.getVelocityData().getTotalVerticalVelocity()) > 1.0E-5D) { resetGravityD("verticalVelocity"); return; }
        if (profile.getVelocityData().getTotalHorizontalVelocity() > 0.0D) { resetGravityD("horizontalVelocity"); return; }

        if (profile.getLastBlockBreakTimer().hasNotPassed(5 + transTicks + pingTicks)) { resetGravityD("blockBreak"); return; }

        if (profile.getPotionData().isHasJump()) { resetGravityD("jumpPotion"); return; }
        if (profile.getPotionData().isHasLevitation()) { resetGravityD("levitation"); return; }

        if (data.getSincePowderSnowTicks() < 15) {
            resetGravityD("powderSnow");
            return;
        }

        if (data.isOnGround()
                || data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround()
                || Math.abs(dy) < 1.0E-5D) {
            trackingFall = false;
            predictedDY = 0.0D;
            predictedFallDist = 0.0D;
            predictedTicks = 0;
            negGravStreak = Math.max(0.0D, negGravStreak - 0.35D);
            return;
        }

        if (dy >= 0.0D) {
            trackingFall = true;

            double normalPrediction = predictGravityDY(profile, data, lastDy);
            PredictionResult predictionResult = selectGravityPrediction(data, normalPrediction, true);

            predictedDY = predictionResult.prediction;
            predictedFallDist = 0.0D;
            predictedTicks = Math.max(1, data.getCustomAirTicks());
            negGravStreak = Math.max(0.0D, negGravStreak - 0.20D);
            return;
        }

        trackingFall = true;
        predictedTicks = Math.max(predictedTicks + 1, data.getCustomAirTicks());

        double normalExpectedDY = predictGravityDY(profile, data, lastDy);
        PredictionResult expectedResult = selectGravityPrediction(data, normalExpectedDY, true);
        double expectedDY = expectedResult.prediction;

        double normalDoubleGravityDY = predictGravityDY(profile, data, normalExpectedDY);
        PredictionResult doubleResult = selectGravityPrediction(data, normalDoubleGravityDY, false);
        double doubleGravityDY = doubleResult.prediction;

        if (isVanillaMicroFallTransition(dy, lastDy, expectedDY, doubleGravityDY)) {
            trackingFall = true;
            predictedDY = dy;
            predictedFallDist += Math.max(0.0D, -dy);
            predictedTicks = Math.max(predictedTicks + 1, data.getCustomAirTicks());
            decayGravityD(0.45D);
            return;
        }

        if (isUnsafeGravityStart(data, dy, lastDy)) {
            trackingFall = false;
            predictedDY = 0.0D;
            predictedFallDist = 0.0D;
            predictedTicks = 0;
            decayGravityD(0.45D);
            return;
        }

        predictedDY = expectedDY;

        if (expectedDY < 0.0D) {
            predictedFallDist += -expectedDY;
        }

        double allowed = getFastFallAllowed(profile, data, dy, lastDy, expectedDY);
        double excess = expectedDY - dy;

        boolean slowFalling = profile.getPotionData().isHasSlowFalling();

        if (slowFalling) return;
        boolean tooFast = dy < expectedDY - allowed;

        boolean doubleGravityMatch = tooFast
                && doubleGravityDY < expectedDY
                && Math.abs(dy - doubleGravityDY) < Math.abs(dy - expectedDY);

        boolean terminalBreak = slowFalling
                ? dy < -0.125D - allowed
                : dy < -3.92D - allowed;

        double severity = allowed <= 0.0D ? excess : excess / allowed;

        verbose(this.getClass().getSimpleName(), dy, expectedDY,
                ChatColor.RED + "Verbose (4)"
                        + "\nfallDist " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDist
                        + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getCustomAirTicks()
                        + "\npredTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedTicks
                        + "\nexpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedDY
                        + "\nnormalExpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + normalExpectedDY
                        + "\nexpectedType " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedResult.type
                        + "\ndoubleGravityDY " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityDY
                        + "\ncurrentDY " + MsgType.MAIN_THEME_COLOR.getMessage() + dy
                        + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDy
                        + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess
                        + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + allowed
                        + "\nseverity " + MsgType.MAIN_THEME_COLOR.getMessage() + severity
                        + "\ndoubleGravityMatch " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityMatch
                        + "\nslowFalling " + MsgType.MAIN_THEME_COLOR.getMessage() + slowFalling
                        + "\nstreak " + MsgType.MAIN_THEME_COLOR.getMessage() + negGravStreak);

        if (tooFast || terminalBreak) {
            double added = 0.75D;

            if (doubleGravityMatch) {
                added += 0.85D;
            }

            if (severity > 2.25D) {
                added += 0.45D;
            }

            if (severity > 3.25D) {
                added += 0.55D;
            }

            if (slowFalling) {
                added += 0.45D;
            }

            if (terminalBreak) {
                added += 1.25D;
            }

            negGravStreak += added;

            boolean hardFastFall = terminalBreak
                    || (doubleGravityMatch && excess > (slowFalling ? 0.030D : 0.050D) && data.getCustomAirTicks() >= 3)
                    || (excess > (slowFalling ? 0.045D : 0.095D) && severity > 3.0D);

            double required = hardFastFall ? 3D : 6D;

            if (hardFastFall && increaseBuffer() > required || negGravStreak > required) {
                fail("Negative Gravity Modification " + (hardFastFall ? "(Fast Fall)" : "(Streak : "+ negGravStreak+")"),
                        "fallDist " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDist
                                + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getCustomAirTicks()
                                + "\nexpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedDY
                                + "\nnormalExpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + normalExpectedDY
                                + "\nexpectedType " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedResult.type
                                + "\ndoubleGravityDY " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityDY
                                + "\ncurrentDY " + MsgType.MAIN_THEME_COLOR.getMessage() + dy
                                + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDy
                                + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess
                                + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + allowed
                                + "\nseverity " + MsgType.MAIN_THEME_COLOR.getMessage() + severity
                                + "\ndoubleGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityMatch
                                + "\nslowFalling " + MsgType.MAIN_THEME_COLOR.getMessage() + slowFalling
                                + "\nstreak " + MsgType.MAIN_THEME_COLOR.getMessage() + negGravStreak);

                trackingFall = false;
                predictedDY = 0.0D;
                predictedFallDist = 0.0D;
                predictedTicks = 0;
                negGravStreak = 0.0D;

                negGravStreak = Math.max(required + 2, negGravStreak);
            }
        } else {
            negGravStreak = Math.max(0.0D, negGravStreak - 0.35D);
            decreaseBufferBy(0.05);
        }
    }

    private PredictionResult selectGravityPrediction(MovementData data, double normalPrediction, boolean allowJump) {
        double actual = data.getDeltaY();
        PredictionResult best = new PredictionResult(normalPrediction, "normal", Math.abs(actual - normalPrediction));

        double placedLanding = getPlacedBlockLandingPrediction(data, normalPrediction);

        if (Double.isFinite(placedLanding)) {
            double offset = Math.abs(actual - placedLanding);

            if (offset < best.offset) {
                best = new PredictionResult(placedLanding, "placedBlockLanding", offset);
            }
        }

        if (allowJump) {
            double placedJump = getPlacedBlockJumpPrediction(data);

            if (Double.isFinite(placedJump)) {
                double offset = Math.abs(actual - placedJump);

                if (offset < best.offset) {
                    best = new PredictionResult(placedJump, "placedBlockJump", offset);
                }
            }
        }

        return best;
    }

    private double getPlacedBlockLandingPrediction(MovementData data, double normalPrediction) {
        Object actionData = getActionData();

        if (actionData == null || data == null) {
            return Double.NaN;
        }

        int ticks = getBlockPlacePredictionTicks(actionData);

        if (!hasRecentConfirmedUnderPlace(actionData, ticks)) {
            return Double.NaN;
        }

        double topY = getLastConfirmedUnderPlaceTopY(actionData);
        double lastY = getLastY(data);
        double currentY = getCurrentY(data);

        if (!Double.isFinite(topY) || !Double.isFinite(lastY) || !Double.isFinite(currentY)) {
            return Double.NaN;
        }

        if (!isHorizontallyOverPlacedBlock(data, actionData)) {
            return Double.NaN;
        }

        double predictedY = lastY + normalPrediction;
        double tolerance = getPlacedBlockCollisionTolerance();

        boolean movingTowardBlock = data.getLastDeltaY() <= 0.08D || normalPrediction <= 0.0D;
        boolean crossesTop = lastY >= topY - tolerance && predictedY <= topY + tolerance;
        boolean actualAtTop = Math.abs(currentY - topY) <= tolerance
                || data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround();

        if (!movingTowardBlock || !crossesTop || !actualAtTop) {
            return Double.NaN;
        }

        return topY - lastY;
    }

    private double getPlacedBlockJumpPrediction(MovementData data) {
        Object actionData = getActionData();

        if (actionData == null || data == null) {
            return Double.NaN;
        }

        int ticks = Math.min(5 + profile.getConnectionData().getClientTickTrans(), getBlockPlacePredictionTicks(actionData));

        if (!hasRecentConfirmedUnderPlace(actionData, ticks)) {
            return Double.NaN;
        }

        if (!isHorizontallyOverPlacedBlock(data, actionData)) {
            return Double.NaN;
        }

        double topY = getLastConfirmedUnderPlaceTopY(actionData);
        double lastY = getLastY(data);

        if (!Double.isFinite(topY) || !Double.isFinite(lastY)) {
            return Double.NaN;
        }

        double tolerance = getPlacedBlockCollisionTolerance();

        boolean wasOnPlacedBlock = Math.abs(lastY - topY) <= tolerance
                || data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround();

        if (!wasOnPlacedBlock) {
            return Double.NaN;
        }

        if (data.getDeltaY() <= 0.0D) {
            return Double.NaN;
        }

        return MoveUtils.getJumpMotion(profile);
    }

    private boolean isHorizontallyOverPlacedBlock(MovementData data, Object actionData) {
        if (data == null || actionData == null || data.getLocation() == null) {
            return false;
        }

        double x = getCurrentX(data);
        double z = getCurrentZ(data);

        int blockX = getInt(actionData, "getLastConfirmedUnderPlaceX", Integer.MIN_VALUE);
        int blockZ = getInt(actionData, "getLastConfirmedUnderPlaceZ", Integer.MIN_VALUE);

        if (blockX == Integer.MIN_VALUE || blockZ == Integer.MIN_VALUE) {
            return false;
        }

        double blockCenterX = blockX + 0.5D;
        double blockCenterZ = blockZ + 0.5D;

        double dx = Math.abs(x - blockCenterX);
        double dz = Math.abs(z - blockCenterZ);

        double tolerance = 0.95D;

        try {
            tolerance += Math.min(0.20D, profile.getMovementData().getDeltaXZ() * 0.35D);
        } catch (Throwable ignored) {
        }

        try {
            tolerance += Math.min(0.15D, profile.getConnectionData().getClientTickTrans() * 0.015D);
        } catch (Throwable ignored) {
        }

        return dx <= tolerance && dz <= tolerance;
    }

    private double getPlacedBlockCollisionTolerance() {
        double tolerance = 0.03125D;

        try {
            tolerance += Math.min(0.0625D, profile.getConnectionData().getClientTickTrans() * 0.004D);
        } catch (Throwable ignored) {
        }

        try {
            tolerance += Math.min(0.0625D, (profile.getConnectionData().getTransPing() / 50.0D) * 0.003D);
        } catch (Throwable ignored) {
        }

        return Math.min(0.125D, tolerance);
    }

    private Object getActionData() {
        try {
            return profile.getActionData();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int getBlockPlacePredictionTicks(Object actionData) {
        int reflected = getInt(actionData, "getBlockPlacePredictionTicks", -1);

        if (reflected > 0) {
            return reflected;
        }

        int transTicks = 0;
        int pingTicks = 0;

        try {
            transTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());
        } catch (Throwable ignored) {
        }

        try {
            pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        } catch (Throwable ignored) {
        }

        return Math.max(3, Math.min(20, 3 + transTicks + pingTicks));
    }

    private boolean hasRecentConfirmedUnderPlace(Object actionData, int ticks) {
        Boolean result = getBooleanWithInt(actionData, "hasRecentConfirmedUnderPlace", ticks);

        if (result != null) {
            return result;
        }

        int lastTicks = getInt(actionData, "getLastConfirmedUnderPlaceTicks", 1000);
        return lastTicks <= ticks;
    }

    private double getLastConfirmedUnderPlaceTopY(Object actionData) {
        double topY = getDouble(actionData, "getLastConfirmedUnderPlaceTopY", Double.NaN);

        if (Double.isFinite(topY)) {
            return topY;
        }

        int y = getInt(actionData, "getLastConfirmedUnderPlaceY", Integer.MIN_VALUE);

        if (y == Integer.MIN_VALUE) {
            return Double.NaN;
        }

        return y + 1.0D;
    }

    private double predictGravityDY(Profile profile, MovementData data, double previousDY) {
        if (!Double.isFinite(previousDY)) {
            previousDY = 0.0D;
        }

        boolean slowFalling = profile.getPotionData().isHasSlowFalling() && previousDY <= 0.0D;

        double gravity = slowFalling ? 0.01D : GRAVITY;
        double drag = 0.9800000190734863D;

        double prediction = (previousDY - gravity) * drag;

        if (slowFalling && prediction < -0.125D) {
            prediction = -0.125D;
        }

        if (prediction < -3.92D) {
            prediction = -3.92D;
        }

        if (Math.abs(prediction) < 0.003D) {
            prediction = 0.0D;
        }

        return Double.isFinite(prediction) ? prediction : 0.0D;
    }

    private double getFastFallAllowed(Profile profile, MovementData data, double dy, double lastDy, double expectedDY) {
        int pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        boolean slowFalling = profile.getPotionData().isHasSlowFalling() && lastDy <= 0.0D;

        double allowed = slowFalling ? 0.009D : 0.018D;

        allowed += Math.min(0.030D, Math.abs(expectedDY) * 0.060D);
        allowed += Math.min(0.025D, Math.abs(lastDy) * 0.040D);
        allowed += Math.min(0.040D, pingTicks * 0.0025D);

        if (data.getCustomAirTicks() <= 2) {
            allowed += 0.030D;
        }

        if (Math.abs(lastDy) <= 1.0E-6D && dy < 0.0D) {
            allowed += 0.060D;
        }

        if (data.getSinceCollideTicks() < 5 + profile.getConnectionData().getClientTickTrans()) {
            allowed += 0.025D;
        }

        if (profile.isBedrockPlayer()) {
            allowed += 0.025D;
        }

        if (slowFalling) {
            return Math.min(0.060D, allowed);
        }

        return Math.min(0.120D, allowed);
    }

    private void resetGravityD(String reason) {
        debugExemptD(reason);
        trackingFall = false;
        predictedDY = 0.0D;
        predictedFallDist = 0.0D;
        predictedTicks = 0;
        negGravStreak = 0.0D;
        resetPlacedBlockGravityState();
    }

    private void resetPlacedBlockGravityState() {
        trackingFall = false;
        predictedDY = 0.0D;
        predictedFallDist = 0.0D;
        predictedTicks = 0;
    }

    private void debugExemptD(String reason) {
        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log("Fly A (4): is Exempting (" + reason + ")");
        }
    }

    private double getPrediction(Profile user, double deltaY) {
        MovementData md = user.getMovementData();
        double lastDeltaY = md.getLastDeltaY();

        if (!Double.isFinite(lastDeltaY)) {
            lastDeltaY = 0.0D;
        }

        if (md.isOnGround()) {
            return 0.0D;
        }

        if (isInPowderSnow(md)) {
            if (hasLeatherBoots(user.getPlayer())) {
                return 0.0D;
            }

            double predicted = (lastDeltaY - 0.02D) * 0.65D;

            if (predicted > 0.0D) {
                predicted = 0.0D;
            }

            if (predicted < -0.16D) {
                predicted = -0.16D;
            }

            return Double.isFinite(predicted) ? predicted : 0.0D;
        }

        final double GRAVITY_NORMAL = 0.08D;
        final double GRAVITY_SLOWFALL = 0.01D;
        final double AIR_DRAG = 0.9800000190734863D;
        final double TERMINAL_VELOCITY = -3.92D;
        final double SLOWFALL_CLAMP = -0.125D;

        if (user.getPotionData().isHasLevitation()) {
            int amp = (int) user.getPotionData().getLevitationAmplifier();
            double levPerTick = (0.9D * (amp + 1)) / 20.0D;
            double predicted = lastDeltaY + levPerTick;
            return Double.isFinite(predicted) ? predicted : levPerTick;
        }

        if (user.getMovementData().isLastOnGround() && deltaY > 0.0D) {
            int jumpAmp = (int) user.getPotionData().getJumpAmplifier();
            return 0.42D + (jumpAmp * 0.1D);
        }

        double gravity = user.getPotionData().isHasSlowFalling() ? GRAVITY_SLOWFALL : GRAVITY_NORMAL;
        double predicted = (lastDeltaY - gravity) * AIR_DRAG;

        if (user.getPotionData().isHasSlowFalling() && predicted < SLOWFALL_CLAMP) {
            predicted = SLOWFALL_CLAMP;
        }

        if (predicted < TERMINAL_VELOCITY) {
            predicted = TERMINAL_VELOCITY;
        }

        return Double.isFinite(predicted) ? predicted : 0.0D;
    }

    private double computeAllowedDelta(Profile profile, double deltaY) {
        double base = 0.005D;
        double dynamicFromMotion = Math.abs(deltaY) * 0.35D;

        double pingMs = 0.0D;

        try {
            pingMs = Math.max(0.0D, profile.getConnectionData().getTransPing());
        } catch (Throwable ignored) {
        }

        double pingAllowance = Math.min(0.5D, pingMs / 1000.0D);

        boolean serverGroundFlags = profile.getMovementData().isServerYGround()
                || profile.getMovementData().isPositionYGround()
                || profile.getMovementData().isServerGround();

        double allowed = base + dynamicFromMotion + pingAllowance;

        if (profile.getPotionData().isHasSlowFalling()) {
            allowed = Math.max(allowed, 0.30D);
        }

        if (serverGroundFlags) {
            allowed = Math.max(allowed, 0.25D);
        }

        return Math.min(allowed, 1.5D);
    }

    public static double hypot(double... value) {
        double total = 0.0D;

        for (double val : value) {
            total += val * val;
        }

        return FastMath.sqrt(total);
    }

    public static float hypot(float... value) {
        float total = 0.0F;

        for (float val : value) {
            total += val * val;
        }

        return (float) FastMath.sqrt(total);
    }

    private boolean hasLeatherBoots(Player player) {
        if (player == null) {
            return false;
        }

        ItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.getType() == Material.LEATHER_BOOTS;
    }

    private boolean isInPowderSnow(MovementData md) {
        return md.getNearbyBlocksResult() != null
                && md.getNearbyBlocksResult().getBlockTypes().stream()
                .anyMatch(material -> material.name().equals("POWDER_SNOW"));
    }

    private double getCurrentX(MovementData data) {
        return getDoubleFromObject(data.getLocation(), "getX", 0.0D);
    }

    private double getCurrentY(MovementData data) {
        return getDoubleFromObject(data.getLocation(), "getY", 0.0D);
    }

    private double getCurrentZ(MovementData data) {
        return getDoubleFromObject(data.getLocation(), "getZ", 0.0D);
    }

    private double getLastY(MovementData data) {
        Object lastLocation = invoke(data, "getLastLocation");

        if (lastLocation != null) {
            double value = getDoubleFromObject(lastLocation, "getY", Double.NaN);

            if (Double.isFinite(value)) {
                return value;
            }
        }

        return getCurrentY(data) - data.getDeltaY();
    }

    private Object invoke(Object object, String methodName) {
        if (object == null || methodName == null) {
            return null;
        }

        try {
            Method method = object.getClass().getMethod(methodName);
            return method.invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int getInt(Object object, String methodName, int fallback) {
        Object value = invoke(object, methodName);

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return fallback;
    }

    private double getDouble(Object object, String methodName, double fallback) {
        Object value = invoke(object, methodName);

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        return fallback;
    }

    private double getDoubleFromObject(Object object, String methodName, double fallback) {
        Object value = invoke(object, methodName);

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        return fallback;
    }

    private Boolean getBooleanWithInt(Object object, String methodName, int value) {
        if (object == null || methodName == null) {
            return null;
        }

        try {
            Method method = object.getClass().getMethod(methodName, int.class);
            Object result = method.invoke(object, value);

            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private void debugExempt(String reason) {
        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log("Fly A (1): is Exempting (" + reason + ")");
        }
    }

    private void debugExemptB(String reason) {
        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log("Fly A (2): is Exempting (" + reason + ")");
        }
    }

    private void debugExemptC(String reason) {
        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log("Fly A (3): is Exempting (" + reason + ")");
        }
    }

    private boolean isVanillaMicroFallTransition(double dy, double lastDy, double expectedDY, double doubleGravityDY) {
        final double VANILLA_MICRO = 0.003016261509046103D;
        final double VANILLA_NEG = -0.07840000152587834D;

        boolean expectedMicro = Math.abs(expectedDY - VANILLA_MICRO) <= 1.0E-9D;
        boolean currentVanillaNeg = Math.abs(dy - VANILLA_NEG) <= 1.0E-8D;
        boolean lastSmallPositive = lastDy > 0.0D && lastDy <= 0.085D;
        boolean doubleGravityNear = Math.abs(doubleGravityDY - VANILLA_NEG) <= 0.006D
                || Math.abs(doubleGravityDY - -0.07544406518949479D) <= 0.006D;

        return expectedMicro && currentVanillaNeg && lastSmallPositive && doubleGravityNear;
    }

    private boolean isUnsafeGravityStart(MovementData data, double dy, double lastDy) {
        if (data == null) {
            return true;
        }

        if (data.getCustomAirTicks() <= 1 && Math.abs(lastDy) <= 1.0E-6D && dy < -0.08D) {
            return true;
        }

        if (data.getCustomAirTicks() <= 0 && data.getFallDistance() > 0.0D && dy < 0.0D) {
            return true;
        }

        return false;
    }

    private void decayGravityD(double amount) {
        negGravStreak = Math.max(0.0D, negGravStreak - amount);
    }

    private static final class PredictionResult {

        private final double prediction;
        private final String type;
        private final double offset;

        private PredictionResult(double prediction, String type, double offset) {
            this.prediction = prediction;
            this.type = type;
            this.offset = offset;
        }
    }
}