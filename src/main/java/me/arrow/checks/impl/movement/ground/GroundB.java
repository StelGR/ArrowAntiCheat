package me.arrow.checks.impl.movement.ground;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.PotionType;

// other impossible states, like the description claims, it uses material from the world, instead of listening to either the server
// from math, or client packet, it sees what is in the chunks, kind of, and verifies if you are in air or not.

public class GroundB extends Check {

    private int impossibleGroundTicks;
    private int spoofGroundTicks;

    public GroundB(Profile profile) {
        super(profile, CheckType.GROUND, "B", "Verifies the players ground state by using world material");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isMovementPacket(event.getPacketType())) {
            return;
        }

        MovementData movementData = profile.getMovementData();

        if (movementData.getSinceGlidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()
                || profile.getExempt().isVehicle()
                || profile.getExempt().isTeleports()
                || profile.getVehicleData().getSinceVehicleTicks() < 5
                || movementData.isInsideLiquid()
                || movementData.isNearLava()
                || movementData.isNearWater()) return;


        int airTicks = movementData.getCustomAirTicks();
        int clientAirTicks = movementData.getClientAirTicks();

        boolean clientGround = movementData.isOnGround();
        boolean serverGround = movementData.isServerGround();
        boolean serverYGround = movementData.isServerYGround();
        boolean inAir = movementData.isCustomInAir();

        double deltaXZ = movementData.getDeltaXZ();
        double deltaY = movementData.getDeltaY();

        boolean holdingBlock = isHoldingBlock();
        boolean recentlyPlaced = profile.getLastBlockPlaceTimer().hasNotPassed(getBlockPlaceLimit());
        boolean recentlyCancelledPlace = profile.getLastBlockPlaceCancelTimer().hasNotPassed(3 + getLagTicks());

        double airTickLimit = getAirTickLimit(
                movementData,
                holdingBlock,
                recentlyPlaced,
                recentlyCancelledPlace
        );

        //invalid 1 was a terribly made check, i removed it, invalid2 does most of the job. but it can still false, needs improvements



        boolean invalid = airTicks > (airTickLimit + 12) && clientGround && serverGround && inAir;
//                (inAir && (airTicks >= 6 || clientAirTicks >= 8) && serverYGround && !serverGround && !clientGround)
//                && movementData.getDeltaXZ() >= (0.9 + profile.getVelocityData().getTotalHorizontalVelocity())
//                && movementData.getSinceRiptidingTicks() > 15;

        boolean nearEdge = CollisionUtils.isNearEdge(movementData.getLocation());
        if (nearEdge && movementData.getLastDeltaY() != 0) invalid = false;

        boolean invalid2 = (
                (clientGround && serverGround && inAir)
                || (inAir && airTicks > 3 && clientAirTicks == 0)
                || (inAir && impossibleGroundTicks >= 3)
                || (inAir && clientAirTicks > 6 && serverYGround)
                || (!clientGround && serverGround && inAir)
        )
                && movementData.getDeltaXZ() >= (0.9 + profile.getVelocityData().getTotalHorizontalVelocity())
                && !profile.getVelocityData().isTakingVelocity()
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
                            + "\n * ImpossibleTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + impossibleGroundTicks
                            + "\n * SpoofTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + spoofGroundTicks
                            + "\n * DeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                            + "\n * DeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * LagTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + getLagTicks()
                            + "\n * TransPing " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getConnectionData().getTransPing()
                            + "\n * VelocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalHorizontalVelocity()
                            + "\n * VelocityV " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalVerticalVelocity()
                            + "\n * JumpLevel " + MsgType.MAIN_THEME_COLOR.getMessage() + getJumpBoostLevel()
                            + "\n * JumpExpected " + MsgType.MAIN_THEME_COLOR.getMessage() + getExpectedJumpMotion());
        }

        if (invalid || invalid2) {
            fail("Impossible ground state " + (invalid ? "(1)" : "(2)"),
                        "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverYGround
                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                                + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                                //+ "\nimpossibleGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + impossibleGroundTicks
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);

        }
    }

    private boolean shouldExempt(MovementData movementData) {
        return profile.shouldCancel()
                || profile.getPlayer().isDead()
                || profile.getMovementData().isOnBoat()
                || profile.getMovementData().isNearBoat()
                || movementData.isNearClimbable()
                || movementData.isNearWater()
                || movementData.isNearLava()
                || movementData.isInsideLiquid()
                || movementData.isNearBubble()
                || movementData.isNearWebs()
                || movementData.isNearShulker()
                || movementData.isNearShulkerBox()
                || movementData.isNearGhast()
                || movementData.isUnderblock()
                || movementData.getSincePowderSnowTicks() < 10
//                || profile.isExempt().isTeleports()
                || profile.getVehicleData().getSinceNearVehicleTicks() < 5;
    }

    private double getAirTickLimit(MovementData movementData,
                                   boolean holdingBlock,
                                   boolean recentlyPlaced,
                                   boolean recentlyCancelledPlace) {

        double limit = 5.0D;

        int jumpBoost = getJumpBoostLevel();

        if (jumpBoost > 0) {
            limit += Math.min(3.0D, jumpBoost * 0.75D);
        }

        if (movementData.getDeltaXZ() > 0.0D) {
            limit += 1.0D;
        }

        if (recentlyPlaced && holdingBlock) {
            limit += 3.0D;
        }

        if (recentlyCancelledPlace) {
            limit += 2.0D;
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

    private int getBlockPlaceLimit() {
        return 3 + getLagTicks();
    }

    private int getJumpBoostLevel() {
        try {
            if (!profile.getPotionData().isHasJump()) {
                return 0;
            }

            return Math.max(0, profile.getPotionData().getPotionEffectLevel(PotionType.JUMP_BOOST));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private double getExpectedJumpMotion() {
        return 0.42D + (getJumpBoostLevel() * 0.1D);
    }

    private boolean isHoldingBlock() {
        try {
            boolean blockInHand = Arrow.getInstance()
                    .getNmsManager()
                    .getNmsInstance()
                    .getItemInMainHand(profile.getPlayer())
                    .getType()
                    .isBlock();

            boolean blockInOffHand = Arrow.getInstance()
                    .getNmsManager()
                    .getNmsInstance()
                    .getItemInOffHand(profile.getPlayer())
                    .getType()
                    .isBlock();

            return blockInHand || blockInOffHand;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isMovementPacket(PacketTypeCommon packetType) {
        return packetType.equals(PacketType.Play.Client.PLAYER_FLYING)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean isPositionPacket(PacketTypeCommon packetType) {
        return packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private void decay() {
        impossibleGroundTicks = Math.max(0, impossibleGroundTicks - 1);
        spoofGroundTicks = Math.max(0, spoofGroundTicks - 1);
        decreaseBufferBy(0.05);
    }
}