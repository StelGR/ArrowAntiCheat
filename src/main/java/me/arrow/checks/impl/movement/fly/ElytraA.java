package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.SampleList;

import static me.arrow.utils.customutils.Math.MathUtil.getDevation;

// very retarded sample based elytra check, works suprisingly well, somewhat, sometimes..

@Experimental
public class ElytraA extends Check {

    public ElytraA(Profile profile) {
        super(profile, CheckType.ELYTRA, "A", "Checks for weird elytra stuff");
    }


    SampleList<Double> samples = new SampleList<>(20);

    SampleList<Double> fallingSamples = new SampleList<>(30);

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            if (profile.shouldCancel()
                    || profile.isExempt().isTeleports()
                    || profile.isExempt().isDead()
                    || profile.getMovementData().getSinceRiptidingTicks() < 30
                    || profile.getVelocityData().getTotalHorizontalVelocity() > 0
                    || profile.getMovementData().getSinceGlitchedInsideBlockTicks() < 15 + profile.getConnectionData().getClientTickTrans()
                    || !profile.isWearingFunctionalElytra()) {
                return;
            }
            try {
                if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
                    if (Arrow.getInstance().getNmsManager().getNmsInstance().isSwimming(profile.getPlayer()) && profile.getMovementData().isNearWater()) {
                        return;
                    }
                }
            } catch (NoSuchMethodError ignored) {

            }

            MovementData movementData = profile.getMovementData();

            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();
            boolean inAir = movementData.isCustomInAir();
            double deltaY = movementData.getDeltaY();
            boolean isMoving = movementData.isMoving();
            int airTicks = movementData.getCustomAirTicks();
            double pitch = profile.getRotationData().getPitch();

            if (inAir && isMoving
                    && !profile.getPlayer().isGliding()
                    && deltaY > -0.4f
                    && movementData.getSinceNearWaterTicks() > 10) {
                if (deltaY != 0) {
                    samples.add(deltaY);

                    if (samples.isCollected()) {
                        final double deviation = getDevation(this.samples);

                        if (deviation < 0.2D && airTicks > 10) fail("Weird Elytra movement",
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                        + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nairTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + airTicks
                                        + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                        + "\ndeviation " + MsgType.MAIN_THEME_COLOR.getMessage() + deviation);
                    }
                }
            }

            verbose(this.getClass().getSimpleName(), deltaY, movementData.getDeltaXZ(), "* Verbose\n * deltaXZ: "+movementData.getDeltaXZ()
                    + "\n * deltaY "+ deltaY
                    + "\n * lastDeltaY" + movementData.getLastDeltaY()
            );


            if (inAir && profile.getPlayer().isGliding()
                    && !movementData.isUnderblock()
                    && movementData.getSinceInsideWaterTicks() > 10) {
                if (deltaY == movementData.getLastDeltaY()) {
                    fallingSamples.add(deltaY);

                    if (fallingSamples.isCollected()) {
                        final double deviation = getDevation(this.fallingSamples);

                        if (deviation == 0) fail("Invalid Elytra Glide (Not Falling)",
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                        + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                        + "\ndeviation " + MsgType.MAIN_THEME_COLOR.getMessage() + deviation);
                    }
                }
            }

            if (inAir && profile.getPlayer().isGliding()
                    && !movementData.isUnderblock()
                    && movementData.getSinceInsideWaterTicks() > 10
                    && movementData.getSinceBubbleTicks() > 15) {
                if (deltaY != movementData.getLastDeltaY()) {
                    if ( (Math.abs(pitch) <= 84) && (pitch > 15 || pitch < -15) && movementData.getDeltaXZ() == 0) {
                        fail("Impossible elytra movement",
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                        + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ()
                                        + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                        + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch);
                    }
                }
            }

            if (inAir && profile.getPlayer().isGliding() && (Math.abs(pitch) <= 85)
                    && (movementData.getDeltaXZ() > (3.6 + profile.getVelocityData().getVelocityHSustain())
                    || movementData.getDeltaY() > (2 + profile.getVelocityData().getVelocityV())
                    || movementData.getDeltaY() < (-3.2))
                    ) {
                fail("Terminal Velocity",
                        "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ()
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch);
            }
        }
    }
}
