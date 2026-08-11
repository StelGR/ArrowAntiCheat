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

import static me.arrow.utils.ChatUtils.debugExempt;

public class GravityB extends Check {

    Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    double bufferB;

    public GravityB(Profile profile) {
        super(profile, CheckType.GRAVITY, "B", "Checks for vertical movement that does not match the expected gravity cycle.");
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

            if (!profile.isBedrockPlayer()) GravityPredictionB(movementData, movementData.getDeltaY());
        } finally {
            Profiler.stop("Gravity B", profiler);
        }
    }

    private void GravityPredictionB(MovementData movementData, double deltaY) {

        if (isExempt(movementData)) return;

        double lastDeltaY = movementData.getLastDeltaY();
        boolean isClientGround = movementData.isOnGround();
        boolean isServerGround = movementData.isServerGround();
        boolean isServerYGround = movementData.isServerYGround();
        boolean exempt = movementData.getSinceGlidingTicks() < 15
                || Math.abs(deltaY - MoveUtils.getJumpMotion(profile)) <= 1.0E-9D;

        double expected = 0.33319999363422426D;
        if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && !isClientGround && Math.abs(deltaY - expected) < 1E-6
                && profile.getActionData().hasRecentUnderPlaceSupport(20 + (profile.getConnectionData().getClientTickTrans() * 2))) return;

        if (!isClientGround && !(profile.getVelocityData().isTakingVelocity()
                && profile.getVelocityData().getVelocityTicks() < 4 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            double normalPrediction = (lastDeltaY - 0.08D) * 0.9800000190734863D;

            PredictionResult predictionResult = selectGravityPrediction(movementData, normalPrediction, true);
            double prediction = predictionResult.prediction;

            if (movementData.getSincePredictUpwardsTicks() < 10
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
        double predictedY = lastY + normalPrediction;
        double tolerance = getPlacedBlockCollisionTolerance();
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
        boolean wasOnPlacedBlock = Math.abs(lastY - topY) <= tolerance
                && (data.isLastOnGround() || data.isLastServerGround() || data.isLastPositionYGround() || data.getCustomAirTicks() <= 1);
        return wasOnPlacedBlock && data.getDeltaY() > 0.0D
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

    boolean isExempt(MovementData movementData) {
        if (movementData == null
                || movementData.isOnBoat()
                || movementData.isNearBoat()
                || movementData.isNearShulker()
                || movementData.isNearShulkerBox()
                || movementData.isNearLava()
                || movementData.isNearWater()
                || profile.getTick() < 120
                || profile.getExempt().isVehicle()
                || profile.shouldCancel()
                || movementData.getSinceGlidingTicks() < 30 + (profile.getConnectionData().getClientTickTrans() * 4)
                || !CollisionUtils.isChunkLoaded(movementData.getLocation())
                || (profile.getMovementData().getSinceLevitationEffectTicks() < 10 && profile.getPotionData().getLevitationTicks() > 0)) return true;

        ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();
        if (world.shouldExemptMovementChecks()
                || world.nextToGhostWall
                || world.physicsMismatch
                || world.onGhostBlock
                || world.insideGhostBlock
                || world.underGhostBlock
                || profile.getBlockProcessor().isCancelledBlockPlacementExempt(12 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            bufferB = 0.0D;
            return true;
        }

        if (movementData.getSinceTeleportTicks() < 5) return true;
        if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + (profile.getConnectionData().getClientTickTrans() * 2)))
            return true;

        if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity B: Exempt - vehicle");
            return true;
        }

        int ghostLiquidWebTicks = Math.min(profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                profile.getBlockProcessor().getLastPendingPhysicsPlaceTick());
        if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean())
                OtherUtility.log("Gravity B: is Exempting (ghostblock liquid/web)");
            bufferB = 0.0D;
            return true;
        }
        if (profile.getBlockProcessor().isNearGhostBlock()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity B: is Exempting (near Ghostblock)");
            bufferB = 0.0D;
            return true;
        }
        if (profile.getBlockProcessor().isUnderGhostBlock()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity B: is Exempting (under Ghostblock)");
            bufferB = 0.0D;
            return true;
        }

        if (profile.getGeysersTracker().isBeingPushed()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity B: Exempt - geysers (26.2+)");
            return true;
        }
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
                || !profile.isExempt().isRespawned()) return true;

        if (profile.getExempt().isReelingIn()) {
            debugExempt("reelingIn", "GravityB");
            return true;
        }

        if (movementData.getSinceNearSlimeTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))
                && movementData.getDeltaY() > MoveUtils.getJumpMotion(profile)
                && movementData.getSinceNearPistonTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))) return true;

        if (movementData.getSincePowderSnowTicks() < 10) {
            debugExempt("Powder Snow", "GravityB");
            return true;
        }

        if (movementData.isInsideWater()
                || movementData.isInsideLiquid()
                || movementData.isBottomOfWater()
                || movementData.isNearWater()
                || movementData.isNearClimbable()) {
            bufferB = 0.0D;
            return true;
        }
        return false;
    }
}

