package me.arrow.checks.impl.misc.timer;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.OtherUtility;

public class TimerC extends Check {

    long lastMs;
    int threshold = 250;
    long timerBalance = 0;

    public TimerC(Profile profile) {
        super(profile, CheckType.TIMER, "C", "Checks for timer balance (basic)");
    }



    @Override
    public void handle(PacketSendEvent event) {

    }



    @Override
    public void handle(PacketReceiveEvent event) {
        if (!OtherUtility.isFlying(event.getPacketType())
                || profile.shouldCancel()
                || profile.getTick() < 120
                || profile.getPredictionData().isDigging()
                || profile.getMovementData().getMovingTicks() < 20) {
            return;
        }

        long now = System.currentTimeMillis();

        long elapsed = now - lastMs;
        lastMs = now;

        verbose("TimerC", 0, threshold, "bal " + timerBalance + "\nelapsed " + elapsed + "\nlastMS " + lastMs);

        if (elapsed >= 900L && profile.getMovementData().getMovingTicks() < 20) {
            return;
        }

        long newBalance = timerBalance + 50 - elapsed;

        if (newBalance > threshold) {
            fail("Speeding up clock (balance)","bal " + MsgType.MAIN_THEME_COLOR.getMessage() + newBalance
                    + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + elapsed
                    + "\nlastMS " + MsgType.MAIN_THEME_COLOR.getMessage() + lastMs);
            newBalance -= 50;
        }
        timerBalance = Math.max(-2500, newBalance);

        verbose("TimerC", newBalance, threshold, "bal " + timerBalance + "\nelapsed " + elapsed + "\nlastMS " + lastMs);
    }

}
