package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Set;

import static me.arrow.utils.ChatUtils.debugExempt;


@Experimental
public class GravityA extends Check {

    Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = GravityUtility.IGNORED_DAMAGE_CAUSES;

    double bufferA;

    public GravityA(Profile profile) {
        super(profile, CheckType.GRAVITY, "A", "Checks whether a player's vertical movement follows normal gravity.");
    }

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

            if (isExempt(movementData)) return;

            GravityPredictionA(movementData, movementData.getDeltaY(), movementData.isOnGround(), actionData);
        } finally {
            Profiler.stop("Gravity A", profiler);
        }
    }

    private void GravityPredictionA(MovementData movementData, double deltaY, boolean onGround, ActionData actionData) {
        double normalPrediction = getPrediction(profile, deltaY);
        GravityUtility.PredictionResult predictionResult = GravityUtility.selectGravityPrediction(profile, movementData, actionData, normalPrediction, !onGround);
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
        

        if (!onGround) {
            if (totalUp > max && Math.abs(prediction) > max) {
                int requiredBuffer = profile.getPotionData().isHasSlowFalling() ? 4 : 2;

                if (++bufferA > requiredBuffer) {
                    fail("Not following Gravity",
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
                bufferA -= Math.min(bufferA, 0.025D);
            }
        }
    }



    private double getPrediction(Profile user, double deltaY) {
        MovementData md = user.getMovementData();
        double lastDeltaY = Double.isFinite(md.getLastDeltaY()) ? md.getLastDeltaY() : 0.0D;
        if (md.isOnGround()) return 0.0D;

//        if (profile.getMovementData().getSinceLevitationEffectTicks() < 10 && profile.getPotionData().getLevitationTicks() > 0) {
//            int amp = user.getPotionData().getLevitationAmplifier();
//            double levPerTick = (0.9D * (amp + 1)) / 20.0D;
//            double predicted = lastDeltaY + levPerTick;
//            return Double.isFinite(predicted) ? predicted : levPerTick;
//        }

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



    boolean isExempt(MovementData movementData) {
        if (GravityUtility.isCommonGravityExempt(profile, movementData, "GravityA")) {
            bufferA = 0.0D;
            return true;
        }
        if (movementData.isNearShelfMushroom() || movementData.isNearDripLeaf()) {
            debugExempt("specialPlant", "GravityA");
            bufferA = 0.0D;
            return true;
        }
        return false;
    }

}
