package me.arrow.checks.impl.movement.illegalmove;

// impossible speed check and basic step check, i know it can be bypassed, if you can make a list in the material list for ALL minecraft blocks that are
// 1:1 full sized, not bigger or smaller, then you can make it adapt the limit to > 0.5 when you are next to those blocks
// but you need to account for the direction, cus you could have the issue where someone moves up, up a slab next to a full size block
// causing a false? maybe, idk, this check can be improved if you have the skills

// moved from MotionF

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;


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

            long profiler = Profiler.start();

            try {
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
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove A: is Exempting (reelingIn)");
                    return;
                }

                if (movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4)) {
                    if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("IllegalMove A: is Exempting (teleports)");
                    return;
                }

                double deltaY = movementData.getDeltaY();
                double deltaXZ = movementData.getDeltaXZ();

                String data = MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (1)\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                        + "\n * nearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isNearWall()
                        + "\n * lastNearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastNearWall()
                        + "\n * lastLastNearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastLastNearWall()
                        + "\n * nearWallPacket " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPacketNearWall()
                        + "\n * nearWall(2) " + MsgType.MAIN_THEME_COLOR.getMessage() + CollisionUtils.isNearWall(movementData.getLocation());

                String data2 = MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (2)\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                        + "\n * nearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isNearWall()
                        + "\n * lastNearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastNearWall()
                        + "\n * lastLastNearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastLastNearWall()
                        + "\n * nearWallPacket " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPacketNearWall()
                        + "\n * nearWall(2) " + MsgType.MAIN_THEME_COLOR.getMessage() + CollisionUtils.isNearWall(movementData.getLocation());

                String data3 = MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (3)\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                        + "\n * nearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isNearWall()
                        + "\n * lastNearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastNearWall()
                        + "\n * lastLastNearWall " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isLastLastNearWall()
                        + "\n * nearWallPacket " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPacketNearWall()
                        + "\n * nearWall(2) " + MsgType.MAIN_THEME_COLOR.getMessage() + CollisionUtils.isNearWall(movementData.getLocation());

                if (deltaY < -3.921
                        && !profile.isExempt().isTeleports()
                        && profile.getMovementData().getSinceRiptidingTicks() > 30
                        && !profile.getVelocityData().isTakingVelocity()) {
                    verbose(this.getClass().getSimpleName(), deltaY, -3.92, "DeltaY: " + data);
                    fail("Falling too fast", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
                }

                // checking for velocity here, is very useless, also i think jump ampliefier math is wrong
                // i haven't seen a false though

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
                    verbose(this.getClass().getSimpleName(), deltaXZ, 9.9, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (XZ)\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ);
                    fail("Impossible deltaXZ movement", "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ);
                }

                verbose(this.getClass().getSimpleName(), deltaY, 1, data2);


                int ghostPhysicsTicks = 10 + (profile.getConnectionData().getClientTickTrans() * 4);

                if (profile.getBlockProcessor().isGhostPhysicsPlacementExempt(ghostPhysicsTicks)) {
                    if (Config.Setting.DEBUG.getBoolean())
                        OtherUtility.log("IllegalMoveA: is Exempting (ghostblock liquid/web/pending physics place)");
                    return;
                }

                double stepHeight = 0.5975D;

                //temporary piston fix
                if (movementData.getSinceNearSlimeTicks() <= (15 + (profile.getConnectionData().getClientTickTrans() * 2))
                        && deltaY > MoveUtils.getJumpMotion(profile)
                        && movementData.getSinceNearPistonTicks() <= (20 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                    return;
                }

                if (SpeedUtilities.getJumpBoostPotionLevel(profile) > 0) {
                    stepHeight += (SpeedUtilities.getJumpBoostPotionLevel(profile) * 0.1F);
                }



                if (Config.Setting.COMPATIBILITY.getBoolean()) {
                    PlayerInventory playerinv = profile.getPlayer().getInventory();
                    ItemStack playerBoots = playerinv.getBoots();

                    if (playerBoots != null) {
                        if (playerBoots.getItemMeta() != null) {
                            if (playerBoots.getItemMeta().getLore() != null) {
                                if (playerBoots.getItemMeta().getLore().contains("Traveler")) {
                                    stepHeight += 0.5;
                                }
                            }
                        }
                    }
                }

                if ((deltaY > stepHeight)
                        && (movementData.isNearWall() || movementData.isLastNearWall() || movementData.isLastLastNearWall())
                        && (!profile.isBouncingOnSlime()
                        && !profile.isExempt().isTeleports()
                        && movementData.getSincePowderSnowTicks() > 20
                        && !(movementData.isOnBoat()
                        || movementData.isNearBoat())
                        && !movementData.isNearLava()
                        && !movementData.isNearWater()
                        && !movementData.isClimb()
                        && !profile.getVelocityData().isTakingVelocity()
                        && movementData.getSinceRiptidingTicks() > 15
                        && movementData.getSinceGlidingTicks() > 15)) {
                    verbose(this.getClass().getSimpleName(), deltaY, 1.0, data3);
                    fail("Step?", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                            + "\nmaxJumpHeight " + MsgType.MAIN_THEME_COLOR.getMessage() + stepHeight);
                }
            } finally {
                Profiler.stop("IllegalMove A", profiler);
            }
        }
    }
}
