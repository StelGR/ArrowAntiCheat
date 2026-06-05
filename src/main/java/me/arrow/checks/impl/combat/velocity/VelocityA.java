package me.arrow.checks.impl.combat.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;


@Experimental
public class VelocityA extends Check {

// this (probably) wont false in real combat, read velocity b comment for more info, but it's super basic, mainly tested only on 1.8

    public VelocityA(Profile profile) {
        super(profile, CheckType.VELOCITY, "A", "Checks vertical knockback");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    private double thresholdA, thresholdB, thresholdC;

    private static final Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    private static Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        addCauseIfPresent(set, "VOID");
        addCauseIfPresent(set, "POISON");
        addCauseIfPresent(set, "WITHER");
        addCauseIfPresent(set, "FALL");
        addCauseIfPresent(set, "MAGIC");
        addCauseIfPresent(set, "FIRE");
        addCauseIfPresent(set, "FIRE_TICK");
        addCauseIfPresent(set, "CAMPFIRE");
        addCauseIfPresent(set, "SUFFOCATION");
        addCauseIfPresent(set, "LIGHTNING");
        addCauseIfPresent(set, "CONTACT");
        addCauseIfPresent(set, "THORNS");
        addCauseIfPresent(set, "FLY_INTO_WALL");
        addCauseIfPresent(set, "CRAMMING");
        addCauseIfPresent(set, "WORLD_BORDER");
        return set;
    }

    private static void addCauseIfPresent(Set<EntityDamageEvent.DamageCause> set, String name) {
        try {
            set.add(EntityDamageEvent.DamageCause.valueOf(name));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isMovement(event)) {
            return;
        }



        MovementData movementData = profile.getMovementData();
        VelocityData velocityData = profile.getVelocityData();

        if (movementData.isMovingUp()
                || movementData.isNearWall()
                || movementData.isUnderblock()
                || movementData.getSinceMovingUpTicks() < 5
                || profile.getBlockProcessor().isUnderGhostBlock()
                || profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
            return;
        }


        invalidVerticalA(movementData, velocityData);
        invalidVerticalB(movementData, velocityData);
        invalidVerticalC(movementData, velocityData);
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }


    public void invalidVerticalA(MovementData movementData, VelocityData velocityData) {
        double deltaY = movementData.getDeltaY();

        double velocity = velocityData.getVelocityVfvc();

        double ratio = deltaY / velocity;

        if (deltaY < 0.42f && velocity < 2 && velocity > 0.2) {
            if (velocityData.getVelocityTicks() == 1
                    && !movementData.isOnGround() && movementData.isLastOnGround()) {

                if (ratio <= 0.99 && ratio >= 0.0) {
                    if (thresholdA++ > 2) {
                        fail("Invalid Vertical Velocity (1)", "velocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocity
                                + "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + ratio
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY);
                    }
                } else {
                    thresholdA -= Math.min(thresholdA, 0.0005);
                }
            }
        }
    }


    public void invalidVerticalB(MovementData movementData, VelocityData velocityData) {
        double deltaY = movementData.getDeltaY();

        double velocity = velocityData.getVelocityVfvc();

        double ratio = deltaY / velocity;

        if (velocityData.getVelocityTicks() == 1) {
            if (deltaY < 0.42f && velocity < 2 && velocity > 0.2) {
                if (ratio > 1.00001) {
                    if (thresholdB++ > 1) {
                        fail("Invalid Vertical Velocity (2)","deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY +
                                "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + ratio);
                    }
                } else {
                    thresholdB -= Math.min(thresholdB, 0.001);
                }
            }
        }
    }

    public void invalidVerticalC(MovementData movementData, VelocityData velocityData) {

        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8) && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {

            if (profile.getPlayer() != null && profile.getPlayer().getLastDamageCause() != null) {
                EntityDamageEvent.DamageCause cause = profile.getPlayer().getLastDamageCause().getCause();

                if (IGNORED_CAUSES.contains(cause)) {
                    return;
                }
            }
            double deltaY = movementData.getDeltaY();

            double velocity = velocityData.getVelocityVfvc();

            if (profile.getLastAttackByEntityTimer().hasNotPassed(20)
                    || profile.getLastShotByArrowTimer().hasNotPassed(20)) {

                if (velocityData.getVelocityTicks() == 1
                        && movementData.isLastOnGround()) {

                    if ((deltaY / velocity) == 0.0) {
                        if (thresholdC++ > 5 && velocity != -0.0783739241897089) {
                            fail("Invalid Vertical Velocity (3)", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nvelocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocity);
                        }
                    } else {
                        thresholdC -= Math.min(thresholdC, 0.125);
                    }
                }
            }
        }
    }
}