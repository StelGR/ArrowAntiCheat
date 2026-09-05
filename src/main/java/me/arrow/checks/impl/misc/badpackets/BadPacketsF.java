package me.arrow.checks.impl.misc.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.OtherUtility;

/**
 * BadPackets F - Freeze / Movement Packet Suppression Check
 *
 * Detects clients that continuously acknowledge server transactions/ticks
 * while suppressing all movement/flying packets (LiquidBounce Freeze, Blink,
 * Disablers, or packet cancellation exploits).
 *
 * Robust against client lag spikes (e.g. F3+S, resource pack reloads) by
 * validating accepted transaction IDs, ignoring transaction bursts, and exempting
 * high transaction ping / spike fluctuations.
 */
@Experimental
public class BadPacketsF extends Check {

    private int ticksWithoutMovement;
    private double buffer;
    private long lastAcceptedTxTime;

    public BadPacketsF(Profile profile) {
        super(profile, CheckType.BADPACKETS, "F", "Detects cancelling movement packets while accepting transactions (Freeze/Blink)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        PacketTypeCommon packetType = event.getPacketType();

        // If the player sends any movement/flying packet, reset stall counter and buffer
        if (OtherUtility.isFlying(packetType)) {
            this.ticksWithoutMovement = 0;
            this.buffer = 0.0D;
            return;
        }

        // Check for incoming transaction / pong confirmations from the client
        if (isTransaction(packetType)) {
            // Verify that ConnectionData actually matched and accepted this transaction ID
            if (profile.getConnectionData() == null || !profile.getConnectionData().isLastTransactionAccepted()) {
                return;
            }

            if (isExempt()) {
                this.ticksWithoutMovement = 0;
                this.buffer = 0.0D;
                return;
            }

            // Burst protection: If multiple transactions arrive in a burst (< 25ms apart, e.g. after F3+S reload),
            // do not treat them as individual elapsed server ticks.
            long now = System.currentTimeMillis();
            if (now - lastAcceptedTxTime < 25L) {
                return;
            }
            this.lastAcceptedTxTime = now;

            this.ticksWithoutMovement++;

            // If 30+ transactions/ticks have elapsed with zero movement packets sent
            if (this.ticksWithoutMovement >= 30) {
                if (++this.buffer > 2.0D) {
                    fail("Cancelled movement packets while accepting transactions",
                            "Ticks: " + MsgType.MAIN_THEME_COLOR.getMessage() + this.ticksWithoutMovement
                                    + "\nTPing: " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getConnectionData().getTransPing()
                                    + "\nBuffer: " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format("%.1f", this.buffer));
                }
            }
        }
    }

    private boolean isTransaction(PacketTypeCommon packetType) {
        return packetType.equals(PacketType.Play.Client.WINDOW_CONFIRMATION)
                || packetType.equals(PacketType.Play.Client.PONG);
    }

    private boolean isExempt() {
        if (profile == null) return true;
        if (profile.getPlayer() == null || !profile.getPlayer().isOnline()) return true;
        if (profile.getTick() < 60) return true;
        if (profile.shouldCancel()) return true;
        if (profile.isExempt().isTeleports()) return true;
        if (profile.isExempt().isDead()) return true;
        if (profile.isBedrockPlayer()) return true;

        // Exempt during lag spikes / client freeze (F3+S, resource pack reload, ping spikes)
        if (profile.getConnectionData() != null) {
            if (profile.getConnectionData().getTransPing() > 700
                    || profile.getConnectionData().getDropTransTime() > 400
                    || profile.getConnectionData().isLagging()) {
                return true;
            }
        }

        if (profile.getVehicleData() != null) {
            return profile.getVehicleData().getVehicleTicks() > 0
                    || profile.getVehicleData().getSinceVehicleTicks() < 20;
        }

        return false;
    }
}
