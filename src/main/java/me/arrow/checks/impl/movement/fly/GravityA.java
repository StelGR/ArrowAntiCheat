package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

public class GravityA extends Check {

    Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    double bufferA;

    public GravityA(Profile profile) {
        super(profile, CheckType.GRAVITY, "A", "Checks whether a player's vertical movement follows normal gravity.");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                && !event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                && !event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                && !event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            return;
        }

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
            return;
        }

        if (movementData.getSinceTeleportTicks() < 5) {
            return;
        }

        if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            return;
        }

        if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity A: Exempt - vehicle");
            return;
        }

        int ghostLiquidWebTicks = Math.min(
                profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
        );

        if (ghostLiquidWebTicks < 10 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity A: is Exempting (ghostblock liquid/web)");
            bufferA = 0.0D;
            return;
        }

        if (profile.getBlockProcessor().isNearGhostBlock()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity A: is Exempting (near Ghostblock)");
            bufferA = 0.0D;
            return;
        }

        if (profile.getBlockProcessor().isUnderGhostBlock()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity A: is Exempting (under Ghostblock)");
            bufferA = 0.0D;
            return;
        }

        if (movementData.getSinceGlidingTicks() < 20) {
            bufferA = 0.0D;
            return;
        }

        if (profile.getGeysersTracker().isBeingPushed()) {
            if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity A: Exempt - geysers (26.2+)");
            return;
        }

        GravityPredictionA(movementData, movementData.getDeltaY(), movementData.isOnGround());
    }

    private void GravityPredictionA(MovementData movementData, double deltaY, boolean onGround) {
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
        if (movementData.isOnSlime()) return;

        if (movementData.getSinceNearSlimeTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))
                && deltaY > MoveUtils.getJumpMotion(profile)
                && movementData.getSinceNearPistonTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            return;
        }

        if (movementData.isNearClimbable()) { debugExempt("nearClimbable"); return; }
        if (profile.getExempt().isReelingIn()) { debugExempt("reelingIn"); return; }

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

        boolean exempt = movementData.getSinceGlidingTicks() < 20;

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

    private static Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        for (String name : new String[]{"VOID", "POISON", "WITHER", "FALL", "MAGIC", "FIRE", "FIRE_TICK", "CAMPFIRE", "SUFFOCATION", "LIGHTNING", "CONTACT", "THORNS", "FLY_INTO_WALL", "CRAMMING", "WORLD_BORDER"}) {
            try {
                set.add(EntityDamageEvent.DamageCause.valueOf(name));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return set;
    }

    private PredictionResult selectGravityPrediction(MovementData data, double normalPrediction, boolean allowJump) {
        double actual = data.getDeltaY();
        PredictionResult best = new PredictionResult(normalPrediction, "normal", Math.abs(actual - normalPrediction));
        double placedLanding = getPlacedBlockLandingPrediction(data, normalPrediction);

        if (Double.isFinite(placedLanding)) {
            double offset = Math.abs(actual - placedLanding);
            if (offset + 1.0E-7D < best.offset && offset <= getPlacedBlockCollisionTolerance() + 0.015D) {
                best = new PredictionResult(placedLanding, "placedBlockLanding", offset);
            }
        }

        if (allowJump) {
            double placedJump = getPlacedBlockJumpPrediction(data);
            if (Double.isFinite(placedJump)) {
                double offset = Math.abs(actual - placedJump);
                double launchTolerance = profile.isBedrockPlayer() ? 0.090D : 0.060D;
                if (offset + 1.0E-7D < best.offset && offset <= launchTolerance) {
                    best = new PredictionResult(placedJump, "placedBlockJump", offset);
                }
            }
        }
        return best;
    }

    private double getPlacedBlockLandingPrediction(MovementData data, double normalPrediction) {
        ActionData actionData = profile.getActionData();
        if (actionData == null || data == null || data.getLocation() == null) return Double.NaN;

        int ticks = actionData.getBlockPlacePredictionTicks();
        if (!actionData.hasRecentConfirmedUnderPlace(ticks)) return Double.NaN;

        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY())
                ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE
                ? Double.NaN : actionData.getLastConfirmedUnderPlaceY() + 1.0D;
        double currentY = data.getLocation().getY();
        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY() : currentY - data.getDeltaY();

        if (!Double.isFinite(topY) || !Double.isFinite(lastY) || !Double.isFinite(currentY)
                || !isHorizontallyOverPlacedBlock(data, actionData)) return Double.NaN;

        double predictedY = lastY + normalPrediction;
        double tolerance = getPlacedBlockCollisionTolerance();
        boolean movingTowardBlock = normalPrediction <= 0.08D && currentY <= lastY + 0.08D;
        boolean crossesTop = lastY >= topY - tolerance && predictedY <= topY + tolerance;
        boolean actualAtTop = Math.abs(currentY - topY) <= tolerance;
        return movingTowardBlock && crossesTop && actualAtTop ? topY - lastY : Double.NaN;
    }

    private double getPlacedBlockJumpPrediction(MovementData data) {
        ActionData actionData = profile.getActionData();
        if (actionData == null || data == null || data.getLocation() == null) return Double.NaN;

        int ticks = Math.min(5 + profile.getConnectionData().getClientTickTrans(), actionData.getBlockPlacePredictionTicks());
        if (!actionData.hasRecentConfirmedUnderPlace(ticks) || !isHorizontallyOverPlacedBlock(data, actionData)) return Double.NaN;

        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY())
                ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE
                ? Double.NaN : actionData.getLastConfirmedUnderPlaceY() + 1.0D;
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
        int blockX = actionData.getLastConfirmedUnderPlaceX();
        int blockZ = actionData.getLastConfirmedUnderPlaceZ();
        if (blockX == Integer.MIN_VALUE || blockZ == Integer.MIN_VALUE) return false;

        double tolerance = 0.800001D + Math.min(0.050D, getPlacedBlockCollisionTolerance());
        if (profile.isBedrockPlayer()) tolerance += 0.015D;
        return Math.abs(data.getLocation().getX() - (blockX + 0.5D)) <= tolerance
                && Math.abs(data.getLocation().getZ() - (blockZ + 0.5D)) <= tolerance;
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

    private double getExpectedJumpMotion() {
        double motion = MoveUtils.getJumpMotion(profile);
        if (!Double.isFinite(motion) || motion < 0.30D || motion > 1.60D) {
            motion = 0.42D;
            if (profile.getPotionData().isHasJump()) {
                motion += Math.max(0, profile.getPotionData().getJumpAmplifier()) * 0.10D;
            }
        }
        return motion;
    }

    private double getPrediction(Profile user, double deltaY) {
        MovementData md = user.getMovementData();
        double lastDeltaY = Double.isFinite(md.getLastDeltaY()) ? md.getLastDeltaY() : 0.0D;
        if (md.isOnGround()) return 0.0D;

        if (isInPowderSnow(md)) {
            if (hasLeatherBoots(user.getPlayer())) return 0.0D;
            double predicted = (lastDeltaY - 0.02D) * 0.65D;
            if (predicted > 0.0D) predicted = 0.0D;
            if (predicted < -0.16D) predicted = -0.16D;
            return Double.isFinite(predicted) ? predicted : 0.0D;
        }

        if (user.getPotionData().isHasLevitation()) {
            int amp = user.getPotionData().getLevitationAmplifier();
            double levPerTick = (0.9D * (amp + 1)) / 20.0D;
            double predicted = lastDeltaY + levPerTick;
            return Double.isFinite(predicted) ? predicted : levPerTick;
        }

        if (md.isLastOnGround() && deltaY > 0.0D) {
            return 0.42D + (user.getPotionData().getJumpAmplifier() * 0.1D);
        }

        double gravity = user.getPotionData().isHasSlowFalling() ? 0.01D : 0.08D;
        double predicted = (lastDeltaY - gravity) * 0.9800000190734863D;
        if (user.getPotionData().isHasSlowFalling() && predicted < -0.125D) predicted = -0.125D;
        if (predicted < -3.92D) predicted = -3.92D;
        return Double.isFinite(predicted) ? predicted : 0.0D;
    }

    private double computeAllowedDelta(Profile profile, double deltaY) {
        double pingMs = 0.0D;
        try {
            pingMs = Math.max(0.0D, profile.getConnectionData().getTransPing());
        } catch (Throwable ignored) {
        }

        double allowed = 0.005D + (Math.abs(deltaY) * 0.35D) + Math.min(0.5D, pingMs / 1000.0D);
        if (profile.getPotionData().isHasSlowFalling()) allowed = Math.max(allowed, 0.30D);
        if (profile.getMovementData().isServerYGround()
                || profile.getMovementData().isPositionYGround()
                || profile.getMovementData().isServerGround()) {
            allowed = Math.max(allowed, 0.25D);
        }
        return Math.min(allowed, 1.5D);
    }

    private boolean hasLeatherBoots(Player player) {
        if (player == null) return false;
        ItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.getType() == Material.LEATHER_BOOTS;
    }

    private boolean isInPowderSnow(MovementData movementData) {
        return movementData.getNearbyBlocksResult() != null
                && movementData.getNearbyBlocksResult().getBlockTypes().stream()
                .anyMatch(material -> material.name().equals("POWDER_SNOW"));
    }

    private void debugExempt(String reason) {
        if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Gravity A: is Exempting (" + reason + ")");
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
