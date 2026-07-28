package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.tasks.TickTask;
import me.arrow.utils.TaskUtils;

import java.util.concurrent.TimeUnit;

public class TimerC extends Check {

    long lastFlyingPacket = profile.getConnectionData().getTransactionStamp();
    long balance;
    boolean capped;
    long TELEPORT_OFFSET = 50000000L;
    long FLYING_OFFSET = 50000000L;

    public TimerC(Profile profile) {
        super(profile, CheckType.TIMER, "C", "Checks for timer balance abuse");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            this.balance -= TELEPORT_OFFSET;
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!isFlyingPacket(event)) {
            return;
        }

        if (profile.getConnectionData().getTransactionStamp() == 0L && this.lastFlyingPacket == 0L) {
            return;
        }

        if (profile.getConnectionData().getTransactionStamp() != 0L && this.lastFlyingPacket == 0L) {
            this.lastFlyingPacket = profile.getConnectionData().getTransactionStamp() - 250000000L;
        }

        long capLenght = (10000 * 1000000L) + toNanos(2000L);
        long now = event.getTimestamp();
        long delay = FLYING_OFFSET - (now - this.lastFlyingPacket);
        long diff = Math.max(FLYING_OFFSET, now - this.lastFlyingPacket);
        this.balance = Math.max(-capLenght, this.balance + delay);
        if (this.balance > FLYING_OFFSET + toNanos(5L)) {
            if (this.ready()) {
                if (increaseBuffer() > 1.0) {
                    if (!this.capped) {
                        this.fail("Timer Balance",
                                "cap " + MsgType.MAIN_THEME_COLOR.getMessage() + capLenght
                                        + "\nnow " + MsgType.MAIN_THEME_COLOR.getMessage() + now
                                        + "\nbalance " + MsgType.MAIN_THEME_COLOR.getMessage() + balance
                                        + "\ndelay " + MsgType.MAIN_THEME_COLOR.getMessage() + delay
                                        + "\ndiff " + MsgType.MAIN_THEME_COLOR.getMessage() + diff
                                        + "\ntps " + MsgType.MAIN_THEME_COLOR.getMessage() + TickTask.getTPS()
                                        + "\ntickTime " + MsgType.MAIN_THEME_COLOR.getMessage() + TickTask.getTickTime()
                                        + "\ntransPing " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getConnectionData().getTransPing()
                                        + "\npingJitter " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getConnectionData().getDropTransTime()
                                        + "\ntick " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getTick()
                        );
                    } else {
                        this.kickTimer();
                    }
                }
            }
            this.balance = 0L;
        }
        else {
            decreaseBufferBy(0.005);
        }

        if (this.balance <= -capLenght) {
            this.capped = true;
        }

        this.lastFlyingPacket = now;
    }

    private boolean ready() {
        return (profile.isHasReceivedTransaction() || profile.getTick() > 100);
    }

    private boolean isFlyingPacket (PacketReceiveEvent event){
        return event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }

    public static long toNanos ( long time){
        return TimeUnit.MILLISECONDS.toNanos(time);
    }

    private void kickTimer() {
        if (!profile.isBanned()) {
            TaskUtils.task(() -> profile.getPlayer().kickPlayer("Timed out (T.A)"));
            profile.setBanned(true);
        }
    }
}
