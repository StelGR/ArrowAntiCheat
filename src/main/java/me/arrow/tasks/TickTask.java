package me.arrow.tasks;

import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.utils.MathUtils;

/**
 * A task that we'll be using in order to grab the server's state and whatnot.
 *
 * <p>Runs on the <b>main server thread</b> (Bukkit scheduler, period = 1 tick)
 * so every {@link #run()} invocation corresponds to exactly one server tick.
 *
 * <p>TPS is calculated using Exponentially Weighted Moving Averages (EWMA)
 * over 5-second, 1-minute, 5-minute and 15-minute windows — the same
 * approach used by Spark. No external API is required.</p>
 */
public class TickTask implements Runnable {

    private final Arrow plugin;

    public TickTask(Arrow plugin) {
        this.plugin = plugin;
    }

    // ── Global tick counter ──────────────────────────────────────────────
    private static int ticks;

    // ── TPS (Exponentially Weighted Moving Averages) ─────────────────────
    //  alpha = 1 - e^(-interval / window)
    //  where interval = 1 tick = 1/20 s and window = seconds
    private static final double ALPHA_5S  = 1.0D - Math.exp(-1.0D / (5   * 20));
    private static final double ALPHA_1M  = 1.0D - Math.exp(-1.0D / (60  * 20));
    private static final double ALPHA_5M  = 1.0D - Math.exp(-1.0D / (300 * 20));
    private static final double ALPHA_15M = 1.0D - Math.exp(-1.0D / (900 * 20));

    /** EWMA of tick duration in nanoseconds for each window. */
    private static double ewma5s  = 50_000_000D; // start assuming 20 TPS
    private static double ewma1m  = 50_000_000D;
    private static double ewma5m  = 50_000_000D;
    private static double ewma15m = 50_000_000D;

    // ── Tick timing ──────────────────────────────────────────────────────
    private static long lastTickNanos = System.nanoTime();

    /** Duration of the last tick in milliseconds. */
    @Getter
    private static long tickTime;

    /** System.currentTimeMillis() at the last detected lag spike. */
    @Getter
    private static long lastLagSpike;

    // ── MSPT circular buffer (last 1200 ticks ≈ 1 minute) ───────────────
    private static final int MSPT_BUFFER_SIZE = 1200;
    private static final double[] msptBuffer = new double[MSPT_BUFFER_SIZE];
    private static int msptIndex;
    private static int msptCount;

    // ── Warm-up: ignore the first 100 ticks for TPS calculations ────────
    private static final int WARMUP_TICKS = 100;

    // ──────────────────────────────────────────────────────────────────────
    @Override
    public void run() {

        // Increment tick counter
        ticks++;

        final long now = System.nanoTime();
        final long currentTimeMillis = System.currentTimeMillis();

        if (ticks > WARMUP_TICKS) {

            // ── Tick duration ────────────────────────────────────────────
            final long deltaNanos = now - lastTickNanos;
            tickTime = deltaNanos / 1_000_000L;

            // ── Update EWMA windows ─────────────────────────────────────
            ewma5s  += ALPHA_5S  * (deltaNanos - ewma5s);
            ewma1m  += ALPHA_1M  * (deltaNanos - ewma1m);
            ewma5m  += ALPHA_5M  * (deltaNanos - ewma5m);
            ewma15m += ALPHA_15M * (deltaNanos - ewma15m);

            // ── MSPT buffer ─────────────────────────────────────────────
            final double mspt = deltaNanos / 1_000_000.0D;
            msptBuffer[msptIndex] = mspt;
            msptIndex = (msptIndex + 1) % MSPT_BUFFER_SIZE;
            if (msptCount < MSPT_BUFFER_SIZE) msptCount++;

            // ── Lag spike detection ─────────────────────────────────────
            //  A tick that took >100 ms (2× normal) or TPS below 18
            if (tickTime >= 100L || tpsFromEwma(ewma5s) <= 18.0D) {
                lastLagSpike = currentTimeMillis;
            }
        }

        lastTickNanos = now;

        // Tick player profiles
        this.plugin.getProfileManager().getProfileMap().values()
                .forEach(profile -> profile.handleTick(currentTimeMillis));
    }

    // ── TPS helpers ──────────────────────────────────────────────────────

    /** Converts an EWMA tick-duration (nanos) to TPS, capped at 20. */
    private static double tpsFromEwma(double ewmaNanos) {
        return Math.min(1_000_000_000.0D / ewmaNanos, 20.0D);
    }

    /**
     * Primary TPS getter (5-second window). Drop-in replacement for the
     * previous {@code getTPS()}.  All existing call-sites keep working.
     */
    public static double getTPS() {
        return MathUtils.decimalRound(tpsFromEwma(ewma5s), 2);
    }

    /** TPS over a 5-second window. */
    public static double getTPS5s() {
        return MathUtils.decimalRound(tpsFromEwma(ewma5s), 2);
    }

    /** TPS over a 1-minute window. */
    public static double getTPS1m() {
        return MathUtils.decimalRound(tpsFromEwma(ewma1m), 2);
    }

    /** TPS over a 5-minute window. */
    public static double getTPS5m() {
        return MathUtils.decimalRound(tpsFromEwma(ewma5m), 2);
    }

    /** TPS over a 15-minute window. */
    public static double getTPS15m() {
        return MathUtils.decimalRound(tpsFromEwma(ewma15m), 2);
    }

    public static int getCurrentTick() {
        return Math.abs(ticks);
    }

    public static long getLastLagSpike() {
        return MathUtils.elapsed(lastLagSpike);
    }

    /**
     * Returns the MSPT (milliseconds per tick) of the last tick.
     * This is the same value as {@link #getTickTime()} but as a double.
     */
    public static double getMSPT() {
        if (msptCount == 0) return 50.0D;
        int prevIndex = (msptIndex - 1 + MSPT_BUFFER_SIZE) % MSPT_BUFFER_SIZE;
        return MathUtils.decimalRound(msptBuffer[prevIndex], 2);
    }

    /**
     * Returns the median MSPT over the recent buffer window (~1 min).
     */
    public static double getMedianMSPT() {
        if (msptCount == 0) return 50.0D;
        double[] sorted = new double[msptCount];
        System.arraycopy(msptBuffer, 0, sorted, 0, msptCount);
        java.util.Arrays.sort(sorted);
        return MathUtils.decimalRound(sorted[msptCount / 2], 2);
    }

    /**
     * Returns the 95th-percentile MSPT over the recent buffer window.
     */
    public static double get95thMSPT() {
        if (msptCount == 0) return 50.0D;
        double[] sorted = new double[msptCount];
        System.arraycopy(msptBuffer, 0, sorted, 0, msptCount);
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(msptCount * 0.95D) - 1;
        return MathUtils.decimalRound(sorted[Math.max(0, idx)], 2);
    }
}
