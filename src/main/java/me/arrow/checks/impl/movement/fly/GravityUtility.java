package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import lombok.Getter;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * Shared utility for gravity checks (Gravity A, B, C, D).
 * Encapsulates placed-block gravity predictions, jump motion calculations,
 * common gravity tolerances, and ignored damage causes.
 */
public class GravityUtility {

    public static final double DEFAULT_GRAVITY = 0.08D;
    public static final double SLOW_FALLING_GRAVITY = 0.01D;
    public static final double AIR_DRAG = 0.9800000190734863D;
    public static final double VANILLA_MICRO_FALL = 0.003016261509046103D;
    public static final double VANILLA_FALL_MOTION = -0.0784000015258789D;
    public static final double LEGACY_TOWER_MOTION = 0.33319999363422426D;

    public static final Set<EntityDamageEvent.DamageCause> IGNORED_DAMAGE_CAUSES = buildIgnoredCauses();

    public static Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        for (String name : new String[]{
                "VOID", "POISON", "WITHER", "FALL", "MAGIC", "FIRE", "FIRE_TICK",
                "CAMPFIRE", "SUFFOCATION", "LIGHTNING", "CONTACT", "THORNS",
                "FLY_INTO_WALL", "CRAMMING", "WORLD_BORDER"
        }) {
            try {
                set.add(EntityDamageEvent.DamageCause.valueOf(name));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return set;
    }

    @Getter
    public static class PredictionResult {
        public double prediction;
        public final String type;
        public double offset;

        public PredictionResult(double prediction, String type, double offset) {
            this.prediction = prediction;
            this.type = type;
            this.offset = offset;
        }

    }

    public static double getExpectedJumpMotion(Profile profile) {
        double motion = MoveUtils.getJumpMotion(profile);
        if (!Double.isFinite(motion) || motion < 0.30D || motion > 1.60D) {
            motion = 0.42D;
            if (SpeedUtilities.getJumpBoostPotionLevel(profile) > 0) {
                motion += Math.max(0, SpeedUtilities.getJumpBoostPotionLevel(profile)) * 0.10D;
            }
        }
        return motion;
    }

    public static double getPlacedBlockCollisionTolerance(Profile profile) {
        int transTicks = profile != null && profile.getConnectionData() != null ? profile.getConnectionData().getClientTickTrans() : 0;
        return getPlacedBlockCollisionTolerance(profile, transTicks);
    }

    public static double getPlacedBlockCollisionTolerance(Profile profile, int transTicks) {
        double tolerance = 0.03125D;
        try {
            tolerance += Math.min(0.0625D, Math.max(0, transTicks) * 0.004D);
        } catch (Throwable ignored) {
        }
        try {
            tolerance += Math.min(0.0625D, (profile.getConnectionData().getTransPing() / 50.0D) * 0.003D);
        } catch (Throwable ignored) {
        }
        return Math.min(0.125D, tolerance);
    }

    public static boolean isHorizontallyOverPlacedBlock(Profile profile, MovementData data, ActionData actionData) {
        int transTicks = profile != null && profile.getConnectionData() != null ? profile.getConnectionData().getClientTickTrans() : 0;
        return isHorizontallyOverPlacedBlock(profile, data, actionData, transTicks);
    }

    public static boolean isHorizontallyOverPlacedBlock(Profile profile, MovementData data, ActionData actionData, int transTicks) {
        if (data == null || actionData == null || data.getLocation() == null) return false;
        int blockX = actionData.getLastConfirmedUnderPlaceX();
        int blockZ = actionData.getLastConfirmedUnderPlaceZ();
        if (blockX == Integer.MIN_VALUE || blockZ == Integer.MIN_VALUE) return false;

        double tolerance = 0.800001D + Math.min(0.050D, getPlacedBlockCollisionTolerance(profile, transTicks));
        if (profile != null && profile.isBedrockPlayer()) tolerance += 0.015D;
        return Math.abs(data.getLocation().getX() - (blockX + 0.5D)) <= tolerance
                && Math.abs(data.getLocation().getZ() - (blockZ + 0.5D)) <= tolerance;
    }

    public static double getPlacedBlockLandingPrediction(Profile profile, MovementData data, ActionData actionData, double normalPrediction) {
        int transTicks = profile != null && profile.getConnectionData() != null ? profile.getConnectionData().getClientTickTrans() : 0;
        return getPlacedBlockLandingPrediction(profile, data, actionData, normalPrediction, transTicks);
    }

    public static double getPlacedBlockLandingPrediction(Profile profile, MovementData data, ActionData actionData, double normalPrediction, int transTicks) {
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
                || !isHorizontallyOverPlacedBlock(profile, data, actionData, transTicks)) return Double.NaN;

        double predictedY = lastY + normalPrediction;
        double tolerance = getPlacedBlockCollisionTolerance(profile, transTicks);
        boolean movingTowardBlock = normalPrediction <= 0.08D && currentY <= lastY + 0.08D;
        boolean crossesTop = lastY >= topY - tolerance && predictedY <= topY + tolerance;
        boolean actualAtTop = Math.abs(currentY - topY) <= tolerance;
        return movingTowardBlock && crossesTop && actualAtTop ? topY - lastY : Double.NaN;
    }

    public static double getPlacedBlockJumpPrediction(Profile profile, MovementData data, ActionData actionData) {
        return getPlacedBlockJumpPrediction(profile, data, actionData, getExpectedJumpMotion(profile));
    }

    public static double getPlacedBlockJumpPrediction(Profile profile, MovementData data, ActionData actionData, double expectedJumpMotion) {
        int transTicks = profile != null && profile.getConnectionData() != null ? profile.getConnectionData().getClientTickTrans() : 0;
        return getPlacedBlockJumpPrediction(profile, data, actionData, expectedJumpMotion, transTicks);
    }

    public static double getPlacedBlockJumpPrediction(Profile profile, MovementData data, ActionData actionData, double expectedJumpMotion, int transTicks) {
        if (actionData == null || data == null || data.getLocation() == null) return Double.NaN;

        int ticks = Math.min(5 + transTicks, actionData.getBlockPlacePredictionTicks());
        if (!actionData.hasRecentConfirmedUnderPlace(ticks) || !isHorizontallyOverPlacedBlock(profile, data, actionData, transTicks)) return Double.NaN;

        double topY = Double.isFinite(actionData.getLastConfirmedUnderPlaceTopY())
                ? actionData.getLastConfirmedUnderPlaceTopY()
                : actionData.getLastConfirmedUnderPlaceY() == Integer.MIN_VALUE
                ? Double.NaN : actionData.getLastConfirmedUnderPlaceY() + 1.0D;
        double lastY = data.getLastLocation() != null && Double.isFinite(data.getLastLocation().getY())
                ? data.getLastLocation().getY() : data.getLocation().getY() - data.getDeltaY();
        if (!Double.isFinite(topY) || !Double.isFinite(lastY)) return Double.NaN;

        double tolerance = getPlacedBlockCollisionTolerance(profile, transTicks);
        boolean wasOnPlacedBlock = Math.abs(lastY - topY) <= tolerance
                && (data.isLastOnGround() || data.isLastServerGround() || data.isLastPositionYGround() || data.getCustomAirTicks() <= 1);
        return wasOnPlacedBlock && data.getDeltaY() > 0.0D
                && data.getCustomAirTicks() <= 2 + transTicks
                ? expectedJumpMotion : Double.NaN;
    }

    public static PredictionResult selectGravityPrediction(Profile profile, MovementData data, ActionData actionData, double normalPrediction, boolean allowJump) {
        return selectGravityPrediction(profile, data, actionData, normalPrediction, allowJump, getExpectedJumpMotion(profile));
    }

    public static PredictionResult selectGravityPrediction(Profile profile, MovementData data, ActionData actionData, double normalPrediction, boolean allowJump, double expectedJumpMotion) {
        int transTicks = profile != null && profile.getConnectionData() != null ? profile.getConnectionData().getClientTickTrans() : 0;
        return selectGravityPrediction(profile, data, actionData, normalPrediction, allowJump, expectedJumpMotion, transTicks);
    }

    public static PredictionResult selectGravityPrediction(Profile profile, MovementData data, ActionData actionData, double normalPrediction, boolean allowJump, double expectedJumpMotion, int transTicks) {
        double actual = data.getDeltaY();
        PredictionResult best = new PredictionResult(normalPrediction, "normal", Math.abs(actual - normalPrediction));
        double placedLanding = getPlacedBlockLandingPrediction(profile, data, actionData, normalPrediction, transTicks);

        if (Double.isFinite(placedLanding)) {
            double offset = Math.abs(actual - placedLanding);
            if (offset + 1.0E-7D < best.offset && offset <= getPlacedBlockCollisionTolerance(profile, transTicks) + 0.015D) {
                best = new PredictionResult(placedLanding, "placedBlockLanding", offset);
            }
        }

        if (allowJump) {
            double placedJump = getPlacedBlockJumpPrediction(profile, data, actionData, expectedJumpMotion, transTicks);
            if (Double.isFinite(placedJump)) {
                double offset = Math.abs(actual - placedJump);
                double launchTolerance = profile != null && profile.isBedrockPlayer() ? 0.090D : 0.060D;
                if (offset + 1.0E-7D < best.offset && offset <= launchTolerance) {
                    best = new PredictionResult(placedJump, "placedBlockJump", offset);
                }
            }
        }
        return best;
    }

    public static boolean isLegacyTower(Profile profile, MovementData md, double deltaY) {
        if (profile == null || md == null) return false;
        return profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && !md.isOnGround()
                && Math.abs(deltaY - LEGACY_TOWER_MOTION) < 1E-6
                && profile.getActionData().hasRecentUnderPlaceSupport(20 + (profile.getConnectionData().getClientTickTrans() * 2));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static double readBukkitAttribute(Profile profile, String... names) {
        try {
            Class<?> attributeClass = Class.forName("org.bukkit.attribute.Attribute");
            Object attribute = null;

            for (String name : names) {
                try {
                    attribute = attributeClass.getField(name).get(null);
                } catch (Throwable ignored) {
                    if (attributeClass.isEnum()) {
                        try {
                            attribute = Enum.valueOf((Class<? extends Enum>) attributeClass, name);
                        } catch (Throwable ignoredAgain) {
                        }
                    }
                }

                if (attribute != null) {
                    break;
                }
            }

            if (attribute == null || profile == null || profile.getPlayer() == null) {
                return Double.NaN;
            }

            Object instance = profile.getPlayer().getClass()
                    .getMethod("getAttribute", attributeClass)
                    .invoke(profile.getPlayer(), attribute);

            if (instance == null) {
                return Double.NaN;
            }

            Object value = instance.getClass().getMethod("getValue").invoke(instance);
            return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    @Getter
    public static class GravityMotion {
        public final double displacementY;
        public final double terminalDY;

        public GravityMotion(double displacementY, double terminalDY) {
            this.displacementY = displacementY;
            this.terminalDY = terminalDY;
        }

    }

    public static double predictGravityDY(double previousDY, double gravity) {
        if (!Double.isFinite(previousDY)) previousDY = 0.0D;
        double prediction = (previousDY - gravity) * AIR_DRAG;
        if (Math.abs(prediction) < 0.003D) prediction = 0.0D;
        return Double.isFinite(prediction) ? prediction : 0.0D;
    }

    public static GravityMotion simulateGravity(double previousDY, double gravity, int ticks) {
        double velocity = Double.isFinite(previousDY) ? previousDY : 0.0D;
        double displacement = 0.0D;
        int simulatedTicks = Math.max(1, ticks);

        for (int i = 0; i < simulatedTicks; i++) {
            velocity = predictGravityDY(velocity, gravity);
            displacement += velocity;
        }
        return new GravityMotion(displacement, velocity);
    }

    public static double getTerminalVelocity(double gravity) {
        if (gravity <= 1.0E-9D) return -64.0D;
        return -(gravity * AIR_DRAG) / (1.0D - AIR_DRAG);
    }

    public static boolean isVanillaMicroFallTransition(double dy, double lastDy, double expectedDY, double doubleGravityDY) {
        boolean expectedMicro = Math.abs(expectedDY - VANILLA_MICRO_FALL) <= 1.0E-9D;
        boolean currentVanillaNeg = Math.abs(dy - -0.07840000152587834D) <= 1.0E-8D;
        boolean lastSmallPositive = lastDy > 0.0D && lastDy <= 0.085D;
        boolean doubleGravityNear = Math.abs(doubleGravityDY - -0.07840000152587834D) <= 0.006D
                || Math.abs(doubleGravityDY + 0.07544406518949479D) <= 0.006D;
        return expectedMicro && currentVanillaNeg && lastSmallPositive && doubleGravityNear;
    }

    public static PredictionResult getRecoveredGravityPhasePrediction(Profile profile, MovementData data, double previousDY, double currentDY, double fallDistance, double jumpMotion, double gravity) {
        if (data == null || data.isOnGround() || Math.abs(previousDY) > 1.0E-6D || currentDY >= -0.070D || fallDistance <= 0.0D) {
            return null;
        }

        double motionTolerance = profile != null && profile.isBedrockPlayer() ? 0.025D : 1.0E-5D;
        double distanceTolerance = profile != null && profile.isBedrockPlayer() ? 0.090D : 0.015D;
        double[] startingVelocities = {0.0D, jumpMotion};
        String[] predictionTypes = {"recovered-edge-fall", "recovered-jump-fall"};
        PredictionResult best = null;

        for (int trajectory = 0; trajectory < startingVelocities.length; trajectory++) {
            double velocity = startingVelocities[trajectory];
            double accumulatedBefore = 0.0D;

            for (int gravityStep = 1; gravityStep <= 20; gravityStep++) {
                velocity = predictGravityDY(velocity, gravity);
                double motionOffset = Math.abs(currentDY - velocity);
                double distanceOffset = Math.abs(fallDistance - accumulatedBefore);

                if (gravityStep >= 2 && motionOffset <= motionTolerance && distanceOffset <= distanceTolerance) {
                    double combinedOffset = motionOffset + distanceOffset;
                    if (best == null || combinedOffset < best.offset) {
                        best = new PredictionResult(velocity, predictionTypes[trajectory], combinedOffset);
                    }
                }
                accumulatedBefore += Math.max(0.0D, -velocity);
            }
        }
        return best;
    }

    public static double estimateTerminalDY(double displacementY, int ticks, boolean launchSample, GravityMotion expectedMotion, double expectedJumpMotion) {
        if (ticks <= 1 || launchSample) return displacementY;
        double displacementError = displacementY - expectedMotion.displacementY;
        double terminal = expectedMotion.terminalDY + ((2.0D * displacementError) / (ticks + 1.0D));
        double maximumUpward = Math.max(0.72D, expectedJumpMotion + 0.40D);
        if (!Double.isFinite(terminal)) terminal = displacementY / ticks;
        return Math.max(-64.0D, Math.min(maximumUpward, terminal));
    }

    public static int getLagCompensationTicks(Profile profile) {
        try {
            int ticks = Math.max(0, profile.getConnectionData().getClientTickTrans());
            return Math.min(profile.isBedrockPlayer() ? 8 : 6, ticks);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean isGhostLiquidWeb(Profile profile, int baseTicks, int transTicks) {
        try {
            int ghostTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );
            return ghostTicks < baseTicks + (transTicks * 4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isMovingPrediction(Profile profile, MovementData data, int transTicks) {
        if (data == null) return false;
        int bufferWindow = 10 + (transTicks * 2);
        if (data.getSincePredictUpwardsTicks() < bufferWindow
                || data.getSincePredictDownwardsTicks() < bufferWindow
                || data.getSincePredictUpwardsTicksWithoutMaterial() < bufferWindow) {
            return true;
        }
        return (data.isNearBoat() && Math.abs(data.getDeltaY()) < 0.7D)
                || data.isUnderBoat() || data.isLastUnderBoat();
    }

    public static boolean hasRecentGravityVelocity(Profile profile, int transTicks) {
        try {
            if (profile.getVelocityData().isTakingVelocity() && profile.getVelocityData().getVelocityTicks() <= 6) {
                return true;
            }
            int pendingWindow = 3 + transTicks;
            int confirmedWindow = 10 + transTicks;

            double pendingY = profile.getVelocityData().getPendingEntityVelocity() == null
                    ? 0.0D : profile.getVelocityData().getPendingEntityVelocity().getY();
            double entityY = profile.getVelocityData().getVelocityV();
            double explosionY = profile.getVelocityData().getExplosionKnockbackPacket() == null
                    ? 0.0D : profile.getVelocityData().getExplosionKnockbackPacket().getY();

            boolean pendingEntity = profile.getVelocityData().getEntityVelocityPacketTicks() <= pendingWindow && Math.abs(pendingY) > 1.0E-5D;
            boolean confirmedEntity = profile.getVelocityData().getEntityVelocityTicks() <= confirmedWindow && Math.abs(entityY) > 1.0E-5D;
            boolean pendingExplosion = profile.getVelocityData().getExplosionVelocityPacketTicks() <= pendingWindow;
            boolean confirmedExplosion = profile.getVelocityData().getExplosionVelocityTicks() <= confirmedWindow
                    && (Math.abs(explosionY) > 1.0E-5D || Math.abs(profile.getVelocityData().getTotalVerticalVelocity()) > 1.0E-5D);

            return pendingEntity || confirmedEntity || pendingExplosion || confirmedExplosion;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasRecentGravitySupportChange(Profile profile, int ticks) {
        ActionData actionData = profile.getActionData();
        return actionData != null && (actionData.hasRecentConfirmedUnderPlace(ticks)
                || actionData.hasRecentConfirmedUnderBreak(ticks)
                || actionData.hasRecentBlockUpdateUnder(ticks)
                || actionData.hasRecentPistonUpdate(ticks));
    }

    public static boolean isCommonGravityExempt(Profile profile, MovementData data, String checkName) {
        return isCommonGravityExempt(profile, data, checkName, false);
    }

    public static boolean isCommonGravityExempt(Profile profile, MovementData data, String checkName, boolean exemptSlowFalling) {
        if (data == null || profile == null) return true;
        if (profile.shouldCancel()) {
            ChatUtils.debugExempt("cancelled", checkName);
            return true;
        }

        int transTicks = getLagCompensationTicks(profile);

        // Vehicles
        if (profile.getExempt().isVehicle() || profile.isExempt().vehicle()
                || (profile.getPlayer() != null && profile.getPlayer().isInsideVehicle())
                || profile.getVehicleData().getSinceVehicleTicks() < 3 + transTicks) {
            ChatUtils.debugExempt("vehicle", checkName);
            return true;
        }

        // Teleportation & Respawn
        if (data.getSinceTeleportTicks() <= 3 || profile.isExempt().isTeleports() || !profile.isExempt().isRespawned()) {
            ChatUtils.debugExempt("teleport", checkName);
            return true;
        }

        // Reeling in
        if (profile.getExempt().isReelingIn()) {
            ChatUtils.debugExempt("reelingIn", checkName);
            return true;
        }

        // Slime / Bouncing
        if (profile.isBouncingOnSlime() || data.isOnSlime()) {
            ChatUtils.debugExempt("slimeBounce", checkName);
            return true;
        }
        if (data.getSinceNearSlimeTicks() <= 20 + (transTicks * 2) && data.getSinceNearPistonTicks() <= 20 + (transTicks * 2)) {
            ChatUtils.debugExempt("slimePiston", checkName);
            return true;
        }

        // Geysers push
        if (profile.getGeysersTracker().isBeingPushed()) {
            ChatUtils.debugExempt("geyserPush", checkName);
            return true;
        }

        // Gliding / Elytra
        if (data.isGlidingOrRecentlyGlided(30) || data.elytraMomentum() > 0) {
            ChatUtils.debugExempt("gliding", checkName);
            return true;
        }

        // Riptide
        if (data.isRiptiding() || data.getSinceRiptidingTicks() < 10 + transTicks) {
            ChatUtils.debugExempt("riptiding", checkName);
            return true;
        }

        // Chunk not loaded
        if (!CollisionUtils.isChunkLoaded(data.getLocation())) {
            ChatUtils.debugExempt("chunkNotLoaded", checkName);
            return true;
        }

        // Liquids & Web
        if (data.isNearWater() || data.isNearLava() || data.isInsideWater() || data.isInsideLiquid()
                || data.isOnTopOfWater() || data.isBottomOfWater()
                || data.getSinceInsideWaterTicks() < 5 || data.getSinceNearWaterTicks() < 5) {
            ChatUtils.debugExempt("liquid", checkName);
            return true;
        }
        if (data.isNearWebs() || isGhostLiquidWeb(profile, 10, transTicks)) {
            ChatUtils.debugExempt("ghostLiquidWeb", checkName);
            return true;
        }

        // Blocks, Climbing & Ceiling
        if (data.isNearClimbable() || data.isClimb()) {
            ChatUtils.debugExempt("climbable", checkName);
            return true;
        }
        if (data.isNearBed() || data.isNearShulker() || data.isNearShulkerBox() || data.isOnHoney()
                || data.isNearHoney() || data.getMovingOnHoneyTicks() > 0 || data.isNearContact()
                || data.isNearBuggyBlock()) {
            ChatUtils.debugExempt("specialBlock", checkName);
            return true;
        }
        if (data.isUnderblock() || data.isLastUnderblock() || data.getSinceUnderblockTicks() <= 2
                || data.getMovingUnderblockTicks() > 0
                || data.isUnderBoat() || data.isLastUnderBoat() || data.getSinceUnderBoatTicks() <= 3) {
            ChatUtils.debugExempt("underBlock", checkName);
            return true;
        }
        if (data.getSinceOnGhostBlock() < 10 || profile.getBlockProcessor().isNearGhostBlock() || profile.getBlockProcessor().isUnderGhostBlock()) {
            ChatUtils.debugExempt("ghostBlock", checkName);
            return true;
        }
        if (data.getNearbyBlocksResult() != null && data.getNearbyBlocksResult().getBlockTypes().stream()
                .anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.BERRIES))) {
            ChatUtils.debugExempt("berries", checkName);
            return true;
        }
        if (profile.getBlockProcessor().isCancelledBlockPlacementExempt(5)
                || profile.getBlockProcessor().isCancelledBlockPlaceAbove(12 + (transTicks * 2))
                || profile.getActionData().getLastConfirmedUnderBreakTicks() < 5 + (transTicks * 2)) {
            ChatUtils.debugExempt("cancelledBlockPlace", checkName);
            return true;
        }
        if (data.getSincePowderSnowTicks() < 15 + (transTicks * 2)) {
            ChatUtils.debugExempt("powderSnow", checkName);
            return true;
        }

        // Effects, Damage & Velocity
        if (exemptSlowFalling && (profile.getPotionData().isHasSlowFalling() || profile.getPotionData().getSlowFallingTicks() > 0)) {
            ChatUtils.debugExempt("slowFalling", checkName);
            return true;
        }
        if (data.getSinceLevitationEffectTicks() < 10 && profile.getPotionData().getLevitationTicks() > 0) {
            ChatUtils.debugExempt("levitation", checkName);
            return true;
        }
        if (profile.getDamageData().hasAnyCause(IGNORED_DAMAGE_CAUSES, 6 + transTicks)) {
            ChatUtils.debugExempt("recentDamage", checkName);
            return true;
        }
        if (hasRecentGravityVelocity(profile, transTicks)) {
            ChatUtils.debugExempt("recentVelocity", checkName);
            return true;
        }
        if (isMovingPrediction(profile, data, transTicks)) {
            ChatUtils.debugExempt("movingPrediction", checkName);
            return true;
        }

        // Compatibility
        if (Config.Setting.COMPATIBILITY.getBoolean() && profile.getPlayer() != null) {
            ItemStack boots = profile.getPlayer().getInventory().getBoots();
            if (OtherUtility.itemContains(boots, "traveler") && data.isNearWall()) {
                ChatUtils.debugExempt("travelerStep", checkName);
                return true;
            }
        }

        return false;
    }
}
