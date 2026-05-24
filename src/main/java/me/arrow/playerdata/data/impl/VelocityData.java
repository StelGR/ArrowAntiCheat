package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// our velocity data, we have sustain and drain velocity, one slowly goes down like normal
// the other only resets when we stop moving or velocity drains. but it stays at the packet value we
// received on transaction, we could basically make it so on velocity we add it for bedrock players,
// but right now i would rather fix transactions on them, will be more precise that way.

@Getter
@Setter
public class VelocityData implements Data {

    private Vector velocity = new Vector();
    private Vector velocityfvc = new Vector();

    private Vector explosionKnockbackPacket = new Vector();
    private Vector explosionKnockback = new Vector();
    private Vector explosionKnockbackfvc = new Vector();

    private Vector velocitySustain = new Vector();
    private Vector explosionKnockbackSustain = new Vector();

    private double velocityH, velocityV;
    private double velocityHfvc, velocityVfvc;

    private double velocityHSustain, velocityVSustain;

    private static final double STACKED_STOP_EPSILON = 0.003D;
    private static final int STACKED_FULL_STOP_TICKS = 10;

    private double stackedVerticalVelocity;
    private double stackedHorizontalVelocity;
    private int stackedFullStopTicks;
    private int velocityTicks;

    private final Profile profile;

    private boolean attackedRecently;
    private long lastAttackTime;

    private final Map<Integer, WrappedData> pWrappedDataMap = new ConcurrentHashMap<>();
    private final Map<Short, WrappedData> tWrappedDataMap = new ConcurrentHashMap<>();

    private long lastDecayTick = -1L;

    public VelocityData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PONG)) {
            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
            int id = pong.getId();
            handleAck(id, (short) id);
        } else if (event.getPacketType().equals(PacketType.Play.Client.WINDOW_CONFIRMATION)) {
            WrapperPlayClientWindowConfirmation trans = new WrapperPlayClientWindowConfirmation(event);
            short id = trans.getActionId();
            handleAck(id, id);
        }

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {

            velocityTicks++;

            long currentTick = System.currentTimeMillis() / 50L;

            if (currentTick - lastDecayTick >= 2) {
                lastDecayTick = currentTick;

                MovementData movementData = profile.getMovementData();

                boolean verticalStopped =
                        Math.abs(movementData.getDeltaY()) < STACKED_STOP_EPSILON
                                && Math.abs(movementData.getLastDeltaY()) < STACKED_STOP_EPSILON;

                boolean horizontalStopped =
                        movementData.getDeltaXZ() < STACKED_STOP_EPSILON
                                && movementData.getLastDeltaXZ() < STACKED_STOP_EPSILON;

                if (!movementData.isMoving()
                        || (horizontalStopped && verticalStopped)) {
                    resetHorizontalVelocitySustain();
                }

                if (velocityH > 0.0D) {
                    double totalH = velocityH;

                    totalH *= movementData.isOnGround()
                            ? movementData.getFrictionFactor()
                            : 0.91F;

                    velocityH = Math.max(totalH - 0.001D, 0.0D);
                }

                if (velocityV > 0.0D) {
                    double totalV = velocityV;

                    totalV = (totalV * (movementData.isOnGround()
                            ? movementData.getFrictionFactor()
                            : 0.91F)) - 0.04D;

                    velocityV = Math.max(totalV, 0.0D);
                }

                if (velocityV < 0.00001D) {
                    velocityV = 0.0D;
                    resetVerticalVelocitySustain();
                }

                if (velocityH < 0.00001D) {
                    velocityH = 0.0D;
                    resetHorizontalVelocitySustain();
                }

                explosionKnockback.multiply(0.95D);

                if (Math.abs(explosionKnockback.getX()) < 0.0001D) {
                    explosionKnockback.setX(0.0D);
                }

                if (Math.abs(explosionKnockback.getY()) < 0.0001D) {
                    explosionKnockback.setY(0.0D);
                }

                if (Math.abs(explosionKnockback.getZ()) < 0.0001D) {
                    explosionKnockback.setZ(0.0D);
                }

                if (isZero(explosionKnockback)) {
                    explosionKnockbackSustain = new Vector();
                }
            }

            updateStackedVelocityState();
        }

        if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            attackedRecently = true;
            lastAttackTime = System.currentTimeMillis();
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity wrapper = new WrapperPlayServerEntityVelocity(event);

            if (profile.getPlayer() == null || wrapper.getEntityId() != profile.getPlayer().getEntityId()) {
                return;
            }

            Vector3d v = wrapper.getVelocity();
            Vector packetVelocity = new Vector(v.getX(), v.getY(), v.getZ());

            /*
             * Do NOT apply velocity here.
             * Only store it as pending and apply it when the transaction confirms.
             */
            add(Actions.VELOCITY, packetVelocity);

        } else if (event.getPacketType() == PacketType.Play.Server.EXPLOSION) {
            WrapperPlayServerExplosion explosion = new WrapperPlayServerExplosion(event);
            Vector3d knockback = explosion.getKnockback();

            if (knockback == null) {
                return;
            }

            Vector packetExplosion = new Vector(knockback.getX(), knockback.getY(), knockback.getZ());

            /*
             * Do NOT apply explosion here.
             * Only store it as pending and apply it when the transaction confirms.
             */
            add(Actions.EXPLOSION, packetExplosion);
        }
    }

    private void handleAck(int id, short id2) {
        WrappedData wrappedData = pWrappedDataMap.remove(id);

        if (wrappedData == null) {
            wrappedData = tWrappedDataMap.remove(id2);
        }

        if (wrappedData == null) {
            return;
        }

        handleWrappedData(wrappedData);
    }

    private void handleWrappedData(WrappedData wrappedData) {
        if (wrappedData == null || wrappedData.getVector() == null) {
            return;
        }

        Vector vector = copy(wrappedData.getVector());

        if (wrappedData.getAction() == Actions.VELOCITY) {
            setVelocityTicks(0);

            /*
             * Transaction-confirmed normal velocity.
             */
            setVelocity(copy(vector));

            /*
             * Transaction-confirmed FVC velocity.
             */
            setVelocityfvc(copy(vector));

            /*
             * Transaction-confirmed sustain velocity.
             */
            setVelocitySustain(maxVector(getVelocitySustain(), vector));

            double horizontal = Math.hypot(vector.getX(), vector.getZ());
            double vertical = vector.getY();

            addStackedVelocity(copy(vector));

            setVelocityH(horizontal);
            setVelocityV(vertical);

            setVelocityHfvc(horizontal);
            setVelocityVfvc(vertical);

            setVelocityHSustain(Math.max(getVelocityHSustain(), horizontal));
            setVelocityVSustain(Math.max(getVelocityVSustain(), vertical));

        } else if (wrappedData.getAction() == Actions.EXPLOSION) {
            setVelocityTicks(0);

            /*
             * Transaction-confirmed normal explosion velocity.
             */
            setExplosionKnockbackPacket(copy(vector));
            setExplosionKnockback(copy(vector));

            /*
             * Transaction-confirmed FVC explosion velocity.
             */
            setExplosionKnockbackfvc(copy(vector));

            /*
             * Transaction-confirmed sustain explosion velocity.
             */
            setExplosionKnockbackSustain(maxVector(getExplosionKnockbackSustain(), vector));

            addStackedVelocity(copy(vector));
        }
    }


    private void addStackedVelocity(Vector vector) {
        if (vector == null) {
            return;
        }

        double horizontal = Math.hypot(vector.getX(), vector.getZ());
        double vertical = Math.max(vector.getY(), 0.0D);

        if (horizontal > 0.0D) {
            this.stackedHorizontalVelocity += horizontal;
            this.stackedFullStopTicks = 0;
        }

        if (vertical > 0.0D) {
            this.stackedVerticalVelocity += vertical;
            this.stackedFullStopTicks = 0;
        }
    }


    private void updateStackedVelocityState() {
        if (profile == null || profile.getMovementData() == null) {
            resetStackedVelocity();
            resetVelocitySustain();
            return;
        }

        MovementData movementData = profile.getMovementData();

        boolean grounded = movementData.isOnGround() || movementData.isServerGround();

        if (grounded) {
            resetStackedVelocity();
            return;
        }

        boolean noVerticalMovement =
                Math.abs(movementData.getDeltaY()) < STACKED_STOP_EPSILON
                        && Math.abs(movementData.getLastDeltaY()) < STACKED_STOP_EPSILON;

        boolean noHorizontalMovement =
                movementData.getDeltaXZ() < STACKED_STOP_EPSILON
                        && movementData.getLastDeltaXZ() < STACKED_STOP_EPSILON;

        boolean fullyStopped = noVerticalMovement && noHorizontalMovement;

        if (fullyStopped) {
            stackedFullStopTicks++;
        } else {
            stackedFullStopTicks = 0;
        }

        if (stackedFullStopTicks >= STACKED_FULL_STOP_TICKS) {
            resetStackedVelocity();
            resetVelocitySustain();
        }
    }

    public void resetStackedVelocity() {
        this.stackedVerticalVelocity = 0.0D;
        this.stackedHorizontalVelocity = 0.0D;
        this.stackedFullStopTicks = 0;
    }

    public void resetStackedVerticalVelocity() {
        this.stackedVerticalVelocity = 0.0D;
        this.stackedFullStopTicks = 0;
    }

    public void resetStackedHorizontalVelocity() {
        this.stackedHorizontalVelocity = 0.0D;
        this.stackedFullStopTicks = 0;
    }

    public void resetVelocitySustain() {
        resetVerticalVelocitySustain();
        resetHorizontalVelocitySustain();
    }

    public void resetVerticalVelocitySustain() {
        this.velocityVSustain = 0.0D;

        if (this.velocitySustain != null) {
            this.velocitySustain.setY(0.0D);
        }

        if (this.explosionKnockbackSustain != null) {
            this.explosionKnockbackSustain.setY(0.0D);
        }
    }

    public void resetHorizontalVelocitySustain() {
        this.velocityHSustain = 0.0D;

        if (this.velocitySustain != null) {
            this.velocitySustain.setX(0.0D);
            this.velocitySustain.setZ(0.0D);
        }

        if (this.explosionKnockbackSustain != null) {
            this.explosionKnockbackSustain.setX(0.0D);
            this.explosionKnockbackSustain.setZ(0.0D);
        }
    }

    public void add(Actions action, Vector vector) {
        if (vector == null) return;

        WrappedData wrappedData = new WrappedData(
                System.currentTimeMillis(),
                action,
                copy(vector)
        );

        boolean modernTransaction = PacketEvents.getAPI()
                .getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_17);

        if (modernTransaction) {
            int transactionId = profile.getTransactionProcessor().getNextTimeTransaction();

            WrapperPlayServerPing packetConfirm = new WrapperPlayServerPing(transactionId);
            pWrappedDataMap.put(packetConfirm.getId(), wrappedData);
            profile.sendPacket(packetConfirm);
            return;
        }

        short sTransactionId = profile.getTransactionProcessor().getNextSTimeTransaction();

        WrapperPlayServerWindowConfirmation packet =
                new WrapperPlayServerWindowConfirmation(0, sTransactionId, false);

        tWrappedDataMap.put(packet.getActionId(), wrappedData);
        profile.sendPacket(packet);
    }

    public enum Actions {
        VELOCITY,
        EXPLOSION
    }

    @Getter
    @AllArgsConstructor
    public static class WrappedData {
        private final long timestamp;
        private final Actions action;
        private final Vector vector;
    }

    public Vector getTotalVelocity() {
        return copy(velocity).add(copy(explosionKnockback));
    }

    public Vector getTotalVelocitySustained() {
        return copy(velocitySustain).add(copy(explosionKnockbackSustain));
    }

    public double getTotalVerticalVelocity() {
        return getVelocityV() + copy(explosionKnockback).getY();
    }

    public double getTotalHorizontalVelocity() {
        Vector explosion = copy(explosionKnockback);

        return Math.hypot(
                explosion.getX(),
                explosion.getZ()
        ) + velocityH;
    }

    public double getTotalVerticalVelocitySustain() {
        return getVelocityVSustain() + copy(explosionKnockbackSustain).getY();
    }

    public double getTotalHorizontalVelocitySustain() {
        Vector explosion = copy(explosionKnockbackSustain);

        return Math.hypot(
                explosion.getX(),
                explosion.getZ()
        ) + velocityHSustain;
    }

    public double getTotalVerticalFVCVelocity() {
        return getVelocityVfvc() + copy(explosionKnockbackfvc).getY();
    }

    public double getTotalHorizontalFVCVelocity() {
        Vector explosion = copy(explosionKnockbackfvc);

        return Math.hypot(
                explosion.getX(),
                explosion.getZ()
        ) + velocityHfvc;
    }

    public boolean isTakingVelocity() {
        return getTotalHorizontalVelocity() > 0.0D
                || getTotalVerticalVelocity() > 0.0D
                || getTotalHorizontalVelocitySustain() > 0.0D
                || getTotalVerticalVelocitySustain() > 0.0D
                || stackedHorizontalVelocity > 0.0D
                || stackedVerticalVelocity > 0.0D;
    }

    private static Vector copy(Vector vector) {
        return vector == null ? new Vector(0.0D, 0.0D, 0.0D) : vector.clone();
    }

    private static boolean isZero(Vector vector) {
        if (vector == null) {
            return true;
        }

        return vector.getX() == 0.0D
                && vector.getY() == 0.0D
                && vector.getZ() == 0.0D;
    }

    private static Vector maxVector(Vector current, Vector incoming) {
        if (current == null) {
            return copy(incoming);
        }

        if (incoming == null) {
            return copy(current);
        }

        double currentHorizontal = Math.hypot(current.getX(), current.getZ());
        double incomingHorizontal = Math.hypot(incoming.getX(), incoming.getZ());

        double x = incomingHorizontal > currentHorizontal ? incoming.getX() : current.getX();
        double z = incomingHorizontal > currentHorizontal ? incoming.getZ() : current.getZ();
        double y = Math.max(current.getY(), incoming.getY());

        return new Vector(x, y, z);
    }
}