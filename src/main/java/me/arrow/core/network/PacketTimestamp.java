package me.arrow.core.network;

import java.util.concurrent.TimeUnit;

/** Converts PacketEvents' backend-dependent timestamp into wall-clock milliseconds. */
public final class PacketTimestamp {

    private PacketTimestamp() {
    }

    public static long toMillis(long timestamp) {
        long now = System.currentTimeMillis();
        if (timestamp <= 0L || Math.abs(now - timestamp) > 60_000L) {
            long age = System.nanoTime() - timestamp;
            return age >= 0L && age <= TimeUnit.SECONDS.toNanos(60L)
                    ? now - TimeUnit.NANOSECONDS.toMillis(age)
                    : now;
        }
        return timestamp;
    }
}
