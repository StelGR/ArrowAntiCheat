package me.arrow.checks.impl.combat.velocity;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.BlockProcessor;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.customutils.Math.MathHelper;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.geysermc.geyser.api.item.custom.CustomRenderOffsets;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Open-Karhu Velocity B, this was ported using AI thanks to a friend who got access to paid GPT, so you will see alot of yapping.
 * Again, im not an expert in combat checks so to keep Arrow up to a specific standard we have to use different anticheat checks for combat.
 * Which is why i got some autoclicker checks from a friend of mine making his own anticheat, with permission
 * I will happily take this down if requested.
 */
public class VelocityB extends Check {

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
    private static final boolean[] BOOLEANS_REVERSED = {false, true};

    private double kbZ;
    private double kbX;
    private double allowance;
    private double violations;

    private int ticks;
    private int attacks;
    private int pendingAttacks;
    private int bruteforcedAttacks;
    private int moveTicks;
    private int movementSequence;
    private int localVelocitySequence;

    /*
     * Karhu does not turn an outgoing velocity packet directly into
     * tickedVelocity. It first keeps it pending across the transaction
     * boundary, then checks the first plausible client movement response.
     * Keeping that state here is what lets this class remain profile-agnostic
     * without touching Arrow's VelocityData/transaction processors.
     */
    private final ConcurrentLinkedDeque<PendingVelocity> pendingVelocities = new ConcurrentLinkedDeque<>();
    private static final int MAX_PENDING_VELOCITIES = 12;
    private static final int MAX_PENDING_MOVEMENT_AGE = 650;

    private boolean lastVelocityWasForced;
    private double lastPendingHorizontalError;
    private double lastPendingVerticalError;
    private float karhuCurrentFriction = 0.54600006F;
    private float karhuLastTickFriction = 0.54600006F;

    /*
     * Arrow does not expose every Karhu collision timestamp directly. Keep the
     * equivalent timers here so the port remains isolated to VelocityB.
     */
    private int sinceClimbable = 1000;
    private int sinceLiquid = 1000;
    private int sinceBoat = 1000;
    private int sinceBlockCollision = 1000;
    private int sinceGhostCollision = 1000;
    private int sinceSneakEdge = 1000;
    private int sinceVerticalCollision = 1000;

    private boolean onGround;
    private boolean attack;
    private boolean wasInWeb;

    public VelocityB(Profile profile) {
        super(profile, CheckType.VELOCITY, "B", "Checks horizontal knockback (Credits: Karhu)");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (profile == null || profile.getPlayer() == null) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            captureVelocityPacket(event);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.PING
                || event.getPacketType() == PacketType.Play.Server.WINDOW_CONFIRMATION) {
            capturePostTransactionBoundary(event);
        }
    }

    private void captureVelocityPacket(PacketSendEvent event) {
        WrapperPlayServerEntityVelocity wrapper = new WrapperPlayServerEntityVelocity(event);
        if (wrapper.getEntityId() != profile.getPlayer().getEntityId()) {
            return;
        }

        Vector3d velocity = wrapper.getVelocity();
        if (velocity == null) {
            return;
        }

        /*
         * This is the important Karhu behaviour: the exact final packet sent by
         * the server is the knockback profile. Panda/Azurite/Carbon/vanilla do
         * not need hard-coded presets here; whatever the fork put in the
         * ENTITY_VELOCITY packet is what gets predicted.
         *
         * Karhu then brackets that packet between a transaction that was
         * already in flight (pre-ping) and the next transaction (post-ping).
         * The previous port skipped that window and waited for Arrow's custom
         * post-velocity acknowledgement, which can be one movement packet too
         * late -- exactly the kind of desync that shows up during bow boosts.
         */
        int sequence = ++this.localVelocitySequence;
        if (profile.getVelocityData() != null) {
            sequence = Math.max(sequence, profile.getVelocityData().getEntityVelocitySequence());
            this.localVelocitySequence = sequence;
        }

        TransactionKey preTransaction = getOutstandingRegularTransaction();
        PendingVelocity pending = new PendingVelocity(
                sequence,
                new Vector(velocity.getX(), velocity.getY(), velocity.getZ()),
                this.movementSequence,
                preTransaction
        );

        /* NetHandler.queueToPrePing executes immediately when no ping exists. */
        pending.preBoundaryReached = preTransaction == null;
        this.pendingVelocities.addLast(pending);

        while (this.pendingVelocities.size() > MAX_PENDING_VELOCITIES) {
            this.pendingVelocities.pollFirst();
        }
    }

    private void capturePostTransactionBoundary(PacketSendEvent event) {
        TransactionKey sent = readRegularServerTransaction(event);
        if (sent == null) {
            return;
        }

        for (PendingVelocity pending : this.pendingVelocities) {
            if (pending.postTransaction != null) {
                continue;
            }

            /*
             * Arrow retransmits its current transaction while waiting. Karhu's
             * queueToPostPing attaches to the NEXT transaction, not another
             * copy of the pre-ping transaction, so never use the same id here.
             */
            if (pending.preTransaction != null && pending.preTransaction.equals(sent)) {
                continue;
            }

            pending.postTransaction = sent;
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            handleAttack(event);
            return;
        }

        TransactionKey acknowledged = readClientTransaction(event);
        if (acknowledged != null) {
            handleTransactionBoundary(acknowledged);
            return;
        }

        if (!isMovement(event)) {
            return;
        }

        if (profile == null
                || profile.getPlayer() == null
                || !profile.getPlayer().isOnline()
                || profile.getMovementData() == null
                || profile.getVelocityData() == null) {
            resetState();
            clearPacketCombatState();
            return;
        }

        MovementData movementData = profile.getMovementData();

        ++this.movementSequence;
        updateMoveTicks(event);
        updateKarhuEnvironmentTimers(movementData);
        updateKarhuFrictionState(movementData);

        try {
            Vector tickVelocity = pollKarhuTickedVelocity(movementData);
            if (tickVelocity != null) {
                this.kbX = tickVelocity.getX();
                this.kbZ = tickVelocity.getZ();
                this.allowance = 0.001D;

                if (this.moveTicks <= 1) {
                    this.allowance = offsetMove() + 0.001D;
                }
            }

            if (canCheckCondition()) {
                if (this.attack) {
                    bruteforceAttack(movementData);
                }

                float f4 = 0.91F;
                if (this.onGround) {
                    f4 = this.karhuCurrentFriction;
                }

                this.kbX = Math.abs(this.kbX) < clamp() ? 0.0D : this.kbX;
                this.kbZ = Math.abs(this.kbZ) < clamp() ? 0.0D : this.kbZ;

                if (canRunPrediction(movementData)) {
                    VelocityKeys keys = computeKeys(this.kbX, this.kbZ, movementData);
                    if (keys == null) {
                        resetState();
                    } else {
                        evaluatePrediction(keys, movementData, f4);
                    }
                } else {
                    resetState();
                }
            } else {
                resetState();
            }
        } finally {
            this.onGround = movementData.isOnGround();
            this.wasInWeb = movementData.isNearWebs();
            clearPacketCombatState();
        }
    }

    private void handleAttack(PacketReceiveEvent event) {
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        /* KarhuPlayer.checkVelocity counts every ATTACK packet. */
        ++this.pendingAttacks;

        /* Karhu VelocityB's own AttackEvent branch only accepts players. */
        if (profile.getCombatData() != null
                && profile.getCombatData().getTrackedEntities() != null
                && profile.getCombatData().getTrackedEntities().containsKey(wrapper.getEntityId())) {
            this.attack = true;
            ++this.attacks;
        }
    }

    private void evaluatePrediction(VelocityKeys keys, MovementData movementData, float f4) {
        double dClientKb = movementData.getDeltaXZ();
        float strafe = keys.strafe;
        float forward = keys.forward;
        float friction = keys.friction;
        boolean fastMath = keys.fastMath;
        boolean thinkJump = keys.jump;

        if (thinkJump) {
            float radians = movementData.getLocation().getYaw() * (float) (Math.PI / 180.0D);
            this.kbX -= MathHelper.sin(radians) * 0.2F;
            this.kbZ += MathHelper.cos(radians) * 0.2F;
        }

        moveFlying(strafe, forward, friction, fastMath, movementData);

        double dKbZ = movementData.getDeltaX() / this.kbX;
        double dKbX = movementData.getDeltaZ() / this.kbZ;
        double dKb = Math.hypot(this.kbX, this.kbZ);
        double diff = dKb - dClientKb;
        double p = dClientKb / dKb * 100.0D;
        double minPtc = 99.99D;

        if (profile.getPlayer().getMaximumNoDamageTicks() < 10) {
            minPtc -= 20.0D;
        }

        if (isNewerThan8()) {
            minPtc -= 20.0D;
        }

        if (profile.getCombatData().isUsedSpear() && onGround) {
            minPtc -= 20.0D;
        }

        double maxVL = isNewerThan8() ? 8.0D : 5.0D;
        boolean reversed = dKbZ < -0.05D || dKbX < -0.05D;

        /*
         * This second jump impulse mirrors Open-Karhu VelocityB exactly. It is
         * intentionally not "cleaned up" here because the requested behavior
         * is a direct port of Karhu's state machine.
         */
        if (thinkJump) {
            float radians = movementData.getLocation().getYaw() * (float) (Math.PI / 180.0D);
            this.kbX -= MathHelper.sin(radians) * 0.2F;
            this.kbZ += MathHelper.cos(radians) * 0.2F;
        }

        boolean jumped = isJumped(movementData);

        if (profile.isBouncingOnSlime()) {
            violations = 0;
            return;
        }



        if ((p < minPtc && Math.abs(diff) > this.allowance) || (reversed && !jumped)) {
            this.violations = Math.min(15.0D,
                    this.violations + Math.abs(1.975D - Math.abs(dClientKb / dKb)));

            if (this.violations > maxVL) {
                fail(
                        "Horizontal Modification",
                        "approx pct " + theme() + format(3, p)
                                + "\nclient " + theme() + format(3, dClientKb)
                                + "\nserver " + theme() + format(3, dKb)
                                + "\njump " + theme() + jumped + " | " + thinkJump
                                + "\ntick " + theme() + this.ticks + " | " + this.moveTicks
                                + "\nattack " + theme() + this.attack + " | " + getLastAttackTick() + " | " + this.attacks
                                + "\nattackFit " + theme() + this.bruteforcedAttacks
                                + "\nst/fo/fr " + theme() + strafe + " | " + forward + " | " + friction
                                + "\nversion " + theme() + profile.getVersion()
                                + "\nreverse " + theme() + reversed + " | " + format(3, dKbX) + " | " + format(3, dKbZ)
                                + "\nsync " + theme() + (this.lastVelocityWasForced ? "forced" : "matched")
                                + " | " + format(5, this.lastPendingHorizontalError)
                                + " | " + format(5, this.lastPendingVerticalError)
                                + "\nbuffer " + theme() + format(3, this.violations)
                );
            }

            resetState();
        } else {
            this.violations = Math.max(this.violations - 0.125D, 0.0D);
        }

        this.kbX *= f4;
        this.kbZ *= f4;

        if (this.ticks++ >= 8 || (this.kbZ == 0.0D && this.kbX == 0.0D)) {
            resetState();
        }
    }

    private void resetState() {
        this.kbX = 0.0D;
        this.kbZ = 0.0D;
        this.ticks = 0;
    }

    private void clearPacketCombatState() {
        this.attack = false;
        this.attacks = 0;
        this.pendingAttacks = 0;
        this.bruteforcedAttacks = 0;
    }

    private boolean canCheckCondition() {
        return this.kbX * this.kbX + this.kbZ * this.kbZ > offsetMove() + 0.001D
                && flightElapsedMoreThan30Ticks();
    }

    private boolean canRunPrediction(MovementData movementData) {
        return !movementData.isNearWebs()
                && !this.wasInWeb
                && movementData.getSinceGlidingTicks() != 0
                && !profile.getPlayer().isInsideVehicle()
                && this.sinceSneakEdge > 5
                && movementData.getSinceTeleportTicks() > 0
                && this.sinceClimbable > 5
                && movementData.getSinceCollideTicks() > 8
                && this.sinceLiquid > 5
                && this.sinceBoat > 1
                && this.sinceBlockCollision > 1
                && this.sinceGhostCollision > 1;
    }

    private VelocityKeys computeKeys(double x, double z, MovementData movementData) {
        Map<Double, VelocityKeys> dataAssessments = new HashMap<>();

        for (float[] floats : KEY_COMBOS) {
            for (boolean using : BOOLEANS) {
                for (boolean sprinting : BOOLEANS) {
                    for (boolean sneaking : BOOLEANS_REVERSED) {
                        for (boolean jump : BOOLEANS_REVERSED) {
                            float strafe = floats[0];
                            float forward = floats[1];
                            float friction = moveEntityWithHeading(sprinting, movementData).inputFriction;

                            if (sneaking) {
                                strafe = (float) (strafe * 0.3D);
                                forward = (float) (forward * 0.3D);
                            }

                            if (using) {
                                strafe *= 0.2F;
                                forward *= 0.2F;
                            }

                            boolean didJump = false;
                            if (jump && sprinting && this.onGround) {
                                float radians = movementData.getLocation().getYaw() * (float) (Math.PI / 180.0D);
                                this.kbX -= MathHelper.sin(radians) * 0.2F;
                                this.kbZ += MathHelper.cos(radians) * 0.2F;
                                didJump = true;
                            }

                            strafe *= 0.98F;
                            forward *= 0.98F;
                            moveFlying(strafe, forward, friction, false, movementData);

                            double deltaX = movementData.getDeltaX() - this.kbX;
                            double deltaZ = movementData.getDeltaZ() - this.kbZ;
                            double offsetH = Math.hypot(deltaX, deltaZ);

                            dataAssessments.put(offsetH,
                                    new VelocityKeys(strafe, forward, friction, false, didJump));

                            this.kbX = x;
                            this.kbZ = z;
                        }
                    }
                }
            }
        }

        double closest = dataAssessments.keySet().stream()
                .mapToDouble(value -> value)
                .min()
                .orElse(3865386.0D);

        VelocityKeys result = dataAssessments.get(closest);
        dataAssessments.clear();
        return result;
    }

    private void moveFlying(float strafe,
                            float forward,
                            float friction,
                            boolean fastMath,
                            MovementData movementData) {
        float f = strafe * strafe + forward * forward;
        if (f >= 1.0E-4F) {
            f = MathHelper.sqrt_float(f);
            if (f < 1.0F) {
                f = 1.0F;
            }

            f = friction / f;
            strafe *= f;
            forward *= f;

            float yawRadius = movementData.getLocation().getYaw() * (float) Math.PI / 180.0F;
            float f1 = fastMath ? (float) Math.sin(yawRadius) : MathHelper.sin(yawRadius);
            float f2 = fastMath ? (float) Math.cos(yawRadius) : MathHelper.cos(yawRadius);

            this.kbX += strafe * f2 - forward * f1;
            this.kbZ += forward * f2 + strafe * f1;
        }
    }

    private HeadingData moveEntityWithHeading(boolean sprint, MovementData movementData) {
        float f4 = 0.91F;
        float f5 = getWalkSpeed();

        if (this.onGround) {
            f4 = this.karhuCurrentFriction;
            float f = 0.16277136F / (f4 * f4 * f4);

            if (sprint) {
                f5 += f5 * 0.3F;
            }

            f5 *= f;
        } else {
            f5 = sprint ? 0.025999999F : 0.02F;
        }

        return new HeadingData(f4, f5);
    }

    private void bruteforceAttack(MovementData movementData) {
        Map<Double, AttackFit> diffs = new HashMap<>();
        double ogX = this.kbX;
        double ogZ = this.kbZ;
        double original = Math.hypot(movementData.getDeltaX() - this.kbX,
                movementData.getDeltaZ() - this.kbZ);

        diffs.put(original, new AttackFit(this.kbX, this.kbZ, 0));

        double unMovedOgZ;
        for (int j = 0; ++j <= this.attacks; ogZ = unMovedOgZ) {
            ogX *= 0.6D;
            ogZ *= 0.6D;

            double unMovedOgX = ogX;
            unMovedOgZ = ogZ;

            VelocityKeys keys = computeKeys(ogX, ogZ, movementData);
            if (keys != null) {
                MotionPair directionAdd = moveFlyingPair2(
                        keys.strafe,
                        keys.forward,
                        keys.friction,
                        movementData
                );

                if (directionAdd != null) {
                    ogX += directionAdd.x;
                    ogZ += directionAdd.z;
                }
            }

            double diffMult = Math.hypot(movementData.getDeltaX() - ogX,
                    movementData.getDeltaZ() - ogZ);

            diffs.put(diffMult, new AttackFit(unMovedOgX, unMovedOgZ, j));
            ogX = unMovedOgX;
        }

        double closest = diffs.keySet().stream()
                .mapToDouble(value -> value)
                .min()
                .orElse(-420.0D);

        AttackFit pair = diffs.get(closest);
        if (pair != null) {
            this.kbX = pair.x;
            this.kbZ = pair.z;
            this.bruteforcedAttacks = pair.attacks;
        }

        diffs.clear();
    }

    private MotionPair moveFlyingPair2(float strafe,
                                       float forward,
                                       float friction,
                                       MovementData movementData) {
        float f = strafe * strafe + forward * forward;
        if (f < 1.0E-4F) {
            return null;
        }

        f = MathHelper.sqrt_float(f);
        if (f < 1.0F) {
            f = 1.0F;
        }

        f = friction / f;
        strafe *= f;
        forward *= f;

        float yawRadius = movementData.getLocation().getYaw() * (float) Math.PI / 180.0F;
        float f1 = MathHelper.sin(yawRadius);
        float f2 = MathHelper.cos(yawRadius);

        return new MotionPair(
                strafe * f2 - forward * f1,
                forward * f2 + strafe * f1
        );
    }

    private void handleTransactionBoundary(TransactionKey acknowledged) {
        for (PendingVelocity pending : this.pendingVelocities) {
            if (!pending.preBoundaryReached
                    && pending.preTransaction != null
                    && pending.preTransaction.equals(acknowledged)) {
                pending.preBoundaryReached = true;
            }

            if (pending.postTransaction != null
                    && pending.postTransaction.equals(acknowledged)) {
                /*
                 * This mirrors queueToPostPing: if the movement matcher did not
                 * consume the velocity before this boundary, velocityTick is
                 * force-applied and VelocityB sees it on the next flying packet.
                 */
                pending.postBoundaryReached = true;
            }
        }
    }

    private Vector pollKarhuTickedVelocity(MovementData movementData) {
        if (this.pendingVelocities.isEmpty()) {
            return null;
        }

        PendingVelocity activated = null;
        PendingFit activatedFit = null;
        boolean activatedForced = false;

        for (PendingVelocity pending : this.pendingVelocities) {
            int sentAge = this.movementSequence - pending.sentMovementSequence;

            if (sentAge > MAX_PENDING_MOVEMENT_AGE) {
                this.pendingVelocities.remove(pending);
                continue;
            }

            /* Before Karhu's pre-ping callback this velocity does not exist yet. */
            if (!pending.preBoundaryReached) {
                continue;
            }

            PendingFit fit = fitPendingVelocity(pending.velocity, movementData);
            if (fit != null) {
                pending.bestHorizontalError = Math.min(pending.bestHorizontalError, fit.horizontalError);
                pending.bestVerticalError = Math.min(pending.bestVerticalError, fit.verticalError);
            }

            if (fit != null && fit.matches) {
                activated = pending;
                activatedFit = fit;
                activatedForced = false;
                this.pendingVelocities.remove(pending);
            } else if (pending.postBoundaryReached) {
                activated = pending;
                activatedFit = fit;
                activatedForced = true;
                this.pendingVelocities.remove(pending);
            } else {
                continue;
            }

            /*
             * Keep scanning. Karhu can consume several velocity entries on the
             * same movement packet; repeated velocityTick calls leave the last
             * consumed vector as tickedVelocity for VelocityB.
             */
        }

        if (activated == null) {
            return null;
        }

        this.lastVelocityWasForced = activatedForced;
        this.lastPendingHorizontalError = activatedFit == null
                ? activated.bestHorizontalError
                : activatedFit.horizontalError;
        this.lastPendingVerticalError = activatedFit == null
                ? activated.bestVerticalError
                : activatedFit.verticalError;

        /* Karhu velocityTick stores the ORIGINAL packet vector. */
        return activated.velocity.clone();
    }

    private TransactionKey getOutstandingRegularTransaction() {
        if (profile == null) {
            return null;
        }

        Map<Integer, Long> modern = profile.getISentTransactions();
        if (modern != null && !modern.isEmpty()) {
            Integer id = modern.keySet().iterator().next();
            if (id != null) {
                return TransactionKey.modern(id);
            }
        }

        Map<Short, Long> legacy = profile.getSSentTransactions();
        if (legacy != null && !legacy.isEmpty()) {
            Short id = legacy.keySet().iterator().next();
            if (id != null) {
                return TransactionKey.legacy(id);
            }
        }

        return null;
    }

    private TransactionKey readRegularServerTransaction(PacketSendEvent event) {
        try {
            if (event.getPacketType() == PacketType.Play.Server.PING) {
                int id = new WrapperPlayServerPing(event).getId();
                Map<Integer, Long> regular = profile.getISentTransactions();
                return regular != null && regular.containsKey(id)
                        ? TransactionKey.modern(id)
                        : null;
            }

            if (event.getPacketType() == PacketType.Play.Server.WINDOW_CONFIRMATION) {
                short id = new WrapperPlayServerWindowConfirmation(event).getActionId();
                Map<Short, Long> regular = profile.getSSentTransactions();
                return regular != null && regular.containsKey(id)
                        ? TransactionKey.legacy(id)
                        : null;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private TransactionKey readClientTransaction(PacketReceiveEvent event) {
        try {
            if (event.getPacketType() == PacketType.Play.Client.PONG) {
                return TransactionKey.modern(new WrapperPlayClientPong(event).getId());
            }

            if (event.getPacketType() == PacketType.Play.Client.WINDOW_CONFIRMATION) {
                return TransactionKey.legacy(
                        new WrapperPlayClientWindowConfirmation(event).getActionId()
                );
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private PendingFit fitPendingVelocity(Vector velocity, MovementData movementData) {
        if (velocity == null || movementData == null) {
            return null;
        }

        double originalHorizontal = Math.hypot(velocity.getX(), velocity.getZ());
        double minimum = clamp();
        double kbX = Math.abs(velocity.getX()) < minimum ? 0.0D : velocity.getX();
        double kbY = Math.abs(velocity.getY()) < minimum ? 0.0D : velocity.getY();
        double kbZ = Math.abs(velocity.getZ()) < minimum ? 0.0D : velocity.getZ();

        MotionPair horizontal;
        boolean attacked = this.pendingAttacks != 0;

        if (attacked) {
            horizontal = bruteforcePendingAttack(kbX, kbZ, movementData);
        } else {
            horizontal = loopPendingKeys(kbX, kbZ, movementData);
        }

        if (horizontal == null) {
            return null;
        }

        double horizontalError = Math.hypot(
                movementData.getDeltaX() - horizontal.x,
                movementData.getDeltaZ() - horizontal.z
        );
        double verticalError = Math.abs(movementData.getDeltaY() - kbY);

        boolean collidedHorizontal = this.sinceBlockCollision <= 2
                || movementData.isNearWall()
                || movementData.isPacketNearWall();
        boolean collidedVertical = this.sinceVerticalCollision <= 2
                || movementData.isUnderblock();

        double precisionY;
        double precisionH;

        if (attacked) {
            precisionY = collidedVertical ? 0.205D : 0.03125D;
            precisionH = collidedHorizontal
                    ? Math.max(originalHorizontal, horizontalError)
                    : 0.03125D;
        } else {
            precisionY = collidedVertical ? 0.43D : 0.03125D;
            precisionH = collidedHorizontal
                    ? Math.max(originalHorizontal, horizontalError)
                    : 0.031D;
        }

        boolean verticalMatch = verticalError <= precisionY
                || (!attacked && isJumped(movementData));
        boolean matches = horizontalError <= precisionH && verticalMatch;

        return new PendingFit(matches, horizontalError, verticalError);
    }

    /* Exact local equivalent of NMSValueParser.loopKeysGetKeys used by
       KarhuPlayer.checkVelocity before VelocityB receives tickedVelocity. */
    private MotionPair loopPendingKeys(double kbX, double kbZ, MovementData movementData) {
        Map<Double, MotionPair> assessments = new HashMap<>();
        double originalX = kbX;
        double originalZ = kbZ;
        boolean actuallySneaking = profile.getActionData() != null
                && profile.getActionData().isSneaking();

        for (float[] floats : KEY_COMBOS) {
            for (boolean sprint : BOOLEANS) {
                for (boolean blocking : BOOLEANS_REVERSED) {
                    for (boolean jumped : BOOLEANS_REVERSED) {
                        float strafe = floats[0];
                        float forward = floats[1];

                        if (jumped && sprint) {
                            float radians = movementData.getLocation().getYaw()
                                    * (float) (Math.PI / 180.0D);
                            kbX -= MathHelper.sin(radians) * 0.2F;
                            kbZ += MathHelper.cos(radians) * 0.2F;
                        }

                        if (actuallySneaking) {
                            strafe = (float) (strafe * 0.3D);
                            forward = (float) (forward * 0.3D);
                        }

                        if (blocking) {
                            strafe *= 0.2F;
                            forward *= 0.2F;
                        }

                        strafe *= 0.98F;
                        forward *= 0.98F;

                        MotionPair addition = moveFlyingPairPending(
                                strafe,
                                forward,
                                sprint,
                                movementData
                        );

                        if (addition != null) {
                            kbX += addition.x;
                            kbZ += addition.z;
                        }

                        double error = Math.hypot(
                                movementData.getDeltaX() - kbX,
                                movementData.getDeltaZ() - kbZ
                        );

                        MotionPair candidate = new MotionPair((float) kbX, (float) kbZ);
                        if (error <= 0.001D) {
                            return candidate;
                        }

                        assessments.put(error, candidate);
                        kbX = originalX;
                        kbZ = originalZ;
                    }
                }
            }
        }

        double closest = assessments.keySet().stream()
                .mapToDouble(value -> value)
                .min()
                .orElse(3865386.0D);

        return assessments.get(closest);
    }

    /* Exact local equivalent of NMSValueParser.bruteforceAttack for the
       pending-velocity synchronization stage. */
    private MotionPair bruteforcePendingAttack(double kbX,
                                               double kbZ,
                                               MovementData movementData) {
        Map<Double, MotionPair> differences = new HashMap<>();
        double originalError = Math.hypot(
                movementData.getDeltaX() - kbX,
                movementData.getDeltaZ() - kbZ
        );
        differences.put(originalError, new MotionPair((float) kbX, (float) kbZ));

        double originalX = Math.abs(kbX) < clamp() ? 0.0D : kbX;
        double originalZ = Math.abs(kbZ) < clamp() ? 0.0D : kbZ;
        double workingX = originalX;
        double workingZ = originalZ;

        for (int j = 0; ++j <= this.pendingAttacks; workingZ = originalZ) {
            workingX *= 0.6D;
            workingZ *= 0.6D;

            MotionPair fitted = loopPendingKeys(workingX, workingZ, movementData);
            if (fitted != null) {
                double error = Math.hypot(
                        movementData.getDeltaX() - fitted.x,
                        movementData.getDeltaZ() - fitted.z
                );

                if (error <= 0.001D) {
                    return fitted;
                }

                differences.put(error, fitted);
            }

            workingX = originalX;
        }

        double closest = differences.keySet().stream()
                .mapToDouble(value -> value)
                .min()
                .orElse(0.0D);

        return differences.get(closest);
    }

    private MotionPair moveFlyingPairPending(float strafe,
                                             float forward,
                                             boolean sprint,
                                             MovementData movementData) {
        float friction = moveEntityWithHeadingPending(sprint).inputFriction;
        float f = strafe * strafe + forward * forward;
        if (f < 1.0E-4F) {
            return null;
        }

        f = MathHelper.sqrt_float(f);
        if (f < 1.0F) {
            f = 1.0F;
        }

        f = friction / f;
        strafe *= f;
        forward *= f;

        float yawRadius = movementData.getLocation().getYaw() * (float) Math.PI / 180.0F;
        float sin = MathHelper.sin(yawRadius);
        float cos = MathHelper.cos(yawRadius);

        return new MotionPair(
                strafe * cos - forward * sin,
                forward * cos + strafe * sin
        );
    }

    private HeadingData moveEntityWithHeadingPending(boolean sprint) {
        float carryFriction = 0.91F;
        float inputFriction = getWalkSpeed();

        if (this.onGround) {
            carryFriction = this.karhuLastTickFriction;
            float acceleration = 0.16277136F
                    / (carryFriction * carryFriction * carryFriction);

            if (sprint) {
                inputFriction += inputFriction * 0.3F;
            }

            inputFriction *= acceleration;
        } else {
            inputFriction = sprint ? 0.025999999F : 0.02F;
        }

        return new HeadingData(carryFriction, inputFriction);
    }

    private void updateKarhuFrictionState(MovementData movementData) {
        this.karhuLastTickFriction = this.karhuCurrentFriction;
        this.karhuCurrentFriction = lookupKarhuFriction(movementData);
    }

    private float lookupKarhuFriction(MovementData movementData) {
        if (movementData == null || movementData.getLastLocation() == null) {
            return 0.54600006F;
        }

        try {
            /*
             * Open-Karhu intentionally looks up the block under LAST location,
             * not Arrow's already-advanced current location. That one-tick
             * distinction matters on edges/ice/slime and is preserved here.
             */
            double downLookup = profile.getVersion() != null
                    && profile.getVersion().getProtocolVersion() < 573
                    ? 1.0D
                    : 0.5000001D;

            CustomLocation lookup = movementData.getLastLocation().clone().subtract(0.0D, downLookup, 0.0D);
            Material material = lookup.getBlock().getType();
            String name = material.name();

            float slipperiness;
            if (profile.getVersion() != null
                    && profile.getVersion().getProtocolVersion() < 47
                    && "SLIME_BLOCK".equals(name)) {
                slipperiness = 0.6F;
            } else if (profile.getVersion() != null
                    && profile.getVersion().isOlderThan(ClientVersion.V_1_15)
                    && "HONEY_BLOCK".equals(name)) {
                slipperiness = 0.8F;
            } else if (profile.getVersion() != null
                    && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)
                    && "BLUE_ICE".equals(name)) {
                slipperiness = 0.98F;
            } else {
                slipperiness = CollisionUtils.getBlockSlipperiness(material);
            }

            return slipperiness * 0.91F;
        } catch (Throwable ignored) {
            return 0.54600006F;
        }
    }

    private void updateMoveTicks(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            this.moveTicks++;
        } else {
            this.moveTicks = 0;
        }
    }

    private void updateKarhuEnvironmentTimers(MovementData movementData) {
        this.sinceClimbable = tickSince(this.sinceClimbable,
                movementData.isNearClimbable() || movementData.isClimb());

        this.sinceLiquid = tickSince(this.sinceLiquid,
                movementData.isNearWater()
                        || movementData.isInsideLiquid()
                        || movementData.isInsideWater()
                        || movementData.isNearLava());

        this.sinceBoat = tickSince(this.sinceBoat,
                movementData.isOnBoat());

        this.sinceBlockCollision = tickSince(this.sinceBlockCollision,
                movementData.isNearWall()
                        || movementData.isPacketNearWall()
                        || movementData.isUnderblock());

        this.sinceVerticalCollision = tickSince(this.sinceVerticalCollision,
                movementData.isUnderblock());

        BlockProcessor blockProcessor = profile.getBlockProcessor();
        boolean ghostCollision = blockProcessor != null
                && (blockProcessor.isNearGhostBlock()
                || blockProcessor.isOnGhostBlock()
                || blockProcessor.isInsideGhostBlock()
                || blockProcessor.isUnderGhostBlock()
                || blockProcessor.isInteractingGhostBlock());

        this.sinceGhostCollision = tickSince(this.sinceGhostCollision, ghostCollision);

        boolean sneakEdge = profile.getActionData() != null
                && profile.getActionData().isSneaking()
                && movementData.isOnGround()
                && movementData.getLastNearEdgeTicks() == 0;

        this.sinceSneakEdge = tickSince(this.sinceSneakEdge, sneakEdge);
    }

    private int tickSince(int current, boolean active) {
        if (active) {
            return 0;
        }

        return current < 1_000_000 ? current + 1 : current;
    }

    private boolean isJumped(MovementData movementData) {
        double jumpMotion = 0.42F;

        if (profile.getPotionData() != null && SpeedUtilities.getJumpBoostPotionLevel(profile) > 0) {
            jumpMotion += (float) profile.getPotionData().getJumpAmplifier() * 0.1F;
        }

        double difference = movementData.getDeltaY() - jumpMotion;

        return (Math.abs(difference) <= 0.03125D
                && movementData.isLastOnGround()
                && !movementData.isOnGround())
                || (movementData.isLastOnGround()
                && !movementData.isOnGround()
                && this.sinceVerticalCollision <= 1
                && movementData.getDeltaY() > 0.0D)
                || movementData.getSinceTeleportTicks() <= 2;
    }

    private boolean flightElapsedMoreThan30Ticks() {
        if (profile.getLastFlightToggleTimer() == null) {
            return true;
        }

        return profile.getLastFlightToggleTimer().passed(30);
    }

    private float getWalkSpeed() {
        return MathUtil.getAttributeSpeed(profile, false);
    }

    private double offsetMove() {
        return profile.getVersion() != null
                && profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_18_2)
                ? 2.0E-4D
                : 0.03D;
    }

    private double clamp() {
        return isNewerThan8() ? 0.003D : 0.005D;
    }

    private boolean isNewerThan8() {
        return profile.getVersion() != null
                && !profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
    }

    private int getLastAttackTick() {
        return profile.getCombatData() == null
                ? Integer.MAX_VALUE
                : profile.getCombatData().getAttackedTicks();
    }

    private String theme() {
        return MsgType.MAIN_THEME_COLOR.getMessage();
    }

    private String format(int places, double value) {
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }

    private boolean isMovement(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    private static class PendingVelocity {
        int sequence;
        Vector velocity;
        int sentMovementSequence;
        TransactionKey preTransaction;
        volatile TransactionKey postTransaction;
        volatile boolean preBoundaryReached;
        volatile boolean postBoundaryReached;
        double bestHorizontalError = Double.MAX_VALUE;
        double bestVerticalError = Double.MAX_VALUE;

        private PendingVelocity(int sequence,
                                Vector velocity,
                                int sentMovementSequence,
                                TransactionKey preTransaction) {
            this.sequence = sequence;
            this.velocity = velocity;
            this.sentMovementSequence = sentMovementSequence;
            this.preTransaction = preTransaction;
        }
    }

    private static class TransactionKey {
        boolean modern;
        int id;

        private TransactionKey(boolean modern, int id) {
            this.modern = modern;
            this.id = id;
        }

        private static TransactionKey modern(int id) {
            return new TransactionKey(true, id);
        }

        private static TransactionKey legacy(short id) {
            return new TransactionKey(false, id);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof TransactionKey other)) {
                return false;
            }

            return this.modern == other.modern && this.id == other.id;
        }

        @Override
        public int hashCode() {
            return 31 * Boolean.hashCode(this.modern) + this.id;
        }
    }

    private static class PendingFit {
        boolean matches;
        double horizontalError;
        double verticalError;

        private PendingFit(boolean matches, double horizontalError, double verticalError) {
            this.matches = matches;
            this.horizontalError = horizontalError;
            this.verticalError = verticalError;
        }
    }

    private static class VelocityKeys {
        float strafe;
        float forward;
        float friction;
        boolean fastMath;
        boolean jump;

        private VelocityKeys(float strafe,
                             float forward,
                             float friction,
                             boolean fastMath,
                             boolean jump) {
            this.strafe = strafe;
            this.forward = forward;
            this.friction = friction;
            this.fastMath = fastMath;
            this.jump = jump;
        }
    }

    private static class HeadingData {
        float carryFriction;
        float inputFriction;

        private HeadingData(float carryFriction, float inputFriction) {
            this.carryFriction = carryFriction;
            this.inputFriction = inputFriction;
        }
    }

    private static class MotionPair {
        float x;
        float z;

        private MotionPair(float x, float z) {
            this.x = x;
            this.z = z;
        }
    }

    private static class AttackFit {
        double x;
        double z;
        int attacks;

        private AttackFit(double x, double z, int attacks) {
            this.x = x;
            this.z = z;
            this.attacks = attacks;
        }
    }
}