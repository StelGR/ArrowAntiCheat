package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.customutils.OtherUtility;

// this is a bit more complicated that Motion D, but i am getting tired, it is also basically like 3 checks in one, i have not seen it false
// but it could false, that's why its set as experimental


// Update: 04/07/2026
// it is no longer experimental, as i have not seen major falses.

public class MotionB extends Check {
    public MotionB(Profile profile) {
        super(profile, CheckType.MOTION, "B", "Checks for invalid deltaY movements");
    }

    double buffer, lastLocationY, lastDeltaY;
    double buffer2;
    double buffer3;
    double buffer4;


    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            long profiler = Profiler.start();

            try {
                MovementData movementData = profile.getMovementData();

                if (exempt("cancelled", profile.shouldCancel())) { resetBuffers(); return; }
                if (exempt("teleports", movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4))) { resetBuffers(); return; }
                if (exempt("slimeBounce", profile.isBouncingOnSlime())) { resetBuffers(); return; }
                if (exempt("onSlime", movementData.isOnSlime())) { resetBuffers(); return; }
                if (exempt("nearShulker", movementData.isNearShulker())) { resetBuffers(); return; }
                if (exempt("nearShulkerBox", movementData.isNearShulkerBox())) { resetBuffers(); return; }
                if (exempt("insideVehicle", profile.getPlayer().isInsideVehicle())) { resetBuffers(); return; }
                if (exempt("onBoat", movementData.isOnBoat())) { resetBuffers(); return; }
                if (exempt("nearBoat", movementData.isNearBoat())) { resetBuffers(); return; }
                if (exempt("predictUpwards", movementData.getSincePredictUpwardsTicks() < 10)) { resetBuffers(); return; }
                if (exempt("nearClimbable", movementData.isNearClimbable())) { resetBuffers(); return; }
                if (exempt("underBlock", movementData.isUnderblock())) { resetBuffers(); return; }
                if (exempt("nearBerries", movementData.getNearbyBlocksResult() != null
                        && movementData.getNearbyBlocksResult().getBlockTypes().stream().anyMatch(material -> MaterialType.isMaterial(material.name(), MaterialType.BERRIES)))) { resetBuffers(); return; }
                if (exempt("nearBed", movementData.isNearBed())) { resetBuffers(); return; }
                if (exempt("gliding", movementData.isGlidingOrRecentlyGlided(30))) { resetBuffers(); return; }
                if (exempt("nearWall", movementData.isNearWall())) { resetBuffers(); return; }

                int ghostPhysicsTicks = 10 + (profile.getConnectionData().getClientTickTrans() * 4);

                if (exempt("cancelledBlockPlaceAbove", profile.getBlockProcessor().isCancelledBlockPlaceAbove(ghostPhysicsTicks))) return;

                if (exempt("underBreak", profile.getActionData().getLastConfirmedUnderBreakTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2))) return;

                if (exempt("underPlace", profile.getActionData().getLastConfirmedUnderPlaceTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2))) return;

                double locationY = profile.getPlayer().getLocation().getY();

                double deltaY = movementData.getDeltaY();
                double locationDeltaY = locationY - this.lastLocationY;
                boolean exempt;
                if (exempt("nearWater", movementData.isNearWater())) {
                    exempt = true;
                } else if (exempt("nearLava", movementData.isNearLava())) {
                    exempt = true;
                } else if (exempt("nearWebs", movementData.isNearWebs())) {
                    exempt = true;
                } else {
                    exempt = false;
                }
                boolean serverGround = movementData.isServerGround();
                boolean clientGround = movementData.isOnGround();
                double fallDistance = profile.getPlayer().getFallDistance();

                if (deltaY != 0)
                    verbose(this.getClass().getSimpleName(), buffer2, 4, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (2)" +
                            MsgType.SECOND_THEME_COLOR.getMessage() + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + MsgType.SECOND_THEME_COLOR.getMessage() + "\n * locationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                            + MsgType.SECOND_THEME_COLOR.getMessage() + "\n * ground " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                            + MsgType.SECOND_THEME_COLOR.getMessage() + "\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                            + MsgType.SECOND_THEME_COLOR.getMessage() + "\n * fallDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance);

                if (deltaY > -0.43f && deltaY < -0.41f && locationDeltaY > 0.0 && serverGround && clientGround) {
                    if (++buffer4 > 3) {
                        fail("Impossible deltaY",
                                "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nlocationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                                        + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround);
                    }

                    verbose(this.getClass().getSimpleName(), buffer, 3, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (4)\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * locationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                            + "\n * clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                            + "\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround);
                } else {
                    buffer4 -= Math.min(buffer4, 0.1);
                }


                //debug(profile.getPlayer().getName() + ", deltaY "+deltaY+", locationDeltaY"+ locationDeltaY+ ", locationY "+locationY+", serverGround "+serverGround+", clientGround "+clientGround+ ", fallDistance "+fallDistance);

//            if (deltaY > 0.0
//                    && locationDeltaY < 0.0
//                    && !exempt
//                    && !movementData.isUnderblock()
//                    && !profile.isBouncingOnSlime()
//                    && !CollisionUtils.isStandingOnMaterial(movementData.getLocation(), movementData.getNearbyBlocksResult(), true, MaterialType.HONEY)
//                    && profile.getVelocityData().getTotalVerticalVelocity() == 0
//                    && profile.getVelocityData().getTotalHorizontalVelocity() == 0) {
//                if (++buffer > 3) {
//                    fail("Invalid motionY movements (1)",
//                            "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
//                            + "\nlocationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
//                            + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
//                            + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround);
//                }
//
//                verbose(this.getClass().getSimpleName(), buffer, 3, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose (1)\n * deltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
//                        + "\n * locationDeltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
//                        + "\n * clientGround "+MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
//                        + "\n * serverGround "+MsgType.MAIN_THEME_COLOR.getMessage()+serverGround);
//            } else {
//                buffer -= Math.min(buffer, 0.1);
//            }

                if (clientGround
                        && deltaY != 0
                        && !exempt
                        && !profile.isBouncingOnSlime()
                        && !CollisionUtils.isStandingOnMaterial(movementData.getLocation(), movementData.getNearbyBlocksResult(), MaterialType.HONEY)
                        && profile.getVelocityData().getTotalHorizontalVelocity() == 0 && profile.getVelocityData().getTotalVerticalVelocity() == 0
                        && movementData.getSlimeTicks() > 0
                        && fallDistance == 0) {
                    if (++buffer2 > 4) {
                        fail("Invalid motionY movements",
                                "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nlocationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                                        + "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nfallDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance);
                    }

                    verbose(this.getClass().getSimpleName(), buffer2, 4, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (2)\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * locationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                            + "\n * ground +" + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                            + "\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                            + "\n * fallDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance);
                } else {
                    buffer2 -= Math.min(buffer2, 0.125);
                }


                if (exempt("predictUpwards", movementData.getSincePredictUpwardsTicks() < 10 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                    buffer3 = 0;
                    return;
                }
                if (exempt("predictUpwardsWithoutMaterial", movementData.getSincePredictUpwardsTicksWithoutMaterial() < 10 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                    buffer3 = 0;
                    return;
                }


                if (clientGround
                        && deltaY > 0
                        && locationDeltaY > 0
                        && !exempt) {
                    if (++buffer3 > 2) {
                        fail("Accelerating upwards while being on ground",
                                "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nlocationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                                        + "\nground " + MsgType.MAIN_THEME_COLOR.getMessage() + "true\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround);
                    }

                    verbose(this.getClass().getSimpleName(), buffer3, 2, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (3)\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\n * locationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                            + "\n * ground&b true\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround);
                } else {
                    buffer3 -= Math.min(buffer3, 0.025);
                }

                this.lastLocationY = locationY;
                this.lastDeltaY = deltaY;
            } finally {
                Profiler.stop("Motion B", profiler);
            }
        }
    }

    private void resetBuffers() {
        buffer = 0.0D;
        buffer2 = 0.0D;
        buffer3 = 0.0D;
        buffer4 = 0.0D;
    }
}
