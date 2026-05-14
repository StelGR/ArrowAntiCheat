package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

// basic sped up timer check, falses on huge lag spikes or TPS drops, but that's normal i think


public class TimerA extends Check {

    public TimerA(Profile profile) {
        super(profile, CheckType.TIMER, "A", "Checks for game speedup modifications");
    }

    int maxValue = 250;
    long balance;
    double threshold;
    long lastMs;

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)) {
            this.balance -= 800L;
            balance = Math.min(-800L, balance);
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            long now = System.currentTimeMillis();

            long elapsed = now - lastMs;

            if (!profile.getMovementData().isMoving() && profile.getVersion().isOlderThan(ClientVersion.V_1_9)) {
                balance = 0;
                elapsed = 0;
                lastMs = now;
            }

            if (profile.isExempt().isTeleports() || profile.getTick() < 120) {
                balance = 0;
                elapsed = 0;
                lastMs = now;
            }

            if ((profile.getConnectionData().getTransPing() - profile.getConnectionData().getLastTransPing()) > 1000) {
                this.balance -= 800L;
                balance = Math.min(-800L, balance);
            }

            balance = (balance + 50) - elapsed;

            if (this.balance > this.maxValue)  {
                if (threshold++ > 2) {
                    fail("Speeding up Time",
                            "balance " + MsgType.MAIN_THEME_COLOR.getMessage() + balance
                                    + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + elapsed
                                    + "\nlastMs " + MsgType.MAIN_THEME_COLOR.getMessage() + lastMs);
                }

                balance = 0;
                elapsed = 0;
                lastMs = now;

                //this.balance = 0;
            } else {
                threshold -= Math.min(threshold, 0.1);
            }

            verbose(this.getClass().getSimpleName(), balance, maxValue, "balance " + balance
                    + "\nelapsed " + elapsed
                    + "\nlastMs " + lastMs);

            //this.lastPacket = now;

            lastMs = now;
            balance = Math.min(-800L, balance);
        }
    }
}
