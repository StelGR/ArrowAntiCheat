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
    private static final double FLAG_INTERVAL = 100.0D;

    private static final double SETBACK_PENALTY = 800.0D;

    /*
     * Above this, treat the delay as a lag spike / packet queue candidate.
     * Do not reset balance to 0. Create burst debt instead.
     */
    private static final double LAG_SPIKE_DELAY_MS = 150.0D;

    /*
     * Max amount of burst catch-up we forgive.
     * 2500ms covers a pretty nasty 2.5 second spike without letting someone
     * pre-bank infinite timer bypass.
     */
    private static final double MAX_BURST_DEBT_MS = 2500.0D;

    /*
     * If lag debt is old, do not let it protect future timer abuse.
     */
    private static final long BURST_DEBT_EXPIRE_NS = 3_000_000_000L;

    /*
     * Modern clients may send very slowly while completely idle.
     * Do not let that create huge negative balance.
     */
    private static final double MODERN_IDLE_DELAY_MS = 200.0D;

    private double balance;
    private double nextFlagBalance = MAX_BALANCE;
    private double threshold;

    private double burstDebt;
    private long burstDebtNano = -1L;

    private long lastNano = -1L;

    public TimerA(Profile profile) {
        super(profile, CheckType.TIMER, "A", "Checks for game speedup modifications");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)) {
            balance = Math.max(MIN_BALANCE, balance - SETBACK_PENALTY);
            nextFlagBalance = MAX_BALANCE;
            threshold = Math.max(0.0D, threshold - 2.0D);

            /*
             * Setbacks can cause bursty movement/teleport correction packets.
             * Clear old debt and restart timing safely.
             */
            burstDebt = 0.0D;
            burstDebtNano = -1L;
            lastNano = -1L;
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

        if (shouldHardReset(elapsed)) {
            reset(now);
            return;
        }

        if (shouldIgnoreModernIdle(elapsed)) {
            handleModernIdle(now);
            return;
        }

        expireOldBurstDebt(now);

        /*
         * If the server receives no packet for a while, the client may have kept
         * sending packets but the network/server delivered them late.
         *
         * Do not reset balance to 0 here. That causes queued packets to false.
         * Instead:
         * - apply negative balance/debt
         * - store burstDebt so queued fast packets must first repay lag debt
         */
        if (elapsed > LAG_SPIKE_DELAY_MS) {
            double spikeDebt = elapsed - EXPECTED_PACKET_DELAY_MS;

            burstDebt = Math.min(MAX_BURST_DEBT_MS, burstDebt + spikeDebt);
            burstDebtNano = now;

            balance = Math.max(MIN_BALANCE, balance - spikeDebt);
            nextFlagBalance = MAX_BALANCE;
            threshold = Math.max(0.0D, threshold - 1.0D);

            verbose(this.getClass().getSimpleName(), balance, MAX_BALANCE,
                    "balance " + format(balance)
                            + "\nelapsed " + format(elapsed)
                            + "\nburstDebt " + format(burstDebt)
                            + "\nnextFlagBalance " + format(nextFlagBalance)
                            + "\nthreshold " + threshold
                            + "\nmode lag-spike");

            return;
        }

        double gain = EXPECTED_PACKET_DELAY_MS - elapsed;

        /*
         * If packets arrive too fast right after a lag spike, consume the lag debt
         * first. This is the important false-positive fix.
         */
        if (gain > 0.0D && burstDebt > 0.0D) {
            double consumed = Math.min(gain, burstDebt);

            gain -= consumed;
            burstDebt -= consumed;
            burstDebtNano = now;
        }

        double newBalance = balance + gain;
        balance = Math.max(MIN_BALANCE, newBalance);

        /*
         * If the player is back to normal packet rate and the debt is tiny, clear it.
         */
        if (burstDebt > 0.0D && Math.abs(gain) < 1.0D && elapsed >= 45.0D && elapsed <= 60.0D) {
            burstDebt = Math.max(0.0D, burstDebt - 5.0D);

            if (burstDebt <= 0.0D) {
                burstDebtNano = -1L;
            }
        }

        if (balance > nextFlagBalance) {
            if (++threshold > 20.0D) {
                fail("Speeding up Time",
                        "balance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(balance)
                                + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(elapsed)
                                + "\nburstDebt " + MsgType.MAIN_THEME_COLOR.getMessage() + format(burstDebt)
                                + "\nnextFlagBalance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(nextFlagBalance)
                                + "\nthreshold " + MsgType.MAIN_THEME_COLOR.getMessage() + threshold);
            }

            threshold = Math.min(25.0D, threshold);
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
                        + "\nburstDebt " + format(burstDebt)
                        + "\nnextFlagBalance " + format(nextFlagBalance)
                        + "\nthreshold " + threshold);
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean shouldHardReset(double elapsed) {
        if (elapsed <= 0.0D) {
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

        return false;
    }

    private boolean shouldIgnoreModernIdle(double elapsed) {
        if (profile.getVersion().isOlderThan(ClientVersion.V_1_9)) {
            return false;
        }

        if (profile.getMovementData() == null) {
            return false;
        }

        /*
         * On modern clients, fully idle players can send movement packets much less
         * frequently. Do not let that build free negative balance.
         */
        return !profile.getMovementData().isMoving()
                && elapsed > MODERN_IDLE_DELAY_MS;
    }

    private void handleModernIdle(long now) {
        lastNano = now;

        balance = Math.min(0.0D, balance);
        balance = Math.max(MIN_BALANCE, balance + 5.0D);

        nextFlagBalance = MAX_BALANCE;
        threshold = Math.max(0.0D, threshold - 0.5D);

        burstDebt = 0.0D;
        burstDebtNano = -1L;
    }

    private void expireOldBurstDebt(long now) {
        if (burstDebt <= 0.0D || burstDebtNano == -1L) {
            return;
        }

        if (now - burstDebtNano > BURST_DEBT_EXPIRE_NS) {
            burstDebt = 0.0D;
            burstDebtNano = -1L;
        }
    }

    private void reset(long now) {
        balance = 0.0D;
        nextFlagBalance = MAX_BALANCE;
        threshold = 0.0D;
        burstDebt = 0.0D;
        burstDebtNano = -1L;
        lastNano = now;
    }

    private String format(double value) {
        return String.format("%.3f", value);
    }
}