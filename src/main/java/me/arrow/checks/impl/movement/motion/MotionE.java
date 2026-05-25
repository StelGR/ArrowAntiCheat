package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.Math.MathUtil;

// sample based water walking check, most of it though is detected by the ghostblock processor, so it's kinda useless
// it will not flag in water, only walking above it, and jumping bypasses this, so if you can bypass the ghostblock processor
// this becomes essentially useless

@Experimental
public class MotionE extends Check {
    public MotionE(Profile profile) {
        super(profile, CheckType.MOTION, "E", "Checks for Jesus hacks");
    }

    // keeps recent deltaY samples while on top of water
    SampleList<Double> samples = new SampleList<>(15);

    @Override
    public void handle(PacketSendEvent event) { }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            if (profile.shouldCancel()
                    || profile.getLastBlockPlaceCancelTimer().hasNotPassed(6)
                    || profile.getMovementData().isUnderblock()
                    || profile.getMovementData().isOnBoat()
                    || profile.getMovementData().isNearBoat()
                    || Arrow.getInstance().getNmsManager().getNmsInstance().isSwimming(profile.getPlayer())
                    || profile.getMovementData().getSinceRiptidingTicks() < 5
                    || profile.getActionData().getLastConfirmedUnderPlaceTicks() < 5
                    || profile.getMovementData().isNearBuggyBlock()) {
                return;
            }

            MovementData movementData = profile.getMovementData();

            int clientAirTicks = movementData.getClientAirTicks();
            int serverAirTicks = movementData.getCustomAirTicks();

            double deltaY = movementData.getDeltaY();
            double deltaXZ = movementData.getDeltaXZ();
            boolean inAir = movementData.isCustomInAir();
            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();

            boolean waterstate = profile.getMovementData().isOnTopOfWater();

            if (profile.getMovementData().isNearWater())
                verbose(this.getClass().getSimpleName(),clientAirTicks, serverAirTicks, "* Verbose\n * clientAir "+ clientAirTicks
                        + "\n * serverAir " + serverAirTicks
                        +"\n * sGround " + serverGround
                        +"\n * cGround " + clientGround
                        +"\n * air " + inAir
                        +"\n * inW " + profile.getMovementData().isInsideWater()
                        +"\n * onW " + profile.getMovementData().isOnTopOfWater()
                        +"\n * deltaY " + deltaY);

            if (profile.getActionData().getLastConfirmedUnderPlaceTicks() < 5) return;

            WaterWalking(waterstate, deltaY, deltaXZ, inAir, serverGround, clientGround, clientAirTicks, serverAirTicks);
        }
    }

    public void WaterWalking(boolean waterstate, double deltaY, double deltaXZ, boolean inAir, boolean serverGround, boolean clientGround, int clientAirTicks, int serverAirTicks) {
        boolean invalid = waterstate && deltaY == 0 && deltaXZ >= 0.05 && inAir;

        if (waterstate) samples.add(deltaY);

        if (samples.isCollected()) {
            double std = MathUtil.getStandardDeviation(samples);

            if (std == 0) {
                if (invalid && !profile.getMovementData().isBottomOfWater() && !profile.getMovementData().isInsideWater()) {
                    fail("Walking On Water?",
                            "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                    + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                    + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                                    + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\ninWater " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getMovementData().isInsideWater()
                                    + "\nonTopOfWater " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getMovementData().isOnTopOfWater());
                }
            } else {
                // non-constant sample sequence -> clear samples and continue. Bounce detection above still active.
                samples.clear();
            }
        }
    }
}
