package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

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
                    || movementData.getSinceGlidingTicks() < 30 + (profile.getConnectionData().getClientTickTrans() * 4)
                    || !CollisionUtils.isChunkLoaded(movementData.getLocation())
                    || movementData.getSinceLevitationEffectTicks() < 10) {
                return;
            }

            ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

            if (world.shouldExemptMovementChecks()
                    || world.nextToGhostWall
                    || world.physicsMismatch
                    || world.onGhostBlock
                    || world.insideGhostBlock
                    || world.underGhostBlock
                    || profile.getBlockProcessor().isCancelledBlockPlacementExempt(12 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                bufferA = 0.0D;
                bufferB = 0.0D;
                bufferC = 0.0D;
                return;
            }

            if (movementData.getSinceTeleportTicks() < 5) {
                return;
            }

            if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                lastOffset = 0.0D;
                resetPlacedBlockGravityState();
                return;
            }

            if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly A: Exempt - vehicle");
                return;
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 2)) {
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

            if (profile.getMovementData().getSinceGlidingTicks() < 20) {
                bufferA = 0.0D;
                bufferB = 0.0D;
                bufferC = 0.0D;
                return;
            }

            double deltaY = movementData.getDeltaY();
            double lastDeltaY = movementData.getLastDeltaY();
            boolean clientGround = movementData.isOnGround();

            if (profile.getGeysersTracker().isBeingPushed()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly A: Exempt - geysers (26.2+)");
                return;
            }

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
        if (profile.getPlayer().isInsideVehicle()) { debugExempt("insideVehicle"); return; }

        if (profile.isBouncingOnSlime()) { debugExempt("slimeBounce"); return; }
        if (movementData.isOnTopOfWater()) { debugExempt("onTopOfWater"); return; }
        if (movementData.isInsideWater()) { debugExempt("insideWater"); return; }
        if (movementData.isInsideLiquid()) { debugExempt("insideLiquid"); return; }
        if (movementData.isNearLava()) { debugExempt("nearLava"); return; }
        if (movementData.isNearWater()) { debugExempt("nearWater"); return; }
        if (movementData.isNearBuggyBlock()) { debugExempt("nearBuggyBlock"); return; }
        if (profile.getVelocityData().getVelocityTicks() < 8 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            debugExempt("isTakingVelocity");
            return;
        }
        if (movementData.isNearWebs()) { debugExempt("nearWebs"); return; }
        if (movementData.isUnderblock()) { debugExempt("underblock"); return; }
        if (movementData.isNearBed()) { debugExempt("nearBed"); return; }
        if (movementData.isNearHoney()) { debugExempt("nearHoney"); return; }
        if (movementData.isNearDripLeaf()) { debugExempt("nearDripLeaf"); return; }
        if (profile.getActionData().getLastConfirmedUnderBreakTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2)) { debugExempt("breaking block under self"); return; }
        if (movementData.isNearShulkerBox()) { debugExempt("nearShulkerBox"); return; }
        if (movementData.isNearShulker()) { debugExempt("nearShulker"); return; }
        if (movementData.getSinceOnGhostBlock() < 10 + profile.getConnectionData().getClientTickTrans()) { debugExempt("sinceGhostblock"); return; }
        if (movementData.isOnSlime()) {
            return;
        }

        //temporary piston fix
        if (movementData.getSinceNearSlimeTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))
                && deltaY > MoveUtils.getJumpMotion(profile)
                && movementData.getSinceNearPistonTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            return;
        }

        if (movementData.isNearClimbable()) { debugExempt("nearClimbable"); return; }
        if (profile.getExempt().isReelingIn()) { debugExempt("reelingIn"); return; }
//        if (movementData.getSinceElytraEquipTicks() < 10) { debugExempt("Elytra Equip"); return; }

        if (movementData.getSincePowderSnowTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            debugExempt("Powder Snow");
            return;
        }

        if (movementData.isMovingUp()
                || movementData.isMovingDown()
                || movementData.getSincePredictUpwardsTicks() < 10
                || movementData.getSincePredictDownwardsTicks() < 10
                || movementData.getSincePredictDownwardsTicksWithoutMaterial() < 5) {
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
                || profile.isExempt().isTeleports()
                || !profile.isExempt().isRespawned()) {
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

        //temporary piston fix
        if (movementData.getSinceNearSlimeTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))
                && deltaY > MoveUtils.getJumpMotion(profile)
                && movementData.getSinceNearPistonTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            return;
        }

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

        double expected = 0.33319999363422426D;

        if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && !isClientGround && Math.abs(deltaY - expected) < 1E-6
                && profile.getActionData().hasRecentUnderPlaceSupport(20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            return; // vanilla building up
        }

        if (!isClientGround &&
                !(profile.getVelocityData().isTakingVelocity() && profile.getVelocityData().getVelocityTicks() < 4 + (profile.getConnectionData().getClientTickTrans() * 2))) {

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
        boolean onSlime = md.isOnSlime();
        boolean nearBed = md.isNearBed();
        boolean clientGround = md.isOnGround();
        boolean serverGround = md.isServerGround();
        boolean isGliding = md.getSinceGlidingTicks() < 10;
        boolean hasVelocity = profile.getVelocityData().isTakingVelocity() && profile.getVelocityData().getVelocityTicks() < 4 + (profile.getConnectionData().getClientTickTrans() * 2);

        if (onSlime) { return; }
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

        //temporary piston fix
        if (profile.getMovementData().getSinceNearSlimeTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))
//                && deltaY > MoveUtils.getJumpMotion(profile)
                && profile.getMovementData().getSinceNearPistonTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            debugExemptC("nearpiston + slime + bounce");
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

        if (md.getSincePowderSnowTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            lastOffset = 0.0D;
            return;
        }

        if (md.getSinceInsideWaterTicks() < 15) {
            lastOffset = 0.0D;
            return;
        }

        final double JUMP_START_TOL_BASE = 0.06D;
        final double JUMP_START_TOL_PER_AMP = 0.02D;
        double jumpTolDynamic = JUMP_START_TOL_BASE + (jumpAmplifier * JUMP_START_TOL_PER_AMP);

        if (!clientGround && Math.abs(deltaY - jumpStart) <= jumpTolDynamic) {
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

        if (microMatches && !clientGround) {
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

        if (Math.abs(deltaY - LAND_NEG) <= LAND_TOL && lastDeltaY < FALL_THRESH && pred1 < PRED_FALL_THRESH && pred2 < PRED_FALL_THRESH && !clientGround) {
            double y = md.getLocation().getY();
            double frac = y - Math.floor(y + 1.0E-9D);

            if (frac < FRACT_Y_TOL || frac > (1.0D - FRACT_Y_TOL)) {
                lastOffset = Math.abs(deltaY - LAND_NEG);
                return;
            }
        }

        lastOffset = Math.min(off1, off2);

        if (lastOffset == 0.2268933260512424D && deltaY == -0.07840000152587834D && !clientGround) {
            return;
        }

        double expected = 0.33319999363422426D;

        if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && !clientGround && Math.abs(deltaY - expected) < 1E-6
                && profile.getActionData().hasRecentUnderPlaceSupport(20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            return; // vanilla building up
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
                            + "\ntook Damage? " + MsgType.MAIN_THEME_COLOR.getMessage() + (profile.getDamageData().getLastCause() == null ? "No" : profile.getDamageData().getLastCause()));
        }

        if (!clientGround) {
            if (++bufferC > 2.0D) {
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
                                + "\ntook Damage? " + MsgType.MAIN_THEME_COLOR.getMessage() + (profile.getDamageData().getLastCause() == null ? "No" : profile.getDamageData().getLastCause()));
                bufferC = Math.max(5, bufferC);
            }
        } else {
            bufferC -= Math.min(bufferC, 0.025D);
        }
    }

    static double GRAVITY = 0.08D;

    boolean trackingFall;
    double predictedDY;
    double predictedFallDist;
    int predictedTicks;

    double negGravStreak;
    int lastGravityDSampleTick = Integer.MIN_VALUE;

    public void GravityPredictionD(MovementData data) {
        final double dy = data.getDeltaY();
        final double lastDy = data.getLastDeltaY();
        final double fallDist = data.getFallDistance();
        final int transTicks = profile.getConnectionData().getClientTickTrans();

        if (!Double.isFinite(dy) || !Double.isFinite(lastDy)) {
            resetGravityD("invalidMotion");
            return;
        }

        int movementTick = data.getTick();

        if (lastGravityDSampleTick != Integer.MIN_VALUE
                && movementTick - lastGravityDSampleTick > 1) {
            resetGravityDTrackingOnly();
        }

        lastGravityDSampleTick = movementTick;

        if (isGravityDExempt(data, transTicks)) {
            return;
        }

        boolean actualGround = isActualGround(data);
        boolean trustedClientGround = isTrustedClientGround(data);
        int airTicks = getAirTicks(data);
        boolean slowFalling = profile.getPotionData().isHasSlowFalling();

        double expectedDY = predictGravityDY(profile, data, lastDy);
        double allowed = getFastFallAllowed(profile, data, dy, lastDy, expectedDY);
        double excess = expectedDY - dy;
        double expectedAcceleration = lastDy - expectedDY;
        double actualAcceleration = lastDy - dy;
        double accelerationExcess = actualAcceleration - expectedAcceleration;

        boolean directFastFall = isDirectFastFallMotion(
                data, dy, lastDy, expectedDY, excess, allowed, airTicks, actualGround, slowFalling, transTicks
        );
        boolean lowHopMotion = isLowHopMotion(
                data, dy, lastDy, expectedDY, allowed, excess, airTicks, actualGround, trustedClientGround, transTicks
        );
        boolean impossibleFastLanding = isImpossibleFastLanding(
                data,
                dy,
                lastDy,
                expectedDY,
                excess,
                allowed,
                actualGround,
                slowFalling,
                transTicks
        );

        if (directFastFall || lowHopMotion || impossibleFastLanding) {
            int evidenceAirTicks = impossibleFastLanding ? Math.max(airTicks, predictedTicks) : airTicks;
            boolean fastFallEvidence = directFastFall || impossibleFastLanding;

            if (handleGravityDFlag(data,
                    true,
                    fallDist,
                    evidenceAirTicks,
                    expectedDY,
                    expectedDY,
                    "direct",
                    Double.NaN,
                    dy,
                    lastDy,
                    excess,
                    excess - allowed,
                    allowed,
                    allowed <= 0.0D ? excess : excess / allowed,
                    accelerationExcess,
                    false,
                    lowHopMotion,
                    false,
                    false,
                    false,
                    actualGround,
                    trustedClientGround,
                    transTicks,
                    fastFallEvidence ? 0.75D : lowHopMotion ? 0.75D : 1.25D,
                    impossibleFastLanding ? 3.25D : directFastFall ? 3.00D : lowHopMotion ? 2.25D : 1.75D)) {
                return;
            }
        }

        if (actualGround || trustedClientGround || Math.abs(dy) < 1.0E-5D) {
            resetGravityDTrackingOnly();
            decayGravityDStreaks(0.35D);
            return;
        }

        if (dy >= 0.0D) {
            trackingFall = true;
            predictedDY = selectGravityPrediction(data, expectedDY, true).prediction;
            predictedFallDist = 0.0D;
            predictedTicks = Math.max(1, data.getCustomAirTicks());
            decayGravityDStreaks(0.20D);
            return;
        }

        trackingFall = true;
        predictedTicks = Math.max(predictedTicks + 1, data.getCustomAirTicks());

        final PredictionResult expectedResult = selectGravityPrediction(data, expectedDY, true);
        final double selectedExpectedDY = expectedResult.prediction;

        final double normalDoubleGravityDY = predictGravityDY(profile, data, expectedDY);
        final PredictionResult doubleResult = selectGravityPrediction(data, normalDoubleGravityDY, false);
        final double doubleGravityDY = doubleResult.prediction;

        if (isVanillaMicroFallTransition(dy, lastDy, selectedExpectedDY, doubleGravityDY)) {
            trackingFall = true;
            predictedDY = dy;
            predictedFallDist += Math.max(0.0D, -dy);
            predictedTicks = Math.max(predictedTicks + 1, data.getCustomAirTicks());
            decayGravityDStreaks(0.45D);
            return;
        }

        if (isUnsafeGravityStart(data, dy, lastDy)) {
            resetGravityDTrackingOnly();
            decayGravityDStreaks(0.45D);
            return;
        }

        predictedDY = selectedExpectedDY;

        if (selectedExpectedDY < 0.0D) {
            predictedFallDist -= selectedExpectedDY;
        }

        final double selectedAllowed = getFastFallAllowed(profile, data, dy, lastDy, selectedExpectedDY);
        final double selectedExcess = selectedExpectedDY - dy;
        final boolean selectedTooFast = dy < selectedExpectedDY - selectedAllowed;
        final double selectedExtraGravity = selectedExcess - selectedAllowed;
        final double selectedExpectedAcceleration = lastDy - selectedExpectedDY;
        final double selectedActualAcceleration = lastDy - dy;
        final double selectedAccelerationExcess = selectedActualAcceleration - selectedExpectedAcceleration;
        final double severity = selectedAllowed <= 0.0D ? selectedExcess : selectedExcess / selectedAllowed;

        if (slowFalling) {
            decayGravityDStreaks(0.35D);
            return;
        }

        boolean doubleGravityMatch = selectedTooFast
                && doubleGravityDY < selectedExpectedDY
                && Math.abs(dy - doubleGravityDY) < Math.abs(dy - selectedExpectedDY);

        boolean earlyMotionCut = selectedTooFast
                && airTicks >= 2
                && airTicks <= 6 + transTicks
                && lastDy > 0.080D
                && selectedExpectedDY > 0.030D
                && dy < selectedExpectedDY - Math.max(0.075D, selectedAllowed * 1.60D)
                && selectedAccelerationExcess > Math.max(0.050D, selectedAllowed * 1.20D);

        boolean lateMotionSet = selectedTooFast
                && airTicks >= 5
                && airTicks <= 18 + transTicks
                && dy < -0.300D
                && selectedExtraGravity > 0.060D
                && selectedActualAcceleration > selectedExpectedAcceleration + Math.max(0.045D, selectedAllowed * 1.10D);

        boolean hardMotionSet = selectedTooFast
                && airTicks >= 2
                && dy < -0.520D
                && selectedExcess > Math.max(0.105D, selectedAllowed * 2.00D);

        boolean impossibleAcceleration = selectedTooFast
                && airTicks >= 2
                && dy < -0.095D
                && selectedAccelerationExcess > Math.max(0.055D, selectedAllowed * 1.45D);

        boolean terminalBreak = dy < -3.92D - selectedAllowed;

        boolean genericFastFall = selectedTooFast || terminalBreak;

        verbose(this.getClass().getSimpleName(), dy, selectedExpectedDY,
                ChatColor.RED + "Verbose (4)"
                        + "\nfallDist " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDist
                        + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                        + "\npredTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedTicks
                        + "\nexpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedExpectedDY
                        + "\nnormalExpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedDY
                        + "\nexpectedType " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedResult.type
                        + "\ndoubleGravityDY " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityDY
                        + "\ncurrentDY " + MsgType.MAIN_THEME_COLOR.getMessage() + dy
                        + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDy
                        + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedExcess
                        + "\nextraGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedExtraGravity
                        + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedAllowed
                        + "\nseverity " + MsgType.MAIN_THEME_COLOR.getMessage() + severity
                        + "\naccelExcess " + MsgType.MAIN_THEME_COLOR.getMessage() + selectedAccelerationExcess
                        + "\ndoubleGravityMatch " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityMatch
                        + "\nearlyMotionCut " + MsgType.MAIN_THEME_COLOR.getMessage() + earlyMotionCut
                        + "\nlateMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + lateMotionSet
                        + "\nhardMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + hardMotionSet
                        + "\nimpossibleAcceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + impossibleAcceleration
                        + "\nterminalBreak " + MsgType.MAIN_THEME_COLOR.getMessage() + terminalBreak
                        + "\ndirectEvidence " + MsgType.MAIN_THEME_COLOR.getMessage()
                        + (directFastFall || impossibleFastLanding || lowHopMotion)
                        + "\nstreak " + MsgType.MAIN_THEME_COLOR.getMessage() + negGravStreak);

        if (genericFastFall) {
            boolean hardFastFall = terminalBreak
                    || hardMotionSet
                    || lateMotionSet
                    || earlyMotionCut
                    || impossibleAcceleration
                    || (doubleGravityMatch && selectedExcess > 0.050D && airTicks >= 3)
                    || (selectedExcess > 0.095D && severity > 3.0D);

            boolean extremeFastFall = terminalBreak
                    || directFastFall
                    || hardMotionSet
                    || (airTicks >= 3 && dy < -0.700D && selectedExcess > 0.180D);

            double required = extremeFastFall ? 0.75D : hardFastFall ? 1.50D : 4.00D;
            double added = getFastFallStreakAdd(doubleGravityMatch, earlyMotionCut, lateMotionSet, hardMotionSet, impossibleAcceleration, terminalBreak, severity);

            if (handleGravityDFlag(data,
                    false,
                    fallDist,
                    airTicks,
                    selectedExpectedDY,
                    expectedDY,
                    expectedResult.type,
                    doubleGravityDY,
                    dy,
                    lastDy,
                    selectedExcess,
                    selectedExtraGravity,
                    selectedAllowed,
                    severity,
                    selectedAccelerationExcess,
                    doubleGravityMatch,
                    earlyMotionCut,
                    lateMotionSet,
                    hardMotionSet,
                    impossibleAcceleration,
                    actualGround,
                    trustedClientGround,
                    transTicks,
                    required,
                    added)) {
                return;
            }
        } else {
            decayGravityDStreaks(0.35D);
            decreaseBufferBy(0.05D);
        }
    }

    private boolean isGravityDExempt(MovementData data, int transTicks) {
        if (profile.shouldCancel()) { resetGravityD("shouldCancel"); return true; }
        if (profile.isBouncingOnSlime()) { resetGravityD("bouncingOnSlime"); return true; }
        if (profile.isExempt().isTeleports()) { resetGravityD("teleport"); return true; }
        if (profile.isExempt().vehicle()) { resetGravityD("vehicle"); return true; }
        if (profile.getMovementData().getSinceOnGhostBlock() <= 10 + transTicks) { resetGravityD("ghostBlock"); return true; }

        if (data.isNearWater()) { resetGravityD("nearWater"); return true; }
        if (data.isNearLava()) { resetGravityD("nearLava"); return true; }
        if (data.isNearWebs()) { resetGravityD("nearWebs"); return true; }
        if (data.isNearBoat()) { resetGravityD("nearBoat"); return true; }
        if (data.isNearBed()) { resetGravityD("nearBed"); return true; }
        if (data.isNearShulker()) { resetGravityD("nearShulker"); return true; }
        if (data.isNearShulkerBox()) { resetGravityD("nearShulkerBox"); return true; }
        if (data.isNearClimbable()) { resetGravityD("nearClimbable"); return true; }
        if (data.isOnSlime()) { resetGravityD("onSlime"); return true; }
        if (data.isNearContact()) { resetGravityD("nearContact"); return true; }
        if (data.getSinceGlidingTicks() < 20 + transTicks) { resetGravityD("gliding"); return true; }
        if (data.isOnHoney()) { resetGravityD("onHoney"); return true; }
        if (data.isInsideWater()) { resetGravityD("insideWater"); return true; }
        if (data.isOnTopOfWater()) { resetGravityD("onTopOfWater"); return true; }
        if (data.isBottomOfWater()) { resetGravityD("bottomOfWater"); return true; }
        if (data.isUnderblock()) { resetGravityD("underBlock"); return true; }
        if (data.getMovingUnderblockTicks() > 0) { resetGravityD("movingUnderBlock"); return true; }
        if (data.getSinceRiptidingTicks() < 10 + transTicks) { resetGravityD("riptiding"); return true; }

        if (profile.getVelocityData().isTakingVelocity()) {
            resetGravityD("takingVelocity");
            return true;
        }

        if (profile.getPotionData().isHasJump()) { resetGravityD("jumpPotion"); return true; }
        if (profile.getPotionData().isHasLevitation()) { resetGravityD("levitation"); return true; }

        if (data.getSincePowderSnowTicks() < 15 + (transTicks * 2)) {
            resetGravityD("powderSnow");
            return true;
        }

        return false;
    }

    private boolean isActualGround(MovementData data) {
        return !data.isCustomInAir()
                && (data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround());
    }

    private boolean isTrustedClientGround(MovementData data) {
        return data.isOnGround()
                && !data.isCustomInAir()
                && data.getServerAirTicks() <= 1
                && data.getCustomAirTicks() <= 1;
    }

    private int getAirTicks(MovementData data) {
        return Math.max(data.getCustomAirTicks(), Math.max(data.getClientAirTicks(), data.getServerAirTicks()));
    }

    private boolean isDirectFastFallMotion(MovementData data,
                                           double dy,
                                           double lastDy,
                                           double expectedDY,
                                           double excess,
                                           double allowed,
                                           int airTicks,
                                           boolean actualGround,
                                           boolean slowFalling,
                                           int transTicks) {
        if (slowFalling
                || actualGround
                || data.isNearStepMaterial()
                || hasRecentGravitySupportChange(4 + (transTicks * 2))) {
            return false;
        }

        boolean tickWindow = airTicks >= 2 && airTicks <= 20 + transTicks;
        boolean descendingPhase = lastDy <= 0.040D || expectedDY <= 0.0D;
        boolean acceleratedFall = dy < -0.300D
                && excess > Math.max(0.095D, allowed * 1.75D);
        boolean hardMotionSet = dy < -0.520D
                && excess > Math.max(0.105D, allowed * 2.00D);
        boolean terminalBreak = dy < -3.92D - allowed;

        return tickWindow
                && (terminalBreak || hardMotionSet || (descendingPhase && acceleratedFall));
    }

    private boolean isImpossibleFastLanding(MovementData data,
                                            double dy,
                                            double lastDy,
                                            double expectedDY,
                                            double excess,
                                            double allowed,
                                            boolean actualGround,
                                            boolean slowFalling,
                                            int transTicks) {
        if (!actualGround
                || slowFalling
                || !trackingFall
                || predictedTicks < 4
                || data.isNearStepMaterial()) {
            return false;
        }

        // A real landing only shortens the predicted downward move. It cannot make
        // that move substantially more negative. Fast-fall clients can cross the
        // remaining gap and land in one packet, which used to be discarded here.
        if (data.isLastServerGround() || data.isLastPositionYGround()) {
            return false;
        }

        if (hasRecentGravitySupportChange(4 + (transTicks * 2))) {
            return false;
        }

        double requiredExcess = Math.max(0.165D, allowed * 2.75D);
        boolean wasDescending = lastDy < -0.040D || predictedDY < -0.040D;
        boolean largeLandingMove = dy < -0.300D;
        boolean impossibleDisplacement = dy < expectedDY - requiredExcess
                && excess > requiredExcess;

        return wasDescending && largeLandingMove && impossibleDisplacement;
    }

    private boolean isLowHopMotion(MovementData data,
                                   double dy,
                                   double lastDy,
                                   double expectedDY,
                                   double allowed,
                                   double excess,
                                   int airTicks,
                                   boolean actualGround,
                                   boolean trustedClientGround,
                                   int transTicks) {
        if (actualGround
                || trustedClientGround
                || profile.getPotionData().isHasSlowFalling()
                || data.isNearStepMaterial()) {
            return false;
        }

        int supportTicks = 5 + (transTicks * 2);

        if (hasRecentGravitySupportChange(supportTicks)) {
            return false;
        }

        boolean earlyJumpTick = airTicks >= 1 && airTicks <= 5 + transTicks;
        boolean leftGround = data.isLastOnGround()
                || data.isLastServerGround();
        boolean fromJump = leftGround || lastDy > 0.180D;
        double launchTolerance = profile.isBedrockPlayer() ? 0.120D : 0.075D;
        boolean reducedJumpLaunch = leftGround
                && data.getClientAirTicks() == 1
                && airTicks <= 2
                && dy > 0.040D
                && dy < MoveUtils.getJumpMotion(profile) - launchTolerance;
        double requiredCut = Math.max(0.090D, allowed * 1.40D);
        boolean expectedToKeepRising = expectedDY > 0.040D;
        boolean severeRiseCut = dy < expectedDY - requiredCut
                && excess > requiredCut;

        return reducedJumpLaunch
                || (earlyJumpTick && fromJump && expectedToKeepRising && severeRiseCut);
    }

    private boolean hasRecentGravitySupportChange(int ticks) {
        ActionData actionData = profile.getActionData();

        return actionData != null
                && (actionData.hasRecentConfirmedUnderPlace(ticks)
                || actionData.hasRecentConfirmedUnderBreak(ticks)
                || actionData.hasRecentBlockUpdateUnder(ticks)
                || actionData.hasRecentPistonUpdate(ticks));
    }

    private double getFastFallStreakAdd(boolean doubleGravityMatch,
                                        boolean earlyMotionCut,
                                        boolean lateMotionSet,
                                        boolean hardMotionSet,
                                        boolean impossibleAcceleration,
                                        boolean terminalBreak,
                                        double severity) {
        double added = 0.75D;

        if (doubleGravityMatch) {
            added += 0.85D;
        }

        if (earlyMotionCut) {
            added += 0.70D;
        }

        if (lateMotionSet) {
            added += 1.05D;
        }

        if (hardMotionSet) {
            added += 1.60D;
        }

        if (impossibleAcceleration) {
            added += 0.75D;
        }

        if (severity > 2.25D) {
            added += 0.45D;
        }

        if (severity > 3.25D) {
            added += 0.55D;
        }

        if (terminalBreak) {
            added += 1.25D;
        }

        return added;
    }

    private boolean handleGravityDFlag(MovementData data,
                                       boolean directEvidence,
                                       double fallDist,
                                       int airTicks,
                                       double expectedDY,
                                       double normalExpectedDY,
                                       String expectedType,
                                       double doubleGravityDY,
                                       double dy,
                                       double lastDy,
                                       double excess,
                                       double extraGravity,
                                       double allowed,
                                       double severity,
                                       double accelerationExcess,
                                       boolean doubleGravityMatch,
                                       boolean earlyMotionCut,
                                       boolean lateMotionSet,
                                       boolean hardMotionSet,
                                       boolean impossibleAcceleration,
                                       boolean actualGround,
                                       boolean trustedClientGround,
                                       int transTicks,
                                       double required,
                                       double added) {
        if (!directEvidence) {
            if (data.getSincePredictUpwardsTicks() < 5 + transTicks) {
                resetGravityD("predictUpwards");
                return true;
            }

            if (data.getSincePredictDownwardsTicks() < 5 + transTicks) {
                resetGravityD("predictDownwards");
                return true;
            }
        }

        negGravStreak += added;

        if (increaseBuffer() > required || negGravStreak > required) {
            fail("Negative Gravity Modification",
                    "fallDist " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDist
                            + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                            + "\nexpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedDY
                            + "\nnormalExpectedDY " + MsgType.MAIN_THEME_COLOR.getMessage() + normalExpectedDY
                            + "\nexpectedType " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedType
                            + "\ndoubleGravityDY " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityDY
                            + "\ncurrentDY " + MsgType.MAIN_THEME_COLOR.getMessage() + dy
                            + "\nlastDY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDy
                            + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess
                            + "\nextraGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + extraGravity
                            + "\nallowed " + MsgType.MAIN_THEME_COLOR.getMessage() + allowed
                            + "\nseverity " + MsgType.MAIN_THEME_COLOR.getMessage() + severity
                            + "\naccelExcess " + MsgType.MAIN_THEME_COLOR.getMessage() + accelerationExcess
                            + "\ndoubleGravity " + MsgType.MAIN_THEME_COLOR.getMessage() + doubleGravityMatch
                            + "\nearlyMotionCut " + MsgType.MAIN_THEME_COLOR.getMessage() + earlyMotionCut
                            + "\nlateMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + lateMotionSet
                            + "\nhardMotionSet " + MsgType.MAIN_THEME_COLOR.getMessage() + hardMotionSet
                            + "\nimpossibleAcceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + impossibleAcceleration
                            + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + data.isOnGround()
                            + "\ncustomInAir " + MsgType.MAIN_THEME_COLOR.getMessage() + data.isCustomInAir()
                            + "\nactualGround " + MsgType.MAIN_THEME_COLOR.getMessage() + actualGround
                            + "\ntrustedClientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + trustedClientGround
                            + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getDeltaXZ()
                            + "\nstreak " + MsgType.MAIN_THEME_COLOR.getMessage() + negGravStreak);

            resetGravityDTrackingOnly();
            negGravStreak = Math.max(required + 2.0D, negGravStreak);
            return true;
        }

        return false;
    }

    private void resetGravityDTrackingOnly() {
        trackingFall = false;
        predictedDY = 0.0D;
        predictedFallDist = 0.0D;
        predictedTicks = 0;
    }

    private void decayGravityDStreaks(double amount) {
        negGravStreak = Math.max(0.0D, negGravStreak - amount);
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
        ActionData actionData = profile.getActionData();

        if (actionData == null || data == null || data.getLocation() == null) {
            return Double.NaN;
        }

        int ticks = actionData.getBlockPlacePredictionTicks();

        if (!actionData.hasRecentConfirmedUnderPlace(ticks)) {
            return Double.NaN;
        }

        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY())
                ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE
                ? Double.NaN
                : actionData.getLastConfirmedUnderPlaceY() + 1.0D;

        double currentY = data.getLocation().getY();
        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY()
                : currentY - data.getDeltaY();

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
        ActionData actionData = profile.getActionData();

        if (actionData == null || data == null || data.getLocation() == null) {
            return Double.NaN;
        }

        int ticks = Math.min(5 + profile.getConnectionData().getClientTickTrans(), actionData.getBlockPlacePredictionTicks());

        if (!actionData.hasRecentConfirmedUnderPlace(ticks)) {
            return Double.NaN;
        }

        if (!isHorizontallyOverPlacedBlock(data, actionData)) {
            return Double.NaN;
        }

        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY())
                ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE
                ? Double.NaN
                : actionData.getLastConfirmedUnderPlaceY() + 1.0D;

        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY()
                : data.getLocation().getY() - data.getDeltaY();

        if (!Double.isFinite(topY) || !Double.isFinite(lastY)) {
            return Double.NaN;
        }

        double tolerance = getPlacedBlockCollisionTolerance();

        boolean wasOnPlacedBlock = Math.abs(lastY - topY) <= tolerance
                || data.isServerGround()
                || data.isServerYGround()
                || data.isPositionYGround();

        if (!wasOnPlacedBlock || data.getDeltaY() <= 0.0D) {
            return Double.NaN;
        }

        return MoveUtils.getJumpMotion(profile);
    }

    private boolean isHorizontallyOverPlacedBlock(MovementData data, ActionData actionData) {
        if (data == null || actionData == null || data.getLocation() == null) {
            return false;
        }

        double x = data.getLocation().getX();
        double z = data.getLocation().getZ();

        int blockX = actionData.getLastConfirmedUnderPlaceX();
        int blockZ = actionData.getLastConfirmedUnderPlaceZ();

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
        negGravStreak = 0.0D;
        resetPlacedBlockGravityState();
    }

    private void resetPlacedBlockGravityState() {
        resetGravityDTrackingOnly();
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
            int amp = user.getPotionData().getLevitationAmplifier();
            double levPerTick = (0.9D * (amp + 1)) / 20.0D;
            double predicted = lastDeltaY + levPerTick;
            return Double.isFinite(predicted) ? predicted : levPerTick;
        }

        if (user.getMovementData().isLastOnGround() && deltaY > 0.0D) {
            int jumpAmp = user.getPotionData().getJumpAmplifier();
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

    private void debugExemptD(String reason) {
        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log("Fly A (4): is Exempting (" + reason + ")");
        }
    }

    private boolean isVanillaMicroFallTransition(double dy, double lastDy, double expectedDY, double doubleGravityDY) {
        final double VANILLA_MICRO = 0.003016261509046103D;
        final double VANILLA_NEG = -0.07840000152587834D;

        boolean expectedMicro = Math.abs(expectedDY - VANILLA_MICRO) <= 1.0E-9D;
        boolean currentVanillaNeg = Math.abs(dy - VANILLA_NEG) <= 1.0E-8D;
        boolean lastSmallPositive = lastDy > 0.0D && lastDy <= 0.085D;
        boolean doubleGravityNear = Math.abs(doubleGravityDY - VANILLA_NEG) <= 0.006D
                || Math.abs(doubleGravityDY + 0.07544406518949479D) <= 0.006D;

        return expectedMicro && currentVanillaNeg && lastSmallPositive && doubleGravityNear;
    }

    private boolean isUnsafeGravityStart(MovementData data, double dy, double lastDy) {
        if (data == null) {
            return true;
        }

        if (data.getCustomAirTicks() <= 1 && Math.abs(lastDy) <= 1.0E-6D && dy < -0.08D) {
            return true;
        }

        return data.getCustomAirTicks() <= 0 && data.getFallDistance() > 0.0D && dy < 0.0D;
    }

    private void decayGravityD(double amount) {
        negGravStreak = Math.max(0.0D, negGravStreak - amount);
    }

    static class PredictionResult {

        double prediction;
        final String type;
        double offset;

        private PredictionResult(double prediction, String type, double offset) {
            this.prediction = prediction;
            this.type = type;
            this.offset = offset;
        }
    }
}
