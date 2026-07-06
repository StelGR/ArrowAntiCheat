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
import org.bukkit.util.Vector;

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

        if (profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.getMovementData() == null
                || profile.getVelocityData() == null
                || movementData.isMovingUp()
                || movementData.isNearWall()
                || movementData.getSinceMovingUpTicks() < 5
                || profile.getBlockProcessor().isUnderGhostBlock()) {
            return;
        }


        invalidHorizontalA(movementData, velocityData);
        spoofVelocity(movementData, velocityData);
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }


    public void invalidHorizontalA(MovementData movementData, VelocityData velocityData) {
        if (velocityData.getVelocityTicks() != 1) {
            thresholdA -= Math.min(thresholdA, 0.05D);
            return;
        }

        Vector velocity = velocityData.getVelocityfvc();

        if (velocity == null) {
            thresholdA -= Math.min(thresholdA, 0.05D);
            return;
        }

        double velocityH = Math.hypot(velocity.getX(), velocity.getZ());

        if (velocityH < 0.075D) {
            thresholdA -= Math.min(thresholdA, 0.05D);
            return;
        }

        Vector actual = new Vector(movementData.getDeltaX(), 0.0D, movementData.getDeltaZ());
        Vector lastActual = new Vector(movementData.getLastDeltaX(), 0.0D, movementData.getLastDeltaZ());
        Vector expectedDirection = velocity.clone().setY(0.0D).normalize();

        double projected = actual.dot(expectedDirection);
        double lastProjected = lastActual.dot(expectedDirection);
        double friction = getHorizontalCarryFriction(movementData);
        double compensatedProjected = projected - (lastProjected * friction);
        double movementAllowance = Math.max(0.0D, MathUtil.movingFlyingV3(profile, false));
        double stateAllowance = getHorizontalVelocityStateAllowance(movementData);
        double expected = Math.max(0.025D, velocityH - movementAllowance - stateAllowance);
        double ratio = Math.max(Math.max(0.0D, projected), Math.max(0.0D, compensatedProjected)) / expected;

        if (ratio < 0.62D) {
            double add = ratio < 0.25D ? 1.25D : 0.75D;

            if ((thresholdA += add) > 5.0D) {
                fail("Invalid Horizontal Velocity",
                        "ratio " + MsgType.MAIN_THEME_COLOR.getMessage() + ratio
                                + "\nprojected " + MsgType.MAIN_THEME_COLOR.getMessage() + projected
                                + "\ncompensated " + MsgType.MAIN_THEME_COLOR.getMessage() + compensatedProjected
                                + "\nlastProjected " + MsgType.MAIN_THEME_COLOR.getMessage() + lastProjected
                                + "\nexpected " + MsgType.MAIN_THEME_COLOR.getMessage() + expected
                                + "\nvelocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityH
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ()
                                + "\nmoveAllowance " + MsgType.MAIN_THEME_COLOR.getMessage() + movementAllowance
                                + "\nstateAllowance " + MsgType.MAIN_THEME_COLOR.getMessage() + stateAllowance
                                + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientAirTicks()
                                + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getServerAirTicks());
                thresholdA = Math.max(6.0D, thresholdA);
            }
        } else {
            thresholdA -= Math.min(thresholdA, 0.35D);
        }
    }

    private double getHorizontalCarryFriction(MovementData movementData) {
        if (movementData == null) {
            return 0.91D;
        }

        if (movementData.isOnGround() || movementData.isLastOnGround()) {
            double friction = movementData.getFrictionFactor();

            if (Double.isFinite(friction) && friction > 0.0D) {
                return Math.min(1.0D, friction);
            }
        }

        return 0.91D;
    }

    private double getHorizontalVelocityStateAllowance(MovementData movementData) {
        double allowance = 0.030D;

        if (movementData.getClientAirTicks() <= 3 || movementData.getServerAirTicks() <= 3) {
            allowance += 0.060D;
        }

        if (movementData.getDeltaY() > 0.0D || movementData.getLastDeltaY() > 0.0D) {
            allowance += 0.045D;
        }

        if (movementData.getSinceCollideTicks() < 5 + profile.getConnectionData().getClientTickTrans()) {
            allowance += 0.040D;
        }

        if (movementData.isOnGround() || movementData.isLastOnGround()) {
            allowance += 0.025D;
        }

        return allowance;
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
