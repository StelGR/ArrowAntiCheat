package me.arrow.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.CombatData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;

public class KillauraA extends Check {

    private double lastDeltaXZ;
    private double keepSprintBuffer;

    private long lastAttackMillis;
    private int attackTicks;

    private boolean lastAttackTargetPlayer;
    private boolean sprintingOnAttack;
    private boolean inferredSprintOnAttack;
    private boolean attackEvaluated;

    public KillauraA(Profile profile) {
        super(profile, CheckType.KILLAURA, "A", "Checks for keep sprint (Credits: MrCloudMan)");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {

        PacketTypeCommon packetType = event.getPacketType();

        if (packetType.equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            handleAttack(event);
            return;
        }

        if (isMovement(packetType)) {
            handleMovement(packetType);
        }
    }

    private void handleAttack(PacketReceiveEvent event) {

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);

        if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();

        /*
         * Register attack immediately.
         * Do not return early because target tracking failed.
         */
        lastAttackMillis = System.currentTimeMillis();
        attackTicks = 4;
        attackEvaluated = false;

        sprintingOnAttack =
                actionData.isSprinting()
                        || actionData.isLastSprinting();

        inferredSprintOnAttack = isMovingLikeSprint(movementData);

        lastAttackTargetPlayer = resolveTargetPlayer(packet.getEntityId());

        CombatData combatData = profile.getCombatData();

        try {
            Player attacked = Bukkit.getPlayer(
                    Objects.requireNonNull(combatData.getTrackedEntities().get(packet.getEntityId()))
            );

            if (attacked != null) {
                combatData.setTarget(attacked.getEntityId());
            }
        } catch (Exception ignored) {
        }
    }

    private void handleMovement(PacketTypeCommon packetType) {

        if (profile.shouldCancel()) {
            reset();
            return;
        }

        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();

        /*
         * Rotation/on-ground-only packets reuse the last positional delta.
         * Treating them as a knockback response creates an artificial 0.0
         * acceleration and was the primary modern false positive.
         */
        if (!hasPositionUpdate(packetType)) {
            if (attackTicks > 0) attackTicks--;
            return;
        }

        double deltaXZ = movementData.getDeltaXZ();
        double acceleration = Math.abs(deltaXZ - lastDeltaXZ);

        /*
         * Always update at the end too, but keep this value available
         * for the current movement calculation.
         */
        if (attackTicks <= 0) {
            lastDeltaXZ = deltaXZ;
            keepSprintBuffer = Math.max(0.0D, keepSprintBuffer - 0.03D);
            decreaseBufferBy(0.04D);
            return;
        }

        attackTicks--;

        if (attackEvaluated) {
            lastDeltaXZ = deltaXZ;
            return;
        }

        if (hardExempt(movementData)) {
            lastDeltaXZ = deltaXZ;
            attackTicks = 0;
            keepSprintBuffer = Math.max(0.0D, keepSprintBuffer - 0.25D);
            decreaseBufferBy(0.15D);
            return;
        }

        long swingDelay = System.currentTimeMillis() - lastAttackMillis;

        boolean packetSprinting =
                actionData.isSprinting()
                        || actionData.isLastSprinting()
                        || actionData.isLastLastSprinting();

        boolean inferredSprinting = isMovingLikeSprint(movementData);
        boolean legacyClient = profile.getVersion() != null
                && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
        double lateralRatio = getLateralMovementRatio(movementData);
        boolean pureLegacyStrafe = legacyClient && lateralRatio >= 0.82D;

        /*
         * NoPackets can hide packet sprinting.
         * So this check accepts packet sprint OR movement-inferred sprint.
         */
        boolean sprinting =
                packetSprinting
                        || inferredSprinting
                        || sprintingOnAttack
                        || inferredSprintOnAttack;

        double slipperiness = movementData.getLastFrictionFactor();

        if (!Double.isFinite(slipperiness) || slipperiness <= 0.0D) {
            slipperiness = 0.6D;
        }

        double carryFriction = movementData.isLastLastOnGround()
                ? Math.min(1.0D, slipperiness) * 0.91D
                : 0.91D;
        double expectedPostHitX = movementData.getLastDeltaX() * carryFriction * 0.6D;
        double expectedPostHitZ = movementData.getLastDeltaZ() * carryFriction * 0.6D;
        double expectedPostHitXZ = Math.hypot(expectedPostHitX, expectedPostHitZ);
        double attackMotionError = Math.abs(deltaXZ - expectedPostHitXZ);
        double movementAllowance = Math.max(0.13D, MathUtil.getAttributeSpeed(profile, true));

        /*
         * A 1.8 sprint hit multiplies the attacker's carried X/Z motion by
         * 0.6. Karhu compares the following movement against that result; it
         * does not assume that any smooth forward sprint is KeepSprint.
         */
        boolean legacyInvalid = legacyClient
                && lastAttackTargetPlayer
                && swingDelay < 180L
                && sprintingOnAttack
                && !pureLegacyStrafe
                && deltaXZ > 0.1D
                && attackMotionError > movementAllowance
                && acceleration < 0.005D;

        boolean modernInvalid = !legacyClient
                && lastAttackTargetPlayer
                && swingDelay < 180L
                && sprinting
                && packetSprinting
                && lastDeltaXZ > 0.245D
                && deltaXZ > 0.255D
                && acceleration < 0.0015D;

        boolean invalid = legacyInvalid || modernInvalid;

        // A spoofed stop-sprint packet is extra evidence only after the same
        // post-hit motion prediction has already failed.
        boolean noPacketSprintSuspicious = legacyInvalid && !packetSprinting;

        attackEvaluated = true;
        attackTicks = 0;

        verbose(this.getClass().getSimpleName(), getBuffer(), 5.0D,
                "* KeepSprint Simple"
                        + "\n §f* invalid: §b" + invalid
                        + "\n §f* noPacketSprintSuspicious: §b" + noPacketSprintSuspicious
                        + "\n §f* deltaXZ: §b" + deltaXZ
                        + "\n §f* lastDeltaXZ: §b" + lastDeltaXZ
                        + "\n §f* acceleration: §b" + acceleration
                        + "\n §f* expectedPostHitXZ: §b" + expectedPostHitXZ
                        + "\n §f* attackMotionError: §b" + attackMotionError
                        + "\n §f* movementAllowance: §b" + movementAllowance
                        + "\n §f* legacyClient: §b" + legacyClient
                        + "\n §f* lateralRatio: §b" + lateralRatio
                        + "\n §f* pureLegacyStrafe: §b" + pureLegacyStrafe
                        + "\n §f* swingDelay: §b" + swingDelay
                        + "\n §f* packetSprinting: §b" + packetSprinting
                        + "\n §f* inferredSprinting: §b" + inferredSprinting
                        + "\n §f* sprintingOnAttack: §b" + sprintingOnAttack
                        + "\n §f* inferredSprintOnAttack: §b" + inferredSprintOnAttack
                        + "\n §f* targetPlayer: §b" + lastAttackTargetPlayer
                        + "\n §f* attackTicks: §b" + attackTicks
                        + "\n §f* buffer: §b" + keepSprintBuffer
        );

        if (invalid || noPacketSprintSuspicious) {
            double add = invalid ? (legacyClient ? 0.85D : 0.65D) : 0.55D;

            if (noPacketSprintSuspicious) {
                add += 0.35D;
            }

            keepSprintBuffer += add;

            double required = legacyClient ? 5.0D : 7.0D;

            if (increaseBufferBy(add) > required || keepSprintBuffer > required) {
                fail("KeepSprint",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaXZ
                                 + "\nacceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + acceleration
                                + "\nexpectedPostHitXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedPostHitXZ
                                + "\nattackMotionError " + MsgType.MAIN_THEME_COLOR.getMessage() + attackMotionError
                                + "\nmovementAllowance " + MsgType.MAIN_THEME_COLOR.getMessage() + movementAllowance
                                + "\nlegacyClient " + MsgType.MAIN_THEME_COLOR.getMessage() + legacyClient
                                + "\nlateralRatio " + MsgType.MAIN_THEME_COLOR.getMessage() + lateralRatio
                                + "\nswingDelay " + MsgType.MAIN_THEME_COLOR.getMessage() + swingDelay
                                + "\npacketSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + packetSprinting
                                + "\ninferredSprinting " + MsgType.MAIN_THEME_COLOR.getMessage() + inferredSprinting
                                + "\nsprintingOnAttack " + MsgType.MAIN_THEME_COLOR.getMessage() + sprintingOnAttack
                                + "\ninferredSprintOnAttack " + MsgType.MAIN_THEME_COLOR.getMessage() + inferredSprintOnAttack
                                + "\ntargetPlayer " + MsgType.MAIN_THEME_COLOR.getMessage() + lastAttackTargetPlayer
                                + "\nattackTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + attackTicks
                                + "\nkeepSprintBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + keepSprintBuffer
                                + "\nbuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + getBuffer());

                keepSprintBuffer = Math.max(5.25D, keepSprintBuffer);
            }
        } else {
            keepSprintBuffer = Math.max(0.0D, keepSprintBuffer - 0.20D);
            decreaseBufferBy(0.15D);
        }

        lastDeltaXZ = deltaXZ;
    }

    private boolean resolveTargetPlayer(int entityId) {
        try {
            CombatData combatData = profile.getCombatData();

            Player attacked = Bukkit.getPlayer(
                    Objects.requireNonNull(combatData.getTrackedEntities().get(entityId))
            );

            return attacked != null;
        } catch (Exception ignored) {
            /*
             * If your trackedEntities map is unreliable, this will be false.
             * That is safer for false positives, but it means the check will not flag.
             */
            return false;
        }
    }

    private boolean isMovingLikeSprint(MovementData movementData) {

        if (hardExempt(movementData)) {
            return false;
        }

        double deltaXZ = movementData.getDeltaXZ();

        /*
         * Supporting evidence only.
         */
        if (movementData.isOnGround() || movementData.isServerGround()) {
            return deltaXZ > 0.205D;
        }

        return deltaXZ > 0.260D;
    }

    private boolean hardExempt(MovementData movementData) {
        return profile.getPlayer().isInsideVehicle()
                || profile.isExempt().isTeleports()
                || profile.getVelocityData().isTakingVelocity()

                /*
                 * Do not hard-exempt velocityTicks <= 5.
                 * It can stop this check from ever running depending on your data system.
                 */

                || movementData.getDeltaXZ() < 0.04D
                || movementData.isOnBoat()
                || movementData.isNearBoat()
                || movementData.isInsideWater()
                || movementData.isNearWater()
                || movementData.isNearWebs()
                || movementData.isNearClimbable()
                || movementData.isOnSlime()
                || movementData.isOnHoney()
                || movementData.isOnIce()
                || movementData.isColliding()
                || profile.isBouncingOnSlime();
    }

    private boolean isMovement(PacketTypeCommon packetType) {
        return packetType.equals(PacketType.Play.Client.PLAYER_FLYING)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean hasPositionUpdate(PacketTypeCommon packetType) {
        return packetType != null
                && (packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION));
    }

    private double getLateralMovementRatio(MovementData movementData) {
        double horizontal = movementData.getDeltaXZ();

        if (horizontal <= 1.0E-6D || profile.getRotationData() == null) {
            return 0.0D;
        }

        double yaw = Math.toRadians(profile.getRotationData().getYaw());
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double lateral = Math.abs(movementData.getDeltaX() * rightX + movementData.getDeltaZ() * rightZ);
        return Math.min(1.0D, lateral / horizontal);
    }

    private void reset() {
        attackTicks = 0;
        lastAttackMillis = 0L;

        sprintingOnAttack = false;
        inferredSprintOnAttack = false;
        lastAttackTargetPlayer = false;
        attackEvaluated = false;

        lastDeltaXZ = 0.0D;
        keepSprintBuffer = 0.0D;

        resetBuffer();
    }
}
