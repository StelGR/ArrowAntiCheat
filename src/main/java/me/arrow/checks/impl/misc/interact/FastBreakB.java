package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.impl.misc.interact.FastBreakTracker.BreakAttempt;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.OtherUtility;

import java.util.Locale;

/** Detects sustained mining faster than the expected block break duration. */
@Experimental
public class FastBreakB extends Check {

    public FastBreakB(Profile profile) {
        super(profile, CheckType.FASTBREAK, "B", "Checks for increased mining speed.");
    }

    @Override
    public void handle(PacketSendEvent event) {
        // Dig state is owned by FastBreakTracker; arbitrary block updates are not cancels.
    }

    @Override
    public synchronized void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) decreaseBufferBy(0.005D);
    }

    synchronized void handleAttempt(BreakAttempt attempt) {
        if (attempt.progress >= 1.0F || attempt.minimumTicks <= 1) {
            decreaseBufferBy(0.1D);
            return;
        }

        long elapsedMillis = attempt.elapsedNanos / 1_000_000L;
        long elapsedTicks = Math.round(attempt.elapsedNanos / 50_000_000.0D);
        if (elapsedTicks <= 1 || elapsedMillis < 75L) {
            return; // FastBreakA owns instant and sub-two-tick attempts.
        }

        long minMillis = (long) attempt.minimumTicks * 50L;
        int ping = profile.getConnectionData() != null ? profile.getConnectionData().getPing() : 50;
        long pingToleranceMillis = Math.min(300L, Math.max(100L, (long) ping + 50L));
        long allowedMillis = Math.max(50L, minMillis - pingToleranceMillis);

        if (elapsedMillis < allowedMillis) {
            double ratio = (double) elapsedMillis / minMillis;
            double speedup = (1.0D - ratio) * 100.0D;
            double bufferAdd = Math.min(2.0D, 0.5D + (1.0D - ratio));

            if (increaseBufferBy(bufferAdd) >= 2.0D) {
                fail("Increased mining speed",
                        "block " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.material.name()
                                + "\nitem " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.heldItem
                                + "\nhaste " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.haste
                                + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + elapsedTicks + "t (" + elapsedMillis + "ms)"
                                + "\nmin " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.minimumTicks + "t"
                                + "\nspeedup " + MsgType.MAIN_THEME_COLOR.getMessage() + format(1, speedup) + "%"
                                + "\nprogress " + MsgType.MAIN_THEME_COLOR.getMessage() + format(3, attempt.progress)
                                + "\npos " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.x + ", " + attempt.y + ", " + attempt.z);
            }
        } else {
            decreaseBufferBy(0.2D);
        }
    }

    private String format(int places, double value) {
        return String.format(Locale.ROOT, "%" + "." + places + "f", value);
    }
}
