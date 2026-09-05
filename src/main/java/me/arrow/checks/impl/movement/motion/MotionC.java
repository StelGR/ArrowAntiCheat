package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.customutils.OtherUtility;

// this works, very similarly to Fly B, but near walls, it is not as good though since I haven't kept it up to date that much
// it works very well though, for detecting wall climbs above 3 blocks, not perfect, but does the job.

// update 108-pre1, this no longer works due to how CollisionUtils handles server air ticks now, so some wallclimbs will now work

public class MotionC extends Check {

    public MotionC(Profile profile) {
        super(profile, CheckType.MOTION, "C", "Checks for wallclimb");
    }

    double airTickLimit = 8;


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            long profiler = Profiler.start();

            try {
                MovementData movementData = profile.getMovementData();

                if (profile.shouldCancel()
                        || movementData.isOnBoat()
                        || movementData.isNearBoat()
                        || movementData.isNearClimbable()
                        || !profile.isExempt().isRespawned()
                        || profile.getPlayer().isDead()
                        || movementData.isNearBuggyBlock()
                        || movementData.isNearWater()
                        || movementData.isNearGhast()
                        || movementData.isNearShulker()
                        || movementData.getSinceNearWaterTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2)
                        || movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4)
                        || profile.getPlayer().isInsideVehicle()
                        || (profile.getMovementData().getSinceLevitationEffectTicks() < 10 && profile.getPotionData().getLevitationTicks() > 0)) {
                    return;
                }

                ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

                if (world.shouldExemptMovementChecks()
                        || world.physicsMismatch
                        || world.onGhostBlock
                        || world.nearGhostBlock
                        || world.insideGhostBlock
                        || profile.getBlockProcessor().isCancelledBlockPlacementExempt(10 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                    return;
                }

                int ghostPhysicsTicks = 10 + (profile.getConnectionData().getClientTickTrans() * 4);

                if (profile.getBlockProcessor().isGhostPhysicsPlacementExempt(ghostPhysicsTicks)) {
                    if (Config.Setting.DEBUG.getBoolean())
                        OtherUtility.log("Motion C: is Exempting (ghostblock liquid/web/pending physics place)");
                    return;
                }

                if (profile.isExempt().isReelingIn()) {
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (reelingIn)");
                    return;
                }

                if (movementData.getSinceGlidingTicks() < 25 + profile.getConnectionData().getClientTickTrans()) {
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (elytra glide)");
                    return;
                }

                if (movementData.isNearShulkerBox()) {
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (ShulkerBox)");
                    return;
                }

                if (movementData.getSinceBubbleTicks() < 15 + profile.getConnectionData().getClientTickTrans()) {
                    if (Config.Setting.DEBUG.getBoolean())
                        OtherUtility.log("Motion C: is Exempting (since bubble water)");
                    return;
                }

                if (profile.isBouncingOnSlime()) {
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Exempting (Bouncing Slime)");
                    return;
                }

                if (movementData.getSincePowderSnowTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                    return;
                }

                boolean hasJumpBoost = SpeedUtilities.getJumpBoostPotionLevel(profile) > 0;
                double jumpLevel = hasJumpBoost
                        ?
                        (4 + (SpeedUtilities.getJumpBoostPotionLevel(profile)))
                        : 0;

                int clientTickTrans = profile.getConnectionData().getClientTickTrans();
                int clientAirTicks = movementData.getClientAirTicks();
                double deltaY = movementData.getDeltaY();
                double deltaXZ = movementData.getDeltaXZ();
                boolean isNearWall = movementData.isNearWall()
                        || movementData.isLastNearWall()
                        || movementData.isLastLastNearWall()
                        || movementData.isPacketNearWall()
                        || movementData.getLastNearWallTicks() <= 1;
                boolean serverGround = movementData.isServerYGround();
                boolean clientGround = movementData.isOnGround();
                int nearWallTicks = movementData.getNearWallTicks();
                int serverAirTicks = movementData.getCustomAirTicks();

                boolean recentlyPlaced = profile.getActionData().getLastConfirmedUnderPlaceTicks() < 5 + (clientTickTrans * 2);

                if (hasJumpBoost) {
                    if (recentlyPlaced) {
                        airTickLimit = (12 + clientTickTrans) + jumpLevel; // adjust 0.2->0.1 as original ternary
                    } else {
                        airTickLimit = (8 + clientTickTrans) + jumpLevel;
                    }
                } else {
                    airTickLimit = recentlyPlaced ? 12 : 8;
                }

                if (deltaXZ != 0) airTickLimit += recentlyPlaced ? 4 : 2;

                airTickLimit += Math.ceil(getVelocityTicks());

                if (movementData.isNearFence()) airTickLimit += 4;

                //temporary piston fix
                if (movementData.getSinceNearSlimeTicks() <= (40 + (profile.getConnectionData().getClientTickTrans() * 2))
                        && movementData.getSinceNearPistonTicks() <= (40 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                    airTickLimit += 8;
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion C: is Extending PistonSlimeTicks");
                }

                boolean invalid = serverAirTicks > airTickLimit
                        && deltaY > -0.23
                        && isNearWall
                        && nearWallTicks > 8
                        && movementData.getSinceGlidingTicks() > 20 + (profile.getConnectionData().getClientTickTrans() * 2);

                if (isNearWall)
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
            } finally {
                Profiler.stop("Motion C", profiler);
            }
        }
    }

    private double getVelocityTicks() {
        double vel = Math.max(
                profile.getVelocityData().getTotalVerticalVelocitySustain(),
                profile.getVelocityData().getStackedVerticalVelocity()
        );

        double velMag = Math.max(
                vel,
                profile.getVelocityData().getTotalVerticalVelocity()
        );

        double horizo = profile.getVelocityData().getTotalHorizontalVelocity();

        velMag += horizo;

        double baseTicksVel = 10;
        double baseVelocity = 0.0005;
        double scale = 28;

        return velMag <= baseVelocity ? 0 : baseTicksVel + (scale * (velMag - baseVelocity));
    }
}
