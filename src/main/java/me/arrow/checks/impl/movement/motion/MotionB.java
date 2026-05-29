package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.MaterialType;

// this is a bit more complicated that Motion D, but i am getting tired, it is also basically like 3 checks in one, i have not seen it false
// but it could false, that's why its set as experimental

@Experimental
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
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {


            MovementData movementData = profile.getMovementData();

            if (profile.shouldCancel()
                    || profile.isExempt().isTeleports()
                    || profile.isBouncingOnSlime()
                    || movementData.isOnSlime()
                    || movementData.isNearShulkerBox()
                    || profile.getPlayer().isInsideVehicle()
                    || movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isMovingUp()
                    || profile.getLastBlockPlaceTimer().hasNotPassed(10 + profile.getConnectionData().getClientTickTrans())
                    || profile.getLastBlockPlaceCancelTimer().hasNotPassed(5 + profile.getConnectionData().getClientTickTrans())
                    || movementData.isNearClimbable()
                    || movementData.isUnderblock()
                    || (movementData.getNearbyBlocksResult() != null
                    && movementData.getNearbyBlocksResult().getBlockTypes().stream().anyMatch(material -> MaterialType.isMaterial(material.name(), MaterialType.BERRIES)))
                    || movementData.isNearBed()
                    || movementData.isNearWall()) {
                return;
            }

            double locationY = profile.getPlayer().getLocation().getY();

            double deltaY = movementData.getDeltaY();
            double locationDeltaY = locationY - this.lastLocationY;
            boolean exempt =  movementData.isNearWater() || movementData.isNearLava() || movementData.isNearWebs();
            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();
            double fallDistance = profile.getPlayer().getFallDistance();

            if (deltaY != 0) verbose(this.getClass().getSimpleName(), buffer2, 4, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose (2)" +
                    MsgType.SECOND_THEME_COLOR.getMessage() +"\n * deltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                    + MsgType.SECOND_THEME_COLOR.getMessage() +"\n * locationDeltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                    + MsgType.SECOND_THEME_COLOR.getMessage() +"\n * ground "+MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                    + MsgType.SECOND_THEME_COLOR.getMessage() +"\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                    + MsgType.SECOND_THEME_COLOR.getMessage() +"\n * fallDistance "+MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance);

            if (deltaY > -0.43f && deltaY < -0.41f && locationDeltaY > 0.0 && serverGround && clientGround) {
                if (++buffer4 > 3) {
                    fail("Impossible deltaY",
                            "deltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nlocationDeltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                                    + "\nserverGround "+ MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                    + "\nclientGround "+ MsgType.MAIN_THEME_COLOR.getMessage() + clientGround);
                }

                verbose(this.getClass().getSimpleName(), buffer, 3, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose (4)\n * deltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * locationDeltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                        + "\n * clientGround "+MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                        + "\n * serverGround "+MsgType.MAIN_THEME_COLOR.getMessage()+serverGround);
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

            if (profile.getLastFallDamageTimer().passed(25)
                    && clientGround
                    && deltaY != 0
                    && !exempt
                    && !movementData.isMovingUp()
                    && !profile.isBouncingOnSlime()
                    && !CollisionUtils.isStandingOnMaterial(movementData.getLocation(), movementData.getNearbyBlocksResult(), true, MaterialType.HONEY)
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

                verbose(this.getClass().getSimpleName(), buffer2, 4, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose (2)\n * deltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * locationDeltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                        + "\n * ground +"+MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                        + "\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                        + "\n * fallDistance "+MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance);
            } else {
                buffer2 -= Math.min(buffer2, 0.125);
            }

            if (movementData.isMovingUp()) {
                buffer3 = 0;
                return;
            }

            if (clientGround
                    && deltaY > 0
                    && locationDeltaY > 0
                    && !exempt) {
                if (++buffer3 > 2) {
                    fail("Accelerating upwards while being on ground",
                            "deltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\nlocationDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                            + "\nground "+ MsgType.MAIN_THEME_COLOR.getMessage()+"true\nserverGround "+ MsgType.MAIN_THEME_COLOR.getMessage() + serverGround);
                }

                verbose(this.getClass().getSimpleName(), buffer3, 2, MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose (3)\n * deltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * locationDeltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + locationDeltaY
                        + "\n * ground&b true\n * serverGround "+MsgType.MAIN_THEME_COLOR.getMessage() + serverGround);
            } else {
                buffer3 -= Math.min(buffer3, 0.025);
            }

            this.lastLocationY = locationY;
            this.lastDeltaY = deltaY;
        }
    }
}
