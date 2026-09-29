package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.types.Check;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.util.Vector;

/** Horizontal Elytra acceleration prediction after the glide has settled. */
@Experimental
public class ElytraB extends Check {
    private static final double[] DIVE_SAMPLES = {0.0D, 0.5D, 1.0D, 1.5D};

    private Vector lastMove = new Vector();
    private double buffer;
    private int rocketGraceTicks;
    private int offAxisTicks;

    public ElytraB(Profile profile) {
        super(profile, CheckType.ELYTRA, "B", "Predicts impossible Elytra air acceleration");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.USE_ITEM)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            if (profile.getMovementData().isGlidingNow()) {
                rocketGraceTicks = Math.max(rocketGraceTicks, 45 + profile.getConnectionData().getClientTickTrans());
            }
            return;
        }
        if (!OtherUtility.isFlying(event.getPacketType())) return;

        MovementData data = profile.getMovementData();
        Vector move = new Vector(data.getDeltaX(), 0.0D, data.getDeltaZ());
        boolean invalidState = profile.shouldCancel()
                || profile.getPlayer() == null
                || profile.getPlayer().isDead()
                || profile.isExempt().isTeleports()
                || profile.isExempt().vehicle()
                || !data.isGlidingNow()
                || data.getCustomAirTicks() <= 13
                || data.getGlidingTicks() <= 5
                || data.isNearWater() || data.isInsideLiquid() || data.isNearLava()
                || data.getSinceBubbleTicks() <= 15
                || data.isNearWall() || data.isColliding() || data.isUnderblock()
                || data.isNearWebs() || data.isNearClimbable()
                || data.getSinceRiptidingTicks() <= 30
                || profile.getVelocityData().isTakingVelocity()
                || rocketGraceTicks > 0;

        if (invalidState) {
            reset(move);
            if (rocketGraceTicks > 0) rocketGraceTicks--;
            return;
        }

        float yawDegrees = profile.getRotationData().getYaw();
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(profile.getRotationData().getPitch());
        double pitchCos = Math.cos(pitch);
        double diveForce = Math.max(0.0D, -data.getLastDeltaY()) * pitchCos * pitchCos * 0.10D;
        Vector carry = lastMove.clone().multiply(0.99D);
        Vector forward = new Vector(-Math.sin(yaw), 0.0D, Math.cos(yaw));

        double closest = Double.MAX_VALUE;
        for (double sample : DIVE_SAMPLES) {
            Vector predicted = carry.clone().add(forward.clone().multiply(diveForce * sample));
            closest = Math.min(closest, move.distance(predicted));
        }

        double horizontalAccel = data.getDeltaXZ() - data.getLastDeltaXZ();
        MovementPredictionUtil.DirectionalMovement direction =
                MovementPredictionUtil.predictDirectionalMovement(data, yawDegrees);
        boolean stableYaw = profile.getRotationData().getDeltaYaw() < 2.5F
                && profile.getRotationData().getLastDeltaYaw() < 2.5F;
        boolean backwards = direction.isBackwards() && direction.getDot() < -0.45D;
        boolean sideways = direction.isSideways() && direction.getAbsoluteAngle() >= 85.0D;
        boolean offAxis = data.getDeltaXZ() > 0.50D && profile.getRotationData().getPitch() <= 20.0F
                && stableYaw && horizontalAccel > -0.0125D && (backwards || sideways);
        offAxisTicks = offAxis ? Math.min(20, offAxisTicks + 1) : Math.max(0, offAxisTicks - 2);
        double tolerance = 0.085D + Math.max(0.0D, -data.getLastDeltaY()) * 0.12D
                + Math.max(0.0D, Math.sin(pitch)) * 0.06D
                + profile.getConnectionData().getClientTickTrans() * 0.01D;
        boolean invalidAcceleration = horizontalAccel > 0.10D && closest > tolerance;
        boolean invalidDirection = offAxisTicks > 4;
        boolean invalid = invalidAcceleration || invalidDirection;
        String reason = invalidDirection ? "off-axis" : "acceleration";

        if (invalid) {
            buffer += invalidDirection ? 1.25D : Math.min(2.0D, Math.max(0.5D, (closest - tolerance) * 12.0D));
            if (buffer > 8.0D) {
                fail("Invalid Elytra acceleration",
                        "reason " + MsgType.MAIN_THEME_COLOR.getMessage() + reason
                                + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getCustomAirTicks()
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getDeltaXZ()
                                + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + data.getLastDeltaXZ()
                                + "\nacceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + horizontalAccel
                                + "\nprediction " + MsgType.MAIN_THEME_COLOR.getMessage() + closest
                                + "\ntolerance " + MsgType.MAIN_THEME_COLOR.getMessage() + tolerance
                                + "\ndiveForce " + MsgType.MAIN_THEME_COLOR.getMessage() + diveForce
                                + "\nsector " + MsgType.MAIN_THEME_COLOR.getMessage() + direction.getSector()
                                + "\nangle " + MsgType.MAIN_THEME_COLOR.getMessage() + direction.getSignedAngle()
                                + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getRotationData().getPitch());
                buffer = 4.0D;
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.25D);
        }

        verbose(getClass().getSimpleName(), buffer, 8,
                "airTicks " + data.getCustomAirTicks() + "\ndeltaXZ " + data.getDeltaXZ()
                        + "\nacceleration " + horizontalAccel + "\nprediction " + closest
                        + "\ntolerance " + tolerance + "\ndiveForce " + diveForce
                        + "\nsector " + direction.getSector() + "\nangle " + direction.getSignedAngle()
                        + "\noffAxisTicks " + offAxisTicks
                        + "\nrocketGrace " + rocketGraceTicks);
        lastMove = move;
    }

    private void reset(Vector move) {
        buffer = Math.max(0.0D, buffer - 0.5D);
        offAxisTicks = 0;
        lastMove = move;
    }
}
