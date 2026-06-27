package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.customutils.OtherUtility;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.util.Vector;

// this is a very decent check, it accounts for acceleration and deceleration, the acceleration part is based off of OpenKarhu's speed b
// it does have alot of improvements though, but it has some issues with modified attribute speed, remember the goal of the anticheat is to work on  ALL minecraft versions.

@Experimental
public class SpeedB extends Check {
    public SpeedB(Profile profile) {
        super(profile, CheckType.SPEED, "B", "Checks for deceleration/acceleration");
    }


    float lastDeltaYaw;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            if (profile.shouldCancel()
                    || profile.getPlayer().isDead()
                    || !profile.isExempt().isRespawned()
                    || profile.isExempt().isTeleports()
                    || profile.isExempt().vehicle()) {
                vlBuffer = 0;
                return;
            }

            if (profile.getExempt().isReelingIn()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("SpeedB: is Exempting (reelingIn)");
                return;
            }



            MovementData movementData = profile.getMovementData();
            ActionData actionData = profile.getActionData();
            RotationData rotationData = profile.getRotationData();

            double deltaX = movementData.getDeltaX();
            double deltaZ = movementData.getDeltaZ();
            double deltaY = movementData.getDeltaY();
            double deltaXZ = movementData.getDeltaXZ();
            double lastDeltaXZ = movementData.getLastDeltaXZ();

            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();
            float movingTicks = movementData.getMovingTicks();
            int clientAirTicks = movementData.getClientAirTicks();
            boolean sprinting = actionData.isSprinting();

            double velocityH = profile.getVelocityData().getTotalHorizontalVelocity();
            float deltaYaw = rotationData.getDeltaYaw();
            double mdAccel = movementData.getAccelXZ();
            double accel = Math.abs(deltaXZ - lastDeltaXZ);

            calculateDeceleration(movementData, deltaXZ, lastDeltaXZ, deltaYaw, accel, mdAccel);


            if (profile.getBlockProcessor().isNearGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Speed B: is Exempting (near Ghostblock)");
                return;
            }

            calculateAcceleration(movementData, actionData, deltaX, deltaY, deltaZ, deltaXZ, clientGround, serverGround, clientAirTicks, movingTicks, velocityH, sprinting);

            this.lastDeltaYaw = deltaYaw;
        }
    }


    private static final float[][] KEY_COMBOS = {
            {1.0F, -1.0F},
            {1.0F,  0.0F},
            {1.0F,  1.0F},
            {0.0F, -1.0F},
            {0.0F,  0.0F},
            {0.0F,  1.0F},
            {-1.0F, -1.0F},
            {-1.0F,  0.0F},
            {-1.0F,  1.0F}
    };

    private Vector lastMove = new Vector(0.0, 0.0, 0.0);
    private double smallBuffer;
    private double vlBuffer;
    private double sustainVelocity;

    final double AIR_ICE_INCREMENT_PER_TICK = 0.1525;
    final double AIR_ICE_INCREMENT_PER_TICK_SMALLER = 0.06;
    final double AIR_MAX_ICE_SPEED_BOOST = 3.75;

    public void calculateAcceleration(MovementData movementData,
                                      ActionData actionData,
                                      double deltaX,
                                      double deltaY,
                                      double deltaZ,
                                      double deltaXZ,
                                      boolean clientGround,
                                      boolean serverGround,
                                      int clientAirTicks,
                                      float movingTicks,
                                      double velocityH,
                                      boolean sprinting) {

        boolean velocity = profile.getVelocityData().isTakingVelocity();



        if (checkValid(velocity) && movementData.getMovingUnderblockTicks() <= 0) {

            double attribute = SpeedUtilities.getMovementSpeedAttribute(profile);
            float attr = (float) attribute;
            float movementSpeedSP = (float) SpeedUtilities.getSprintingAttributeSpeed(profile);

            float friction = 0.91F;
            float force = 0.02F;
            float forceSprint = 0.026F;

            Vector move = new Vector(deltaX, 0.0, deltaZ);


            Vector compLastMove;
            if (movementData.isLastServerGround()) {
                compLastMove = lastMove.clone().multiply(movementData.getLastFrictionFactor());
            } else {
                compLastMove = lastMove.clone().multiply(0.91F);
            }

            Vector plainComp = compLastMove.clone();


            if (profile.getCombatData().getAttackedTicks() <= 1) {
                compLastMove.multiply(0.6);
            }

            float yaw = movementData.getLocation().getYaw();

            if (movementData.isLastOnGround() && !clientGround && deltaY >= 0.0) {
                float f = (float) (yaw * Math.PI / 180.0F);
                compLastMove.add(new Vector(-sin(f) * 0.2, 0.0, cos(f) * 0.2));
            }

            if (movementData.isLastOnGround()) {
                friction = movementData.getFrictionFactor();
                float f3 = friction * friction * friction;
                float constant = 0.16277136F;
                force = attr * constant / f3;
                forceSprint = movementSpeedSP * constant / f3;
            }

            if (movementData.getSinceGlidingTicks() < 10 + profile.getConnectionData().getClientTickTrans()) return;

            double threshold = movingTicks <= 3.0F ? 0.0325D : 0.0116D;

            threshold += movementData.isLastOnGround()
                    ? SpeedUtilities.getGroundAttributeBonus(profile) * 0.08D
                    : SpeedUtilities.getAirAttributeBonus(profile) * 0.08D;

            threshold += movementData.isLastOnGround()
                    ? SpeedUtilities.getGroundPotionBonus(profile) * 0.035D
                    : SpeedUtilities.getAirPotionBonus(profile) * 0.035D;

            if (deltaXZ < 0.25D && movingTicks <= 2.0F && movementData.isLastOnGround()) {
                threshold += 0.2D;
            }

            if (movementData.getSinceCollideTicks() <= 10 + profile.getConnectionData().getClientTickTrans()) {
                threshold += 0.1D;
            }

            if (movementData.isNearWebs()) {
                threshold += 0.1D;
            }

            if (movementData.getMovingOnHoneyTicks() > 0) {
                threshold += 0.15D;
            }

            if (profile.isExempt().isTeleports()) {
                threshold += 0.3D;
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + profile.getConnectionData().getClientTickTrans()) {
                threshold += 1.5D;
            }

            if (movementData.getLastNearEdgeTicks() <= 3) {
                if (velocity) {
                    threshold += profile.getVelocityData().getTotalHorizontalVelocity() + 0.5D;
                } else {
                    threshold += 0.5D;
                }
            }

            if (profile.getVelocityData().getVelocityTicks() <= 5 && !movementData.isLastOnGround()) {
                threshold += sustainVelocity * 1.25D + 0.6D;
            }

            double tMult = 1.00001D;
            verbose(this.getClass().getSimpleName(), vlBuffer, threshold, "* Verbose (accel) (1)\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                    + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                    + "\n * sprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting
                    + "\n * threshold " + MsgType.MAIN_THEME_COLOR.getMessage() + threshold
                    + "\n * buffer " + MsgType.MAIN_THEME_COLOR.getMessage() + vlBuffer
                    + "\n * friction " + MsgType.MAIN_THEME_COLOR.getMessage() + friction
                    + "\n * clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                    + "\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                    + "\n * velocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityH);

            if (!(deltaXZ > 0.1D)) {
                vlBuffer = Math.max(0.0, vlBuffer - 0.75);
            } else {
                Vector subtracted = move.clone().subtract(compLastMove);

                double bestNormal = Math.min(this.getBest(subtracted, false, forceSprint, true), this.getBest(subtracted, false, force, false));
                double bestBlocking = Math.min(this.getBest(subtracted, true, forceSprint, true), this.getBest(subtracted, true, force, false));

                if (bestNormal > threshold * tMult && bestBlocking > threshold * tMult) {
                    Vector subtractedPlain = move.clone().subtract(plainComp);

                    double bestNormal2 = Math.min(this.getBest(subtractedPlain, false, forceSprint, true), this.getBest(subtractedPlain, false, force, false));
                    double bestBlocking2 = Math.min(this.getBest(subtractedPlain, true, forceSprint, true), this.getBest(subtractedPlain, true, force, false));

                    if (bestNormal2 > threshold * tMult && bestBlocking2 > threshold * tMult) {
                        if (movingTicks <= 3.0F) {
                            if (++smallBuffer > 3.0) {
                                smallBuffer = 3.0;
                                vlBuffer = Math.max(0.0, vlBuffer - 0.075);
                            }
                            lastMove = new Vector(deltaX, 0.0, deltaZ);
                        } else {
                            smallBuffer = Math.max(0.0, smallBuffer - 0.01);
                        }

                        double closest = Math.min(Math.min(bestNormal, bestNormal2), Math.min(bestBlocking, bestBlocking2));
                        double bufferAddition = 0.0D;

                        float movingIceTicks = movementData.getMovingOnIceTicks();
                        double air_iceSpeedBoost;
                        if (movingIceTicks < 15) air_iceSpeedBoost = Math.min(AIR_ICE_INCREMENT_PER_TICK * movingIceTicks, AIR_MAX_ICE_SPEED_BOOST);
                        else air_iceSpeedBoost = Math.min(AIR_ICE_INCREMENT_PER_TICK_SMALLER * movingIceTicks, AIR_MAX_ICE_SPEED_BOOST);

                        double limit = serverGround ? 0.23D : 0.437D;

                        limit += serverGround
                                ? SpeedUtilities.getGroundSpeedLimitBonus(profile)
                                : SpeedUtilities.getAirSpeedLimitBonus(profile);

                        limit += air_iceSpeedBoost;
                        boolean currentlyRiptiding = movementData.getSinceRiptidingTicks() <= 15;
                        int riptideLevel = 0;
                        try {
                            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
                                org.bukkit.enchantments.Enchantment riptide = org.bukkit.enchantments.Enchantment.RIPTIDE;
                                org.bukkit.inventory.ItemStack main = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer());
                                if (main != null && main.containsEnchantment(riptide)) {
                                    riptideLevel = main.getEnchantmentLevel(riptide);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        limit += currentlyRiptiding ? (1.5 * riptideLevel) : 0;
                        limit += sustainVelocity / 2;
                        limit += movementData.elytraMomentum();
                        if (movementData.isLastOnGround() && !clientGround && deltaY >= 0.0D) {
                            limit += SpeedUtilities.getAirAttributeBonus(profile) * 0.45D;
                            limit += SpeedUtilities.getAirPotionBonus(profile) * 0.30D;
                            limit += SpeedUtilities.getAirAttributePotionBonus(profile) * 0.35D;
                        }
                        if (profile.getMovementData().getSinceSpeedPotionEffectTicks() < 15) {
                            limit += 0.05D + (0.01D * SpeedUtilities.getSpeedPotionLevel(profile));
                        }

                        boolean invalid = closest > limit && !profile.isBouncingOnSlime();

                        int required = bestNormal < 0.06 && actionData.getSinceSneakingTicks() <= 3 ? 50 : 30;

                        if (movementData.getSincePredictUpwardsTicks() < 10
                                || movementData.getSincePredictDownwardsTicks() < 10) {
                            vlBuffer = Math.max(0.0D, vlBuffer - 0.5D);
                            return;
                        }

                        if (invalid) {
                            double excess = closest - limit;
                            bufferAddition = Math.min(5.0D, Math.max(5D, excess * 17.25D));

                            if ((vlBuffer += bufferAddition) >= required) {
                                fail("Invalid acceleration",
                                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                                + "\nprediction " + MsgType.MAIN_THEME_COLOR.getMessage() + closest
                                                + "\nlimit " + MsgType.MAIN_THEME_COLOR.getMessage() + limit
                                                + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + excess
                                                + "\nfriction " + MsgType.MAIN_THEME_COLOR.getMessage() + friction
                                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                                + "\nserverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                                + "\nvelocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityH);

                                vlBuffer = Math.max(60D, vlBuffer);
                            }
                        } else {
                            vlBuffer = Math.max(0.0D, vlBuffer - 0.075D);
                        }

                        verbose(this.getClass().getSimpleName(), vlBuffer, required, "* Verbose (accel) (2)\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\n * predictedB1 " + MsgType.MAIN_THEME_COLOR.getMessage() + bestBlocking
                                + "\n * predictedB2 " + MsgType.MAIN_THEME_COLOR.getMessage() + bestBlocking2
                                + "\n * predictedN1 " + MsgType.MAIN_THEME_COLOR.getMessage() + bestNormal
                                + "\n * predictedN2 " + MsgType.MAIN_THEME_COLOR.getMessage() + bestNormal2
                                + "\n * closest " + MsgType.MAIN_THEME_COLOR.getMessage() + closest
                                + "\n * addition " + MsgType.MAIN_THEME_COLOR.getMessage() + bufferAddition
                                + "\n * sprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + sprinting
                                + "\n * threshold " + MsgType.MAIN_THEME_COLOR.getMessage() + required
                                + "\n * buffer " + MsgType.MAIN_THEME_COLOR.getMessage() + vlBuffer
                                + "\n * limit " + MsgType.MAIN_THEME_COLOR.getMessage() + limit
                                + "\n * friction " + MsgType.MAIN_THEME_COLOR.getMessage() + friction
                                + "\n * clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\n * velocity " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityH);

                    } else {
                        vlBuffer = Math.max(0.0, vlBuffer - 0.05);
                    }
                } else {
                    vlBuffer = Math.max(0.0, vlBuffer - 0.05);
                }
            }

            lastMove = new Vector(deltaX, 0.0, deltaZ);

            if (velocity) {
                sustainVelocity = profile.getVelocityData().getTotalHorizontalVelocity();
            }

        } else {
            lastMove = new Vector(deltaX, 0.0, deltaZ);
        }
    }


    public void calculateDeceleration(MovementData movementData, double deltaXZ, double lastDeltaXZ, double deltaYaw, double accel, double mdAccel) {
        double squaredAccel = accel * 100;
        boolean exempt = squaredAccel != 0
                && !(movementData.isNearWater() || movementData.isNearLava() || movementData.isNearWebs() || movementData.isNearClimbable());

        if (deltaYaw > 1.5f
                && deltaYaw != lastDeltaYaw
                && deltaXZ > .15D
                && squaredAccel < 1.0E-5
                && exempt) {
            if (increaseBuffer() > 1) {
                fail("Invalid deceleration",
                        "deltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaYaw
                                + "\nlastDeltaYaw " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaYaw
                                + "\naccel " + MsgType.MAIN_THEME_COLOR.getMessage() + accel
                                + "\naccel (* 100) " + MsgType.MAIN_THEME_COLOR.getMessage() + squaredAccel
                                + "\nmdAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + mdAccel
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaXZ);
            }
        }
        else decreaseBufferBy(0.005);
    }


    private boolean checkValid(boolean velocity) {
        MovementData movementData = profile.getMovementData();

        if (velocity) return false;
        if (profile.isExempt().isTeleports()) return false;
        if (profile.shouldCancel()) return false;
        if (profile.getMovementData().getSinceOnGhostBlock() <= 15 + profile.getConnectionData().getClientTickTrans()) return false;
        if (!profile.isExempt().isRespawned()) return false;
        if (profile.isExempt().vehicle()) return false;
        if (movementData.isNearBoat() || movementData.isOnBoat()) return false;
        if (movementData.isNearBuggyBlock()) return false;
        if (movementData.isNearBed()) return false;
        if (movementData.isIntersecting()) return false;
        if (movementData.isNearWater() || movementData.isNearLava()) return false;
        if (movementData.isNearClimbable()) return false;
        if (movementData.isNearWebs()) return false;
        return !profile.isBouncingOnSlime();
        //if (movementData.getSinceSoulTicks() >= 3) return false;
    }

    private double getBest(Vector move, boolean blocking, float friction, boolean sprint) {
        double lowestMatch = Double.MAX_VALUE;

        for (float[] floats : KEY_COMBOS) {
            for (boolean sneaking : new boolean[]{false, true}) {
                float strafe = floats[0];
                float forward = floats[1];
                Vector moveFlying = this.moveFlying(strafe, forward, blocking, sneaking, friction);
                double diffX = Math.abs(move.getX() - moveFlying.getX());
                double diffZ = Math.abs(move.getZ() - moveFlying.getZ());
                double[] diffXZ = new double[]{diffX, diffZ};
                lowestMatch = Math.min(lowestMatch, hypot(diffXZ));
            }
        }

        return lowestMatch;
    }


    public Vector moveFlying(float strafe, float forward, boolean blocking, boolean sneaking, float friction) {
        if (sneaking) {
            strafe *= 0.3F;
            forward *= 0.3F;
        }

        if (blocking) {
            strafe *= 0.2F;
            forward *= 0.2F;
        }

        strafe *= 0.98F;
        forward *= 0.98F;
        float f = strafe * strafe + forward * forward;
        if (f >= 1.0E-4F) {
            f = sqrt_float(f);
            if (f < 1.0F) {
                f = 1.0F;
            }

            f = friction / f;
            strafe *= f;
            forward *= f;
            float f1 = sin(profile.getMovementData().getLocation().getYaw() * (float) Math.PI / 180.0F);
            float f2 = cos(profile.getMovementData().getLocation().getYaw() * (float) Math.PI / 180.0F);
            float xAdd = strafe * f2 - forward * f1;
            float zAdd = forward * f2 + strafe * f1;
            return new Vector(xAdd, 0.0F, zAdd);
        } else {
            return new Vector(0, 0, 0);
        }
    }


    private static final float[] SIN_TABLE_FAST = new float[4096];
    private static final float[] SIN_TABLE_FAST_NEW = new float[4096];
    public static boolean fastMath = false;
    private static final float[] SIN_TABLE = new float[65536];

    public static float sin(float value) {
        return fastMath ? SIN_TABLE_FAST[(int)(value * 651.8986F) & 4095] : SIN_TABLE[(int)(value * 10430.378F) & 65535];
    }

    public static float cos(float value) {
        return fastMath ? SIN_TABLE_FAST[(int)((value + (float) (Math.PI / 2)) * 651.8986F) & 4095] : SIN_TABLE[(int)(value * 10430.378F + 16384.0F) & 65535];
    }

    public static float sin(boolean fastMath, float value) {
        return fastMath ? SIN_TABLE_FAST[(int)(value * 651.8986F) & 4095] : SIN_TABLE[(int)(value * 10430.378F) & 65535];
    }

    public static float cos(boolean fastMath, float value) {
        return fastMath ? SIN_TABLE_FAST[(int)((value + (float) (Math.PI / 2)) * 651.8986F) & 4095] : SIN_TABLE[(int)(value * 10430.378F + 16384.0F) & 65535];
    }


    public static double hypot(double... value) {
        double total = 0.0;

        for (double val : value) {
            total += val * val;
        }

        return FastMath.sqrt(total);
    }

    public static float sqrt_float(float value) {
        return (float)Math.sqrt(value);
    }
}
