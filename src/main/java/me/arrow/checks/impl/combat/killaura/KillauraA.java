package me.arrow.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.CombatData;
import me.arrow.playerdata.data.impl.MovementData;
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
            handleMovement();
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
        attackTicks = 6;

        sprintingOnAttack =
                actionData.isSprinting()
                        || actionData.isLastSprinting()
                        || actionData.isLastLastSprinting();

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

    private void handleMovement() {

        if (profile.shouldCancel()) {
            reset();
            return;
        }

        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();

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

        /*
         * NoPackets can hide packet sprinting.
         * So this check accepts packet sprint OR movement-inferred sprint.
         */
        boolean sprinting =
                packetSprinting
                        || inferredSprinting
                        || sprintingOnAttack
                        || inferredSprintOnAttack;

        /*
         * This follows the Ness-style KeepSprint logic:
         * - recent hit
         * - valid player target
         * - fast horizontal speed
         * - still sprinting / sprint-like
         * - extremely low acceleration change
         *
         * Legit sprint hits usually create a visible motion disturbance.
         * KeepSprint stays smooth.
         */
        boolean invalid =
                lastAttackTargetPlayer
                        && swingDelay < 180L
                        && sprinting
                        && deltaXZ > 0.22D
                        && acceleration < 0.0035D;

        /*
         * Extra confirmation:
         * If packet sprint is false but movement still looks sprint-like,
         * that is suspicious with NoPackets-style sprint spoofing.
         */
        boolean noPacketSprintSuspicious =
                lastAttackTargetPlayer
                        && swingDelay < 180L
                        && !packetSprinting
                        && inferredSprinting
                        && deltaXZ > 0.22D
                        && acceleration < 0.006D;

        verbose(this.getClass().getSimpleName(), getBuffer(), 5.0D,
                "* KeepSprint Simple"
                        + "\n §f* invalid: §b" + invalid
                        + "\n §f* noPacketSprintSuspicious: §b" + noPacketSprintSuspicious
                        + "\n §f* deltaXZ: §b" + deltaXZ
                        + "\n §f* lastDeltaXZ: §b" + lastDeltaXZ
                        + "\n §f* acceleration: §b" + acceleration
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
            double add = invalid ? 1.0D : 0.65D;

            if (noPacketSprintSuspicious) {
                add += 0.35D;
            }

            keepSprintBuffer += add;

            if (increaseBufferBy(add) > 5.0D || keepSprintBuffer > 5.0D) {
                fail("KeepSprint",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaXZ
                                + "\nacceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + acceleration
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
                || movementData.isColliding()
                || profile.isBouncingOnSlime();
    }

    private boolean isMovement(PacketTypeCommon packetType) {
        return packetType.equals(PacketType.Play.Client.PLAYER_FLYING)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private void reset() {
        attackTicks = 0;
        lastAttackMillis = 0L;

        sprintingOnAttack = false;
        inferredSprintOnAttack = false;
        lastAttackTargetPlayer = false;

        lastDeltaXZ = 0.0D;
        keepSprintBuffer = 0.0D;

        resetBuffer();
    }
}