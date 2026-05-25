package me.arrow.checks.impl.movement.ground;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;

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
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();

            ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

            if (world.shouldExemptMovementChecks()
                    || world.physicsMismatch
                    || world.onGhostBlock
                    || world.underGhostBlock
                    || world.insideGhostBlock) {
                return;
            }

            if (profile.shouldCancel()
                    || profile.getMovementData().isOnBoat()
                    || profile.getMovementData().isNearBoat()
                    || profile.isExempt().isTeleports()
                    || movementData.isNearWebs()
                    || movementData.isNearShulkerBox()
                    || movementData.isPhasing()
                    || movementData.isNearClimbable()
                    || movementData.isNearGhast()
                    || movementData.isNearShulker()
                    || movementData.getSincePredictUpwardsTicks() < 5
                    || profile.getMovementData().getSincePowderSnowTicks() < 10
                    || profile.getVehicleData().getSinceVehicleTicks() < 5
                    || profile.getVehicleData().getSinceNearVehicleTicks() < 5) {
                return;
            }

            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();
            boolean serverGround2 = movementData.isServerYGround();

            boolean invalid2 = !serverGround2 && clientGround && movementData.getCustomAirTicks() != 0;

            boolean invalid1 = !serverGround && !clientGround && movementData.isCustomInAir() && movementData.getClientAirTicks() == 0 && movementData.getServerAirTicks() > 3 && ( movementData.getCustomAirTicks() == 1 || movementData.getCustomAirTicks() > 5);

            boolean invalid3 = serverGround != clientGround
                    && !movementData.isNearWater()
                    && !movementData.isNearLava()
                    && !movementData.isMovingUp()
                    && movementData.getSincePredictDownwardsTicks() < 10
                    && movementData.getSincePredictUpwardsTicks() < 10
                    && movementData.getCustomAirTicks() >= 2
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
            }
            else decreaseBufferBy(0.025);
        }

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();
            if (movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isNearGhast()
                    || movementData.getSincePowderSnowTicks() < 10
                    || movementData.getSincePredictDownwardsTicks() < 10
                    || movementData.getSincePredictUpwardsTicks() < 10
                    || profile.isExempt().isTeleports()
            ) return;

            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();

            boolean invalid = profile.isBedrockPlayer() ?
                    !serverGround && clientGround && !movementData.isMovingDown()
                            && movementData.getCustomAirTicks() > 1
                    : (!serverGround && clientGround );

            if (invalid) {
                if (increaseBuffer() > 1) {
                    fail("Mismatched ground status (1)",
                            "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                    + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerYGround()
                                    + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                    + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks()
                                    + "\nserverAirTicks (1) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks()
                                    + "\nserverAirTicks (2) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks());
                }
            }
            else decreaseBufferBy(0.01);
        }
    }
}