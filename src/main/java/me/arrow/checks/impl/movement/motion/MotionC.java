package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.custom.PotionType;
import me.arrow.utils.customutils.OtherUtility;

// this works, very similarly to Fly B, but near walls, it is not as good though since i haven't kept it up to date that much
// it works very well though, for detecting wall climbs above 3 blocks, not perfect, but does the job.

public class MotionC extends Check {

    public MotionC(Profile profile) {
        super(profile, CheckType.MOTION, "C", "Checks for wallclimb");
    }

    double airTickLimit = 7;


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();

            if (profile.shouldCancel()
                    || movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isNearClimbable()
                    || !profile.isExempt().isRespawned()
                    || profile.getPlayer().isDead()
                    || movementData.isNearBuggyBlock()
                    || movementData.isNearWater()
                    || profile.isBouncingOnSlime()
                    || movementData.isNearGhast()
                    || movementData.isNearShulker()
                    || movementData.getSinceNearWaterTicks() < 12 + profile.getConnectionData().getClientTickTrans()
                    || profile.isExempt().isTeleports()
                    || profile.getPlayer().isInsideVehicle()
                    || profile.getPotionData().isHasLevitation()) {
                return;
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (ghostblock liquid/web/pending physics place)");
                return;
            }

            if (profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (ghostblock liquid/web)");
                return;
            }

            if (profile.isExempt().isReelingIn()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (reelingIn)");
                return;
            }

//            if (movementData.getSinceGl() < 20) {
//                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (Elytra Equip)");
//                return;
//            }

            if (movementData.isNearShulkerBox()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (ShulkerBox)");
                return;
            }

            boolean hasJumpBoost = profile.getPotionData().isHasJump();
            double jumpLevel = hasJumpBoost
                    ? profile.getPotionData().getPotionEffectLevel(PotionType.JUMP_BOOST)
                    + (1 + profile.getPotionData().getJumpAmplifier() * 0.2F)
                    : 0;

            int clientTickTrans = profile.getConnectionData().getClientTickTrans();
            int transPing = profile.getConnectionData().getTransPing();
            int clientAirTicks = movementData.getClientAirTicks();
            double deltaY = movementData.getDeltaY();
            double deltaXZ = movementData.getDeltaXZ();
            boolean isNearWall = movementData.isNearWall();
            boolean serverGround = movementData.isServerYGround();
            boolean clientGround = movementData.isOnGround();
            int nearWallTicks = movementData.getNearWallTicks();
            int serverAirTicks = movementData.getCustomAirTicks();

            boolean blockInHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer()).getType().isBlock();
            boolean blockInOffHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer()).getType().isBlock();
            boolean holdingBlock = blockInHand || blockInOffHand;

            int blockPlaceLimit = clientTickTrans == 0 ? 3 : Math.min(3 + transPing / clientTickTrans, 10);
            boolean recentlyPlaced = profile.getLastBlockPlaceTimer().hasNotPassed(blockPlaceLimit);

            if (hasJumpBoost) {
                if (recentlyPlaced && holdingBlock) {
                    airTickLimit = 12 + jumpLevel - (profile.getPotionData().getJumpAmplifier() * 0.1F); // adjust 0.2->0.1 as original ternary
                } else {
                    airTickLimit = 8 + jumpLevel - (profile.getPotionData().getJumpAmplifier() * 0.1F);
                }
            } else {
                airTickLimit = (recentlyPlaced && holdingBlock) ? 11 : 7;
            }

            if (deltaXZ != 0) airTickLimit += (recentlyPlaced && holdingBlock) ? 4 : 2;

            if (profile.isBouncingOnSlime()) {
                return;
            }

            double horizontal = profile.getVelocityData().getTotalHorizontalVelocitySustain();
            double vertical = profile.getVelocityData().getTotalVerticalVelocitySustain();
            double velMag = horizontal + (vertical * 2);

            double baseTicksVel = 6;
            double baseVelocity = 0.0005;
            double scale = 15.5;
            double maxExtra = 225;

            double extraFromVel = velMag <= baseVelocity ? 0 : baseTicksVel + Math.min(scale * (velMag - baseVelocity), maxExtra);

//            double connectionAdj = Math.min(
//                    0,
//                    1.0D + (profile.getConnectionData().getTransPing() / 50.0D)
//            );

            //double velocityTickExempt = extraFromVel;
            airTickLimit += extraFromVel;

            if (movementData.isNearFence()) airTickLimit += 4;

            boolean invalid = serverAirTicks > airTickLimit
                    && deltaY > -0.05
                    && isNearWall
                    && nearWallTicks > 8
                    && !(movementData.getSinceGlidingTicks() < 10 && movementData.getSinceGlidingTicks() > 1);

            if (isNearWall && serverAirTicks > 0)
                verbose(this.getClass().getSimpleName(), serverAirTicks, airTickLimit, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                        + "\n * clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                        + "\n * nearWallTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + nearWallTicks
                        + "\n * clientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                        + "\n * serverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                        + "\n * airTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit
                        + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * airTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit
                        + "\n * velocity Ticks " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getVelocityTicks()
                        + "\n * jumpAmplifierMath " + MsgType.MAIN_THEME_COLOR.getMessage() + (0.42 + ((profile.getPotionData().getJumpAmplifier() + 1) * 0.1)));

            if (invalid && !movementData.isClimb()) {
                fail("Wallclimb?",
                        "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                        + "\nnearWallTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + nearWallTicks
                        + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                        + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                        + "\nairTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit
                        + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
            }
        }
    }
}
