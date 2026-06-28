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
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.SampleList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import static me.arrow.utils.customutils.Math.MathUtil.getDevation;

// very retarded sample based elytra check, works suprisingly well, somewhat, sometimes..

@Experimental
public class ElytraA extends Check {

    public ElytraA(Profile profile) {
        super(profile, CheckType.ELYTRA, "A", "Checks for weird elytra stuff");
    }


    SampleList<Double> samples = new SampleList<>(20);

    SampleList<Double> fallingSamples = new SampleList<>(30);

    int elytraTicks;
    int rocketBoostTicks;
    int rocketBoostGraceTicks;
    int lastRocketPower = 1;

    int pitchUpSpeedGainTicks;
    int upwardNoRocketTicks;
    int sustainedBoostTicks;

    double lastElytraDeltaXZ;
    double lastElytraDeltaY;

    double terminalBuffer;
    double planeBuffer;

    int possibleRocketUseTicks;
    int rocketInferenceCooldownTicks;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.USE_ITEM)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            handlePossibleFireworkUse();
        }

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
                    && !movementData.isNearWater()
                    && !movementData.isNearLava()
                    && movementData.getSinceInsideWaterTicks() > 10
                    && movementData.getSinceBubbleTicks() > 15) {
                if (deltaY != movementData.getLastDeltaY()) {
                    if ( (Math.abs(pitch) <= 84) && (pitch > 15 || pitch < -15) && movementData.getDeltaXZ() == 0 && movementData.getLastDeltaXZ() == 0) {
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

            tickElytraState(profile.getPlayer().isGliding());

            handleDynamicTerminalVelocity(
                    movementData,
                    serverGround,
                    clientGround,
                    inAir,
                    pitch
            );
        }
    }


    private void handleDynamicTerminalVelocity(MovementData movementData,
                                               boolean serverGround,
                                               boolean clientGround,
                                               boolean inAir,
                                               double pitch) {
        if (!inAir || !profile.getPlayer().isGliding()) {
            terminalBuffer = Math.max(0, terminalBuffer - 0.25);
            planeBuffer = Math.max(0, planeBuffer - 0.25);
            return;
        }

        if (movementData.isUnderblock()
                || movementData.isNearWater()
                || movementData.isNearLava()
                || movementData.getSinceInsideWaterTicks() <= 10
                || movementData.getSinceBubbleTicks() <= 15
                || movementData.getSinceTeleportTicks() <= 10
                || movementData.getSinceRiptidingTicks() < 30
                || profile.getVelocityData().getTotalHorizontalVelocity() > 0) {
            terminalBuffer = Math.max(0, terminalBuffer - 0.5);
            planeBuffer = Math.max(0, planeBuffer - 0.5);
            return;
        }

        double deltaXZ = movementData.getDeltaXZ();
        double lastDeltaXZ = movementData.getLastDeltaXZ();
        double deltaY = movementData.getDeltaY();
        double lastDeltaY = movementData.getLastDeltaY();

        double horizontalAccel = deltaXZ - lastDeltaXZ;
        double verticalAccel = deltaY - lastDeltaY;

        inferMissedRocketBoost(movementData, pitch);

        /*
         * Minecraft pitch:
         * negative = looking upward
         * positive = looking downward
         */
        boolean lookingUp = pitch < -12.5D;
        boolean lookingDown = pitch > 12.5D;

        double allowedHorizontal = getAllowedElytraHorizontalSpeed(pitch, deltaY);
        double allowedUpward = getAllowedElytraUpwardSpeed(pitch);

        if (hasRocketBoost()) {
            switch (lastRocketPower) {
                case 2:
                    allowedHorizontal += 2.85D;
                    break;
                case 3:
                    allowedHorizontal += 3.25D;
                    break;
                default:
                    allowedHorizontal += 2.45D;
                    break;
            }

            allowedUpward += getRocketUpwardAllowance(pitch);
        } else if (hasRecentRocketBoost()) {
            double decay = rocketBoostGraceTicks / (double) Math.max(1, 22 + profile.getConnectionData().getClientTickTrans());
            decay = Math.max(0.0D, Math.min(1.0D, decay));

            allowedHorizontal += 1.95D * decay;
            allowedUpward += getRocketUpwardAllowance(pitch) * 0.70D * decay;

            /*
             * After rocket boost, vanilla can still have high Y for a bit,
             * but it should mostly decay instead of gaining forever.
             */
            if (movementData.getDeltaY() <= movementData.getLastDeltaY() + 0.22D) {
                allowedUpward = Math.max(allowedUpward, movementData.getDeltaY() + 0.05D);
            }
        }

        allowedHorizontal += profile.getVelocityData().getTotalHorizontalVelocity();
        allowedUpward += profile.getVelocityData().getVelocityV();

        /*
         * 1) Hard terminal check.
         * This catches ridiculous horizontal/vertical values.
         */
        boolean hardHorizontal = deltaXZ > allowedHorizontal;
        boolean hardVerticalUp = deltaY > allowedUpward;

        /*
         * During rocket/recent rocket, do not hard-flag upward speed if it is only carrying momentum.
         * The anti-plane section below handles impossible sustained gaining.
         */
        if (hardVerticalUp && hasRecentRocketBoost() && deltaY <= lastDeltaY + 0.25D) {
            hardVerticalUp = false;
        }
        boolean hardVerticalDown = deltaY < -3.25D;

        if (hardHorizontal || hardVerticalUp || hardVerticalDown) {
            if (++terminalBuffer > 2.0D) {
                fail("Terminal Velocity",
                        "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nmaxXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedHorizontal
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\nmaxY " + MsgType.MAIN_THEME_COLOR.getMessage() + allowedUpward
                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                                + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch
                                + "\nrocketTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostTicks
                                + "\nrocketGrace " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostGraceTicks
                                + "\nrocketPower " + MsgType.MAIN_THEME_COLOR.getMessage() + lastRocketPower
                                + "\nterminalBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + terminalBuffer);
                terminalBuffer = 0;
            }
        } else {
            terminalBuffer = Math.max(0, terminalBuffer - 0.35D);
        }

        /*
         * 2) Plane-like impossible energy check.
         *
         * The important cheat pattern:
         * - no rocket
         * - looking upward
         * - horizontal speed is increasing or not decaying
         * - vertical speed is increasing / staying positive
         *
         * Legit elytra can convert dive speed into a short climb, but it should
         * not keep gaining energy like a plane without rocket/riptide/external velocity.
         */
        boolean noRocket = !hasRecentRocketBoost();

        boolean gainingHorizontal = horizontalAccel > 0.0125D;
        boolean holdingHighHorizontal = deltaXZ > 1.65D && horizontalAccel > -0.0125D;
        boolean gainingVertical = verticalAccel > 0.003D;
        boolean upward = deltaY > 0.015D;

        if (noRocket && lookingUp && upward && (gainingHorizontal || holdingHighHorizontal) && gainingVertical) {
            upwardNoRocketTicks++;
        } else {
            upwardNoRocketTicks = Math.max(0, upwardNoRocketTicks - 1);
        }

        /*
         * 3) Looking up after a dive can briefly gain Y, but then it should bleed
         * speed. If speed keeps going up while pitch-up, it is suspicious.
         */
        if (noRocket && lookingUp && deltaXZ > 1.25D && horizontalAccel > 0.018D) {
            pitchUpSpeedGainTicks++;
        } else {
            pitchUpSpeedGainTicks = Math.max(0, pitchUpSpeedGainTicks - 1);
        }

        /*
         * 4) Boost sustain check.
         * During rocket: high speed allowed.
         * After rocket: speed should gradually decay if not diving.
         */
        if (!hasRocketBoost()
                && hasRecentRocketBoost()
                && !lookingDown
                && deltaXZ > 2.35D
                && horizontalAccel > 0.005D) {
            sustainedBoostTicks++;
        } else {
            sustainedBoostTicks = Math.max(0, sustainedBoostTicks - 1);
        }

        if (upwardNoRocketTicks > 5 || pitchUpSpeedGainTicks > 6 || sustainedBoostTicks > 8) {
            if (++planeBuffer > 2.0D) {
                fail("Impossible Elytra Energy",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaXZ
                                + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaXZ
                                + "\nhAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + horizontalAccel
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                                + "\nyAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + verticalAccel
                                + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + pitch
                                + "\nrocketTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostTicks
                                + "\nrocketGrace " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostGraceTicks
                                + "\nupNoRocket " + MsgType.MAIN_THEME_COLOR.getMessage() + upwardNoRocketTicks
                                + "\npitchUpGain " + MsgType.MAIN_THEME_COLOR.getMessage() + pitchUpSpeedGainTicks
                                + "\nsustain " + MsgType.MAIN_THEME_COLOR.getMessage() + sustainedBoostTicks);
                planeBuffer = 0;
            }
        } else {
            planeBuffer = Math.max(0, planeBuffer - 0.25D);
        }

        lastElytraDeltaXZ = deltaXZ;
        lastElytraDeltaY = deltaY;
    }

    private double getAllowedElytraHorizontalSpeed(double pitch, double deltaY) {
        double absPitch = Math.abs(pitch);

        /*
         * No rocket.
         * Looking down converts height into speed, so allow more horizontal speed.
         * Looking up should not keep high/gaining speed for long.
         */
        double allowed;

        if (pitch > 55.0D) {
            allowed = 3.25D; // steep dive
        } else if (pitch > 30.0D) {
            allowed = 2.75D;
        } else if (pitch > 10.0D) {
            allowed = 2.25D;
        } else if (absPitch <= 10.0D) {
            allowed = 2.05D;
        } else if (pitch < -45.0D) {
            allowed = 1.55D; // steep up
        } else {
            allowed = 1.85D; // mild up
        }

        /*
         * If falling fast, allow a bit more horizontal because a dive can be fast.
         */
        if (deltaY < -0.65D) {
            allowed += 0.55D;
        } else if (deltaY < -0.35D) {
            allowed += 0.30D;
        }

        return allowed + 0.20D; // general tolerance
    }

    private double getAllowedElytraUpwardSpeed(double pitch) {
        /*
         * Without rocket, upward motion is usually short and energy-limited.
         * These are intentionally generous, because the anti-plane logic below
         * catches sustained impossible gain.
         */
        if (pitch < -55.0D) {
            return 0.95D;
        }

        if (pitch < -30.0D) {
            return 0.75D;
        }

        if (pitch < -10.0D) {
            return 0.55D;
        }

        return 0.35D;
    }

    private void handlePossibleFireworkUse() {
        try {
            if (!profile.getPlayer().isGliding()) {
                return;
            }

            int pingTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());

            /*
             * Important:
             * Even if the cached hand item is stale, a USE_ITEM while gliding is very often a rocket.
             * We do NOT immediately give full rocket boost here.
             * We only arm a short detection window, then movement must confirm the boost.
             */
            this.possibleRocketUseTicks = Math.max(this.possibleRocketUseTicks, 8 + pingTicks);

            ItemStack main = profile.getActionData().getItemInMainHand();
            ItemStack off = profile.getActionData().getItemInOffHand();

            ItemStack rocket = isFirework(main) ? main : isFirework(off) ? off : null;

            if (rocket == null) {
                return;
            }

            int power = getFireworkPower(rocket);
            armRocketBoost(power, true);
        } catch (Throwable ignored) {
        }
    }

    private void armRocketBoost(int power, boolean confirmedItem) {
        int clampedPower = Math.max(1, Math.min(3, power));
        int pingTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());

        this.lastRocketPower = clampedPower;

        int boostTicks = getRocketBoostDuration(clampedPower);

        /*
         * If the item was confirmed, allow the full window.
         * If inferred from movement, use a shorter window so cheats cannot get infinite free exemption.
         */
        if (!confirmedItem) {
            boostTicks = Math.min(boostTicks, 14 + pingTicks);
            this.rocketInferenceCooldownTicks = 35 + pingTicks;
        }

        this.rocketBoostTicks = Math.max(this.rocketBoostTicks, boostTicks);
        this.rocketBoostGraceTicks = Math.max(this.rocketBoostGraceTicks, 22 + pingTicks);
    }

    private int getRocketBoostDuration(int power) {
        return switch (Math.max(1, Math.min(3, power))) {
            case 2 -> 46;
            case 3 -> 58;
            default -> 34;
        };
    }

    private double getRocketUpwardAllowance(double pitch) {
        /*
         * Looking straight up with rockets can legitimately produce high Y.
         * This is why your -90 / 1.58 deltaY false happened.
         */
        if (pitch <= -80.0D) {
            return 2.25D;
        }

        if (pitch <= -60.0D) {
            return 2.00D;
        }

        if (pitch <= -35.0D) {
            return 1.70D;
        }

        if (pitch <= -10.0D) {
            return 1.45D;
        }

        return 1.20D;
    }

    private boolean isFirework(ItemStack item) {
        if (item == null) {
            return false;
        }

        String name = item.getType().name();

        return name.equals("FIREWORK")
                || name.equals("FIREWORK_ROCKET");
    }

    private int getFireworkPower(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1;
        }

        try {
            if (item.getItemMeta() instanceof FireworkMeta meta) {
                return Math.max(1, Math.min(3, meta.getPower()));
            }
        } catch (Throwable ignored) {
        }

        try {
            Object meta = item.getItemMeta();
            assert meta != null;
            Object power = meta.getClass().getMethod("getPower").invoke(meta);

            if (power instanceof Number number) {
                return Math.max(1, Math.min(3, number.intValue()));
            }
        } catch (Throwable ignored) {
        }

        return 1;
    }

    private void tickElytraState(boolean gliding) {
        if (gliding) {
            elytraTicks++;
        } else {
            elytraTicks = 0;
            rocketBoostTicks = 0;
            rocketBoostGraceTicks = 0;
            possibleRocketUseTicks = 0;
            rocketInferenceCooldownTicks = 0;
            pitchUpSpeedGainTicks = 0;
            upwardNoRocketTicks = 0;
            sustainedBoostTicks = 0;
            terminalBuffer = Math.max(0, terminalBuffer - 0.25);
            planeBuffer = Math.max(0, planeBuffer - 0.25);
            return;
        }

        if (possibleRocketUseTicks > 0) {
            possibleRocketUseTicks--;
        }

        if (rocketInferenceCooldownTicks > 0) {
            rocketInferenceCooldownTicks--;
        }

        if (rocketBoostTicks > 0) {
            rocketBoostTicks--;
            rocketBoostGraceTicks = Math.max(rocketBoostGraceTicks, 10);
        } else if (rocketBoostGraceTicks > 0) {
            rocketBoostGraceTicks--;
        }
    }

    private void inferMissedRocketBoost(MovementData movementData, double pitch) {
        if (!profile.getPlayer().isGliding()) {
            return;
        }

        if (hasRocketBoost()) {
            return;
        }

        double deltaXZ = movementData.getDeltaXZ();
        double lastDeltaXZ = movementData.getLastDeltaXZ();

        double deltaY = movementData.getDeltaY();
        double lastDeltaY = movementData.getLastDeltaY();

        double horizontalAccel = deltaXZ - lastDeltaXZ;
        double verticalAccel = deltaY - lastDeltaY;

        /*
         * Case 1:
         * We saw a use-item packet while gliding, but the cached item did not say firework.
         * If movement then shows boost behavior, treat it as a rocket.
         */
        boolean usedItemThenBoosted =
                possibleRocketUseTicks > 0
                        && (
                        verticalAccel > 0.10D
                                || horizontalAccel > 0.08D
                                || deltaY > 0.65D
                                || deltaXZ > 1.90D
                );

        /*
         * Case 2:
         * Extremely obvious missed rocket.
         * Use a cooldown and shorter boost window so a fly cheat cannot keep refreshing this forever.
         */
        boolean obviousUpwardRocket =
                rocketInferenceCooldownTicks <= 0
                        && pitch <= -65.0D
                        && deltaY > 0.95D
                        && verticalAccel > 0.08D;

        boolean obviousHorizontalRocket =
                rocketInferenceCooldownTicks <= 0
                        && deltaXZ > 2.15D
                        && horizontalAccel > 0.18D;

        if (usedItemThenBoosted) {
            armRocketBoost(lastRocketPower, true);
            possibleRocketUseTicks = 0;
            return;
        }

        if (obviousUpwardRocket || obviousHorizontalRocket) {
            armRocketBoost(lastRocketPower, false);
        }
    }

    private boolean hasRocketBoost() {
        return rocketBoostTicks > 0;
    }

    private boolean hasRecentRocketBoost() {
        return rocketBoostTicks > 0 || rocketBoostGraceTicks > 0;
    }
}
