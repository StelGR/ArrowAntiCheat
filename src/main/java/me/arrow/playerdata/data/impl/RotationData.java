package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import lombok.Getter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.playerdata.processors.impl.CinematicProcessor;
import me.arrow.playerdata.processors.impl.SensitivityProcessor;
import me.arrow.tasks.TickTask;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.MathUtils;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.PLAYER_ROTATION;

public class RotationData implements Data {

    private final Profile profile;

    @Getter
    private final SensitivityProcessor sensitivityProcessor;

    @Getter
    private final CinematicProcessor cinematicProcessor;

    @Getter
    private float yaw, lastYaw, pitch, lastPitch,
            deltaYaw, lastDeltaYaw,
            deltaPitch, lastDeltaPitch,
            yawAccel, lastYawAccel,
            pitchAccel, lastPitchAccel;

    @Getter
    private int rotationsAfterTeleport, lastRotationTicks;

    @Getter
    private float trustedYaw, lastTrustedYaw;

    private boolean trustedYawInitialized;

    private int invalidSnapThreshold;

    public RotationData(Profile profile) {
        this.profile = profile;
        this.sensitivityProcessor = new SensitivityProcessor(profile);
        this.cinematicProcessor = new CinematicProcessor(profile);
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)) {
            final WrapperPlayClientPlayerPositionAndRotation wrapper =
                    new WrapperPlayClientPlayerPositionAndRotation(event);

            processRotation(wrapper.getYaw(), wrapper.getPitch());
        } else if (event.getPacketType().equals(PLAYER_ROTATION)) {
            final WrapperPlayClientPlayerRotation wrapper =
                    new WrapperPlayClientPlayerRotation(event);

            processRotation(wrapper.getYaw(), wrapper.getPitch());
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)) {
            this.rotationsAfterTeleport = 0;
        }
    }

    private void processRotation(float packetYaw, float packetPitch) {
        if (!isFinite(packetYaw) || !isFinite(packetPitch)) {
            return;
        }

        /*
         * Store yaw like Minecraft/F3 display: -180 -> 180.
         * Store pitch as vanilla legal pitch: -90 -> 90.
         */
        final float yaw = MathUtils.clamp180(packetYaw);

        // Duplicate rotation packet (1.17+)
        if (profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_17)
                && profile.getTeleportData().getTeleportTicks() > 1
                && yaw == this.yaw
                && packetPitch == this.pitch
                && profile.getVehicleData().getSinceVehicleTicks() > 1) {
            return;
        }

        final float lastYaw = this.yaw;

        this.lastYaw = lastYaw;
        this.yaw = yaw;

        final float lastPitch = this.pitch;

        this.lastPitch = lastPitch;
        this.pitch = packetPitch;

        final float lastDeltaYaw = this.deltaYaw;

        final float deltaYaw = Math.abs(MathUtils.clamp180(yaw - lastYaw));

        this.lastDeltaYaw = lastDeltaYaw;
        this.deltaYaw = deltaYaw;

        final float lastDeltaPitch = this.deltaPitch;
        final float deltaPitch = Math.abs(pitch - lastPitch);

        this.lastDeltaPitch = lastDeltaPitch;
        this.deltaPitch = deltaPitch;

        final float lastYawAccel = this.yawAccel;
        final float yawAccel = Math.abs(deltaYaw - lastDeltaYaw);

        this.lastYawAccel = lastYawAccel;
        this.yawAccel = yawAccel;

        final float lastPitchAccel = this.pitchAccel;
        final float pitchAccel = Math.abs(deltaPitch - lastDeltaPitch);

        this.lastPitchAccel = lastPitchAccel;
        this.pitchAccel = pitchAccel;

        this.sensitivityProcessor.process();
        this.cinematicProcessor.process();

        this.rotationsAfterTeleport++;
        this.lastRotationTicks = TickTask.getCurrentTick();

        updateTrustedYaw();
        handleSnapBug();
    }

    private void handleSnapBug() {
        if (this.deltaYaw > 10.0F
                && this.deltaPitch == 0.0F
                && this.yawAccel == 0.0F
                && this.deltaYaw == this.sensitivityProcessor.getConstantYaw()
                && this.rotationsAfterTeleport > 5) {

            if (this.invalidSnapThreshold++ > 10) {
                ChatUtils.log("Kicking " + profile.getPlayer().getName() + " for triggering the snap bug.");

                profile.kick("Invalid Rotation Packet");

                this.invalidSnapThreshold = 0;
            }
        } else {
            this.invalidSnapThreshold = 0;
        }
    }

    private void updateTrustedYaw() {
        if (!trustedYawInitialized) {
            this.trustedYaw = this.yaw;
            this.lastTrustedYaw = this.yaw;
            this.trustedYawInitialized = true;
            return;
        }

        this.lastTrustedYaw = this.trustedYaw;

        float diff = MathUtils.clamp180(this.yaw - this.trustedYaw);
        float maxStep = 18.0F;

        if (Math.abs(diff) <= maxStep) {
            this.trustedYaw = this.yaw;
        } else {
            this.trustedYaw = MathUtils.clamp180(this.trustedYaw + clamp(diff, -maxStep, maxStep));
        }
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float wrapDegrees(float value) {
        value %= 360.0F;

        if (value >= 180.0F) {
            value -= 360.0F;
        }

        if (value < -180.0F) {
            value += 360.0F;
        }

        return value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public int getLastRotationTicks() {
        return MathUtils.elapsedTicks(this.lastRotationTicks);
    }
}