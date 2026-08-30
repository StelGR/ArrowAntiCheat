package me.arrow.checks.impl.movement.ground;

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
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.customutils.OtherUtility;

// other impossible states, like the description claims, it uses material from the world, instead of listening to either the server
// from math, or client packet, it sees what is in the chunks, kind of, and verifies if you are in air or not.

public class GroundB extends Check {

    public GroundB(Profile profile) {
        super(profile, CheckType.GROUND, "B", "Verifies the players ground state by using world material");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    final double AIR_ICE_INCREMENT_PER_TICK = 0.1225;
    final double AIR_ICE_INCREMENT_PER_TICK_SMALLER = 0.0625;
    final double AIR_MAX_ICE_SPEED_BOOST = 6.25;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())) {
            return;
        }

        long profiler = Profiler.start();

        try {

            MovementData movementData = profile.getMovementData();

            if (movementData.getSinceGlidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()
                    || profile.getExempt().isVehicle()
                    || movementData.getSinceTeleportTicks() < 5
                    || profile.isBouncingOnSlime()
                    || profile.getVehicleData().getSinceVehicleTicks() < 5
                    || movementData.isInsideLiquid()
                    || movementData.isNearShulker()
                    || movementData.isNearShulkerBox()
                    || movementData.isNearLava()
                    || movementData.isNearWater())
                return;

            if (profile.getActionData().hasRecentPistonUpdate(5 + (profile.getConnectionData().getClientTickTrans() * 2))
//                || profile.getActionData().hasRecentConfirmedBlockUpdateUnder(5 + (profile.getConnectionData().getClientTickTrans() * 2))
            ) {
                if (Config.Setting.DEBUG.getBoolean())
                    OtherUtility.log("Ground B: is Exempting (Block Update/Piston Update)");
                return;
            }

            if (profile.getMovementData().getSinceGlidingTicks() < 25 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Ground B: is Exempting (elytra glide)");
                return;
            }

            int airTicks = movementData.getCustomAirTicks();
            int clientAirTicks = movementData.getClientAirTicks();

            boolean clientGround = movementData.isOnGround();
            boolean serverGround = movementData.isServerGround();
            boolean serverYGround = movementData.isServerYGround();
            boolean inAir = movementData.isCustomInAir();

            double deltaXZ = movementData.getDeltaXZ();
            double deltaY = movementData.getDeltaY();

            boolean recentlyPlaced = profile.getActionData().hasRecentConfirmedUnderPlace(5 + profile.getConnectionData().getClientTickTrans() * 2);

            double airTickLimit = getAirTickLimit(
                    movementData,
                    recentlyPlaced
            );

            double horizontal = Math.max(
                    profile.getVelocityData().getTotalHorizontalVelocitySustain(),
                    profile.getVelocityData().getStackedHorizontalVelocity()
            );
            double vertical = Math.max(
                    profile.getVelocityData().getTotalVerticalVelocitySustain(),
                    profile.getVelocityData().getStackedVerticalVelocity()
            );

            double velMag = (horizontal / 2) + vertical;
            double baseTicksVel = 10;
            double baseVelocity = 0.0005;
            double scale = 14;

            double extraFromVel = velMag <= baseVelocity ? 0 : baseTicksVel + (scale * (velMag - baseVelocity));
            airTickLimit += Math.ceil(extraFromVel);

            boolean invalid = airTicks > (airTickLimit + 12) && clientGround && serverGround && inAir && movementData.isMoving();

            boolean nearEdge = CollisionUtils.isNearEdge(movementData.getLocation());
            if (nearEdge && movementData.getLastDeltaY() != 0 && deltaY == 0 && movementData.getClientAirTicks() == 0)
                invalid = false;

            float movingIceTicks = movementData.getMovingOnIceTicks();

            double air_iceSpeedBoost;
            if (movingIceTicks < 15)
                air_iceSpeedBoost = Math.min(AIR_ICE_INCREMENT_PER_TICK * movingIceTicks, AIR_MAX_ICE_SPEED_BOOST);
            else
                air_iceSpeedBoost = Math.min(AIR_ICE_INCREMENT_PER_TICK_SMALLER * movingIceTicks, AIR_MAX_ICE_SPEED_BOOST);

            double speedlimit = 0.89 + profile.getVelocityData().getTotalHorizontalVelocity();
            speedlimit += air_iceSpeedBoost;

            boolean invalid2 = (
                    (clientGround && serverGround && inAir)
                            || (inAir && airTicks > 3 && clientAirTicks == 0)
                            || (inAir && clientAirTicks > 6 && serverYGround)
                            || (!clientGround && serverGround && inAir)
            )
                    && movementData.getDeltaXZ() > (speedlimit)
                    && movementData.getSinceRiptidingTicks() > 40 + profile.getConnectionData().getClientTickTrans()
                    && movementData.elytraMomentum() == 0;


            if (inAir) {
                verbose(this.getClass().getSimpleName(), airTicks, airTickLimit,
                        MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose"
                                + "\n * ServerGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\n * ServerYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverYGround
                                + "\n * ClientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\n * InAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                + "\n * AirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                                + "\n * ClientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                                + "\n * AirTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit
                                + "\n * DeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\n * DeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\n * LagTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + getLagTicks()
                                + "\n * TransPing " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getConnectionData().getTransPing()
                                + "\n * VelocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalHorizontalVelocity()
                                + "\n * VelocityV " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalVerticalVelocity()
                                + "\n * JumpLevel " + MsgType.MAIN_THEME_COLOR.getMessage() + SpeedUtilities.getJumpBoostPotionLevel(profile)
                                + "\n * JumpExpected " + MsgType.MAIN_THEME_COLOR.getMessage() + getExpectedJumpMotion());
            }

            String verboseInfo = "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                    + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverYGround
                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                    + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                    + "\ncustomAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                    + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks()
                    + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                    + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY;
            if (invalid) {
                if (increaseBuffer() > 3) {
                    fail("Impossible ground state (1)",
                            verboseInfo);
                }
            } else decreaseBufferBy(0.125);
            if (invalid2) {
                fail("Impossible ground state (2)",
                        verboseInfo);
            }
        } finally {
            Profiler.stop("Ground B", profiler);
        }
    }

    private double getAirTickLimit(MovementData movementData,
                                   boolean recentlyPlaced) {

        double limit = 5.0D;

        int jumpBoost = SpeedUtilities.getJumpBoostPotionLevel(profile);

        if (jumpBoost > 0) {
            limit += Math.min(3.0D, jumpBoost * 0.75D);
        }

        if (movementData.getDeltaXZ() > 0.0D) {
            limit += 1.0D;
        }

        if (recentlyPlaced) {
            limit += 3.0D;
        }

        /*
         * Exactly 1 extra tick per 50ms, capped.
         */
        limit += Math.min(4.0D, profile.getConnectionData().getTransPing() / 50.0D);

        /*
         * Extra tolerance for unstable transaction jumps.
         */
        if (profile.getConnectionData().getDropTransTime() > 150) {
            limit += 1.0D;
        }

        return Math.min(limit, 12.0D);
    }

    private int getLagTicks() {
        int ticks = 0;

        try {
            ticks = Math.max(ticks, profile.getConnectionData().getClientTickTrans());
        } catch (Throwable ignored) {
        }

        try {
            ticks = Math.max(ticks, profile.getConnectionData().getClientTick());
        } catch (Throwable ignored) {
        }

        return Math.min(10, ticks);
    }

    private double getExpectedJumpMotion() {
        return 0.42D + (SpeedUtilities.getJumpBoostPotionLevel(profile) * 0.1D);
    }

}