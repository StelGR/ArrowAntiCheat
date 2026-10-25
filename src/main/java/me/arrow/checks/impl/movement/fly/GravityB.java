package me.arrow.checks.impl.movement.fly;



import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Set;

public class GravityB extends Check {

    Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = GravityUtility.IGNORED_DAMAGE_CAUSES;

    double bufferB;

    public GravityB(Profile profile) {
        super(profile, CheckType.GRAVITY, "B", "Checks for invalid gravity");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) return;

        long profiler = Profiler.start();

        try {

            MovementData movementData = profile.getMovementData();

            if (!profile.isBedrockPlayer()) GravityPredictionB(movementData, movementData.getDeltaY());
        } finally {
            Profiler.stop("Gravity B", profiler);
        }
    }

    private void GravityPredictionB(MovementData movementData, double deltaY) {

        if (isExempt(movementData)) {
            bufferB = 0;
            return;
        }

        double lastDeltaY = movementData.getLastDeltaY();
        boolean isClientGround = movementData.isOnGround();
        boolean isServerGround = movementData.isServerGround();
        boolean isServerYGround = movementData.isServerYGround();
        boolean exempt = exempt("jumpStart", Math.abs(deltaY - MoveUtils.getJumpMotion(profile)) <= 1.0E-9D);

        if (exempt("legacyTower", GravityUtility.isLegacyTower(profile, movementData, deltaY))) return;

        if (!isClientGround && !(profile.getVelocityData().isTakingVelocity()
                && profile.getVelocityData().getVelocityTicks() <= 6)) {
            double normalPrediction = (lastDeltaY - 0.08D) * 0.9800000190734863D;

            GravityUtility.PredictionResult predictionResult = GravityUtility.selectGravityPrediction(profile, movementData, profile.getActionData(), normalPrediction, true);
            double prediction = predictionResult.prediction;

            if (exempt("predictUpwards", movementData.getSincePredictUpwardsTicks() < 10)) {
                bufferB -= Math.min(bufferB, 0.25D);
                return;
            }
            if (exempt("predictDownwards", movementData.getSincePredictDownwardsTicks() < 10)) {
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
                    fail("Not following Gravity",
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



    boolean isExempt(MovementData movementData) {
        if (profile.getTick() < 120) return true;
        if (GravityUtility.isCommonGravityExempt(profile, movementData, "GravityB")) {
            bufferB = 0.0D;
            return true;
        }
        return false;
    }
}
