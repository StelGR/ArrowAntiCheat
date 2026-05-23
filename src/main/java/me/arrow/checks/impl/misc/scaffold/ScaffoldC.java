package me.arrow.checks.impl.misc.scaffold;

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

@Experimental
public class ScaffoldC extends Check {

    private static final int CONFIRMED_PLACE_CONTEXT_TICKS = 8;
    private static final int REQUIRED_CONFIRMED_BRIDGE_BLOCKS = 7;
    private static final int BRIDGE_STREAK_TIMEOUT_TICKS = 28;

    private static final int VERTICAL_BRIDGE_EXEMPT_TICKS = 12;

    private static final double BACKWARD_FAST_SPEED = 0.112D;
    private static final double BACKWARD_SWITCH_MIN_SPEED = 0.075D;
    private static final double MAX_ALLOWED_SWITCH_LOSS = 0.010D;
    private static final double MAX_ALLOWED_SWITCH_ACCEL = 0.014D;

    private static final double UPWARD_HIGH_DELTA = 0.4205D;
    private static final double UPWARD_REPEAT_MIN = 0.105D;
    private static final double UPWARD_REPEAT_ACCEL = 0.014D;

    private static final double LEGIT_JUMP_MIN = 0.36D;
    private static final double LEGIT_JUMP_MAX = 0.43D;

    private int confirmedPlaceTicks = 1000;
    private int sinceBridgePlaceTicks = 1000;
    private int confirmedBridgeBlocks;

    private int lastConfirmedX = Integer.MIN_VALUE;
    private int lastConfirmedY = Integer.MIN_VALUE;
    private int lastConfirmedZ = Integer.MIN_VALUE;

    private int backwardsFastTicks;
    private int backwardsNoSlowTicks;
    private int backwardsSwitchTicks;
    private int upwardBadTicks;

    private int verticalBridgeTicks;
    private boolean lastConfirmedPlaceWasVertical;

    private MovementPredictionUtil.MovementSector lastSector = MovementPredictionUtil.MovementSector.STATIONARY;
    private double buffer;

    public ScaffoldC(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "C", "Checks for scaffold no-slowing");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isFlyingPacket(event)) {
            return;
        }

        confirmedPlaceTicks++;
        sinceBridgePlaceTicks++;

        if (verticalBridgeTicks > 0) {
            verticalBridgeTicks--;
        }

        lastConfirmedPlaceWasVertical = false;

        boolean countedConfirmedPlace = updateConfirmedBridgeBlocks();

        if (!isBaseContext()) {
            decayState();
            resetBridgeStreakIfExpired(true);
            return;
        }

        ActionData actionData = profile.getActionData();

        if (actionData == null) {
            decayState();
            resetBridgeStreakIfExpired(true);
            return;
        }

        if (actionData.isSneaking()) {
            decayState();
            resetBridgeStreak();
            return;
        }

        int trans = getClientTickTrans();

        if (!profile.isAirBridging(profile.getPlayer().getLocation())) {
            decayState();
            resetBridgeStreakIfExpired(sinceBridgePlaceTicks > BRIDGE_STREAK_TIMEOUT_TICKS + trans);
            return;
        }

        if (sinceBridgePlaceTicks > BRIDGE_STREAK_TIMEOUT_TICKS + trans) {
            decayState();
            resetBridgeStreak();
            return;
        }

        MovementData movementData = profile.getMovementData();
        RotationData rotationData = profile.getRotationData();

        MovementPredictionUtil.DirectionalMovement direction =
                MovementPredictionUtil.predictDirectionalMovement(movementData, rotationData);

        boolean recentConfirmedPlace = confirmedPlaceTicks <= CONFIRMED_PLACE_CONTEXT_TICKS + trans;
        boolean verticalBridgeContext = verticalBridgeTicks > 0;
        boolean legitJumpPlaceContext = recentConfirmedPlace && isLegitJumpPlaceMotion(movementData);

        if (verticalBridgeContext || legitJumpPlaceContext) {
            decayState();
            lastSector = direction.getSector();

            verbose(this.getClass().getSimpleName(), buffer, 3.0D,
                    "exempting vertical/jump bridge"
                            + "\nconfirmedBlocks " + confirmedBridgeBlocks
                            + "\nconfirmedPlaceTicks " + confirmedPlaceTicks
                            + "\nsinceBridgePlaceTicks " + sinceBridgePlaceTicks
                            + "\nverticalBridgeTicks " + verticalBridgeTicks
                            + "\nlastConfirmedVertical " + lastConfirmedPlaceWasVertical
                            + "\ndeltaY " + round(movementData.getDeltaY())
                            + "\nlastDeltaY " + round(movementData.getLastDeltaY())
                            + "\nclientAirTicks " + movementData.getClientAirTicks()
                            + "\ncountedConfirmedPlace " + countedConfirmedPlace
                            + "\nsector " + direction.getSector());
            return;
        }

        if (confirmedBridgeBlocks < REQUIRED_CONFIRMED_BRIDGE_BLOCKS) {
            decayState();
            lastSector = direction.getSector();

            verbose(this.getClass().getSimpleName(), confirmedBridgeBlocks, REQUIRED_CONFIRMED_BRIDGE_BLOCKS,
                    "waiting confirmed bridge blocks"
                            + "\nconfirmedBlocks " + confirmedBridgeBlocks
                            + "\nrequired " + REQUIRED_CONFIRMED_BRIDGE_BLOCKS
                            + "\nconfirmedPlaceTicks " + confirmedPlaceTicks
                            + "\nsinceBridgePlaceTicks " + sinceBridgePlaceTicks
                            + "\ncountedConfirmedPlace " + countedConfirmedPlace
                            + "\nsector " + direction.getSector());
            return;
        }

        if (!recentConfirmedPlace) {
            buffer = Math.max(0.0D, buffer - 0.04D);
            lastSector = direction.getSector();
            return;
        }

        boolean sneaking = actionData.isSneaking();
        int sinceSneaking = actionData.getSinceSneakingTicks();

        double deltaXZ = movementData.getDeltaXZ();
        double lastDeltaXZ = movementData.getLastDeltaXZ();
        double deltaY = movementData.getDeltaY();
        double lastDeltaY = movementData.getLastDeltaY();

        boolean backwards = direction.isBackwards();
        boolean backwardsNoSneak = backwards && !sneaking && sinceSneaking > 5;
        boolean fastBackwards = backwardsNoSneak && deltaXZ > BACKWARD_FAST_SPEED;
        boolean retainedBackwardSpeed = backwardsNoSneak
                && deltaXZ > BACKWARD_SWITCH_MIN_SPEED
                && lastDeltaXZ > BACKWARD_SWITCH_MIN_SPEED
                && deltaXZ >= lastDeltaXZ - MAX_ALLOWED_SWITCH_LOSS
                && movementData.getAccelXZ() < MAX_ALLOWED_SWITCH_ACCEL;

        boolean switchedBackwardSide = switchedBackwardSide(direction.getSector(), lastSector);
        boolean noSlowSwitch = switchedBackwardSide
                && backwardsNoSneak
                && deltaXZ > BACKWARD_SWITCH_MIN_SPEED
                && lastDeltaXZ > BACKWARD_SWITCH_MIN_SPEED
                && deltaXZ >= lastDeltaXZ - MAX_ALLOWED_SWITCH_LOSS
                && movementData.getAccelXZ() < MAX_ALLOWED_SWITCH_ACCEL;

        boolean upwardHigh = deltaY > UPWARD_HIGH_DELTA;
        boolean upwardRepeated = movementData.getClientAirTicks() > 2
                && deltaY > UPWARD_REPEAT_MIN
                && lastDeltaY > UPWARD_REPEAT_MIN
                && Math.abs(deltaY - lastDeltaY) < UPWARD_REPEAT_ACCEL;

        boolean upwardBad = recentConfirmedPlace
                && !isNormalJumpArc(movementData)
                && !lastConfirmedPlaceWasVertical
                && (upwardHigh || upwardRepeated);

        if (fastBackwards) {
            backwardsFastTicks++;
        } else {
            backwardsFastTicks = Math.max(0, backwardsFastTicks - 1);
        }

        if (retainedBackwardSpeed) {
            backwardsNoSlowTicks++;
        } else {
            backwardsNoSlowTicks = Math.max(0, backwardsNoSlowTicks - 1);
        }

        if (noSlowSwitch) {
            backwardsSwitchTicks++;
        } else {
            backwardsSwitchTicks = Math.max(0, backwardsSwitchTicks - 1);
        }

        if (upwardBad) {
            upwardBadTicks++;
        } else {
            upwardBadTicks = Math.max(0, upwardBadTicks - 1);
        }

        double suspicion = 0.0D;
        String reasons = "";

        if (backwardsFastTicks >= 5) {
            suspicion += 4.0D;
            reasons += "fast backwards without sneak; ";
        }

        if (backwardsNoSlowTicks >= 6) {
            suspicion += 4.5D;
            reasons += "backwards retained speed; ";
        }

        if (backwardsSwitchTicks >= 3) {
            suspicion += 4.5D;
            reasons += "backwards side switch no-slow; ";
        }

        if (upwardBadTicks >= 3) {
            suspicion += 5.0D;
            reasons += "weird upward place motion; ";
        }

        if (direction.getAbsoluteAngle() > 135.0D
                && deltaXZ > 0.13D
                && !sneaking
                && sinceSneaking > 8) {
            suspicion += 2.0D;
            reasons += "pure backwards high speed; ";
        }

        if (suspicion >= 6.5D) {
            if (++buffer > 3.0D) {
                fail("Not slowing down",
                        "suspicion " + MsgType.MAIN_THEME_COLOR.getMessage() + round(suspicion)
                                + "\nconfirmedBlocks " + MsgType.MAIN_THEME_COLOR.getMessage() + confirmedBridgeBlocks
                                + "\nconfirmedPlaceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + confirmedPlaceTicks
                                + "\nsinceBridgePlaceTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + sinceBridgePlaceTicks
                                + "\nverticalBridgeTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + verticalBridgeTicks
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + round(deltaXZ)
                                + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + round(lastDeltaXZ)
                                + "\naccelXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + round(movementData.getAccelXZ())
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + round(deltaY)
                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + round(lastDeltaY)
                                + "\nangle " + MsgType.MAIN_THEME_COLOR.getMessage() + round(direction.getSignedAngle())
                                + "\nsector " + MsgType.MAIN_THEME_COLOR.getMessage() + direction.getSector()
                                + "\nsneaking " + MsgType.MAIN_THEME_COLOR.getMessage() + sneaking
                                + "\nsinceSneak " + MsgType.MAIN_THEME_COLOR.getMessage() + sinceSneaking
                                + "\nbackFast " + MsgType.MAIN_THEME_COLOR.getMessage() + backwardsFastTicks
                                + "\nbackNoSlow " + MsgType.MAIN_THEME_COLOR.getMessage() + backwardsNoSlowTicks
                                + "\nbackSwitch " + MsgType.MAIN_THEME_COLOR.getMessage() + backwardsSwitchTicks
                                + "\nupward " + MsgType.MAIN_THEME_COLOR.getMessage() + upwardBadTicks
                                + "\nreasons " + MsgType.MAIN_THEME_COLOR.getMessage() + reasons.trim());

                buffer = Math.max(0.0D, buffer - 1.0D);
                backwardsFastTicks = 0;
                backwardsNoSlowTicks = 0;
                backwardsSwitchTicks = 0;
                upwardBadTicks = 0;
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.08D);
        }

        verbose(this.getClass().getSimpleName(), buffer, 3.0D,
                "suspicion " + round(suspicion)
                        + "\nconfirmedBlocks " + confirmedBridgeBlocks
                        + "\nconfirmedPlaceTicks " + confirmedPlaceTicks
                        + "\nsinceBridgePlaceTicks " + sinceBridgePlaceTicks
                        + "\nverticalBridgeTicks " + verticalBridgeTicks
                        + "\ndeltaXZ " + round(deltaXZ)
                        + "\nlastDeltaXZ " + round(lastDeltaXZ)
                        + "\naccelXZ " + round(movementData.getAccelXZ())
                        + "\ndeltaY " + round(deltaY)
                        + "\nlastDeltaY " + round(lastDeltaY)
                        + "\nangle " + round(direction.getSignedAngle())
                        + "\nsector " + direction.getSector()
                        + "\nbackFast " + backwardsFastTicks
                        + "\nbackNoSlow " + backwardsNoSlowTicks
                        + "\nbackSwitch " + backwardsSwitchTicks
                        + "\nupward " + upwardBadTicks);

        lastSector = direction.getSector();
    }

    private boolean updateConfirmedBridgeBlocks() {
        ActionData actionData = profile.getActionData();

        if (actionData == null) {
            return false;
        }

        int trans = getClientTickTrans();

        if (!actionData.hasRecentConfirmedUnderPlace(2 + trans)) {
            return false;
        }

        int x = actionData.getLastConfirmedUnderPlaceX();
        int y = actionData.getLastConfirmedUnderPlaceY();
        int z = actionData.getLastConfirmedUnderPlaceZ();

        if (x == lastConfirmedX && y == lastConfirmedY && z == lastConfirmedZ) {
            confirmedPlaceTicks = 0;
            sinceBridgePlaceTicks = 0;
            return false;
        }

        boolean hadPreviousPlace = lastConfirmedY != Integer.MIN_VALUE;
        boolean elevatedPlace = hadPreviousPlace && y > lastConfirmedY;

        lastConfirmedX = x;
        lastConfirmedY = y;
        lastConfirmedZ = z;
        confirmedPlaceTicks = 0;
        sinceBridgePlaceTicks = 0;

        if (elevatedPlace) {
            lastConfirmedPlaceWasVertical = true;
            verticalBridgeTicks = Math.max(verticalBridgeTicks, VERTICAL_BRIDGE_EXEMPT_TICKS + trans);
            resetDetectionCountersOnly();
            confirmedBridgeBlocks = 0;
            return false;
        }

        confirmedBridgeBlocks = Math.min(40, confirmedBridgeBlocks + 1);
        return true;
    }

    private boolean isBaseContext() {
        if (profile == null || profile.getPlayer() == null || profile.getMovementData() == null || profile.getRotationData() == null) {
            return false;
        }

        if (profile.getTick() < 100 || profile.shouldCancel()) {
            return false;
        }

        try {
            if (profile.isExempt().isTeleports()) {
                return false;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (profile.getVelocityData().isTakingVelocity()) {
                return false;
            }
        } catch (Throwable ignored) {
        }

        if (profile.getPlayer().isInsideVehicle() || profile.getPlayer().getAllowFlight()) {
            return false;
        }

        MovementData movementData = profile.getMovementData();

        if (movementData.getDeltaXZ() < 0.035D && Math.abs(movementData.getDeltaY()) < 0.025D) {
            return false;
        }

        return !movementData.isNearWater()
                && !movementData.isNearLava()
                && !movementData.isNearWebs()
                && !movementData.isNearClimbable()
                && !movementData.isNearHoney()
                && !movementData.isOnSlime()
                && !movementData.isOnIce()
                && !movementData.isNearBoat()
                && !movementData.isOnBoat()
                && movementData.getSinceExplosionTicks() >= 20
                && movementData.getSinceTeleportTicks() >= 5;
    }

    private boolean isLegitJumpPlaceMotion(MovementData movementData) {
        if (movementData == null) {
            return false;
        }

        if (verticalBridgeTicks > 0) {
            return true;
        }

        return isNormalJumpArc(movementData)
                && movementData.getClientAirTicks() <= 8
                && movementData.getDeltaY() > 0.0D;
    }

    private boolean isNormalJumpArc(MovementData movementData) {
        if (movementData == null) {
            return false;
        }

        double deltaY = movementData.getDeltaY();

        if (movementData.isLastOnGround() && !movementData.isOnGround()) {
            return deltaY > LEGIT_JUMP_MIN && deltaY < LEGIT_JUMP_MAX;
        }

        return movementData.getClientAirTicks() <= 8
                && deltaY > 0.0D
                && deltaY < LEGIT_JUMP_MAX
                && movementData.getLastDeltaY() > -0.10D;
    }

    private boolean switchedBackwardSide(MovementPredictionUtil.MovementSector current,
                                         MovementPredictionUtil.MovementSector last) {
        return (last == MovementPredictionUtil.MovementSector.BACKWARD_LEFT
                && current == MovementPredictionUtil.MovementSector.BACKWARD_RIGHT)
                || (last == MovementPredictionUtil.MovementSector.BACKWARD_RIGHT
                && current == MovementPredictionUtil.MovementSector.BACKWARD_LEFT);
    }

    private void resetBridgeStreakIfExpired(boolean reset) {
        if (reset) {
            resetBridgeStreak();
        }
    }

    private void resetBridgeStreak() {
        confirmedBridgeBlocks = 0;
        confirmedPlaceTicks = 1000;
        sinceBridgePlaceTicks = 1000;
        lastConfirmedX = Integer.MIN_VALUE;
        lastConfirmedY = Integer.MIN_VALUE;
        lastConfirmedZ = Integer.MIN_VALUE;
        verticalBridgeTicks = 0;
        lastConfirmedPlaceWasVertical = false;
        resetDetectionCountersOnly();
    }

    private void resetDetectionCountersOnly() {
        backwardsFastTicks = 0;
        backwardsNoSlowTicks = 0;
        backwardsSwitchTicks = 0;
        upwardBadTicks = 0;
        lastSector = MovementPredictionUtil.MovementSector.STATIONARY;
        buffer = Math.max(0.0D, buffer - 0.5D);
    }

    private void decayState() {
        backwardsFastTicks = Math.max(0, backwardsFastTicks - 1);
        backwardsNoSlowTicks = Math.max(0, backwardsNoSlowTicks - 1);
        backwardsSwitchTicks = Math.max(0, backwardsSwitchTicks - 1);
        upwardBadTicks = Math.max(0, upwardBadTicks - 1);
        buffer = Math.max(0.0D, buffer - 0.10D);
    }

    private int getClientTickTrans() {
        try {
            return Math.max(0, profile.getConnectionData().getClientTickTrans());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private String round(double value) {
        return String.format("%.4f", value);
    }
}
