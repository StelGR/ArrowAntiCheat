package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.utils.customutils.OtherUtility;

// this is simply start sprint disabler check, works almost perfectly

@Experimental
public class SpeedC extends Check {

    public SpeedC(Profile profile) {
        super(profile, CheckType.SPEED, "C", "Checks for correct non sprint motion");
    }

    double buffer1;

    double airLimit;
    double groundLimit;

    final double maxBuffer1 = 13;
    final double resetRate1 = 0.4;

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!(event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION))) {
            return;
        }

        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();

        if (profile.shouldCancel()
                || profile.isExempt().isTeleports()
                || movementData.getSinceGlidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()
                || profile.getPlayer().isDead()
                || movementData.isOnBoat()
                || movementData.isNearBoat()
                || movementData.isNearWater()
                || movementData.isInsideLiquid()) {
            buffer1 = 0;
            return;
        }

        if (profile.getExempt().isReelingIn()) {
            if (Config.Setting.DEBUG.getBoolean()) {
                OtherUtility.log("SpeedC: is Exempting (reelingIn)");
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

        if (isGround && isLastGround && deltaXZWF > groundLimit && basicInvalidState) {
            if (++buffer1 > maxBuffer1) {
                fail("Incorrect sprint (ground)",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZWF
                                + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + groundLimit
                                + "\nblockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                + "\nattributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributeBonus(profile)
                                + "\npotionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundPotionBonus(profile)
                                + "\ncomboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getGroundAttributePotionBonus(profile));
                buffer1 = Math.max( maxBuffer1 + 2, buffer1);
            }

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
        } else if (!isGround && !isLastGround && deltaXZWF > airLimit && basicInvalidState && fallDistance < 1) {
            if (++buffer1 > maxBuffer1) {
                fail("Incorrect sprint (air)",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZWF
                                + "\nexpected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + airLimit
                                + "\nblockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                                + "\nattributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributeBonus(profile)
                                + "\npotionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirPotionBonus(profile)
                                + "\ncomboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributePotionBonus(profile));

                buffer1 = Math.max(maxBuffer1 + 2, buffer1);
            }

            verbose(this.getClass().getSimpleName(), deltaXZWF, airLimit,
                    MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Air)\n * predicted " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZWF
                            + "\n * expected deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + airLimit
                            + "\n * expected deltaXZ (No Friction) " + MsgType.MAIN_THEME_COLOR.getMessage() + (airLimit / Math.max(0.0001, frictionMultiplier))
                            + "\n * blockFriction " + MsgType.MAIN_THEME_COLOR.getMessage() + blockFriction
                            + "\n * frictionMultiplier " + MsgType.MAIN_THEME_COLOR.getMessage() + frictionMultiplier
                            + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaY()
                            + "\n * airTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                            + "\n * isSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getActionData().isSprinting()
                            + "\n * attributeValue " + MsgType.MAIN_THEME_COLOR.getMessage() + MathUtil.getAttributeSpeed(profile, isSprinting)
                            + "\n * attributeBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributeBonus(profile)
                            + "\n * potionBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirPotionBonus(profile)
                            + "\n * comboBonus " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getAirAttributePotionBonus(profile)
                            + "\n * serverGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerGroundTicks());
        } else {
            buffer1 -= Math.min(buffer1, resetRate1);
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