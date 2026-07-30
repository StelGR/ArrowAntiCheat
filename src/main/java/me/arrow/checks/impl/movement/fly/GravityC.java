package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;

public class GravityC extends Check {

    Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    double bufferC;
    double lastOffset;

    public GravityC(Profile profile) {
        super(profile, CheckType.GRAVITY, "C", "Checks for impossible changes between consecutive vertical movements.");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                && !event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                && !event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                && !event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) return;

        long profiler = Profiler.start();

        try {

            MovementData movementData = profile.getMovementData();
            if (movementData == null
                    || movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isNearShulker()
                    || movementData.isNearShulkerBox()
                    || movementData.isNearLava()
                    || movementData.isNearWater()
                    || profile.getExempt().isVehicle()
                    || profile.shouldCancel()
                    || movementData.getSinceGlidingTicks() < 30 + (profile.getConnectionData().getClientTickTrans() * 4)
                    || !CollisionUtils.isChunkLoaded(movementData.getLocation())
                    || movementData.getSinceLevitationEffectTicks() < 10) return;

            ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();
            if (world.shouldExemptMovementChecks()
                    || world.nextToGhostWall
                    || world.physicsMismatch
                    || world.onGhostBlock
                    || world.insideGhostBlock
                    || world.underGhostBlock
                    || profile.getBlockProcessor().isCancelledBlockPlacementExempt(12 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                bufferC = 0.0D;
                return;
            }

            if (movementData.getSinceTeleportTicks() < 5) return;
            if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                lastOffset = 0.0D;
                return;
            }

            if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity C: Exempt - vehicle");
                return;
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean())
                    OtherUtility.log("Gravity C: is Exempting (ghostblock liquid/web)");
                bufferC = 0.0D;
                return;
            }
            if (profile.getBlockProcessor().isNearGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity C: is Exempting (near Ghostblock)");
                bufferC = 0.0D;
                return;
            }
            if (profile.getBlockProcessor().isUnderGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity C: is Exempting (under Ghostblock)");
                bufferC = 0.0D;
                return;
            }
            if (movementData.getSinceGlidingTicks() < 20) {
                bufferC = 0.0D;
                return;
            }
            if (profile.getGeysersTracker().isBeingPushed()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity C: Exempt - geysers (26.2+)");
                return;
            }
            if (!profile.isBedrockPlayer()) {
                GravityPredictionC(movementData.getDeltaY(), movementData.getLastDeltaY(), movementData.isUnderblock(), movementData);
            }
        } finally {
            Profiler.stop("Gravity C", profiler);
        }
    }

    private void GravityPredictionC(double deltaY, double lastDeltaY, boolean underBlock, MovementData md) {
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
        boolean hasVelocity = profile.getVelocityData().isTakingVelocity()
                && profile.getVelocityData().getVelocityTicks() < 4 + (profile.getConnectionData().getClientTickTrans() * 2);

        if (onSlime) return;
        if (onHoney) { debugExempt("honey"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (onIce) { debugExempt("ice"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (onLadder) { debugExempt("ladder"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (onWeb) { debugExempt("web"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (inLiquid) { debugExempt("liquid"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (nearBed) { debugExempt("bed"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (underBlock) { debugExempt("underBlock"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (isGliding) { debugExempt("gliding"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (profile.isBouncingOnSlime()) { debugExempt("bouncingOnSlime"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (hasVelocity) { debugExempt("hasVelocity"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.isRiptiding()) { debugExempt("riptiding"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (profile.isExempt().isTeleports()) { debugExempt("teleport"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.isNearContact()) { debugExempt("contact"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.isNearWater()) { debugExempt("nearWater"); lastOffset = 0.0D; bufferC = 0.0D; return; }
        if (md.elytraMomentum() > 0) { debugExempt("elytraMomentum"); lastOffset = 0.0D; bufferC = 0.0D; return; }

        if (md.isNearHoney()) {
            debugExempt("nearHoney");
            lastOffset = 0.0D;
            return;
        }

        if (md.getSinceNearSlimeTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))
                && md.getSinceNearPistonTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            debugExempt("nearpiston + slime + bounce");
            return;
        }

        if (md.isNearShulkerBox()) {
            debugExempt("nearShulkerBox");
            lastOffset = 0.0D;
            return;
        }
        if (profile.shouldCancel()) {
            debugExempt("shouldCancel");
            lastOffset = 0.0D;
            return;
        }

        final boolean slowFalling = profile.getPotionData().isHasSlowFalling();

        if (slowFalling) return;

        double jumpAmplifier = profile.getPotionData().getJumpAmplifier();
        double jumpStart = MoveUtils.getJumpMotion(profile);
        final double JUMP_TOL = 0.046D;

        if (md.getSincePredictUpwardsTicks() < 10
                || md.getSincePredictDownwardsTicks() < 5
                || md.getSincePredictUpwardsTicksWithoutMaterial() < 10) {
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
        if (md.getSincePowderSnowTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)
                || md.getSinceInsideWaterTicks() < 15) {
            lastOffset = 0.0D;
            return;
        }

        double jumpTolDynamic = 0.06D + (jumpAmplifier * 0.02D);
        if (!clientGround && Math.abs(deltaY - jumpStart) <= jumpTolDynamic) {
            lastOffset = 0.0D;
            return;
        }



        final double G = 0.08D;
        final double DRAG = 0.9800000190734863D;
        final double EPS = 1.0E-8D;
        double normalPred1 = (lastDeltaY - G) * DRAG;
        if (Math.abs(normalPred1) < 0.005D) normalPred1 = 0.0D;
        PredictionResult pred1Result = selectGravityPrediction(md, normalPred1, true);
        double pred1 = pred1Result.prediction;
        double off1 = Math.abs(deltaY - pred1);
        if (off1 < EPS) { lastOffset = off1; return; }

        double normalPred2 = (normalPred1 - G) * DRAG;
        if (Math.abs(normalPred2) < 0.005D) normalPred2 = 0.0D;
        PredictionResult pred2Result = selectGravityPrediction(md, normalPred2, true);
        double pred2 = pred2Result.prediction;
        double off2 = Math.abs(deltaY - pred2);
        if (off2 < EPS) { lastOffset = off2; return; }

        final double VANILLA_MICRO = 0.003016261509046103D;
        final double VANILLA_NEG = -0.0784000015258789D;
        boolean microMatches = Math.abs(deltaY - VANILLA_MICRO) < 1.0E-12D
                && (Math.abs(pred1 - VANILLA_NEG) < 1.0E-6D
                || Math.abs(pred2 - VANILLA_NEG) < 1.0E-6D
                || Math.abs(pred1 - VANILLA_MICRO) < 1.0E-12D
                || Math.abs(pred2 - VANILLA_MICRO) < 1.0E-12D);
        if (microMatches && !clientGround) {
            lastOffset = Math.abs(deltaY - VANILLA_MICRO);
            return;
        }

        if (!clientGround && deltaY >= jumpStart - JUMP_TOL && deltaY <= jumpStart + JUMP_TOL
                && Math.abs(lastDeltaY) <= 1.0E-3D) {
            lastOffset = 0.0D;
            return;
        }

        final double LAND_NEG = -0.07840000152587834D;
        if (Math.abs(deltaY - LAND_NEG) <= 1.0E-6D && lastDeltaY < -0.15D
                && pred1 < -0.20D && pred2 < -0.20D && !clientGround) {
            double y = md.getLocation().getY();
            double frac = y - Math.floor(y + 1.0E-9D);
            if (frac < 0.12D || frac > 0.88D) {
                lastOffset = Math.abs(deltaY - LAND_NEG);
                return;
            }
        }

        lastOffset = Math.min(off1, off2);
        if (lastOffset == 0.2268933260512424D && deltaY == -0.07840000152587834D && !clientGround) return;

        double expected = 0.33319999363422426D;
        if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && !clientGround && Math.abs(deltaY - expected) < 1E-6
                && profile.getActionData().hasRecentUnderPlaceSupport(20 + (profile.getConnectionData().getClientTickTrans() * 2))) return;

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
                            + "\ntook Damage? " + MsgType.MAIN_THEME_COLOR.getMessage()
                            + (profile.getDamageData().getLastCause() == null ? "No" : profile.getDamageData().getLastCause()));
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
                                + "\ntook Damage? " + MsgType.MAIN_THEME_COLOR.getMessage()
                                + (profile.getDamageData().getLastCause() == null ? "No" : profile.getDamageData().getLastCause()));
                bufferC = Math.max(5, bufferC);
            }
        } else {
            bufferC -= Math.min(bufferC, 0.025D);
        }
    }

    private static Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        for (String name : new String[]{"VOID", "POISON", "WITHER", "FALL", "MAGIC", "FIRE", "FIRE_TICK", "CAMPFIRE", "SUFFOCATION", "LIGHTNING", "CONTACT", "THORNS", "FLY_INTO_WALL", "CRAMMING", "WORLD_BORDER"}) {
            try { set.add(EntityDamageEvent.DamageCause.valueOf(name)); } catch (IllegalArgumentException ignored) { }
        }
        return set;
    }

    private PredictionResult selectGravityPrediction(MovementData data, double normalPrediction, boolean allowJump) {
        double actual = data.getDeltaY();
        PredictionResult best = new PredictionResult(normalPrediction, "normal", Math.abs(actual - normalPrediction));
        double placedLanding = getPlacedBlockLandingPrediction(data, normalPrediction);
        if (Double.isFinite(placedLanding)) {
            double offset = Math.abs(actual - placedLanding);
            if (offset + 1.0E-7D < best.offset && offset <= getPlacedBlockCollisionTolerance() + 0.015D)
                best = new PredictionResult(placedLanding, "placedBlockLanding", offset);
        }
        if (allowJump) {
            double placedJump = getPlacedBlockJumpPrediction(data);
            if (Double.isFinite(placedJump)) {
                double offset = Math.abs(actual - placedJump);
                double launchTolerance = profile.isBedrockPlayer() ? 0.090D : 0.060D;
                if (offset + 1.0E-7D < best.offset && offset <= launchTolerance)
                    best = new PredictionResult(placedJump, "placedBlockJump", offset);
            }
        }
        return best;
    }

    private double getPlacedBlockLandingPrediction(MovementData data, double normalPrediction) {
        ActionData actionData = profile.getActionData();
        if (actionData == null || data == null || data.getLocation() == null) return Double.NaN;
        int ticks = actionData.getBlockPlacePredictionTicks();
        if (!actionData.hasRecentConfirmedUnderPlace(ticks)) return Double.NaN;
        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY()) ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE ? Double.NaN : actionData.getLastConfirmedUnderPlaceY() + 1.0D;
        double currentY = data.getLocation().getY();
        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY() : currentY - data.getDeltaY();
        if (!Double.isFinite(topY) || !Double.isFinite(lastY) || !Double.isFinite(currentY)
                || !isHorizontallyOverPlacedBlock(data, actionData)) return Double.NaN;
        double predictedY = lastY + normalPrediction, tolerance = getPlacedBlockCollisionTolerance();
        return normalPrediction <= 0.08D && currentY <= lastY + 0.08D
                && lastY >= topY - tolerance && predictedY <= topY + tolerance
                && Math.abs(currentY - topY) <= tolerance ? topY - lastY : Double.NaN;
    }

    private double getPlacedBlockJumpPrediction(MovementData data) {
        ActionData actionData = profile.getActionData();
        if (actionData == null || data == null || data.getLocation() == null) return Double.NaN;
        int ticks = Math.min(5 + profile.getConnectionData().getClientTickTrans(), actionData.getBlockPlacePredictionTicks());
        if (!actionData.hasRecentConfirmedUnderPlace(ticks) || !isHorizontallyOverPlacedBlock(data, actionData)) return Double.NaN;
        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY()) ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE ? Double.NaN : actionData.getLastConfirmedUnderPlaceY() + 1.0D;
        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY() : data.getLocation().getY() - data.getDeltaY();
        if (!Double.isFinite(topY) || !Double.isFinite(lastY)) return Double.NaN;
        double tolerance = getPlacedBlockCollisionTolerance();
        boolean supported = Math.abs(lastY - topY) <= tolerance
                && (data.isLastOnGround() || data.isLastServerGround() || data.isLastPositionYGround() || data.getCustomAirTicks() <= 1);
        return supported && data.getDeltaY() > 0.0D
                && data.getCustomAirTicks() <= 2 + profile.getConnectionData().getClientTickTrans()
                ? getExpectedJumpMotion() : Double.NaN;
    }

    private boolean isHorizontallyOverPlacedBlock(MovementData data, ActionData actionData) {
        if (data == null || actionData == null || data.getLocation() == null) return false;
        int blockX = actionData.getLastConfirmedUnderPlaceX(), blockZ = actionData.getLastConfirmedUnderPlaceZ();
        if (blockX == Integer.MIN_VALUE || blockZ == Integer.MIN_VALUE) return false;
        double tolerance = 0.800001D + Math.min(0.050D, getPlacedBlockCollisionTolerance());
        if (profile.isBedrockPlayer()) tolerance += 0.015D;
        return Math.abs(data.getLocation().getX() - (blockX + 0.5D)) <= tolerance
                && Math.abs(data.getLocation().getZ() - (blockZ + 0.5D)) <= tolerance;
    }

    private double getPlacedBlockCollisionTolerance() {
        double tolerance = 0.03125D;
        try { tolerance += Math.min(0.0625D, profile.getConnectionData().getClientTickTrans() * 0.004D); } catch (Throwable ignored) { }
        try { tolerance += Math.min(0.0625D, (profile.getConnectionData().getTransPing() / 50.0D) * 0.003D); } catch (Throwable ignored) { }
        return Math.min(0.125D, tolerance);
    }

    private double getExpectedJumpMotion() {
        double motion = MoveUtils.getJumpMotion(profile);
        if (!Double.isFinite(motion) || motion < 0.30D || motion > 1.60D) {
            motion = 0.42D;
            if (SpeedUtilities.getJumpBoostPotionLevel(profile) > 0) motion += Math.max(0, SpeedUtilities.getJumpBoostPotionLevel(profile)) * 0.10D;
        }
        return motion;
    }

    private void debugExempt(String reason) {
        if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity C: is Exempting (" + reason + ")");
    }

    static class PredictionResult {
        double prediction;
        String type;
        double offset;

        private PredictionResult(double prediction, String type, double offset) {
            this.prediction = prediction;
            this.type = type;
            this.offset = offset;
        }
    }
}
