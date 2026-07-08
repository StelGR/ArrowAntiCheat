package me.arrow.checks.impl.combat.velocity;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.util.Vector;

@Experimental
public class VelocityB extends Check {

    private double horizontalBuffer;
    private double predictedX, predictedZ, allowance = 0.001D;
    private int velocityTicks, attackPackets, fittedAttackReductions;
    private boolean lastGround, attack;

    private static final float[][] KEY_COMBOS = {
            {1.0F, -1.0F},
            {1.0F, 0.0F},
            {1.0F, 1.0F},
            {0.0F, -1.0F},
            {0.0F, 0.0F},
            {0.0F, 1.0F},
            {-1.0F, -1.0F},
            {-1.0F, 0.0F},
            {-1.0F, 1.0F}
    };

    private static final boolean[] BOOLEANS = {true, false};

    public VelocityB(Profile profile) {
        super(profile, CheckType.VELOCITY, "B", "Checks horizontal knockback");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                attack = true;
                attackPackets++;
            }

            return;
        }

        if (!isMovement(event)) {
            return;
        }

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.getMovementData() == null
                || profile.getVelocityData() == null
                || profile.getBlockProcessor() == null
                || profile.getConnectionData() == null
                || profile.getMovementData().isNearWall()
                || profile.getBlockProcessor().isUnderGhostBlock()
                || profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
            resetVelocityState();
            return;
        }

        MovementData movementData = profile.getMovementData();
        VelocityData velocityData = profile.getVelocityData();

        handleHorizontalVelocity(movementData, velocityData);

    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private void handleHorizontalVelocity(MovementData movementData, VelocityData velocityData) {
        int confirmedTicks = velocityData.getVelocityTicks();
        Vector velocity = velocityData.getVelocityfvc();

        if (confirmedTicks == 1 && velocity != null) {
            predictedX = velocity.getX();
            predictedZ = velocity.getZ();
            velocityTicks = 0;
            allowance = movementData.getMovingTicks() <= 1 ? movementData.getDeltaXZ() + 0.001D : 0.001D;
        }

        if (!canCheckHorizontalVelocity(movementData, confirmedTicks)) {
            horizontalBuffer = Math.max(horizontalBuffer - 0.065D, 0.0D);
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        if (attack) {
            fitAttackSlowdown(movementData);
        }

        double carryFriction = lastGround ? getCarryFriction(movementData) : 0.91D;
        predictedX = Math.abs(predictedX) < getVelocityClamp() ? 0.0D : predictedX;
        predictedZ = Math.abs(predictedZ) < getVelocityClamp() ? 0.0D : predictedZ;

        HorizontalInput input = findClosestInput(movementData, predictedX, predictedZ);

        if (input == null) {
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        if (input.jump) {
            double[] jumped = applySprintJump(predictedX, predictedZ, movementData.getLocation().getYaw());
            predictedX = jumped[0];
            predictedZ = jumped[1];
        }

        double[] moved = moveFlying(predictedX, predictedZ, input.strafe, input.forward, input.inputFriction, movementData.getLocation().getYaw());
        predictedX = moved[0];
        predictedZ = moved[1];

        double clientHorizontal = movementData.getDeltaXZ();
        double serverHorizontal = Math.hypot(predictedX, predictedZ);

        if (serverHorizontal <= 0.025D) {
            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        double ratio = clientHorizontal / serverHorizontal;
        double percent = ratio * 100.0D;
        double missing = serverHorizontal - clientHorizontal;
        double requiredPercent = getRequiredPercent();
        boolean jumped = isJumpStart(movementData);
        boolean reversed = isReversed(movementData, predictedX, predictedZ);
        boolean invalid = (percent < requiredPercent && Math.abs(missing) > allowance) || (reversed && !jumped);

        if (invalid) {
            horizontalBuffer = Math.min(15.0D, horizontalBuffer + Math.abs(1.975D - Math.abs(ratio)));

            if (horizontalBuffer > getMaxBuffer()) {
                fail("Invalid Horizontal Velocity",
                        "percent " + MsgType.MAIN_THEME_COLOR.getMessage() + percent
                                + "\nrequired " + MsgType.MAIN_THEME_COLOR.getMessage() + requiredPercent
                                + "\nratio " + MsgType.MAIN_THEME_COLOR.getMessage() + ratio
                                + "\nclient " + MsgType.MAIN_THEME_COLOR.getMessage() + clientHorizontal
                                + "\nserver " + MsgType.MAIN_THEME_COLOR.getMessage() + serverHorizontal
                                + "\nmissing " + MsgType.MAIN_THEME_COLOR.getMessage() + missing
                                + "\nallowance " + MsgType.MAIN_THEME_COLOR.getMessage() + allowance
                                + "\nkbX " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedX
                                + "\nkbZ " + MsgType.MAIN_THEME_COLOR.getMessage() + predictedZ
                                + "\npredictedX " + MsgType.MAIN_THEME_COLOR.getMessage() + input.motionX
                                + "\npredictedZ " + MsgType.MAIN_THEME_COLOR.getMessage() + input.motionZ
                                + "\ninputOffset " + MsgType.MAIN_THEME_COLOR.getMessage() + input.offset
                                + "\nstrafe " + MsgType.MAIN_THEME_COLOR.getMessage() + input.strafe
                                + "\nforward " + MsgType.MAIN_THEME_COLOR.getMessage() + input.forward
                                + "\nfriction " + MsgType.MAIN_THEME_COLOR.getMessage() + input.inputFriction
                                + "\nattackReductions " + MsgType.MAIN_THEME_COLOR.getMessage() + fittedAttackReductions
                                + "\nattacks " + MsgType.MAIN_THEME_COLOR.getMessage() + attackPackets
                                + "\njump " + MsgType.MAIN_THEME_COLOR.getMessage() + jumped + " | " + input.jump
                                + "\nreverse " + MsgType.MAIN_THEME_COLOR.getMessage() + reversed
                                + "\ntick " + MsgType.MAIN_THEME_COLOR.getMessage() + velocityTicks
                                + "\nvelocityTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + confirmedTicks
                                + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + horizontalBuffer);
            }

            resetVelocityState();
            finishMovementTick(movementData);
            return;
        }

        horizontalBuffer = Math.max(horizontalBuffer - 0.065D, 0.0D);
        predictedX *= carryFriction;
        predictedZ *= carryFriction;

        if (velocityTicks++ >= 8 || !hasHorizontalVelocity()) {
            resetVelocityState();
        }

        finishMovementTick(movementData);
    }

    private boolean canCheckHorizontalVelocity(MovementData movementData, int confirmedTicks) {
        return confirmedTicks >= 1
                && confirmedTicks <= 8
                && movementData != null
                && movementData.getLocation() != null
                && hasHorizontalVelocity()
                && (predictedX * predictedX) + (predictedZ * predictedZ) > getMoveOffset() + 0.001D;
    }

    private void fitAttackSlowdown(MovementData movementData) {
        double bestOffset = Math.hypot(movementData.getDeltaX() - predictedX, movementData.getDeltaZ() - predictedZ);
        double bestX = predictedX;
        double bestZ = predictedZ;
        int bestReductions = 0;

        double baseX = Math.abs(predictedX) < getVelocityClamp() ? 0.0D : predictedX;
        double baseZ = Math.abs(predictedZ) < getVelocityClamp() ? 0.0D : predictedZ;

        for (int i = 1; i <= Math.max(1, attackPackets); i++) {
            double reducedX = baseX * Math.pow(0.6D, i);
            double reducedZ = baseZ * Math.pow(0.6D, i);
            HorizontalInput input = findClosestInput(movementData, reducedX, reducedZ);

            if (input == null) {
                continue;
            }

            if (input.offset < bestOffset) {
                bestOffset = input.offset;
                bestX = reducedX;
                bestZ = reducedZ;
                bestReductions = i;
            }
        }

        predictedX = bestX;
        predictedZ = bestZ;
        fittedAttackReductions = bestReductions;
    }

    private HorizontalInput findClosestInput(MovementData movementData, double baseX, double baseZ) {
        HorizontalInput best = null;

        for (float[] keys : KEY_COMBOS) {
            for (boolean sprinting : BOOLEANS) {
                for (boolean blocking : BOOLEANS) {
                    for (boolean sneaking : BOOLEANS) {
                        for (boolean jumping : BOOLEANS) {
                            double motionX = baseX;
                            double motionZ = baseZ;
                            boolean appliedJump = jumping && sprinting && lastGround;

                            if (appliedJump) {
                                double[] jumped = applySprintJump(motionX, motionZ, movementData.getLocation().getYaw());
                                motionX = jumped[0];
                                motionZ = jumped[1];
                            }

                            float strafe = keys[0];
                            float forward = keys[1];

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

                            float inputFriction = getInputFriction(movementData, sprinting);
                            double[] moved = moveFlying(motionX, motionZ, strafe, forward, inputFriction, movementData.getLocation().getYaw());
                            double offset = Math.hypot(movementData.getDeltaX() - moved[0], movementData.getDeltaZ() - moved[1]);

                            if (best == null || offset < best.offset) {
                                best = new HorizontalInput(moved[0], moved[1], offset, strafe, forward, inputFriction, appliedJump);

                                if (offset <= 0.001D) {
                                    return best;
                                }
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    private double[] moveFlying(double motionX, double motionZ, float strafe, float forward, float friction, float yaw) {
        float f = (strafe * strafe) + (forward * forward);

        if (f >= 1.0E-4F) {
            f = (float) Math.sqrt(f);

            if (f < 1.0F) {
                f = 1.0F;
            }

            f = friction / f;
            strafe *= f;
            forward *= f;

            float radians = yaw * (float) Math.PI / 180.0F;
            float sin = (float) Math.sin(radians);
            float cos = (float) Math.cos(radians);

            motionX += strafe * cos - forward * sin;
            motionZ += forward * cos + strafe * sin;
        }

        return new double[]{motionX, motionZ};
    }

    private double[] applySprintJump(double motionX, double motionZ, float yaw) {
        float radians = yaw * (float) Math.PI / 180.0F;

        motionX -= Math.sin(radians) * 0.2D;
        motionZ += Math.cos(radians) * 0.2D;

        return new double[]{motionX, motionZ};
    }

    private boolean isReversed(MovementData movementData, double motionX, double motionZ) {
        boolean reversedX = Math.abs(motionX) > 0.025D && movementData.getDeltaX() / motionX < -0.05D;
        boolean reversedZ = Math.abs(motionZ) > 0.025D && movementData.getDeltaZ() / motionZ < -0.05D;

        return reversedX || reversedZ;
    }

    private boolean isJumpStart(MovementData movementData) {
        if (movementData == null || !movementData.isLastOnGround() || movementData.isOnGround() || movementData.getDeltaY() <= 0.0D) {
            return false;
        }

        double expected = 0.42D;

        if (profile.getPotionData() != null && profile.getPotionData().isHasJump()) {
            expected += profile.getPotionData().getJumpAmplifier() * 0.1D;
        }

        return Math.abs(movementData.getDeltaY() - expected) <= 0.03125D
                || movementData.getServerAirTicks() <= 1
                || movementData.getClientAirTicks() <= 1;
    }

    private float getInputFriction(MovementData movementData, boolean sprinting) {
        if (lastGround) {
            double friction = getCarryFriction(movementData);
            double factor = 0.16277136D / (friction * friction * friction);

            return (float) (MathUtil.getAttributeSpeed(profile, sprinting) * factor);
        }

        return sprinting ? 0.025999999F : 0.02F;
    }

    private double getCarryFriction(MovementData movementData) {
        double friction = movementData.getFrictionFactor();

        if (Double.isFinite(friction) && friction > 0.0D) {
            return Math.min(1.0D, friction);
        }

        return 0.91D;
    }

    private double getRequiredPercent() {
        double required = 99.99D;

        if (profile.getPlayer().getMaximumNoDamageTicks() < 10) {
            required -= 20.0D;
        }

        if (isModernClient()) {
            required -= 20.0D;
        }

        return required;
    }

    private double getMaxBuffer() {
        return isModernClient() ? 8.0D : 5.0D;
    }

    private double getVelocityClamp() {
        return isModernClient() ? 0.003D : 0.005D;
    }

    private double getMoveOffset() {
        return profile.getVersion() != null && profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_18_2) ? 0.0002D : 0.03D;
    }

    private boolean isModernClient() {
        return profile.getVersion() != null && !profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
    }

    private boolean hasHorizontalVelocity() {
        return (predictedX * predictedX) + (predictedZ * predictedZ) > 0.0001D;
    }

    private void resetVelocityState() {
        predictedX = 0.0D;
        predictedZ = 0.0D;
        velocityTicks = 0;
        allowance = 0.001D;
        attack = false;
        attackPackets = 0;
        fittedAttackReductions = 0;
    }

    private void finishMovementTick(MovementData movementData) {
        lastGround = movementData != null && movementData.isOnGround();
        attack = false;
        attackPackets = 0;
        fittedAttackReductions = 0;
    }

    private static class HorizontalInput {
        double motionX;
        double motionZ;
        double offset;
        float strafe;
        float forward;
        float inputFriction;
        boolean jump;

        private HorizontalInput(double motionX,
                                double motionZ,
                                double offset,
                                float strafe,
                                float forward,
                                float inputFriction,
                                boolean jump) {
            this.motionX = motionX;
            this.motionZ = motionZ;
            this.offset = offset;
            this.strafe = strafe;
            this.forward = forward;
            this.inputFriction = inputFriction;
            this.jump = jump;
        }
    }


}
