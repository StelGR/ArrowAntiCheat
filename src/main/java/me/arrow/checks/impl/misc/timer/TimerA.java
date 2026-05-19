package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

public class TimerA extends Check {

    private static final long TELEPORT_OFFSET = 50_000_000L;
    private static final long FLYING_OFFSET = 50_000_000L;

    private static final long TIMER_A_CAP_LENGTH = 800_000_000L;

    private long lastFlyingPacket = System.nanoTime();
    private long balance;
    private boolean capped;

    private double violations;

    public TimerA(Profile profile) {
        super(profile, CheckType.TIMER, "A", "Checks for game speedup modifications");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK)) {
            this.balance -= TELEPORT_OFFSET;
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isFlyingPacket(event)) {
            return;
        }

        long now = System.nanoTime();

        if (now == 0L && this.lastFlyingPacket == 0L) {
            return;
        }

        if (now != 0L && this.lastFlyingPacket == 0L) {
            this.lastFlyingPacket = now - 250_000_000L;
        }

        long capLength = TIMER_A_CAP_LENGTH;

        long delay = FLYING_OFFSET - (now - this.lastFlyingPacket);
        long diff = Math.max(FLYING_OFFSET, now - this.lastFlyingPacket);

        this.balance = Math.max(-capLength, this.balance + delay);

        if (!profile.getMovementData().isMoving()) balance = -200;

        if (this.balance > FLYING_OFFSET + 5_000_000L) {
            if (this.ready()) {
                if (++this.violations > 1.0D) {
                    if (!this.capped) {
                        fail("Speeding up game clock (1)",
                                "balance " + MsgType.MAIN_THEME_COLOR.getMessage() + this.balance / 1_000_000L
                                        + "\nrate " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.min(FLYING_OFFSET / diff, 10L)
                                        + "\nlastPacket " + MsgType.MAIN_THEME_COLOR.getMessage() + now
                                        + "\nuserTick " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getTick());
                    } else {
                        fail("Speeding up game clock (2)",
                                        "balance " + MsgType.MAIN_THEME_COLOR.getMessage() + this.balance / 1_000_000L
                                        + "\nrate " + MsgType.MAIN_THEME_COLOR.getMessage() + Math.min(FLYING_OFFSET / diff, 10L)
                                        + "\nlastPacket " + MsgType.MAIN_THEME_COLOR.getMessage() + now
                                        + "\nuserTick " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getTick());
                    }
                }
            }

            this.balance = 0L;
        } else {
            this.violations = Math.max(0.0D, this.violations - 0.005D);
        }

        if (this.balance <= -capLength) {
            this.capped = true;
        }

        verbose(this.getClass().getSimpleName(), this.balance / 1_000_000.0D, 55.0D,
                "balance " + this.balance / 1_000_000.0D
                        + "\ndelay " + delay / 1_000_000.0D
                        + "\ndiff " + diff / 1_000_000.0D
                        + "\nrate " + Math.min(FLYING_OFFSET / diff, 10L)
                        + "\ncapped " + this.capped
                        + "\nviolations " + this.violations
                        + "\ntick " + profile.getTick());

        this.lastFlyingPacket = now;
    }

    private boolean isFlyingPacket(PacketReceiveEvent event) {
        return event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private boolean ready() {
        return profile.getTick() > 100
                && !profile.shouldCancel()
                && !profile.isExempt().isTeleports()
                && !profile.isExempt().vehicle();
    }
}