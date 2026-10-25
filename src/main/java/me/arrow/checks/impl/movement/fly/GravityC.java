package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Set;

public class GravityC extends Check {

    Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = GravityUtility.IGNORED_DAMAGE_CAUSES;

    double bufferC;
    double lastOffset;

    public GravityC(Profile profile) {
        super(profile, CheckType.GRAVITY, "C", "Checks for impossible changes between consecutive vertical movements.");
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
            ActionData actionData = profile.getActionData();
            if (isExempt(movementData)) return;

            if (!profile.isBedrockPlayer()) {
                GravityPredictionC(movementData.getDeltaY(), movementData.getLastDeltaY(), movementData.isUnderblock(), movementData, actionData);
            }
        } finally {
            Profiler.stop("Gravity C", profiler);
        }
    }

    private boolean isExempt(MovementData md) {
        if (md == null || profile.getTick() < 120) {
            resetGravityBuffer();
            return true;
        }
        if (GravityUtility.isCommonGravityExempt(profile, md, "GravityC", true)) {
            resetGravityBuffer();
            return true;
        }
        if (md.getMovingOnIceTicks() > 0 || md.isNearWall() || md.isNearPane()) {
            resetGravityBuffer();
            return true;
        }
        return false;
    }

    private void GravityPredictionC(double deltaY, double lastDeltaY, boolean underBlock, MovementData md, ActionData actionData) {
        boolean clientGround = md.isOnGround();
        boolean serverGround = md.isServerGround();

        if (deltaY == 0.0D || serverGround) {
            lastOffset = 0.0D;
            return;
        }

        double jumpAmplifier = profile.getPotionData().getJumpAmplifier();
        double jumpStart = MoveUtils.getJumpMotion(profile);
        final double JUMP_TOL = 0.046D;

        double jumpTolDynamic = 0.06D + (jumpAmplifier * 0.02D);
        if (!clientGround && Math.abs(deltaY - jumpStart) <= jumpTolDynamic) {
            lastOffset = 0.0D;
            return;
        }



        final double G = 0.08D;
        final double DRAG = 0.9800000190734863D;
        final double EPS = 1.0E-8D;
        double normalPred1 = (lastDeltaY - G) * DRAG;
        if (Math.abs(normalPred1) < 0.005D) normalPred1 = 0.0D;
        GravityUtility.PredictionResult pred1Result = GravityUtility.selectGravityPrediction(profile, md, actionData, normalPred1, true);
        double pred1 = pred1Result.prediction;
        double off1 = Math.abs(deltaY - pred1);
        if (off1 < EPS) { lastOffset = off1; return; }

        double normalPred2 = (normalPred1 - G) * DRAG;
        if (Math.abs(normalPred2) < 0.005D) normalPred2 = 0.0D;
        GravityUtility.PredictionResult pred2Result = GravityUtility.selectGravityPrediction(profile, md, actionData, normalPred2, true);
        double pred2 = pred2Result.prediction;
        double off2 = Math.abs(deltaY - pred2);
        if (off2 < EPS) { lastOffset = off2; return; }

        final double VANILLA_MICRO = 0.003016261509046103D;
        final double VANILLA_NEG = -0.0784000015258789D;
        boolean microMatches = Math.abs(deltaY - VANILLA_MICRO) < 1.0E-12D
                && (Math.abs(pred1 - VANILLA_NEG) < 1.0E-6D
                || Math.abs(pred2 - VANILLA_NEG) < 1.0E-6D
                || Math.abs(pred1 - VANILLA_MICRO) < 1.0E-12D
                || Math.abs(pred2 - VANILLA_MICRO) < 1.0E-12D);
        if (microMatches && !clientGround) {
            lastOffset = Math.abs(deltaY - VANILLA_MICRO);
            return;
        }

        if (!clientGround && deltaY >= jumpStart - JUMP_TOL && deltaY <= jumpStart + JUMP_TOL
                && Math.abs(lastDeltaY) <= 1.0E-3D) {
            lastOffset = 0.0D;
            return;
        }

        final double LAND_NEG = -0.07840000152587834D;
        if (Math.abs(deltaY - LAND_NEG) <= 1.0E-6D && lastDeltaY < -0.15D
                && pred1 < -0.20D && pred2 < -0.20D && !clientGround) {
            double y = md.getLocation().getY();
            double frac = y - Math.floor(y + 1.0E-9D);
            if (frac < 0.12D || frac > 0.88D) {
                lastOffset = Math.abs(deltaY - LAND_NEG);
                return;
            }
        }

        lastOffset = Math.min(off1, off2);
        if (lastOffset == 0.2268933260512424D && deltaY == -0.07840000152587834D && !clientGround) return;

        if (GravityUtility.isLegacyTower(profile, md, deltaY)) return;

        String predictionType = off1 <= off2 ? pred1Result.type : pred2Result.type;

        String info = "offset " + MsgType.MAIN_THEME_COLOR.getMessage() + lastOffset
                + "\nprediction1 " + MsgType.MAIN_THEME_COLOR.getMessage() + pred1
                + "\nprediction2 " + MsgType.MAIN_THEME_COLOR.getMessage() + pred2
                + "\nnormalPrediction1 " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPred1
                + "\nnormalPrediction2 " + MsgType.MAIN_THEME_COLOR.getMessage() + normalPred2
                + "\npredictionType " + MsgType.MAIN_THEME_COLOR.getMessage() + predictionType
                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                + "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                + "\ntook Damage? " + MsgType.MAIN_THEME_COLOR.getMessage()
                + (profile.getDamageData().getLastCause() == null ? "No" : profile.getDamageData().getLastCause());
        if (deltaY != 0.0D) {
            verbose(this.getClass().getSimpleName(), bufferC, 3,
                    "Verbose (3)"
                            + "\n"+info);
        }

        if (!clientGround) {
            if (++bufferC > 2.0D) {
                fail("Not following Gravity",
                        info);
                bufferC = Math.max(5, bufferC);
            }
        } else {
            bufferC -= Math.min(bufferC, 0.025D);
        }
    }

    private void resetGravityBuffer() {
        bufferC = 0.0D;
    }
}
