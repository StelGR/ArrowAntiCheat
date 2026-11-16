package me.arrow.managers.profiler;


import me.arrow.files.Config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Profiler {

    private static final Map<String, ProfilerData> PROFILES = new ConcurrentHashMap<>();

    private Profiler() {}

    public static long start() {
        return Config.Setting.BENCHMARK_ENABLED.getBoolean() ? System.nanoTime() : 0L;
    }

    public static long startChunkCache() {
        try {
            return Config.Setting.BENCHMARK_ENABLED.getBoolean()
                    || Config.Setting.CHUNK_CACHE_PROFILER_ENABLED.getBoolean()
                    ? System.nanoTime() : 0L;
        } catch (Throwable ignored) {
            // Startup chunk preloading can begin before the config has loaded.
            return 0L;
        }
    }

    public static void stopChunkCache(String name, long start) {
        if (start == 0L) return;
        PROFILES.computeIfAbsent("ChunkCache." + name, ignored -> new ProfilerData())
                .add(System.nanoTime() - start);
    }

    public static void stop(String name, long start) {
        if (start == 0L || !Config.Setting.BENCHMARK_ENABLED.getBoolean()) return;

        long elapsed = System.nanoTime() - start;

        PROFILES
                .computeIfAbsent(name, s -> new ProfilerData())
                .add(elapsed);
    }

    public static void print() {

        System.out.println("===== Arrow Profiler =====");

        PROFILES.forEach((name, data) -> System.out.printf(
                "%s | Calls: %d | Avg: %.2f μs | Max: %.2f μs%n",
                name,
                data.getCalls(),
                data.getAverageTime() / 1000D,
                data.getMaxTime() / 1000D
        ));
    }

    public static void reset() {
        PROFILES.values().forEach(ProfilerData::reset);
    }

    /** Clears all metric names and values for a fresh plugin session. */
    public static void clear() {
        PROFILES.clear();
    }

    public static Map<String, ProfilerData> getProfiles() {
        return PROFILES;
    }
}
