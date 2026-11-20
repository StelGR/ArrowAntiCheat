package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.ReflectionUtils;

/** Tracks Elytra state and the capped velocity carried after glide exit. */
public class ElytraData {

    /** ElytraA's generous max-firework allowances: dive horizontal and straight-up vertical. */
    private static final double MAX_POST_ELYTRA_HORIZONTAL_CARRY = 7.5D;
    private static final double MAX_POST_ELYTRA_UPWARD_CARRY = 3.5D;
    private static final double POST_ELYTRA_CARRY_TOLERANCE = 0.30D;
    private static final double POST_ELYTRA_EXIT_HORIZONTAL_DRAG = 0.95D;
    private static final double POST_ELYTRA_HORIZONTAL_EPSILON = 0.0002D;
    private static final double POST_ELYTRA_VERTICAL_CARRY_END = -0.60D;

    private final Profile profile;

    @Setter
    @Getter
    private int sinceGlidingTicks = 100000;
    @Setter
    @Getter
    private int glidingTicks;
    @Setter
    @Getter
    private int sinceElytraEquipTicks;

    /** Authoritative fall-flying flag from the player's entity metadata. */
    private boolean metadataGliding;
    private boolean lastElytraPose;

    private double lastElytraDeltaX;
    private double lastElytraDeltaY;
    private double lastElytraDeltaZ;
    private double postElytraDeltaX;
    private double postElytraDeltaY;
    private double postElytraDeltaZ;
    private double lastPostElytraDeltaX;
    private double lastPostElytraDeltaY;
    private double lastPostElytraDeltaZ;
    private boolean postElytraHorizontalActive;
    private boolean postElytraVerticalActive;
    private boolean postElytraFirstHorizontalUpdate;
    private boolean previousPacketMoving;

    public ElytraData(Profile profile) {
        this.profile = profile;
    }

    public void processSend(PacketSendEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Server.ENTITY_METADATA)
                || profile.getPlayer() == null) {
            return;
        }

        WrapperPlayServerEntityMetadata metadata;
        try {
            metadata = new WrapperPlayServerEntityMetadata(event);
        } catch (Throwable ignored) {
            return;
        }

        if (metadata.getEntityId() != profile.getPlayer().getEntityId()) return;

        try {
            for (EntityData<?> entry : metadata.getEntityMetadata()) {
                if (entry.getIndex() != 0 || !(entry.getValue() instanceof Byte)) continue;

                metadataGliding = ((((Byte) entry.getValue()) & 0x80) == 0x80);
                if (metadataGliding && profile.isWearingFunctionalElytra()) {
                    sinceGlidingTicks = 0;
                    glidingTicks = Math.max(glidingTicks, 1);
                }
                break;
            }
        } catch (Throwable ignored) {
        }
    }

    public void update(boolean glidingNow, boolean wearingFunctionalElytra,
                       boolean packetMoving, double deltaX, double deltaY, double deltaZ,
                       boolean hasGroundSupport, float lastFrictionFactor) {
        sinceGlidingTicks = glidingNow ? 0 : sinceGlidingTicks + 1;
        glidingTicks = glidingNow ? glidingTicks + 1 : 0;
        sinceElytraEquipTicks = wearingFunctionalElytra ? 0 : sinceElytraEquipTicks + 1;

        updateMomentum(glidingNow, packetMoving, deltaX, deltaY, deltaZ,
                hasGroundSupport, lastFrictionFactor);
    }

    public boolean isGlidingNow() {
        return profile != null
                && profile.isWearingFunctionalElytra()
                && (metadataGliding || ReflectionUtils.isGliding(profile.getPlayer()));
    }

    public boolean isGlidingOrRecentlyGlided() {
        return isGlidingOrRecentlyGlided(30);
    }

    public boolean isGlidingOrRecentlyGlided(int baseGraceTicks) {
        if (profile == null) return false;

        if (isGlidingNow() || glidingTicks > 0 || isPostElytraModelActive()) return true;
        if (!profile.isWearingFunctionalElytra()) return false;

        int transTicks = 0;
        int pingTicks = 0;
        try {
            transTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());
            pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        } catch (Throwable ignored) {
        }

        int maxLag = Math.max(transTicks, pingTicks);
        int lagComp = Math.min(800, maxLag * 4);
        return sinceGlidingTicks < baseGraceTicks + lagComp;
    }

    public float elytraHorizontalMomentum(boolean packetMoving) {
        return (float) Math.hypot(getPostElytraCarryX(packetMoving), getPostElytraCarryZ(packetMoving));
    }

    public float elytraVerticalMomentum(boolean packetMoving) {
        return (float) Math.abs(getPostElytraCarryY(packetMoving));
    }

    public boolean isPostElytraModelActive() {
        return postElytraHorizontalActive || postElytraVerticalActive;
    }

    public double getPostElytraCarryX(boolean packetMoving) {
        return packetMoving && postElytraHorizontalActive ? postElytraDeltaX : 0.0D;
    }

    public double getPostElytraCarryY(boolean packetMoving) {
        return packetMoving && postElytraVerticalActive ? postElytraDeltaY : 0.0D;
    }

    public double getPostElytraCarryZ(boolean packetMoving) {
        return packetMoving && postElytraHorizontalActive ? postElytraDeltaZ : 0.0D;
    }

    public double getLastPostElytraCarryX() {
        return lastPostElytraDeltaX;
    }

    public double getLastPostElytraCarryY() {
        return lastPostElytraDeltaY;
    }

    public double getLastPostElytraCarryZ() {
        return lastPostElytraDeltaZ;
    }

    public boolean hasValidPostElytraMomentum(boolean packetMoving, double deltaXZ, double deltaY) {
        // A handoff packet without position has no new delta to compare. Allow
        // only the first couple of packets after glide exit; repeated stationary
        // packets must return to the normal FlyA path.
        if (!packetMoving) return isPostElytraModelActive() && sinceGlidingTicks <= 2;

        double horizontalCarry = Math.hypot(
                getPostElytraCarryX(true), getPostElytraCarryZ(true));
        double verticalCarry = getPostElytraCarryY(true);
        if (horizontalCarry <= POST_ELYTRA_HORIZONTAL_EPSILON && Math.abs(verticalCarry) <= 0.015D) {
            return false;
        }

        boolean horizontalWithinCarry = horizontalCarry > POST_ELYTRA_HORIZONTAL_EPSILON
                && deltaXZ <= horizontalCarry + POST_ELYTRA_CARRY_TOLERANCE;
        boolean verticalWithinCarry = Math.abs(verticalCarry) > 0.015D
                && deltaY <= Math.abs(verticalCarry) + POST_ELYTRA_CARRY_TOLERANCE;

        // Horizontal carry can explain horizontal speed, but it cannot excuse
        // upward movement that exceeds the capped vertical Elytra carry.
        if (deltaY > Math.max(0.0D, verticalCarry) + POST_ELYTRA_CARRY_TOLERANCE) return false;

        return horizontalWithinCarry || verticalWithinCarry;
    }

    private void updateMomentum(boolean glidingNow, boolean packetMoving,
                                double deltaX, double deltaY, double deltaZ,
                                boolean hasGroundSupport,
                                float lastFrictionFactor) {
        if (glidingNow) {
            if (packetMoving) {
                lastElytraDeltaX = deltaX;
                lastElytraDeltaY = deltaY;
                lastElytraDeltaZ = deltaZ;
            }
            clearPostElytraModel();
            lastElytraPose = true;
            previousPacketMoving = packetMoving;
            return;
        }

        if (lastElytraPose) {
            lastPostElytraDeltaX = previousPacketMoving ? lastElytraDeltaX : 0.0D;
            lastPostElytraDeltaY = previousPacketMoving ? lastElytraDeltaY : 0.0D;
            lastPostElytraDeltaZ = previousPacketMoving ? lastElytraDeltaZ : 0.0D;

            double drag = getPostElytraHorizontalDrag(true, hasGroundSupport, lastFrictionFactor);
            double horizontalSpeed = Math.hypot(lastElytraDeltaX, lastElytraDeltaZ);
            double horizontalScale = horizontalSpeed > MAX_POST_ELYTRA_HORIZONTAL_CARRY
                    ? MAX_POST_ELYTRA_HORIZONTAL_CARRY / horizontalSpeed
                    : 1.0D;
            double cappedGlideY = Math.min(lastElytraDeltaY, MAX_POST_ELYTRA_UPWARD_CARRY);
            double vertical = (cappedGlideY - 0.08D) * 0.98D;
            if (hasGroundSupport) vertical = 0.0D;

            postElytraDeltaX = packetMoving ? lastElytraDeltaX * drag * horizontalScale : lastElytraDeltaX * horizontalScale;
            postElytraDeltaY = packetMoving ? vertical : cappedGlideY;
            postElytraDeltaZ = packetMoving ? lastElytraDeltaZ * drag * horizontalScale : lastElytraDeltaZ * horizontalScale;
            postElytraFirstHorizontalUpdate = !packetMoving;
            lastElytraPose = false;
            updatePostElytraActivity();
            previousPacketMoving = packetMoving;
            return;
        }

        if (!isPostElytraModelActive()) {
            lastPostElytraDeltaX = 0.0D;
            lastPostElytraDeltaY = 0.0D;
            lastPostElytraDeltaZ = 0.0D;
            postElytraFirstHorizontalUpdate = false;
            previousPacketMoving = packetMoving;
            return;
        }

        if (!packetMoving) {
            lastPostElytraDeltaX = 0.0D;
            lastPostElytraDeltaY = 0.0D;
            lastPostElytraDeltaZ = 0.0D;
            previousPacketMoving = false;
            return;
        }

        lastPostElytraDeltaX = previousPacketMoving ? postElytraDeltaX : 0.0D;
        lastPostElytraDeltaY = previousPacketMoving ? postElytraDeltaY : 0.0D;
        lastPostElytraDeltaZ = previousPacketMoving ? postElytraDeltaZ : 0.0D;

        double drag = getPostElytraHorizontalDrag(postElytraFirstHorizontalUpdate,
                hasGroundSupport, lastFrictionFactor);
        postElytraDeltaX *= drag;
        postElytraDeltaZ *= drag;
        postElytraFirstHorizontalUpdate = false;
        postElytraDeltaY = hasGroundSupport
                ? 0.0D
                : (postElytraDeltaY - 0.08D) * 0.98D;

        updatePostElytraActivity();
        previousPacketMoving = true;
    }

    private double getPostElytraHorizontalDrag(boolean firstPostElytraTick,
                                                boolean hasGroundSupport,
                                                float lastFrictionFactor) {
        if (hasGroundSupport) {
            double slipperiness = lastFrictionFactor;
            if (!Double.isFinite(slipperiness) || slipperiness <= 0.0D || slipperiness > 1.5D) {
                slipperiness = 0.6D;
            }
            return slipperiness * 0.91D;
        }
        return firstPostElytraTick ? POST_ELYTRA_EXIT_HORIZONTAL_DRAG : 0.91D;
    }

    private void updatePostElytraActivity() {
        postElytraHorizontalActive = Math.hypot(postElytraDeltaX, postElytraDeltaZ)
                > POST_ELYTRA_HORIZONTAL_EPSILON;
        postElytraVerticalActive = Math.abs(postElytraDeltaY) > 0.015D
                && postElytraDeltaY > POST_ELYTRA_VERTICAL_CARRY_END;
        if (!postElytraHorizontalActive) {
            postElytraDeltaX = 0.0D;
            postElytraDeltaZ = 0.0D;
        }
        if (!postElytraVerticalActive) postElytraDeltaY = 0.0D;
    }

    private void clearPostElytraModel() {
        postElytraDeltaX = 0.0D;
        postElytraDeltaY = 0.0D;
        postElytraDeltaZ = 0.0D;
        lastPostElytraDeltaX = 0.0D;
        lastPostElytraDeltaY = 0.0D;
        lastPostElytraDeltaZ = 0.0D;
        postElytraHorizontalActive = false;
        postElytraVerticalActive = false;
        postElytraFirstHorizontalUpdate = false;
    }
}
