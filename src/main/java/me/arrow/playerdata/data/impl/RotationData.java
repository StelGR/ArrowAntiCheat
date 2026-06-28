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

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;

// niks rotationdata kept almost vanilla, good enough, but i do have a trustedYaw function, to verify the clients yaw switch and prevent bypasses
// on omnisprint by spoofing the yaw.

public class RotationData implements Data {

    private final Profile profile;

    @Getter
    private final SensitivityProcessor sensitivityProcessor;
    @Getter
    private final CinematicProcessor cinematicProcessor;

    @Getter
    private float yaw, lastYaw, pitch, lastPitch, deltaYaw, lastDeltaYaw,
            deltaPitch, lastDeltaPitch, yawAccel, lastYawAccel, pitchAccel, lastPitchAccel;

    @Getter
    private int rotationsAfterTeleport, lastRotationTicks;

    @Getter
    private float trustedYaw, lastTrustedYaw;
    private boolean trustedYawInitialized;

    public RotationData(Profile profile) {
        this.profile = profile;
        this.sensitivityProcessor = new SensitivityProcessor(profile);
        this.cinematicProcessor = new CinematicProcessor(profile);
    }

    private int invalidSnapThreshold;

    @Override
    public void processReceive(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)) {

            final WrapperPlayClientPlayerPositionAndRotation posLookWrapper = new WrapperPlayClientPlayerPositionAndRotation(event);

            processRotation(posLookWrapper.getYaw(), posLookWrapper.getPitch());

        }
        else if (event.getPacketType().equals(PLAYER_ROTATION)) {
            final WrapperPlayClientPlayerRotation lookWrapper = new WrapperPlayClientPlayerRotation(event);

            processRotation(lookWrapper.getYaw(), lookWrapper.getPitch());
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)) {
            this.rotationsAfterTeleport = 0;
        }
    }

    private void processRotation(float yaw, float pitch) {

        //Duplicate rotation packet (1.17+)
        if (profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_17)
                && profile.isExempt().isTeleports()
                && yaw == this.yaw
                && pitch == this.pitch
                && profile.getActionData().getLastRidingTicks() > 1) return;

        final float lastYaw = this.yaw;

        this.lastYaw = lastYaw;
        this.yaw = yaw;

        final float lastPitch = this.pitch;

        this.lastPitch = lastPitch;
        this.pitch = pitch;

        final float lastDeltaYaw = this.deltaYaw;

        /*
        Clamp the deltaYaw to similarize the behavior between 1.8 -> latest versions of minecraft.
        (In 1.9 the packet's data is sent differently)
         */
        final float deltaYaw = Math.abs(MathUtils.clamp180(Math.abs(yaw - lastYaw)));

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

        //Process sensitivity
        this.sensitivityProcessor.process();

        //Process cinematic
        this.cinematicProcessor.process();

        this.rotationsAfterTeleport++;

        this.lastRotationTicks = TickTask.getCurrentTick();


        updateTrustedYaw();
        /*
        This fixes the infamous bug which gets triggered when moving your client to the side in a small window
        And makes you snap around insanely fast, This will not affect legitimate players who snap around at any
        Sensitivity or DPI since it's almost impossible for your deltaYaw to be the same as the rotation constant
        While the acceleration is also zero for 10 times.

        This bug is very problematic since after it gets triggered all of your rotations will have
        An identical pattern, Which can lead to exploits, bugs or even falses.
        (Yes this is more problematic than you think, Due to the way sensitivity works in the minecraft client with the GUI)
         */
        if (this.deltaYaw > 10F
                && this.deltaPitch == 0F
                && this.yawAccel == 0F
                && this.deltaYaw == this.sensitivityProcessor.getConstantYaw()
                && this.rotationsAfterTeleport > 5) {

            if (this.invalidSnapThreshold++ > 10) {

                ChatUtils.log("Kicking " + profile.getPlayer().getName() + " for triggering the snap bug.");

                profile.kick("Invalid Rotation Packet");

                this.invalidSnapThreshold = 0;
            }

        } else this.invalidSnapThreshold = 0;
    }


    private void updateTrustedYaw() {
        if (!trustedYawInitialized) {
            this.trustedYaw = this.yaw;
            this.lastTrustedYaw = this.yaw;
            this.trustedYawInitialized = true;
            return;
        }

        this.lastTrustedYaw = this.trustedYaw;

        float diff = wrapDegrees(this.yaw - this.trustedYaw);
        float maxStep = 18.0f;

        if (Math.abs(diff) <= maxStep) {
            this.trustedYaw = this.yaw;
        } else {
            this.trustedYaw = wrapDegrees(this.trustedYaw + clamp(diff, -maxStep, maxStep));
        }
    }

    private static float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f) value -= 360.0f;
        if (value < -180.0f) value += 360.0f;
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public int getLastRotationTicks() {
        return MathUtils.elapsedTicks(this.lastRotationTicks);
    }
}