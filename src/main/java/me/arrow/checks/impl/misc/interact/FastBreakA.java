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

/** Detects implausibly fast block breaks using shared, validated dig attempts. */
@Experimental
public class FastBreakA extends Check {

    public FastBreakA(Profile profile) {
        super(profile, CheckType.FASTBREAK, "A", "Checks for instant block break");
    }

    @Override
    public void handle(PacketSendEvent event) {
        // Block updates are not reliable evidence that a dig was cancelled.
    }

    @Override
    public synchronized void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) decreaseBufferBy(0.01D);
    }

    synchronized void handleAttempt(BreakAttempt attempt) {
        if (attempt.progress >= 1.0F) {
            decreaseBufferBy(0.2D);
            return;
        }

        long elapsedMillis = attempt.elapsedNanos / 1_000_000L;
        int ping = profile.getConnectionData() != null ? profile.getConnectionData().getPing() : 50;
        long jitterAllowance = Math.min(25L, Math.max(0L, (long) ping - 50L) / 4L);
        long instantThreshold = Math.max(5L, (attempt.minimumTicks <= 2 ? 35L : 75L) - jitterAllowance);
        boolean instantBreak = elapsedMillis < instantThreshold;

        if (instantBreak) {
            if (increaseBufferBy(1.0D) >= 1.0D) {
                fail("Instant Break",
                        "block " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.material.name()
                                + "\nitem " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.heldItem
                                + "\nhaste " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.haste
                                + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + elapsedMillis + "ms"
                                + "\nmin " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.minimumTicks + "t"
                                + "\nthreshold " + MsgType.MAIN_THEME_COLOR.getMessage() + instantThreshold + "ms"
                                + "\nprogress " + MsgType.MAIN_THEME_COLOR.getMessage() + format(3, attempt.progress)
                                + "\npos " + MsgType.MAIN_THEME_COLOR.getMessage() + attempt.x + ", " + attempt.y + ", " + attempt.z);
            }
        } else {
            decreaseBufferBy(0.25D);
        }
    }

    private String format(int places, double value) {
        return String.format(Locale.ROOT, "%" + "." + places + "f", value);
    }
}
