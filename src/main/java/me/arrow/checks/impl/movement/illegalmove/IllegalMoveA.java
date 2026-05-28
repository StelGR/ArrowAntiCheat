package me.arrow.checks.impl.movement.illegalmove;

// impossible speed check and basic step check, i know it can be bypassed, if you can make a list in the material list for ALL minecraft blocks that are
// 1:1 full sized, not bigger or smaller, then you can make it adapt the limit to > 0.5 when you are next to those blocks
// but you need to account for the direction, cus you could have the issue where someone moves up, up a slab next to a full size block
// causing a false? maybe, idk, this check can be improved if you have the skills

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
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.OtherUtility;

@Experimental
public class IllegalMoveA extends Check {
    public IllegalMoveA(Profile profile) {
        super(profile, CheckType.ILLEGALMOVE, "A", "Checks for fast fall, step, and too high deltaXZ");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();


            if (profile.shouldCancel()
                    || !profile.isExempt().isRespawned()
                    || profile.isExempt().isDead()
                    || profile.isExempt().isTeleports()
                    || profile.getVehicleData().getSinceVehicleTicks() < 5
                    || movementData.isNearBed()
                    || profile.isBouncingOnSlime()
                    || movementData.getSinceBubbleTicks() < 15 + profile.getConnectionData().getClientTickTrans()) {
                return;
            }

            if (profile.getExempt().isReelingIn()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion F: is Exempting (reelingIn)");
                return;
            }

            if (profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion F: is Exempting (ghostblock liquid/web)");
                return;
            }

            double deltaY = movementData.getDeltaY();
            double deltaXZ = movementData.getDeltaXZ();

            String data = MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY;
            if (deltaY < -3.921
                    && !profile.isExempt().isTeleports()
                    && profile.getMovementData().getSinceRiptidingTicks() > 30
                    && !profile.getVelocityData().isTakingVelocity()) {
                verbose(this.getClass().getSimpleName(), deltaY, -3.92, data);
                fail("Falling too fast", "deltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
            }

            // checking for velocity here, is very useless, also i think jump ampliefier math is wrong
            // i haven't seen a false though

            double stepHeight = 0.5975D;

            if (profile.getPotionData().isHasJump()) stepHeight += (profile.getPotionData().getJumpAmplifier() * 0.1F);

            if (deltaY > stepHeight
                    && movementData.isNearWall()
                    && !profile.isBouncingOnSlime()
                    && !profile.isExempt().isTeleports()
                    && movementData.getSincePowderSnowTicks() > 20
                    && !(movementData.isOnBoat()
                    || movementData.isNearBoat())
                    && !movementData.isNearLava()
                    && !movementData.isNearWater()
                    && !profile.getVelocityData().isTakingVelocity()
                    && movementData.getSinceRiptidingTicks() > 15
                    && !profile.isBouncingOnSlime()
                    && movementData.getSinceGlidingTicks() > 15) {
                verbose(this.getClass().getSimpleName(),deltaY, 1.0, data);
                fail("Step?", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
            }

            double air_speedMultiplier = SpeedUtilities.getPotionSpeedAirMultiplier(profile);

            double expectedSpeed = 8.9D;

            expectedSpeed *= air_speedMultiplier;
            expectedSpeed += profile.getVelocityData().getTotalHorizontalVelocity();
            boolean currentlyRiptiding = movementData.getSinceRiptidingTicks() < 15 + profile.getConnectionData().getClientTickTrans();

            if (currentlyRiptiding) {
                double riptideCap = 3.45 + (1.5 * profile.getPredictionData().riptideLevel());
                expectedSpeed += riptideCap;
            }

            if (deltaXZ > expectedSpeed) {
                verbose(this.getClass().getSimpleName(),deltaXZ, 9.9, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose\n * deltaXZ "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ);
                fail("Impossible deltaXZ movement", "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ);
            }

            verbose(this.getClass().getSimpleName(), deltaY, 1, data);
        }
    }
}
