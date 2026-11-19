package me.arrow.managers.errors;

import me.arrow.Arrow;
import me.arrow.files.Config;
import org.bukkit.Bukkit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in, rate-limited diagnostics for recoverable Arrow failures. */
public class ErrorMonitor {

    private static final long REPORT_INTERVAL_NANOS = 30_000_000_000L;
    private static final int MAX_TRACKED_FAILURES = 4096;
    private static final ConcurrentHashMap<String, ErrorWindow> WINDOWS = new ConcurrentHashMap<>();

    private ErrorMonitor() {
    }

    public static boolean isEnabled() {
        Arrow arrow = Arrow.getInstance();
        if (arrow == null) return false;
        try {
            return Config.Setting.ERROR_REPORTING.getBoolean();
        } catch (Throwable ignored) {
            // Config may not be initialized yet during early plugin startup/shutdown.
            return false;
        }
    }

    /**
     * Reports a failure with operation and runtime context. Repeated failures of
     * the same kind are summarized at most once every 30 seconds to avoid log floods.
     */
    public static boolean report(String subsystem, String operation, Throwable failure, String context) {
        if (!isEnabled() || failure == null) return false;

        String safeSubsystem = valueOr(subsystem, "unknown");
        String safeOperation = valueOr(operation, "unknown");
        String category = safeSubsystem + '|' + safeOperation + '|' + failure.getClass().getName()
                + '|' + sanitize(rootMessage(failure)) + '|' + sanitize(valueOr(context, "none"));
        pruneWindowsIfNeeded();
        ErrorWindow window = WINDOWS.computeIfAbsent(category, ignored -> new ErrorWindow());
        long count = window.count.incrementAndGet();
        long now = System.nanoTime();

        synchronized (window) {
            if (window.lastReportNanos != 0L && now - window.lastReportNanos < REPORT_INTERVAL_NANOS) {
                return true;
            }
            long suppressed = count - window.lastReportedCount;
            window.lastReportNanos = now;
            window.lastReportedCount = count;

            Bukkit.getLogger().severe(formatReport(
                    safeSubsystem, safeOperation, failure, context, suppressed));
            return true;
        }
    }

    public static boolean report(String subsystem, String operation, Throwable failure) {
        return report(subsystem, operation, failure, null);
    }

    public static boolean reportMessage(String subsystem, String operation, String message, String context) {
        if (!isEnabled()) return false;
        String safeSubsystem = valueOr(subsystem, "unknown");
        String safeOperation = valueOr(operation, "unknown");
        String category = safeSubsystem + '|' + safeOperation + "|message|"
                + sanitize(valueOr(message, "unspecified")) + '|' + sanitize(valueOr(context, "none"));
        pruneWindowsIfNeeded();
        ErrorWindow window = WINDOWS.computeIfAbsent(category, ignored -> new ErrorWindow());
        long count = window.count.incrementAndGet();
        long now = System.nanoTime();
        synchronized (window) {
            if (window.lastReportNanos != 0L && now - window.lastReportNanos < REPORT_INTERVAL_NANOS) {
                return true;
            }
            long suppressed = count - window.lastReportedCount;
            window.lastReportNanos = now;
            window.lastReportedCount = count;
            Bukkit.getLogger().severe("\n========== Arrow Error Report ==========\n"
                    + "Subsystem: " + safeSubsystem + '\n'
                    + "Operation: " + safeOperation + '\n'
                    + "Thread: " + Thread.currentThread().getName() + '\n'
                    + "Occurrences since last report: " + suppressed + '\n'
                    + "Context: " + sanitize(valueOr(context, "none")) + '\n'
                    + "Details: " + sanitize(valueOr(message, "unspecified")) + '\n'
                    + "========== End Arrow Error Report ==========");
            return true;
        }
    }

    private static String formatReport(String subsystem, String operation, Throwable failure,
                                       String context, long occurrences) {
        StringBuilder report = new StringBuilder(1024);
        report.append("\n========== Arrow Error Report ==========\n")
                .append("Subsystem: ").append(subsystem).append('\n')
                .append("Operation: ").append(operation).append('\n')
                .append("Thread: ").append(Thread.currentThread().getName()).append('\n')
                .append("Occurrences since last report: ").append(occurrences).append('\n')
                .append("Context: ").append(sanitize(valueOr(context, "none"))).append('\n');

        Throwable current = failure;
        int causeDepth = 0;
        while (current != null && causeDepth < 8) {
            report.append(causeDepth == 0 ? "Failure: " : "Caused by: ")
                    .append(current.getClass().getName());
            if (current.getMessage() != null) {
                report.append(": ").append(sanitize(current.getMessage()));
            }
            report.append('\n');

            StackTraceElement[] frames = current.getStackTrace();
            int frameLimit = Math.min(frames.length, 80);
            for (int i = 0; i < frameLimit; i++) {
                report.append("  at ").append(frames[i]).append('\n');
            }
            if (frames.length > frameLimit) {
                report.append("  ... ").append(frames.length - frameLimit)
                        .append(" additional frames omitted\n");
            }
            current = current.getCause();
            causeDepth++;
        }
        if (current != null) report.append("  ... additional causes omitted\n");
        return report.append("========== End Arrow Error Report ==========").toString();
    }

    private static String sanitize(String value) {
        String sanitized = value.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 2000 ? sanitized : sanitized.substring(0, 1997) + "...";
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 8) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? "no-message" : message.length() <= 256 ? message : message.substring(0, 256);
    }

    private static void pruneWindowsIfNeeded() {
        if (WINDOWS.size() <= MAX_TRACKED_FAILURES) return;
        long now = System.nanoTime();
        WINDOWS.entrySet().removeIf(entry -> now - entry.getValue().lastReportNanos > TimeUnit.MINUTES.toNanos(2L));
        if (WINDOWS.size() > MAX_TRACKED_FAILURES * 2) WINDOWS.clear();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static final class ErrorWindow {
        private final AtomicLong count = new AtomicLong();
        private volatile long lastReportNanos;
        private long lastReportedCount;
    }
}
