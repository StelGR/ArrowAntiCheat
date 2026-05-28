package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.desync.DesyncType;
import me.arrow.utils.customutils.Math.MathUtil;

import java.util.Locale;

@Experimental
public class OmniSprintA extends Check {

    /*
     * Direction layout:
     *
     * 0 degrees   = W
     * 45 degrees  = W+A / W+D, allowed
     * 90 degrees  = pure A / D, invalid while sprinting
     * 180 degrees = pure S, invalid while sprinting
     */
    private static final double GROUND_INVALID_ANGLE = 78.0D;
    private static final double GROUND_HARD_INVALID_ANGLE = 112.5D;

    private static final double GROUND_MIN_FORWARD_DOT = 0.20D;
    private static final double GROUND_HARD_BACKWARD_DOT = -0.15D;

    /*
     * Air mode should not use only velocity.
     *
     * If a player jumps forward and turns, old momentum can make velocity look
     * sideways/backwards even though their current input is legal.
     *
     * So air mode estimates the player's horizontal input:
     *
     * input ~= currentDelta - lastDelta * airFriction
     */
    private static final double AIR_HORIZONTAL_FRICTION = 0.91D;

    private static final double AIR_INVALID_INPUT_ANGLE = 70.0D;
    private static final double AIR_HARD_INVALID_INPUT_ANGLE = 105.0D;

    private static final double AIR_MIN_INPUT_FORWARD_DOT = 0.30D;
    private static final double AIR_HARD_BACKWARD_INPUT_DOT = -0.10D;

    private static final double MIN_AIR_INPUT_XZ = 0.0035D;

    private static final double MIN_GROUND_DELTA_XZ = 0.075D;
    private static final double MIN_AIR_DELTA_XZ = 0.055D;

    private double groundBuffer;
    private double airBuffer;

    private int groundInvalidTicks;
    private int airInvalidTicks;

    private int sprintTicks;
    private int turnGraceTicks = 20;

    public OmniSprintA(Profile profile) {
        super(profile, CheckType.OMNISPRINT, "A", "Checks for omnisprint");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isPositionMovementPacket(event)) {
            return;
        }

        MovementData movementData = profile.getMovementData();
        RotationData rotationData = profile.getRotationData();
        ActionData actionData = profile.getActionData();

        double deltaX = movementData.getDeltaX();
        double deltaZ = movementData.getDeltaZ();
        double deltaXZ = movementData.getDeltaXZ();

        double lastDeltaX = movementData.getLastDeltaX();
        double lastDeltaZ = movementData.getLastDeltaZ();

        /*
         * Do NOT re-wrap PacketEvents here.
         *
         * RotationData already processed the packet and stored the raw yaw.
         * Re-wrapping the packet can throw readerIndex/writerIndex errors because
         * the packet buffer may already be consumed.
         */
        float yaw = rotationData.getYaw();

        MovementPredictionUtil.DirectionalMovement velocityDirection =
                MovementPredictionUtil.predictDirectionalMovement(deltaX, deltaZ, yaw);

        double airInputX = deltaX - (lastDeltaX * AIR_HORIZONTAL_FRICTION);
        double airInputZ = deltaZ - (lastDeltaZ * AIR_HORIZONTAL_FRICTION);
        double airInputXZ = Math.hypot(airInputX, airInputZ);

        MovementPredictionUtil.DirectionalMovement airInputDirection =
                MovementPredictionUtil.predictDirectionalMovement(airInputX, airInputZ, yaw);

        boolean sprinting = actionData.isSprinting();
        boolean lastSprinting = actionData.isLastSprinting();

        if (sprinting) {
            sprintTicks = Math.min(20, sprintTicks + 1);
        } else {
            sprintTicks = 0;
        }

        double baseSpeed = MathUtil.getBaseSpeed_2(profile.getPlayer());

        double minimumGroundMovement = Math.max(MIN_GROUND_DELTA_XZ, Math.min(0.18D, baseSpeed * 0.45D));
        double minimumAirMovement = Math.max(MIN_AIR_DELTA_XZ, Math.min(0.16D, baseSpeed * 0.35D));

        boolean largeTurn =
                rotationData.getDeltaYaw() > 45.0F
                        || rotationData.getYawAccel() > 60.0F
                        || MovementPredictionUtil.yawDifference(yaw, rotationData.getLastYaw()) > 45.0F;

        if (largeTurn) {
            turnGraceTicks = 0;
        } else {
            turnGraceTicks = Math.min(20, turnGraceTicks + 1);
        }

        boolean ground =
                movementData.isOnGround()
                        || movementData.isServerGround()
                        || movementData.getClientGroundTicks() > 0
                        || movementData.getServerGroundTicksPlus() > 0;

        boolean air =
                !ground
                        && movementData.getClientAirTicks() > 1
                        && movementData.getServerAirTicks() > 1;

        boolean hardExempt =
                !movementData.isPacketMoving()
                        || !velocityDirection.isMoving()

                        || movementData.isInsideWater()
                        || movementData.isNearWater()
                        || movementData.isNearWebs()
                        || movementData.isNearClimbable()
                        || movementData.isOnIce()
                        || movementData.isOnSlime()
                        || movementData.isOnSoulSand()
                        || movementData.isOnHoney()
                        || movementData.isNearBoat()
                        || movementData.isOnBoat()
                        || movementData.isNearWall()
                        || movementData.isColliding()
                        || movementData.getSinceCollideTicks() < 3
                        || movementData.getSinceOnGhostBlock() < 5 + profile.getConnectionData().getClientTickTrans()
                        || movementData.getSinceTeleportTicks() < 3 + profile.getConnectionData().getClientTickTrans()
                        || movementData.isRiptiding()
                        || movementData.getSinceRiptidingTicks() < 20
                        || movementData.getSinceGlidingTicks() < 20
                        || movementData.getSinceElytraEquipTicks() < 20

                        || profile.getVelocityData().isTakingVelocity()
                        || profile.getExempt().isVehicle()
                        || profile.getExempt().isTeleports()
                        || profile.shouldCancel()
                        || profile.isBouncingOnSlime()
                        || profile.getPlayer().isInsideVehicle()
                        || (
                        profile.getVehicleData().getSinceVehicleTicks() > 0
                                && profile.getVehicleData().getSinceVehicleTicks()
                                < 5 + profile.getConnectionData().getClientTickTrans()
                );

        if (hardExempt) {
            rewardAll(0.75D);
            return;
        }

        if (!sprinting && !lastSprinting) {
            groundInvalidTicks = 0;
            airInvalidTicks = 0;
            rewardAll(0.75D);
            return;
        }

        boolean groundImpossible =
                velocityDirection.getAbsoluteAngle() >= GROUND_INVALID_ANGLE
                        || velocityDirection.getDot() < GROUND_MIN_FORWARD_DOT;

        boolean groundHardImpossible =
                velocityDirection.getAbsoluteAngle() >= GROUND_HARD_INVALID_ANGLE
                        || velocityDirection.getDot() < GROUND_HARD_BACKWARD_DOT;

        boolean groundInvalid =
                ground
                        && deltaXZ > minimumGroundMovement
                        && sprinting
                        && lastSprinting
                        && sprintTicks > 1
                        && turnGraceTicks > 1
                        && groundImpossible;

        /*
         * Air detection:
         *
         * This checks the estimated input direction, not only the current velocity.
         * If the player is adding sideways/backwards movement force while sprinting,
         * this will buffer.
         */
        boolean usefulAirInput =
                airInputXZ > MIN_AIR_INPUT_XZ
                        && airInputDirection.isMoving();

        boolean airInputImpossible =
                airInputDirection.getAbsoluteAngle() >= AIR_INVALID_INPUT_ANGLE
                        || airInputDirection.getDot() < AIR_MIN_INPUT_FORWARD_DOT;

        boolean airInputHardImpossible =
                airInputDirection.getAbsoluteAngle() >= AIR_HARD_INVALID_INPUT_ANGLE
                        || airInputDirection.getDot() < AIR_HARD_BACKWARD_INPUT_DOT;

        boolean airInvalid =
                air
                        && deltaXZ > minimumAirMovement
                        && usefulAirInput
                        && sprinting
                        && lastSprinting
                        && sprintTicks > 2
                        && turnGraceTicks > 4
                        && airInputImpossible;

        verbose(this.getClass().getSimpleName(), Math.max(groundBuffer, airBuffer), 8,
                MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (OmniSprint)\n" +
                        " * velocitySector " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityDirection.getSector() +
                        "\nairInputSector " + MsgType.MAIN_THEME_COLOR.getMessage() + airInputDirection.getSector() +

                        "\nvelocityAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getSignedAngle()) +
                        "\nvelocityAbsAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getAbsoluteAngle()) +
                        "\nvelocityDot " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getDot()) +
                        "\nvelocityCross " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getCross()) +

                        "\nairInputX " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputX) +
                        "\nairInputZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputZ) +
                        "\nairInputXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputXZ) +
                        "\nairInputAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputDirection.getSignedAngle()) +
                        "\nairInputAbsAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputDirection.getAbsoluteAngle()) +
                        "\nairInputDot " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputDirection.getDot()) +
                        "\nairInputCross " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputDirection.getCross()) +

                        "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yaw) +
                        "\ndeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + format(rotationData.getDeltaYaw()) +
                        "\nyawAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(rotationData.getYawAccel()) +

                        "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(deltaXZ) +
                        "\nminimumGroundMovement " + MsgType.MAIN_THEME_COLOR.getMessage() + format(minimumGroundMovement) +
                        "\nminimumAirMovement " + MsgType.MAIN_THEME_COLOR.getMessage() + format(minimumAirMovement) +
                        "\nbaseSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(baseSpeed) +

                        "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + ground +
                        "\nair " + MsgType.MAIN_THEME_COLOR.getMessage() + air +
                        "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks() +
                        "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks() +
                        "\nturnGraceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + turnGraceTicks +
                        "\nsprintTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + sprintTicks +

                        "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting +
                        "\nlastSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + lastSprinting +

                        "\ngroundInvalid " + MsgType.MAIN_THEME_COLOR.getMessage() + groundInvalid +
                        "\nairInvalid " + MsgType.MAIN_THEME_COLOR.getMessage() + airInvalid +

                        "\ngroundBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(groundBuffer) +
                        "\nairBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airBuffer));

        handleGround(groundInvalid, groundHardImpossible);
        handleAir(airInvalid, airInputHardImpossible);

        if (airInputDirection.isForward()) return;

        if (groundBuffer > 8.0D) {
            fail("Sprinting in impossible direction",
                    "type " + MsgType.MAIN_THEME_COLOR.getMessage() + "ground" +
                            "\nsector " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityDirection.getSector() +
                            "\nangle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getSignedAngle()) +
                            "\nabsAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getAbsoluteAngle()) +
                            "\ndot " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getDot()) +
                            "\ncross " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getCross()) +
                            "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yaw) +
                            "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(deltaXZ) +
                            "\nbaseSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(baseSpeed) +
                            "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting +
                            "\nlastSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + lastSprinting);

            groundBuffer = 4.0D;
            groundInvalidTicks = 0;
            actionData.getDesync().fix(DesyncType.SPRINTING);
            return;
        }

        if (airBuffer > 6.5D) {
            fail("Sprinting in impossible air direction",
                    "type " + MsgType.MAIN_THEME_COLOR.getMessage() + "air" +
                            "\nvelocitySector " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityDirection.getSector() +
                            "\nairInputSector " + MsgType.MAIN_THEME_COLOR.getMessage() + airInputDirection.getSector() +
                            "\nvelocityAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getSignedAngle()) +
                            "\nvelocityAbsAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getAbsoluteAngle()) +
                            "\nvelocityDot " + MsgType.MAIN_THEME_COLOR.getMessage() + format(velocityDirection.getDot()) +
                            "\nairInputAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputDirection.getSignedAngle()) +
                            "\nairInputAbsAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputDirection.getAbsoluteAngle()) +
                            "\nairInputDot " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputDirection.getDot()) +
                            "\nairInputXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(airInputXZ) +
                            "\nyaw " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yaw) +
                            "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + format(deltaXZ) +
                            "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks() +
                            "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks() +
                            "\nsprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting +
                            "\nlastSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + lastSprinting);

            airBuffer = 3.0D;
            airInvalidTicks = 0;
            actionData.getDesync().fix(DesyncType.SPRINTING);
        }
    }

    private void handleGround(boolean invalid, boolean hard) {
        if (invalid) {
            groundInvalidTicks++;

            if (groundInvalidTicks > 1) {
                groundBuffer += hard ? 1.45D : 1.0D;
            } else {
                groundBuffer += 0.35D;
            }
        } else {
            groundInvalidTicks = Math.max(0, groundInvalidTicks - 1);
            groundBuffer = Math.max(0.0D, groundBuffer - 0.035D);
        }
    }

    private void handleAir(boolean invalid, boolean hard) {
        if (invalid) {
            airInvalidTicks++;

            if (airInvalidTicks > 2) {
                airBuffer += hard ? 1.15D : 0.90D;
            } else {
                airBuffer += 0.25D;
            }
        } else {
            airInvalidTicks = Math.max(0, airInvalidTicks - 1);
            airBuffer = Math.max(0.0D, airBuffer - 0.030D);
        }
    }

    private boolean isPositionMovementPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private void rewardAll(double amount) {
        groundBuffer = Math.max(0.0D, groundBuffer - amount);
        airBuffer = Math.max(0.0D, airBuffer - amount);

        groundInvalidTicks = Math.max(0, groundInvalidTicks - 1);
        airInvalidTicks = Math.max(0, airInvalidTicks - 1);
    }

    private String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }
}