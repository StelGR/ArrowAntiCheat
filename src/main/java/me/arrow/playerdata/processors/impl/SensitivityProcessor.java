package me.arrow.playerdata.processors.impl;

import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.playerdata.processors.Processor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

// this sensitivity calculator, not the processor, was taken from OpenKarhu, does not work properly though, sometimes it's off

/**
 * A sensitivity processor class that we'll be using in order to hold certain data
 * <p>
 * NOTE: This does not include a way to grab the player's sensitivity,
 * Feel free to add your own method since every person does it differently.
 */
public class SensitivityProcessor implements Processor {

    private final Profile profile;

    @Getter
    @Setter
    private double mouseX, mouseY, constantYaw, constantPitch, yawGcd, pitchGcd;

    // Karhu-style buffers (small fast window + larger slow window)
    private final Deque<Float> pitchGcdList = new ArrayDeque<>(5);
    private final Deque<Float> pitchGcdList2 = new ArrayDeque<>(50);

    @Getter
    private int sensitivityPercent = 0; // 0..200
    @Getter
    private double mcpSensitivity = 0.0; // the MCP multiplier (f in MC math)

    // small state
    float lastDeltaPitch = 0.0f;
    float pitchMode = 0.0f;

    // scaling for integer GCD method (must be large enough for precision)
    private static final long SCALAR = 1_000_000L; // 1e6 — safe and stable


    public SensitivityProcessor(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void process() {
        RotationData data = profile.getRotationData();

        final float deltaYaw = data.getDeltaYaw();
        final float deltaPitch = data.getDeltaPitch();

        final float lastDeltaYaw = data.getLastDeltaYaw();
        final float lastDeltaPitch = data.getLastDeltaPitch();

        // compute absolute gcds (as small floats) using integer gcd under the hood
        this.yawGcd = getGcd(Math.abs(deltaYaw), Math.abs(lastDeltaYaw));
        this.pitchGcd = getGcd(Math.abs(deltaPitch), Math.abs(lastDeltaPitch));

        this.constantYaw = this.yawGcd;   // already small float (no extra EXPANDER division needed)
        this.constantPitch = this.pitchGcd;

        // protect from division by zero
        if (this.constantYaw > 0.0) this.mouseX = (int) (deltaYaw / this.constantYaw);
        else this.mouseX = 0;
        if (this.constantPitch > 0.0) this.mouseY = (int) (deltaPitch / this.constantPitch);
        else this.mouseY = 0;

        // main Karhu-style detection
        handleSensitivity();

        // update mcp sensitivity from percent (so other checks can use it)
        updateMcpSensitivity();
    }


    public void handleSensitivity() {
        RotationData data = profile.getRotationData();

        float deltaPitch = Math.abs(data.getDeltaPitch());

        // sanity gate: ignore extreme camera changes (teleports)
        if (deltaPitch >= 4.0f) {
            lastDeltaPitch = deltaPitch;
            return;
        }

        // use the gcd we computed earlier (already normalized float)
        float pitchGcdValue = (float) this.pitchGcd;

        // Karhu uses 0.009 threshold
        if (pitchGcdValue > 0.009f) {

            // --- small fast window (size 5) ---
            pitchGcdList.add(pitchGcdValue);
            if (pitchGcdList.size() == 5) {
                float mode = getMode(pitchGcdList);
                this.pitchMode = mode;
                float mouseDelta = convertToMouseDelta(mode);
                int percent = (int) Math.floor(mouseDelta * 200.0);
                this.sensitivityPercent = clamp(percent, 0, 200);
                this.mouseY = mouseDelta;
                pitchGcdList.clear();
            }

            // --- larger stable window ---
            pitchGcdList2.add(pitchGcdValue);
            if (pitchGcdList2.size() > 40) {
                float mode = getMode(pitchGcdList2);
                this.pitchMode = mode;
                float mouseDelta = convertToMouseDelta(mode);
                int percent = (int) Math.floor(mouseDelta * 200.0);
                this.sensitivityPercent = clamp(percent, 0, 200);
                this.mouseY = mouseDelta;

                if (pitchGcdList2.size() >= 50) {
                    pitchGcdList2.clear();
                }
            }
        }

        // keep last delta
        lastDeltaPitch = deltaPitch;
    }

    /**
     * Convert a pitchGCD -> mouseDelta using Karhu's formula:
     * ((cbrt(value / 0.15 / 8) - 0.2) / 0.6)
     * IMPORTANT: 'value' must be the *normalized* GCD (small float like 0.01)
     */
    private float convertToMouseDelta(float value) {
        if (value <= 0.0f) return 0.0f;
        return (float) (((Math.cbrt(value / 0.15f / 8.0f) - 0.2f) / 0.6f));
    }

    /**
     * Integer-GCD-based float gcd. This converts floats -> scaled longs, computes long gcd,
     * then returns gcd / SCALAR as the normalized float GCD. This is the robust method Karhu-like detectors use.
     */
    private static float getGcd(double a, double b) {
        // handle zeros
        if (a <= 0.0 || b <= 0.0) return 0.0f;

        long la = Math.round(a * SCALAR);
        long lb = Math.round(b * SCALAR);

        if (la == 0L || lb == 0L) return 0.0f;

        long g = gcd(Math.abs(la), Math.abs(lb));
        return (float) (g / (double) SCALAR);
    }

    /** classic iterative gcd for longs */
    private static long gcd(long x, long y) {
        while (y != 0L) {
            long t = x % y;
            x = y;
            y = t;
        }
        return Math.abs(x);
    }

    /**
     * Mode computation grouping floats to micro precision (1e6), returns average of bucket
     */
    private float getMode(Deque<Float> deque) {
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, Double> sums = new HashMap<>();
        final double scale = 1e6;

        for (Float f : deque) {
            long key = Math.round(f * scale);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
            sums.put(key, sums.getOrDefault(key, 0.0) + f);
        }

        long bestKey = 0L;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }

        if (bestCount == 0) return 0.0f;
        double avg = sums.get(bestKey) / bestCount;
        return (float) avg;
    }

    private int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }

    /**
     * Convert sensitivity percent -> MCP multiplier (what Minecraft uses internally).
     * f0 = (s / 142.0) * 0.6 + 0.2; f = (f0^3 * 8) * 0.15
     */
    private void updateMcpSensitivity() {
        int s = this.sensitivityPercent;
        if (s <= 0) {
            this.mcpSensitivity = 0.0;
            return;
        }
        double f0 = ((double) s / 142.0) * 0.6 + 0.2;
        this.mcpSensitivity = (f0 * f0 * f0 * 8.0) * 0.15;
    }
}