package me.arrow.checks.impl.movement.ground;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.customutils.OtherUtility;

// fairly simply ground desync/spoof check, the main one is mismatched ground (1), although (2), (3) and (4) are
// for edge cases, from clients that are able to spoof server side flooring

public class GroundA extends Check {
    public GroundA(Profile profile) {
        super(profile, CheckType.GROUND, "A", "Checks for mismatch between server and client ground");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            long profiler = Profiler.start();

            try {
                MovementData movementData = profile.getMovementData();

                if (isExempt1(movementData)) return;

                boolean serverGround = movementData.isServerGround();
                boolean clientGround = movementData.isOnGround();
                boolean serverGround2 = movementData.isServerYGround();


                boolean invalid2 = !serverGround2 && clientGround && movementData.getCustomAirTicks() != 0;

                boolean invalid1 = !serverGround && !clientGround
                        && movementData.isCustomInAir()
                        && movementData.getClientAirTicks() == 0
                        && movementData.getServerAirTicks() > 3
                        && (movementData.getCustomAirTicks() == 1 || movementData.getCustomAirTicks() > 5);

                boolean invalid3 = serverGround != clientGround
                        && !movementData.isNearWater()
                        && !movementData.isNearLava()
                        && movementData.getSincePredictUpwardsTicks() > 10
                        && movementData.getSincePredictDownwardsTicks() > 10
                        && !profile.getActionData().hasRecentUnderPlaceSupport(10 + (profile.getConnectionData().getClientTickTrans() * 2))
                        && !profile.isBedrockPlayer();

                if (invalid1 || invalid2
                        || invalid3
                ) {
                    if (increaseBuffer() > 1) {
                        fail("Mismatched ground status " + (invalid1 ? "(2)" :
                                        invalid3 ? "(4)" :
                                                "(3)"),
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround2
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks()
                                        + "\nserverAirTicks (1) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks()
                                        + "\nserverAirTicks (2) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks());
                    }
                } else decreaseBufferBy(0.25);
            } finally {
                Profiler.stop("Ground A (2, 3, 4)", profiler);
            }
        }

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            long profiler = Profiler.start();

            try {
                MovementData movementData = profile.getMovementData();

                if (isExempt2(movementData)) return;

                boolean serverGround = movementData.isServerGround();
                boolean clientGround = movementData.isOnGround();

                boolean invalid = profile.isBedrockPlayer() ?
                        !serverGround
                                && clientGround
                                && movementData.getSincePredictDownwardsTicks() > 10
                                && movementData.getCustomAirTicks() > 1
                        : (!serverGround && clientGround);

                if (invalid && movementData.getSincePredictUpwardsTicks() > 10) {
                    if (increaseBuffer() > 2) {
                        fail("Mismatched ground status (1)",
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerYGround()
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks()
                                        + "\nserverAirTicks (1) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks()
                                        + "\nserverAirTicks (2) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks());
                    }
                } else decreaseBuffer();
            } finally {
                Profiler.stop("Ground A (1)", profiler);
            }
        }
    }


    boolean isExempt1(MovementData movementData) {
        ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

        if (world.shouldExemptMovementChecks()
                || world.physicsMismatch
                || world.onGhostBlock
                || world.underGhostBlock
                || world.insideGhostBlock) {
            ChatUtils.debugExempt("WorldTracker", "GroundA");
            return true;
        }

        if (profile.shouldCancel()
                || profile.getTick() < 60
                || profile.getMovementData().isOnBoat()
                || profile.getMovementData().isNearBoat()
                || profile.isExempt().isTeleports()
                || movementData.isNearWebs()
                || movementData.isNearClimbable()
                || movementData.isNearGhast()
                || movementData.isNearShulkerBox()
                || movementData.isNearShulker()
                || movementData.getSincePredictUpwardsTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2)
                || movementData.getSinceCollideTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2)
                || profile.getMovementData().getSincePowderSnowTicks() < 10
                || profile.getVehicleData().getSinceVehicleTicks() < 1) {
            return true;
        }

        int trans = profile.getConnectionData().getClientTickTrans();

        if (profile.getActionData().hasRecentUnderPlaceSupport(10 + (trans * 2))
                || profile.getActionData().hasRecentTowerBlockPlace(10 + (trans * 2), 2 + trans)) {
            ChatUtils.debugExempt("blockSupportAndCancel", "GroundA");
            return true;
        }

        return false;
    }

    boolean isExempt2(MovementData movementData) {

        if (movementData.isOnBoat()
                || movementData.isNearBoat()
                || movementData.isNearShulkerBox()
                || movementData.isNearShulker()
                || movementData.isNearGhast()) {
            ChatUtils.debugExempt("boat/ghast", "GroundA");
            return true;
        }

        if (movementData.getSincePredictDownwardsTicks() < 10
                || movementData.getSincePredictUpwardsTicks() < 10) {
            ChatUtils.debugExempt("predict", "GroundA");
            return true;
        }

        if (movementData.getSincePowderSnowTicks() < 10) {
            ChatUtils.debugExempt("powderSnow", "GroundA");
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            ChatUtils.debugExempt("teleports", "GroundA");
            return true;
        }

        return false;
    }
}