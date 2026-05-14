package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.PotionData;
import me.arrow.playerdata.data.impl.VelocityData;

// very basic fast ladder check, falses alot

@Experimental
public class MotionG extends Check {

    public MotionG(Profile profile) {
        super(profile, CheckType.MOTION, "G", "Fast Ladder Check (Basic)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!(event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                        || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                        || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                        || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
        )) return;

        MovementData md = profile.getMovementData();
        VelocityData vd = profile.getVelocityData();
        PotionData pot = profile.getPotionData();

        if (profile.shouldCancel()
                || profile.getPlayer().isDead()
                || !profile.isExempt().isRespawned()
                || profile.isExempt().isTeleports()
                || profile.isBouncingOnSlime()
                || md.isNearWater()
                || vd.getTotalVerticalVelocity() > 0
        ) return;

        if (profile.getPlayer().isInsideVehicle()) return;

        final double deltaY = md.getDeltaY();
        final double lastDeltaY = md.getLastDeltaY();
        int clientAirTicks = md.getClientAirTicks();
        int serverAirTicks = md.getCustomAirTicks();


        boolean exempt = profile.isBouncingOnSlime()
                || md.isNearShulker()
                || md.isNearShulkerBox()
                || md.isNearBubble()
                || md.getSincePowderSnowTicks() < 5
                || md.getLadderTicks() < 10
                || md.isOnGround()
                || md.isLastOnGround()
                || pot.isHasLevitation()
                || clientAirTicks < 6;

        if (!exempt) {
            if (deltaY > 0.11760000228882465
                    && md.isClimb()) {
                fail("Fast Ladder?" ,"deltaY " + deltaY);
            }
        }
    }
}
