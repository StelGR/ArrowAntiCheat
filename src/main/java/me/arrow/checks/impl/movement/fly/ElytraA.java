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
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.Locale;

import static me.arrow.utils.customutils.Math.MathUtil.getDevation;

// very retarded sample based elytra check, works suprisingly well, somewhat, sometimes..

@Experimental
public class ElytraA extends Check {

    public ElytraA(Profile profile) {
        super(profile, CheckType.ELYTRA, "A", "Checks for weird elytra stuff");
    }


    SampleList<Double> fallingSamples = new SampleList<>(60);

    int elytraTicks;
    int rocketBoostTicks;
    int rocketBoostGraceTicks;
    int lastRocketPower = 1;

    int pitchUpSpeedGainTicks;
    int upwardNoRocketTicks;
    int unpoweredClimbTicks;
    int sustainedBoostTicks;

    double lastElytraDeltaXZ;
    double lastElytraDeltaY;

    double terminalBuffer;
    double planeBuffer;
    double hoverBuffer;
    double zeroXZBuffer;

    double recentDiveSpeed;
    int diveTicks;

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

        if (OtherUtility.isFlying(event.getPacketType())) {

            long profiler = Profiler.start();

            try {
                MovementData movementData = profile.getMovementData();

                if (profile.shouldCancel()
                        || profile.isExempt().isTeleports()
                        || profile.isExempt().isDead()
                        || PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8_8)
                        || movementData.getLocation() == null
                        || !CollisionUtils.isChunkLoaded(movementData.getLocation())
                        || movementData.getSinceRiptidingTicks() < 30
                        || movementData.getSinceNearWaterTicks() < 10
                        || movementData.isNearLava()
                        || movementData.getGlidingTicks() < 5
                        || movementData.isNearWater()
                        || movementData.getSinceBubbleTicks() < 15
                        || movementData.getSinceGlidingTicks() > 0
                        || profile.getVelocityData().getTotalHorizontalVelocity() > 0) {
                    return;
                }

                boolean serverGround = movementData.isServerGround();
                boolean clientGround = movementData.isOnGround();
                boolean inAir = movementData.isCustomInAir();
                double deltaY = movementData.getDeltaY();
                boolean isMoving = movementData.isMoving();
                int airTicks = movementData.getCustomAirTicks();
                double pitch = profile.getRotationData().getPitch();

                verbose(this.getClass().getSimpleName(), deltaY, movementData.getDeltaXZ(), "* Verbose\n * deltaXZ: " + movementData.getDeltaXZ()
                        + "\n * deltaY " + deltaY
                        + "\n * lastDeltaY" + movementData.getLastDeltaY()
                );

                if (inAir && !movementData.isUnderblock()
                        && !movementData.isNearWall()
                        && !movementData.isColliding()
                        && !movementData.isNearClimbable()
                        && !movementData.isInsideLiquid()
                        && !movementData.isNearWebs()
                        && !movementData.isNearBoat()
                        && !movementData.isOnBoat()
                        && !movementData.isNearBed()
                        && !profile.isBouncingOnSlime()) {
                    if (deltaY == movementData.getLastDeltaY()) {
                        fallingSamples.add(deltaY);

                        if (fallingSamples.isCollected()) {
                            final double deviation = getDevation(this.fallingSamples);

                            if (deviation == 0) {
                                if (++hoverBuffer > 3.0D) {
                                    fail("Invalid Elytra Glide (Hover)",
                                            "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                                    + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                                    + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                                    + "\ndeviation " + MsgType.MAIN_THEME_COLOR.getMessage() + deviation);
                                    hoverBuffer = 0;
                                }
                            } else {
                                hoverBuffer = Math.max(0, hoverBuffer - 0.25D);
                            }
                        }
                    } else {
                        hoverBuffer = Math.max(0, hoverBuffer - 0.05D);
                    }
                }

                if (inAir && !movementData.isUnderblock()
                        && !movementData.isNearWall()
                        && !movementData.isColliding()
                        && !movementData.isNearBlocksSlime()) {
                    if (deltaY != movementData.getLastDeltaY()) {
                        if ((Math.abs(pitch) <= 84) && (pitch > 15 || pitch < -15) && movementData.getDeltaXZ() == 0 && movementData.getLastDeltaXZ() == 0) {
                            if (++zeroXZBuffer > 3.0D) {
                                fail("Impossible elytra movement",
                                        "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                                + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ()
                                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                                + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + formatPitch(pitch));
                                zeroXZBuffer = 0;
                            }
                        } else {
                            zeroXZBuffer = Math.max(0, zeroXZBuffer - 0.25D);
                        }
                    }
                }

                tickElytraState(movementData.isGlidingNow());

                handleDynamicTerminalVelocity(
                        movementData,
                        serverGround,
                        clientGround,
                        inAir,
                        pitch
                );

            } finally {
                Profiler.stop("Elytra A", profiler);
            }
        }
    }


    private void handleDynamicTerminalVelocity(MovementData movementData,
                                               boolean serverGround,
                                               boolean clientGround,
                                               boolean inAir,
                                               double pitch) {
        if (!inAir || !movementData.isGlidingNow()) {
            terminalBuffer = Math.max(0, terminalBuffer - 0.25);
            planeBuffer = Math.max(0, planeBuffer - 0.25);
            return;
        }

        if (movementData.isUnderblock()
                || movementData.isNearWater()
                || movementData.isNearLava()
                || movementData.getSinceInsideWaterTicks() <= 10
                || movementData.getSinceBubbleTicks() <= 15
                || profile.isExempt().isTeleports()
                || movementData.getLocation() == null
                || !CollisionUtils.isChunkLoaded(movementData.getLocation())
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

        // Track dive speed during downward pitches to support dive-and-climb (swooping)
        double currentSpeed = Math.hypot(deltaXZ, deltaY);
        if ((pitch > 10.0D && deltaY < -0.15D) || (pitch > 0.0D && deltaY < -0.35D)) {
            recentDiveSpeed = Math.max(recentDiveSpeed, currentSpeed);
            diveTicks++;
        } else {
            recentDiveSpeed = Math.max(0.0D, recentDiveSpeed - 0.02D);
            diveTicks = Math.max(0, diveTicks - 1);
        }

        inferMissedRocketBoost(movementData, pitch);

        /*
         * Minecraft pitch:
         * negative = looking upward
         * positive = looking downward
         */
        boolean lookingUp = pitch < -12.5D;
        boolean lookingDown = pitch > 12.5D;

        double allowedHorizontal = getAllowedElytraHorizontalSpeed(pitch, deltaY) + (recentDiveSpeed * 0.40D);
        double allowedUpward = getAllowedElytraUpwardSpeed(pitch) + (recentDiveSpeed * 0.55D);

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
            double decay = rocketBoostGraceTicks / (double) Math.max(1, 30 + profile.getConnectionData().getClientTickTrans());
            decay = Math.max(0.0D, Math.min(1.0D, decay));

            allowedHorizontal += 2.25D * decay;
            allowedUpward += getRocketUpwardAllowance(pitch) * 0.85D * decay;

            /*
             * After rocket boost, vanilla can still have high Y for a bit,
             * but it should mostly decay instead of gaining forever.
             */
            if (movementData.getDeltaY() <= movementData.getLastDeltaY() + 0.25D) {
                allowedUpward = Math.max(allowedUpward, movementData.getDeltaY() + 0.10D);
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
         * During rocket/recent rocket or recent dive swoop, do not hard-flag upward speed if it is only carrying momentum.
         * The anti-plane section below handles impossible sustained gaining.
         */
        if (hardVerticalUp && (hasRecentRocketBoost() || recentDiveSpeed > 0.6D) && deltaY <= lastDeltaY + 0.30D) {
            hardVerticalUp = false;
        }
        boolean hardVerticalDown = deltaY < -3.25D;

        if (hardHorizontal || hardVerticalUp || hardVerticalDown) {
            if (++terminalBuffer > 2.0D) {
                fail("Terminal Velocity",
                        "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                                + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", deltaXZ)
                                + "\nmaxXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", allowedHorizontal)
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", deltaY)
                                + "\nmaxY " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", allowedUpward)
                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", lastDeltaY)
                                + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + formatPitch(pitch)
                                + "\ndiveSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", recentDiveSpeed)
                                + "\nrocketTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostTicks
                                + "\nrocketGrace " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostGraceTicks
                                + "\nrocketPower " + MsgType.MAIN_THEME_COLOR.getMessage() + lastRocketPower
                                + "\npossibleRockets " + MsgType.MAIN_THEME_COLOR.getMessage() + possibleRocketUseTicks
                                + "\nterminalBuffer " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.2f", terminalBuffer));
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
         * Legit elytra can convert dive speed into a climb (swoop), but total
         * kinetic energy will decay without rockets/riptide/external velocity.
         */
        boolean noRocket = !hasRecentRocketBoost();

        if (hasRecentRocketBoost()) {
            unpoweredClimbTicks = 0;
            upwardNoRocketTicks = 0;
            pitchUpSpeedGainTicks = 0;
        }

        /*
         * Unpowered ascent duration:
         * Without rockets or external velocity, pulling up from a dive can sustain
         * positive climb (deltaY > 0.05) for 30-50+ ticks before gravity reverses it.
         * With prior dive momentum, we allow up to 28 + (recentDiveSpeed * 25) ticks.
         */
        int maxAllowedClimbTicks = 28 + (int) (recentDiveSpeed * 25.0D) + Math.max(0, profile.getConnectionData().getClientTickTrans());

        double currentEnergy = deltaXZ * deltaXZ + deltaY * deltaY;
        double lastEnergy = lastElytraDeltaXZ * lastElytraDeltaXZ + lastElytraDeltaY * lastElytraDeltaY;
        double energyDelta = currentEnergy - lastEnergy;

        if (noRocket && deltaY > 0.05D && !profile.getVelocityData().isTakingVelocity()) {
            unpoweredClimbTicks++;
            // Flag only if climb duration exceeds allowed dive swoop ticks AND energy is not decaying (or absurdly long duration)
            if (unpoweredClimbTicks > maxAllowedClimbTicks && (energyDelta > -0.01D || unpoweredClimbTicks > maxAllowedClimbTicks + 20)) {
                if (++planeBuffer > 2.0D) {
                    fail("Impossible Elytra Ascent",
                            "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", deltaY)
                                    + "\nclimbTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + unpoweredClimbTicks + " (max " + maxAllowedClimbTicks + ")"
                                    + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + formatPitch(pitch)
                                    + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", deltaXZ)
                                    + "\ndiveSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", recentDiveSpeed)
                                    + "\nrocketTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostTicks
                                    + "\nrocketGrace " + MsgType.MAIN_THEME_COLOR.getMessage() + rocketBoostGraceTicks
                                    + "\npossibleRockets " + MsgType.MAIN_THEME_COLOR.getMessage() + possibleRocketUseTicks
                                    + "\nhasRockets " + MsgType.MAIN_THEME_COLOR.getMessage() + playerHasFireworks()
                                    + "\nenergyDelta " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.4f", energyDelta));
                    planeBuffer = 0;
                }
            }
        } else {
            unpoweredClimbTicks = Math.max(0, unpoweredClimbTicks - 1);
        }

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

        if (upwardNoRocketTicks > 6 || pitchUpSpeedGainTicks > 7 || sustainedBoostTicks > 9) {
            if (++planeBuffer > 2.0D) {
                fail("Impossible Elytra Energy",
                        "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", deltaXZ)
                                + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", lastDeltaXZ)
                                + "\nhAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.4f", horizontalAccel)
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", deltaY)
                                + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", lastDeltaY)
                                + "\nyAccel " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.4f", verticalAccel)
                                + "\npitch " + MsgType.MAIN_THEME_COLOR.getMessage() + formatPitch(pitch)
                                + "\ndiveSpeed " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.ROOT, "%.3f", recentDiveSpeed)
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
            allowed += 0.80D;
        } else if (deltaY < -0.35D) {
            allowed += 0.50D;
        }

        return allowed + 0.20D; // general tolerance
    }

    private double getAllowedElytraUpwardSpeed(double pitch) {
        /*
         * Without rocket, upward motion is energy-limited.
         * These are intentionally generous, because the anti-plane logic
         * catches sustained impossible gain while dive momentum is handled dynamically.
         */
        if (pitch < -55.0D) {
            return 1.15D;
        }

        if (pitch < -30.0D) {
            return 0.95D;
        }

        if (pitch < -10.0D) {
            return 0.75D;
        }

        if (pitch < 5.0D) {
            return 0.55D;
        }

        return 0.45D;
    }

    private void handlePossibleFireworkUse() {
        try {
            boolean isGliding = profile.getMovementData().isGlidingNow()
                    || profile.isWearingFunctionalElytra();

            if (!isGliding) {
                return;
            }

            int pingTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());

            /*
             * When USE_ITEM or PLAYER_BLOCK_PLACEMENT is received while gliding:
             * Arm the pending rocket boost window immediately.
             * A right-click in mid-air while gliding is almost always a firework rocket!
             */
            this.possibleRocketUseTicks = Math.max(this.possibleRocketUseTicks, 25 + pingTicks);

            Player player = profile.getPlayer();
            ItemStack main = null;
            ItemStack off = null;

            if (player != null) {
                try {
                    main = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(player);
                    off = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(player);
                } catch (Throwable ignored) {}
            }

            if (main == null || main.getType() == Material.AIR) {
                try {
                    ItemStack aMain = profile.getActionData().getItemInMainHand();
                    if (aMain != null && aMain.getType() != Material.AIR) {
                        main = aMain;
                    }
                } catch (Throwable ignored) {}
            }

            if (off == null || off.getType() == Material.AIR) {
                try {
                    ItemStack aOff = profile.getActionData().getItemInOffHand();
                    if (aOff != null && aOff.getType() != Material.AIR) {
                        off = aOff;
                    }
                } catch (Throwable ignored) {}
            }

            ItemStack rocket = isFirework(main) ? main : isFirework(off) ? off : null;

            if (rocket != null) {
                int power = getFireworkPower(rocket);
                armRocketBoost(power, true);
            }
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
         * If inferred from movement, use a generous window so legitimate rockets are never falsely flagged.
         */
        if (!confirmedItem) {
            boostTicks = Math.min(boostTicks, 30 + pingTicks);
            this.rocketInferenceCooldownTicks = 15 + pingTicks;
        }

        this.rocketBoostTicks = Math.max(this.rocketBoostTicks, boostTicks);
        this.rocketBoostGraceTicks = Math.max(this.rocketBoostGraceTicks, 30 + pingTicks);

        // A powered rocket ascent is never an unpowered climb
        this.unpoweredClimbTicks = 0;
        this.upwardNoRocketTicks = 0;
        this.pitchUpSpeedGainTicks = 0;
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
         */
        if (pitch <= -80.0D) {
            return 2.35D;
        }

        if (pitch <= -60.0D) {
            return 2.10D;
        }

        if (pitch <= -35.0D) {
            return 1.85D;
        }

        if (pitch <= -10.0D) {
            return 1.65D;
        }

        if (pitch <= 10.0D) {
            return 1.45D;
        }

        return 1.25D;
    }

    private boolean isFirework(ItemStack item) {
        if (item == null) {
            return false;
        }

        try {
            String name = item.getType().name();
            return name.contains("FIREWORK");
        } catch (Throwable ignored) {
            return false;
        }
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
            unpoweredClimbTicks = 0;
            sustainedBoostTicks = 0;
            recentDiveSpeed = 0;
            diveTicks = 0;
            terminalBuffer = Math.max(0, terminalBuffer - 0.25);
            planeBuffer = Math.max(0, planeBuffer - 0.25);
            hoverBuffer = Math.max(0, hoverBuffer - 0.25);
            zeroXZBuffer = Math.max(0, zeroXZBuffer - 0.25);
            fallingSamples.clear();
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
            rocketBoostGraceTicks = Math.max(rocketBoostGraceTicks, 30 + Math.max(0, profile.getConnectionData().getClientTickTrans()));
        } else if (rocketBoostGraceTicks > 0) {
            rocketBoostGraceTicks--;
        }
    }

    private void inferMissedRocketBoost(MovementData movementData, double pitch) {
        if (!movementData.isGlidingNow()) {
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

        double currentEnergy = deltaXZ * deltaXZ + deltaY * deltaY;
        double lastEnergy = lastElytraDeltaXZ * lastElytraDeltaXZ + lastElytraDeltaY * lastElytraDeltaY;
        double energyGain = currentEnergy - lastEnergy;

        /*
         * Case 1:
         * We saw a USE_ITEM or PLAYER_BLOCK_PLACEMENT packet while gliding.
         * If movement confirms any upward motion, forward speed, or positive acceleration, confirm the rocket!
         */
        boolean usedItemThenBoosted =
                possibleRocketUseTicks > 0
                        && (
                        verticalAccel > 0.03D
                                || horizontalAccel > 0.03D
                                || deltaY > 0.10D
                                || deltaXZ > 0.50D
                );

        if (usedItemThenBoosted) {
            armRocketBoost(lastRocketPower, true);
            possibleRocketUseTicks = 0;
            return;
        }

        /*
         * Case 2:
         * Autonomous kinematic inference.
         * The player might be lagging, or USE_ITEM was deferred, but their movement shows clear rocket flight.
         * Normal rocket ascent has deltaY around 0.35 - 0.75 and deltaXZ around 0.45 - 1.20 across pitches -89 to +15.
         * Without a deep dive (recentDiveSpeed < 0.75), an unpowered elytra CANNOT maintain deltaY > 0.35 or gain energy!
         */
        boolean hasFireworks = playerHasFireworks();
        boolean gainingSpeedWhileClimbing = deltaY > 0.05D && (energyGain > 0.02D || horizontalAccel > 0.04D);
        boolean climbWithoutPriorDive = deltaY > 0.35D && recentDiveSpeed < 0.75D;
        boolean upwardAccelerationBoost = verticalAccel > 0.06D && deltaY > 0.20D;
        boolean horizontalRocketBoost = deltaXZ > 1.25D && horizontalAccel > 0.10D;

        if (rocketInferenceCooldownTicks <= 0) {
            if (hasFireworks) {
                // If the player has fireworks anywhere in inventory, recognize rocket boosts reliably
                if (climbWithoutPriorDive || gainingSpeedWhileClimbing || upwardAccelerationBoost || horizontalRocketBoost || (deltaY > 0.40D && deltaXZ > 0.45D)) {
                    armRocketBoost(lastRocketPower, true);
                    return;
                }
            } else {
                // Even without seeing fireworks in inventory (e.g. inventory desync), infer obvious boosts
                if ((climbWithoutPriorDive && gainingSpeedWhileClimbing) || upwardAccelerationBoost || horizontalRocketBoost) {
                    armRocketBoost(lastRocketPower, false);
                }
            }
        }
    }

    private boolean hasRocketBoost() {
        return rocketBoostTicks > 0;
    }

    private boolean hasRecentRocketBoost() {
        return rocketBoostTicks > 0 || rocketBoostGraceTicks > 0;
    }

    private String formatPitch(double pitch) {
        if (pitch < -0.5D) {
            return String.format(Locale.ROOT, "%.1f [UP %.1f°]", pitch, -pitch);
        } else if (pitch > 0.5D) {
            return String.format(Locale.ROOT, "%.1f [DOWN %.1f°]", pitch, pitch);
        } else {
            return String.format(Locale.ROOT, "%.1f [LEVEL]", pitch);
        }
    }

    private boolean playerHasFireworks() {
        Player player = profile.getPlayer();
        if (player == null) {
            return false;
        }
        try {
            if (player.getGameMode() == GameMode.CREATIVE) {
                return true;
            }
            for (ItemStack item : player.getInventory().getContents()) {
                if (isFirework(item)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
