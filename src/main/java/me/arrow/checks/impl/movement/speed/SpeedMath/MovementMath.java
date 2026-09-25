package me.arrow.checks.impl.movement.speed.SpeedMath;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.minecraft.MathHelper;
import me.arrow.utils.vec.Vec2f;
import me.arrow.utils.vec.Vec3;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Adapted from Karhu's SimulationHandler. Performs movement simulation using the Arrow {@link Profile}
 * data to predict the player's movement vector for a given tick. Mirrors the original Karhu logic
 * closely, but operates on Arrow's API.
 */
public class MovementMath {
    private final Profile profile;

    public static final List<float[]> KEY_COMBOS = Collections.synchronizedList(Arrays.asList(
            new float[]{0F, 0F},
            new float[]{0F, 1F},
            new float[]{1F, 1F},
            new float[]{-1F, 1F},
            new float[]{1F, 0F},
            new float[]{1F, -1F},
            new float[]{0F, -1F},
            new float[]{-1F, -1F},
            new float[]{-1F, 0F}
    ));

    public static final List<float[]> KEY_COMBOS_MOVING = Collections.synchronizedList(Arrays.asList(
            new float[]{0F, 1F},
            new float[]{1F, 1F},
            new float[]{-1F, 1F},
            new float[]{1F, 0F},
            new float[]{1F, -1F},
            new float[]{0F, -1F},
            new float[]{-1F, -1F},
            new float[]{-1F, 0F},
            new float[]{0F, 0F}
    ));

    public static final boolean[] BOOLEANS = new boolean[]{true, false};
    public static final boolean[] BOOLEANS_REVERSED = new boolean[]{false, true};


    @Getter
    private double outputX;
    @Getter
    private double outputZ;
    @Getter
    private double outputXZ;
    @Getter
    double lowestMatch;
    double testLowest;
    double testOutputX;
    double testOutputZ;
    @Getter
    private boolean sprinting;
    @Getter
    private boolean attacking;
    @Getter
    private boolean useItem;
    @Getter
    private boolean sneak;
    @Getter
    private boolean jumped;
    @Getter
    private float moveForward;
    @Getter
    private float moveStrafe;
    @Getter
    private float attributeSpeed;
    @Getter
    private float f5;
    @Getter
    private float blockFriction;
    @Getter
    private int scenarioAmount;
    @Getter
    private int edgeSneakTick;
    @Setter
    @Getter
    private float knownInputF;
    @Setter
    @Getter
    private float knownInputS;

    public MovementMath(Profile profile) {
        this.profile = profile;
    }

    // ---------------------------------------------------------------------
    // Simulation core (ported from Karhu)
    // ---------------------------------------------------------------------
    public void simulateMovement(double kbX, double kbZ, boolean test) {
        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();
        boolean modernMovement = profile.getVersion().isNewerThan(ClientVersion.V_1_12_2);

        // Karhu's currentFriction / lastTickFriction packet snapshots.
        float friction = movementData.getFrictionFactor();
        float lastTickFriction = movementData.getLastFrictionFactor();
        if (!modernMovement) {
            friction *= 0.91F;
        }
        boolean onGround = movementData.isLastOnGround();
        // RotationData is processed before this simulation.  MovementData's
        // location intentionally retains its old yaw for rotation-only
        // packets, so reading it here makes the predicted input vector one
        // packet stale while a player turns.  Karhu uses the current packet
        // yaw, which is exactly what RotationData represents in Arrow.
        float yaw = profile.getRotationData().getYaw();
        boolean bruteforceSprint = actionData.isSprinting() != actionData.isLastSprinting()
                || actionData.isLastSprinting() != actionData.isLastLastSprinting();
        // Karhu also brute-forces immediately after a server-side sprint
        // attribute update.  Arrow does not retain that packet timestamp, but
        // Bukkit's state still differs from the just-received entity action
        // for that transition, which is the same ambiguous frame.
        try {
            bruteforceSprint |= profile.getPlayer().isSprinting() != actionData.isSprinting();
        } catch (Throwable ignored) {
        }
        org.bukkit.util.Vector velocity = profile.getVelocityData().isTakingVelocity()
                && profile.getVelocityData().getVelocityTicks() <= 1
                ? profile.getVelocityData().getVelocityfvc()
                : null;

        // reset outputs
        if (!test) {
            lowestMatch = Double.MAX_VALUE;
        } else {
            testLowest = Double.MAX_VALUE;
        }
        scenarioAmount = 0;

        mainLoop:
        for (float[] floats : KEY_COMBOS) {
            for (boolean attack : BOOLEANS_REVERSED) {
                for (boolean use : BOOLEANS_REVERSED) {
                    if (bruteforceSprint) {
                        for (boolean sprinting : BOOLEANS_REVERSED) {
                            if (processScenario(floats, attack, use, sprinting, kbX, kbZ, test,
                                    friction, lastTickFriction, velocity, onGround, yaw, modernMovement)) {
                                break mainLoop;
                            }
                        }
                    } else if (processScenario(floats, attack, use, actionData.isSprinting(), kbX, kbZ, test,
                            friction, lastTickFriction, velocity, onGround, yaw, modernMovement)) {
                        break mainLoop;
                    }
                }
            }
        }
    }

    private boolean processScenario(float[] floats, boolean attack, boolean using, boolean sprint,
                                    double kbX, double kbZ, boolean test,
                                    float friction, float lastTickFriction,
                                    org.bukkit.util.Vector velocity, boolean onGround, float yaw,
                                    boolean modernMovement) {
        // Karhu stores the attribute packet with the sprint modifier removed
        // and tries sprint as a separate scenario.  The live Bukkit value is
        // already sprint-modified, so using it here would multiply sprint by
        // 1.3 twice and make ordinary ground movement impossible to match.
        double moveSpeed = me.arrow.utils.ReflectionUtils.getPlayerMovementSpeedWithoutSprint(profile.getPlayer());
        if (!Double.isFinite(moveSpeed) || moveSpeed <= 0.0D) {
            moveSpeed = 0.1D;
        }

        double moveForward = floats[1];
        double moveStrafe = floats[0];
        MovementData movementData = profile.getMovementData();
        ActionData actionData = profile.getActionData();
        for (boolean sneaking : BOOLEANS_REVERSED) {
            for (boolean jump : BOOLEANS_REVERSED) {
                if (attack && (profile.getCombatData().getAttackedTicks() > 2 || profile.getCombatData().getTarget() == -696969)) {
                    continue; // skip impossible case
                }
                double currentMoveForward = moveForward;
                double currentMoveStrafe = moveStrafe;
                if (sneaking) {
                    float multiplier = 0.3f;
                    if (profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_19)) {
                        multiplier = clampFloat(0.3F + (getSwiftSneakevel(profile.getPlayer()) * 0.15F), 0f, 1f);
                    }
                    currentMoveForward *= multiplier;
                    currentMoveStrafe *= multiplier;
                }
                float forward = (float) currentMoveForward;
                float strafe = (float) currentMoveStrafe;
                if (using) {
                    forward *= 0.2f;
                    strafe *= 0.2f;
                }
                forward *= 0.98f;
                strafe *= 0.98f;

                // The new movement must be predicted from Karhu's lastDX/Z,
                // rather than feeding this packet's observation back in.
                double lastDX = movementData.getLastDeltaX();
                double lastDZ = movementData.getLastDeltaZ();

                if (!movementData.isWasWasInWater()) {
                    lastDX *= movementData.isLastLastOnGround() ? lastTickFriction * 0.91F : 0.91F;
                    lastDZ *= movementData.isLastLastOnGround() ? lastTickFriction * 0.91F : 0.91F;
                } else {
                    float f3 = (float) SpeedUtilities.getDepthStriderLevel(profile);
                    float f9 = sprint && modernMovement
                            ? 0.9F : 0.8F;

                    if (f3 > 3.0F) f3 = 3.0F;
                    if (!onGround) f3 *= 0.5F;
                    if (f3 > 0.0F) {
                        f9 += (0.54600006F - f9) * f3 / 3.0F;
                    }
                    if (movementData.getDolphinGraceTicks() > 0) {
                        f9 = 0.96F;
                    }
                    lastDX *= f9;
                    lastDZ *= f9;
                }

                if (!test) {
                    if (velocity != null) {
                        lastDX = velocity.getX();
                        lastDZ = velocity.getZ();
                    }
                } else {
                    lastDX = kbX;
                    lastDZ = kbZ;
                }

                if (attack) {
                    lastDX *= 0.6;
                    lastDZ *= 0.6;
                }

                if (Math.abs(lastDX) < clamp()) lastDX = 0;
                if (Math.abs(lastDZ) < clamp()) lastDZ = 0;

                // Karhu applies the sprint attribute before calculating either
                // ground or water input force.
                if (!modernMovement) {
                    if (sprint) moveSpeed += moveSpeed * 0.3F;
                } else {
                    if (sprint) moveSpeed *= 1.0D + 0.3F;
                }

                float f5;
                if (!movementData.isWasInWater()) {
                    if (onGround) {
                        if (!modernMovement) {
                            f5 = (float) moveSpeed * (0.16277136f / (friction * friction * friction));
                        } else {
                            f5 = (float) moveSpeed * (0.21600002f / (friction * friction * friction));
                        }
                        if (jump && sprint) {
                            float radians = yaw * ((float) Math.PI / 180);
                            lastDX -= MathHelper.sin(radians) * 0.2F;
                            lastDZ += MathHelper.cos(radians) * 0.2F;
                        }
                    } else {
                        f5 = (float) (sprint ? ((double) 0.02F + (double) 0.02F * 0.3D) : 0.02F);
                    }
                    if (modernMovement) {
                        Vec3 result = getInputVector(new Vec3(strafe, 0, forward), f5, yaw);
                        lastDX += result.xCoord;
                        lastDZ += result.zCoord;
                    } else {
                        Vec2f result = moveFlying(forward, strafe, f5, yaw);
                        lastDX += result.x;
                        lastDZ += result.y;
                    }
                } else {
                    float accel = 0.02F;
                    float f3 = Math.min(3.0F, (float) SpeedUtilities.getDepthStriderLevel(profile));
                    if (!onGround) f3 *= 0.5F;
                    if (f3 > 0.0F) {
                        accel += ((float) moveSpeed - accel) * f3 / 3.0F;
                    }
                    f5 = accel;
                    if (modernMovement) {
                        Vec3 result = getInputVector(new Vec3(strafe, 0, forward), f5, yaw);
                        lastDX += result.xCoord;
                        lastDZ += result.zCoord;
                    } else {
                        Vec2f result = moveFlying(forward, strafe, f5, yaw);
                        lastDX += result.x;
                        lastDZ += result.y;
                    }
                }

                double moveDiff = Math.hypot(movementData.getDeltaX() - lastDX, movementData.getDeltaZ() - lastDZ);

                if (test) {
                    if (moveDiff < testLowest) {
                        testLowest = moveDiff;
                        testOutputX = lastDX;
                        testOutputZ = lastDZ;
                        if (testLowest < 1e-15) {
                            return true;
                        }
                    }
                } else {
                    if (moveDiff < lowestMatch) {
                        lowestMatch = moveDiff;
                        outputX = lastDX;
                        outputZ = lastDZ;
                        outputXZ = Math.hypot(outputX, outputZ);
                        this.sprinting = sprint;
                        this.sneak = sneaking;
                        this.jumped = jump;
                        this.attacking = attack;
                        this.useItem = using;
                        this.attributeSpeed = (float) moveSpeed;
                        this.moveForward = forward;
                        this.moveStrafe = strafe;
                        this.f5 = f5;
                        this.blockFriction = friction;
                        if (CollisionUtils.isNearEdge(movementData.getLocation())) {
                            if (sneaking || actionData.isSneaking()) {
                                this.edgeSneakTick = profile.getTick();
                            }
                        }

                        if (this.lowestMatch < 1e-15) {
                            //Bukkit.broadcastMessage("test");
                            //return true;
                        }
                    }
                }
                ++scenarioAmount;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Helper methods (mirrored from Karhu)
    // ---------------------------------------------------------------------
    private Vec2f moveFlying(float forward, float strafe, float f5, float yaw) {
        float inputForce = forward * forward + strafe * strafe;
        if (inputForce >= 1.0E-4F) {
            inputForce = MathHelper.sqrt_float(inputForce);
            if (inputForce < 1.0F) {
                inputForce = 1.0F;
            }
            inputForce = f5 / inputForce;
            forward *= inputForce;
            strafe *= inputForce;
            float yawRad = yaw * (float) Math.PI / 180.0F;
            float yawSin = MathHelper.sin(yawRad);
            float yawCos = MathHelper.cos(yawRad);
            float xAdd = strafe * yawCos - forward * yawSin;
            float zAdd = forward * yawCos + strafe * yawSin;
            return new Vec2f(xAdd, zAdd);
        }
        return new Vec2f(0, 0);
    }

    private Vec3 getInputVector(Vec3 inputs, float f5, float yaw) {
        double inputForce = inputs.lengthSqr();
        if (inputForce >= 1.0E-4F) {
            Vec3 vec3 = (inputForce > 1.0D ? inputs.normalizeModern() : inputs).scale(f5);
            float yawSin = MathHelper.sin(yaw * ((float) Math.PI / 180F));
            float yawCos = MathHelper.cos(yaw * ((float) Math.PI / 180F));
            return new Vec3(vec3.xCoord * (double) yawCos - vec3.zCoord * (double) yawSin,
                    0,
                    vec3.zCoord * (double) yawCos + vec3.xCoord * (double) yawSin);
        }
        return Vec3.ZERO;
    }

    public double clamp() {
        return profile.getVersion().getProtocolVersion() > 47 ? 0.003D : 0.005D;
    }

    public float clampFloat(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    public static int getSwiftSneakevel(Player player) {
        if (player.getInventory().getLeggings() != null) {
            Enchantment enchLegacy = Enchantment.getByName("SWIFT_SNEAK");
            Enchantment enchModern = Enchantment.getByName("swift_sneak");

            if(enchLegacy != null && hasEnchantment(player.getInventory().getLeggings(), enchLegacy)) {
                return player.getInventory().getLeggings().getEnchantments().get(enchLegacy);

            } else if(enchModern != null && hasEnchantment(player.getInventory().getLeggings(), enchModern)) {
                return player.getInventory().getLeggings().getEnchantments().get(enchModern);
            }

        }

        return 0;
    }

    public static boolean hasEnchantment(ItemStack item, Enchantment enchantment) {
        return item.getEnchantments().containsKey(enchantment);
    }
}
