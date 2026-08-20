package me.arrow.checks.impl.movement.ground;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import lombok.Getter;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.customutils.OtherUtility;

// this is, the ghostblock processor, it blocks world guard block glitching to climb walls
// and also blocks almost every attempt to ghostblock fly

@Getter
public class GroundC extends Check {

    public GroundC(Profile profile) {
        super(profile, CheckType.GROUND, "C", "Ghostblock handler (Silent)");
    }

    double buffer;

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
                        || movementData.isNearShulkerBox()
                        || movementData.isNearShulker()
                        || movementData.isOnBoat()
                        || profile.isBouncingOnSlime()
                        || movementData.isNearBed()
                        || profile.isExempt().vehicle()
                        || movementData.isNearGhast()
                        || profile.getTick() < 120
                        || movementData.isNearBoat()) {
                    return;
                }

                boolean ground = movementData.isOnGround();

                boolean serverPositionGround = movementData.isPositionYGround();
                boolean serverPositionGroundLast = movementData.isLastPositionYGround();

                boolean serverYGround = movementData.isServerYGround();


                boolean serverGround = movementData.isServerGround();


                String verboseInfo = "clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + ground
                        + "\nserverPositionGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverPositionGround
                        + "\nserverPositionGroundLast " + MsgType.MAIN_THEME_COLOR.getMessage() + serverPositionGroundLast
                        + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                        + "\nserverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverYGround
                        + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                        + "\nnearEdge " + MsgType.MAIN_THEME_COLOR.getMessage() + CollisionUtils.isNearEdge(movementData.getLocation())
                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                        + "\nlocY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLocation().getY()
                        + "\nlocY (floor) " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.floor(movementData.getLocation().getY())
                        + "\nlocY - locY(floor) difference " + MsgType.MAIN_THEME_COLOR.getMessage() + (movementData.getLocation().getY() - Math.floor(movementData.getLocation().getY()));


                int trans = profile.getConnectionData().getClientTickTrans();

                /*
                 * A real support block can appear or disappear before every ground
                 * source (collision cache, client world and Bukkit world) agrees.
                 * The place support helper verifies that the block actually exists,
                 * so cancelled/ghost tower attempts do not receive this exemption.
                 */
                if (profile.getActionData().hasRecentUnderPlaceSupport(10 + (trans * 2))
                        || profile.getActionData().hasRecentConfirmedUnderBreak(5 + (trans * 2))
                        || profile.getActionData().hasRecentTowerBlockPlace(5 + (trans * 2), 2 + trans)
                        || profile.getActionData().hasRecentPistonUpdate(5 + (trans * 2))

                ) {
                    if (Config.Setting.DEBUG.getBoolean())
                        OtherUtility.log("Ground c: is Exempting (Block Update/Piston Update/Block Place/Block Break)");
                    return;
                }

                if (serverPositionGround && serverYGround && movementData.isCustomInAir() && ground && movementData.getCustomAirTicks() >= 2) {

                    fail("On Ghostblock? (1)", verboseInfo);

                    if (Config.Setting.DEBUG.getBoolean()) {
                        OtherUtility.log("[WARN] " + profile.getPlayer().getName() + " tripped the ghostblock check");
                    }
                }

                if (profile.getMovementData().getSinceOnGhostBlock() <= 1) {
//                boolean nearEdge = CollisionUtils.isNearEdge(movementData.getLocation());

                    if (movementData.getFallDistance() > 1.3 || movementData.getLastFallDistance() > 1.3) return;

                    fail("On Ghostblock? (2)", verboseInfo);

                    if (Config.Setting.DEBUG.getBoolean()) {
                        OtherUtility.log("[WARN] " + profile.getPlayer().getName() + " tripped the ghostblock check");
                    }
                }


                ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

                if (world.shouldHardSetback()) {
                    fail("WorldTracker: Real ghostblock collision", verboseInfo);
                    //movementData.setCustomAirTicks(0);

                    if (Config.Setting.DEBUG.getBoolean()) {
                        OtherUtility.log("[WARN] " + profile.getPlayer().getName() + " tripped the ghostblock check");
                    }
                }

//            if (profile.getBlockProcessor().isOnGhostBlock()) {
//                fail("BlockProcessor: On Ghostblock?", verboseInfo);
//
//                //movementData.setCustomAirTicks(0);
//                if (Config.Setting.DEBUG.getBoolean()) {
//                    OtherUtility.log("[WARN] " + profile.getPlayer().getName() + " tripped the ghostblock check");
//                }
//            }

                verbose(this.getClass().getSimpleName(), movementData.getCustomAirTicks(), 2, verboseInfo);
            } finally {
                Profiler.stop("Ground C (GBH)", profiler);
            }
        }
    }
}
