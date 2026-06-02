package me.arrow.checks.impl.combat.velocity;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;


@Experimental
public class VelocityB extends Check {

// this check, and velocity A, are my old experimental checks from when i made a 1.8 only anticheat, velocity B falses so it's disabled. if you can fix it, feel free.

    public VelocityB(Profile profile) {
        super(profile, CheckType.VELOCITY, "B", "Checks horizontal knockback");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    private double thresholdA, thresholdB;

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

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.getMovementData() == null
                || profile.getVelocityData() == null
                || movementData.isMovingUp()
                || movementData.isNearWall()
                || movementData.getSinceMovingUpTicks() < 5
                || profile.getBlockProcessor().isUnderGhostBlock()
                || profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
            return;
        }


        //invalidHorizontalA(movementData, velocityData);
        spoofVelocity(movementData, velocityData);
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }


    public void invalidHorizontalA(MovementData movementData, VelocityData velocityData) {
        double deltaXZ = movementData.getDeltaXZ();

        double velocityH = velocityData.getVelocityHfvc();

        velocityH -= MathUtil.movingFlyingV3(profile, true);

        double totalVelocity = deltaXZ / velocityH;

        if (totalVelocity < 0.99 && velocityData.getVelocityTicks() == 1) {
            if (++thresholdA > 8) {
                fail("Invalid Horizontal Velocity","velocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + totalVelocity);
            }
        } else {
            thresholdA -= Math.min(thresholdA, 0.025);
        }
    }


    public void spoofVelocity(MovementData movementData, VelocityData velocityData) {
        double deltaY = movementData.getDeltaY();

        double velocity = velocityData.getVelocityVfvc();

        if (velocityData.getVelocityTicks() == 1) {
            if (deltaY < 0.42f && velocity < 2 && velocity > 0.2) {
                if (movementData.isOnGround() && movementData.isLastOnGround()) {
                    if (++thresholdB > 3) {
                        fail("Spoofed ground velocity", "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                +"\nvelocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocity);
                    }
                } else {
                    thresholdB -= Math.min(thresholdB, 0.5);
                }
            }
        }
    }


}