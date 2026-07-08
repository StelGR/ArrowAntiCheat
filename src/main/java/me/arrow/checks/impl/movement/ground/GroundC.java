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
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;

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
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            MovementData movementData = profile.getMovementData();

            if (profile.shouldCancel()
                    || movementData.isNearShulkerBox()
                    || movementData.isNearShulker()
                    || movementData.isOnBoat()
                    || profile.isBouncingOnSlime()
                    || movementData.isNearBed()
                    || profile.isExempt().vehicle()
                    || movementData.isNearGhast()
                    || movementData.isNearBoat()) {
                return;
            }

            boolean ground = movementData.isOnGround();

            boolean serverPositionGround = movementData.isPositionYGround()
                    || movementData.isLastPositionYGround();


            boolean serverGround = movementData.isServerGround();


            String verboseInfo = "clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + ground
                    + "\nserverPositionGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverPositionGround
                    + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                    + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getCustomAirTicks()
                    + "\nnearEdge " + MsgType.MAIN_THEME_COLOR.getMessage() + CollisionUtils.isNearEdge(movementData.getLocation())
                    + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isCustomInAir()
                    + "\nlocY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLocation().getY()
                    + "\nlocY (floor) " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.floor(movementData.getLocation().getY())
                    + "\nlocY - locY(floor) difference " + MsgType.MAIN_THEME_COLOR.getMessage() + (movementData.getLocation().getY() - Math.floor(movementData.getLocation().getY()));

//            if (profile.getActionData().hasRecentConfirmedUnderBreak(5 + (profile.getConnectionData().getClientTickTrans() * 2))
//                    || profile.getActionData().hasRecentConfirmedUnderPlace(8 + (profile.getConnectionData().getClientTickTrans() * 2))
//                    || profile.getActionData().hasRecentPistonUpdate(5 + (profile.getConnectionData().getClientTickTrans() * 2))
//                    || profile.getActionData().hasRecentConfirmedBlockUpdateUnder(8 + (profile.getConnectionData().getClientTickTrans() * 2))) {
//                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Ground c: is Exempting (Block Update/Piston Update/Block Place/Block Break)");
//                return;
//            }

            if (profile.getMovementData().getSinceOnGhostBlock() <= 1) {
//                boolean nearEdge = CollisionUtils.isNearEdge(movementData.getLocation());

                if (movementData.getFallDistance() > 1.3 || movementData.getLastFallDistance() > 1.3) return;

                fail("On Ghostblock?", verboseInfo);

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

            if (profile.getBlockProcessor().isOnGhostBlock()) {
                fail("BlockProcessor: On Ghostblock?", verboseInfo);

                //movementData.setCustomAirTicks(0);
                if (Config.Setting.DEBUG.getBoolean()) {
                    OtherUtility.log("[WARN] " + profile.getPlayer().getName() + " tripped the ghostblock check");
                }
            }

            verbose(this.getClass().getSimpleName(), movementData.getCustomAirTicks(), 2 ,"mathFloor " + (movementData.getLocation().getY() - Math.floor(movementData.getLocation().getY()))
                    + "\ninAir " + movementData.isCustomInAir()
                    + "\nAirTicks " + movementData.getCustomAirTicks()
                    + "\nclientGround " + movementData.isOnGround()
                    + "\nfalldistance " + movementData.getFallDistance());

        }
    }
}
