package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

public class TimerA extends Check {

    private static final double EXPECTED_PACKET_DELAY_MS = 50.0D;
    private static final double MIN_BALANCE = -800.0D;
    private static final double MAX_BALANCE = 250.0D;
    private static final double FLAG_INTERVAL = 50.0D;
    private static final double SETBACK_PENALTY = 800.0D;
    private static final double MAX_REASONABLE_DELAY_MS = 1000.0D;

    private double balance;
    private double nextFlagBalance = MAX_BALANCE;
    private double threshold;
    private long lastNano = -1L;

    public TimerA(Profile profile) {
        super(profile, CheckType.TIMER, "A", "Checks for game speedup modifications");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)) {
            balance = Math.max(MIN_BALANCE, balance - SETBACK_PENALTY);
            nextFlagBalance = MAX_BALANCE;
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isFlyingPacket(event)) {
            return;
        }

        long now = System.nanoTime();

        if (lastNano == -1L) {
            lastNano = now;
            return;
        }

        double elapsed = (now - lastNano) / 1_000_000.0D;
        lastNano = now;

        if (shouldReset(elapsed)) {
            reset(now);
            return;
        }

        /*
         * Correct timer balance:
         *
         * Normal packet rate:
         * elapsed = 50.0ms
         * balance += 0.0
         *
         * Faster timer:
         * elapsed = 49.5ms
         * balance += 0.5
         *
         * Lag / slower packets:
         * elapsed = 80.0ms
         * balance -= 30.0
         *
         * If balance is negative, faster packets must first "pay back" the negative
         * balance before it reaches 0 and starts flagging.
         */
        double newBalance = balance + EXPECTED_PACKET_DELAY_MS - elapsed;
        balance = Math.max(MIN_BALANCE, newBalance);

        if (balance > nextFlagBalance) {
            if (++threshold > 20.0D) {
                fail("Speeding up Time",
                        "balance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(balance)
                                + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(elapsed)
                                + "\nnextFlagBalance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(nextFlagBalance)
                                + "\nthreshold " + MsgType.MAIN_THEME_COLOR.getMessage() + threshold);
            }

            threshold = Math.min(25, threshold);

            nextFlagBalance += FLAG_INTERVAL;
        } else {
            threshold = Math.max(0.0D, threshold - 0.5D);

            if (balance < MAX_BALANCE) {
                nextFlagBalance = MAX_BALANCE;
            }
        }

        verbose(this.getClass().getSimpleName(), balance, MAX_BALANCE,
                "balance " + format(balance)
                        + "\nelapsed " + format(elapsed)
                        + "\nnextFlagBalance " + format(nextFlagBalance)
                        + "\nthreshold " + threshold);
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean shouldReset(double elapsed) {
        if (elapsed <= 0.0D) {
            return true;
        }

        if (elapsed > MAX_REASONABLE_DELAY_MS) {
            return true;
        }

        if (profile.getTick() < 120) {
            return true;
        }

        if (profile.shouldCancel()) {
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            return true;
        }

        return !profile.getMovementData().isMoving()
                && profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    private void reset(long now) {
        balance = 0.0D;
        nextFlagBalance = MAX_BALANCE;
        threshold = 0.0D;
        lastNano = now;
    }

    private String format(double value) {
        return String.format("%.3f", value);
    }
}