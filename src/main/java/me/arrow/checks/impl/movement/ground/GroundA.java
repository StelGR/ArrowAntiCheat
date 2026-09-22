package me.arrow.checks.impl.movement.ground;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.CollisionUtils;
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


                boolean invalid1 = !serverGround && !clientGround
                        && movementData.isCustomInAir()
                        && movementData.getClientAirTicks() == 0
                        && movementData.getServerAirTicks() > 3
                        && (movementData.getCustomAirTicks() == 1 || movementData.getCustomAirTicks() > 5);

                boolean invalid2 = !serverGround2 && clientGround && movementData.getCustomAirTicks() != 0;

                boolean invalid3 = serverGround != clientGround
                        && !movementData.isNearWater()
                        && !movementData.isNearLava()
                        && movementData.getSinceTeleportTicks() > 10
                        && !profile.getVelocityData().isTakingVelocity()
                        && movementData.getSincePredictUpwardsTicks() > 10
                        && movementData.getSincePredictDownwardsTicks() > 10
                        && !profile.getActionData().hasRecentUnderPlaceSupport(10 + (profile.getConnectionData().getClientTickTrans() * 2))
                        && !profile.isBedrockPlayer();

                if (invalid1) {
                    if (increaseBuffer() > 1) {
                        fail("Mismatched ground status (2)" ,
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround2
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks()
                                        + "\nserverAirTicks (1) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks()
                                        + "\nserverAirTicks (2) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks());
                    }
                } else decreaseBufferBy(0.25);


                if (invalid2) {
                    if (increaseBuffer() > 1) {
                        fail("Mismatched ground status (3)" ,
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround2
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks()
                                        + "\nserverAirTicks (1) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks()
                                        + "\nserverAirTicks (2) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks());
                    }
                } else decreaseBufferBy(0.25);

                if (invalid3) {
                    if (increaseBuffer() > 1) {
                        fail("Mismatched ground status (4)" ,
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

        if (world.shouldExemptMovementChecks()) {
            ChatUtils.debugExempt("worldTrackerMovement", "GroundA");
            return true;
        }

        if (world.physicsMismatch) {
            ChatUtils.debugExempt("worldPhysicsMismatch", "GroundA");
            return true;
        }

        if (world.onGhostBlock) {
            ChatUtils.debugExempt("worldOnGhostBlock", "GroundA");
            return true;
        }

        if (world.underGhostBlock) {
            ChatUtils.debugExempt("worldUnderGhostBlock", "GroundA");
            return true;
        }

        if (world.insideGhostBlock) {
            ChatUtils.debugExempt("worldInsideGhostBlock", "GroundA");
            return true;
        }

        if (profile.shouldCancel()) {
            ChatUtils.debugExempt("cancelled", "GroundA");
            return true;
        }

        if (profile.getTick() < 60) {
            ChatUtils.debugExempt("startup", "GroundA");
            return true;
        }

        if (profile.getMovementData().isOnBoat()) {
            ChatUtils.debugExempt("onBoat", "GroundA");
            return true;
        }

        if (profile.getMovementData().isNearBoat()) {
            ChatUtils.debugExempt("nearBoat", "GroundA");
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            ChatUtils.debugExempt("teleports", "GroundA");
            return true;
        }

        if (movementData.isNearWebs()) {
            ChatUtils.debugExempt("nearWebs", "GroundA");
            return true;
        }

        if (movementData.isNearClimbable()) {
            ChatUtils.debugExempt("nearClimbable", "GroundA");
            return true;
        }

        if (movementData.isNearGhast()) {
            ChatUtils.debugExempt("nearGhast", "GroundA");
            return true;
        }

        if (movementData.isNearShulkerBox()) {
            ChatUtils.debugExempt("nearShulkerBox", "GroundA");
            return true;
        }

        if (movementData.isNearShulker()) {
            ChatUtils.debugExempt("nearShulker", "GroundA");
            return true;
        }

        int trans = profile.getConnectionData().getClientTickTrans();

        if (movementData.getSincePredictUpwardsTicks() < 5 + (trans * 2)) {
            ChatUtils.debugExempt("predictUpwards", "GroundA");
            return true;
        }

        if (movementData.getSinceCollideTicks() < 5 + (trans * 2)) {
            ChatUtils.debugExempt("recentCollision", "GroundA");
            return true;
        }

        if (profile.getMovementData().getSincePowderSnowTicks() < 10) {
            ChatUtils.debugExempt("powderSnow", "GroundA");
            return true;
        }

        if (profile.getVehicleData().getSinceVehicleTicks() < 1) {
            ChatUtils.debugExempt("recentVehicle", "GroundA");
            return true;
        }

        if (movementData.isGlidingOrRecentlyGlided(30)) {
            ChatUtils.debugExempt("gliding", "GroundA");
            return true;
        }

        if (movementData.getLocation() == null) {
            ChatUtils.debugExempt("noLocation", "GroundA");
            return true;
        }

        if (!CollisionUtils.isChunkLoaded(movementData.getLocation())) {
            ChatUtils.debugExempt("chunkNotLoaded", "GroundA");
            return true;
        }

        if (profile.getActionData().hasRecentUnderPlaceSupport(10 + (trans * 2))) {
            ChatUtils.debugExempt("underPlaceSupport", "GroundA");
            return true;
        }

        if (profile.getActionData().hasRecentTowerBlockPlace(10 + (trans * 2), 2 + trans)) {
            ChatUtils.debugExempt("towerBlockPlace", "GroundA");
            return true;
        }

        return false;
    }

    boolean isExempt2(MovementData movementData) {

        if (movementData.isOnBoat()) {
            ChatUtils.debugExempt("onBoat", "GroundA");
            return true;
        }

        if (movementData.isNearBoat()) {
            ChatUtils.debugExempt("nearBoat", "GroundA");
            return true;
        }

        if (movementData.isNearShulkerBox()) {
            ChatUtils.debugExempt("nearShulkerBox", "GroundA");
            return true;
        }

        if (movementData.isNearShulker()) {
            ChatUtils.debugExempt("nearShulker", "GroundA");
            return true;
        }

        if (movementData.isNearGhast()) {
            ChatUtils.debugExempt("nearGhast", "GroundA");
            return true;
        }

        if (movementData.getLocation() == null) {
            ChatUtils.debugExempt("noLocation", "GroundA");
            return true;
        }

        if (!CollisionUtils.isChunkLoaded(movementData.getLocation())) {
            ChatUtils.debugExempt("chunkNotLoaded", "GroundA");
            return true;
        }

        if (movementData.getSincePredictDownwardsTicks() < 10) {
            ChatUtils.debugExempt("predictDownwards", "GroundA");
            return true;
        }

        if (movementData.getSincePredictUpwardsTicks() < 10) {
            ChatUtils.debugExempt("predictUpwards", "GroundA");
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
