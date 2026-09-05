package me.arrow.checks.impl.movement.illegalmove;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.utils.customutils.OtherUtility;

// this is simply start sprint disabler check, works almost perfectly

// I need more feedback to ensure this has 0 falses, if that is the case, experimental tag will be removed

@Experimental
public class IllegalMoveC extends Check {

    public IllegalMoveC(Profile profile) {
        super(profile, CheckType.ILLEGALMOVE, "C", "Checks for correct non sprint motion");
    }

    double airBuffer, groundBuffer;

    double airLimit;
    double groundLimit;

    final double maxBuffer = 13;
    final double resetRate1 = 0.4;

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) {
            return;
        }

        long profiler = Profiler.start();

        try {


            MovementData movementData = profile.getMovementData();
            ActionData actionData = profile.getActionData();

            if (profile.shouldCancel()
                    || movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4)
                    || movementData.getSinceGlidingTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)
                    || profile.getPlayer().isDead()
                    || movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isNearWater()
                    || movementData.isInsideLiquid()) {
                airBuffer = 0;
                groundBuffer = 0;
                return;
            }

            if (profile.getActionData().hasRecentPistonUpdate(5 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMoveC: is Exempting (Piston Update)");
                return;
            }

            if (profile.getExempt().isReelingIn()) {
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("IllegalMoveC: is Exempting (reelingIn)");
                }
                return;
            }

            double blockFriction = movementData.getFrictionFactor();

            boolean isGround = movementData.isOnGround();
            boolean isLastGround = movementData.isLastOnGround();

            double deltaXZWF = movementData.getDeltaXZ() * blockFriction;

            boolean isSprinting = actionData.isSprinting();
            boolean isLastSprinting = actionData.isLastSprinting();
            boolean isLastLastSprinting = actionData.isLastLastSprinting();

            double fallDistance = profile.getPlayer().getFallDistance();
            boolean nearWall = movementData.isNearWall();

            double baseAirLimit = 0.221D;
            double baseGroundLimit = 0.24D;

            double frictionMultiplier = getLimitFrictionMultiplier(blockFriction);

            airLimit = baseAirLimit * frictionMultiplier;
            groundLimit = baseGroundLimit * frictionMultiplier;

            airLimit += SpeedUtilities.getAirSpeedLimitBonus(profile) * frictionMultiplier;
            groundLimit += SpeedUtilities.getGroundSpeedLimitBonus(profile) * frictionMultiplier;

            double velocityContribution = Math.max(profile.getVelocityData().getTotalHorizontalVelocity(), 0);

            airLimit += velocityContribution + 0.05D;
            groundLimit += velocityContribution + 0.05D;

            if (movementData.getSinceSpeedPotionEffectTicks() < 15) {
                airLimit += 0.05D + (0.01D * SpeedUtilities.getSpeedPotionLevel(profile));
                groundLimit += 0.05D + (0.01D * SpeedUtilities.getSpeedPotionLevel(profile));
            }

            if (movementData.getSinceCollideTicks() < 10 + profile.getConnectionData().getClientTickTrans()) {
                airLimit += 0.08D;
                groundLimit += 0.08D;
            }

            if (movementData.getSinceRiptidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()) {
                return;
            }

            boolean velocityActive = profile.getVelocityData().getTotalHorizontalVelocity() != 0
                    || profile.getVelocityData().getTotalVerticalVelocity() != 0;

            boolean basicInvalidState = !isSprinting
                    && !isLastSprinting
                    && !isLastLastSprinting
                    && !nearWall
                    && !velocityActive
                    && !profile.getPlayer().isInsideVehicle()
                    && actionData.getSinceLastSprintingTicks() > 20;

            if (isGround) {
                verbose(this.getClass().getSimpleName(), deltaXZWF, groundLimit,
                        MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Ground)\n * predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZWF
                                + "\n * expected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + groundLimit
                                + "\n * expected deltaXZ (No Friction) " + MsgType.MAIN_THEME_COLOR.getMessage() + (groundLimit / Math.max(0.0001, frictionMultiplier))
                                + "\n * blockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                + "\n * frictionMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + frictionMultiplier
                                + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaY()
                                + "\n * airTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                                + "\n * isSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting()
                                + "\n * attributeValue " + MsgType.MAIN_THEME_COLOR.getMessage() + MathUtil.getAttributeSpeed(profile, isSprinting)
                                + "\n * attributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributeBonus(profile)
                                + "\n * potionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundPotionBonus(profile)
                                + "\n * comboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributePotionBonus(profile)
                                + "\n * serverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerGroundTicks());
            } else {
                verbose(this.getClass().getSimpleName(), deltaXZWF, groundLimit,
                        MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Air)\n * predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZWF
                                + "\n * expected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + groundLimit
                                + "\n * expected deltaXZ (No Friction) " + MsgType.MAIN_THEME_COLOR.getMessage() + (groundLimit / Math.max(0.0001, frictionMultiplier))
                                + "\n * blockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                + "\n * frictionMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + frictionMultiplier
                                + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaY()
                                + "\n * airTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                                + "\n * isSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting()
                                + "\n * attributeValue " + MsgType.MAIN_THEME_COLOR.getMessage() + MathUtil.getAttributeSpeed(profile, isSprinting)
                                + "\n * attributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributeBonus(profile)
                                + "\n * potionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundPotionBonus(profile)
                                + "\n * comboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributePotionBonus(profile)
                                + "\n * serverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerGroundTicks());
            }

            if (isGround && isLastGround && deltaXZWF > groundLimit && basicInvalidState) {
                if (++groundBuffer > maxBuffer) {
                    fail("Incorrect sprint (ground)",
                            "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZWF
                                    + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + groundLimit
                                    + "\nblockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                    + "\nattributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributeBonus(profile)
                                    + "\npotionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundPotionBonus(profile)
                                    + "\ncomboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributePotionBonus(profile));
                    groundBuffer = Math.max(maxBuffer + 2, groundBuffer);
                }
            } else {
                groundBuffer -= Math.min(groundBuffer, resetRate1);
            }
            if (!isGround && !isLastGround && deltaXZWF > airLimit && basicInvalidState && fallDistance < 1) {
                if (++airBuffer > maxBuffer) {
                    fail("Incorrect sprint (air)",
                            "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZWF
                                    + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + airLimit
                                    + "\nblockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                    + "\nattributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributeBonus(profile)
                                    + "\npotionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirPotionBonus(profile)
                                    + "\ncomboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributePotionBonus(profile));

                    airBuffer = Math.max(maxBuffer + 2, airBuffer);
                }
            } else {
                airBuffer -= Math.min(airBuffer, resetRate1);
            }
        } finally {
            Profiler.stop("IllegalMove C", profiler);
        }
    }

    private double getLimitFrictionMultiplier(double blockFriction) {
        if (SpeedUtilities.getMovementSpeedAttribute(profile) <= 0.13D
                && SpeedUtilities.getSpeedPotionLevel(profile) <= 0) {
            return blockFriction;
        }

        return Math.max(0.91D, blockFriction);
    }
}