package me.arrow.checks.impl.combat.aimassist;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.checks.impl.combat.aimassist.aimassistUtil.RotationFrame;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * LiquidBounce RotationManager fingerprint.
 *
 * <p>LiquidBounce advances a RotationTarget every game tick and normalizes
 * the generated target before serializing it. That direct target rotation is
 * not limited to the integer mouse-sensitivity quantum that governs normal
 * Java-player yaw/pitch. This check derives the best possible vanilla quantum
 * from a whole rotation window; it only adds evidence when neither axis fits
 * that grid. It uses no interact packet, entity, or attack state.</p>
 */
@Experimental
public class AimK extends Check {

    private static final int WINDOW = 28;
    private static final int ANALYZE_EVERY = 5;
    private static final double MIN_TURN = 0.018D;
    private static final double MIN_QUANTUM = 0.0095D;
    private static final double MAX_QUANTUM = 0.6150D;

    private final List<RotationFrame> frames = new ArrayList<>(WINDOW);
    private int analysesSinceReset;

    public AimK(Profile profile) {
        super(profile, CheckType.AIM, "K", "Off-grid managed rotation");
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!RotationFrame.isRotationPacket(event.getPacketType())) {
            return;
        }

        if (shouldReset()) {
            reset(0.30D);
            return;
        }

        RotationFrame frame = RotationFrame.capture(profile.getRotationData());
        if (frame.length() < MIN_TURN || frame.length() > 90.0D) {
            reset(0.15D);
            return;
        }

        frames.add(frame);
        while (frames.size() > WINDOW) {
            frames.remove(0);
        }

        if (frames.size() == WINDOW && ++analysesSinceReset >= ANALYZE_EVERY) {
            analyze();
            analysesSinceReset = 0;
        }
    }

    private void analyze() {
        GridFit yawFit = findBestGrid(true);
        GridFit pitchFit = findBestGrid(false);
        double yawTravel = travel(true);
        double pitchTravel = travel(false);
        double totalTravel = yawTravel + pitchTravel;

        boolean unquantizedYaw = yawFit.samples >= 14 && yawFit.error >= 0.055D;
        boolean unquantizedPitch = pitchFit.samples >= 14 && pitchFit.error >= 0.055D;
        boolean generatedRotation = totalTravel >= 24.0D && (unquantizedYaw || unquantizedPitch);

        verbose(getClass().getSimpleName(), getBuffer(), 3.0D,
                "yawGrid " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawFit.quantum)
                        + "\nyawGridError " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawFit.error)
                        + "\nyawSamples " + MsgType.MAIN_THEME_COLOR.getMessage() + yawFit.samples
                        + "\npitchGrid " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchFit.quantum)
                        + "\npitchGridError " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchFit.error)
                        + "\npitchSamples " + MsgType.MAIN_THEME_COLOR.getMessage() + pitchFit.samples
                        + "\nyawTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawTravel)
                        + "\npitchTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchTravel));

        if (!generatedRotation) {
            decreaseBufferBy(0.50D);
            return;
        }

        if (increaseBufferBy(1.10D) >= 3.0D) {
            fail("Off-grid Managed Rotation",
                    "yawGrid " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawFit.quantum)
                            + "\nyawGridError " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawFit.error)
                            + "\npitchGrid " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchFit.quantum)
                            + "\npitchGridError " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchFit.error)
                            + "\nyawTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(yawTravel)
                            + "\npitchTravel " + MsgType.MAIN_THEME_COLOR.getMessage() + format(pitchTravel));
            decreaseBufferBy(1.50D);
        }
    }

    private GridFit findBestGrid(boolean yaw) {
        List<Double> deltas = new ArrayList<>(WINDOW);
        for (RotationFrame frame : frames) {
            double delta = Math.abs(yaw ? frame.yaw : frame.pitch);
            if (delta >= MIN_TURN) {
                deltas.add(delta);
            }
        }

        if (deltas.size() < 4) {
            return GridFit.EMPTY;
        }

        GridFit best = GridFit.EMPTY;
        int seedLimit = Math.min(10, deltas.size());
        for (int index = 0; index < seedLimit; index++) {
            double delta = deltas.get(index);
            int maxDivisor = Math.min(1024, (int) Math.floor(delta / MIN_QUANTUM));
            for (int divisor = 1; divisor <= maxDivisor; divisor++) {
                double quantum = delta / divisor;
                if (quantum < MIN_QUANTUM || quantum > MAX_QUANTUM) {
                    continue;
                }

                GridFit fit = measureGrid(quantum, deltas);
                if (fit.error < best.error) {
                    best = fit;
                }
            }
        }
        return best;
    }

    private GridFit measureGrid(double quantum, List<Double> deltas) {
        double error = 0.0D;
        for (double delta : deltas) {
            long steps = Math.max(1L, Math.round(delta / quantum));
            error += Math.abs(delta - steps * quantum) / quantum;
        }
        return new GridFit(quantum, error / deltas.size(), deltas.size());
    }

    private double travel(boolean yaw) {
        double travel = 0.0D;
        for (RotationFrame frame : frames) {
            travel += Math.abs(yaw ? frame.yaw : frame.pitch);
        }
        return travel;
    }

    private boolean shouldReset() {
        return profile.isBedrockPlayer()
                || profile.shouldCancel()
                || profile.isExempt().isVehicle()
                || profile.getRotationData().getCinematicProcessor().isCinematic()
                || profile.getRotationData().getRotationsAfterTeleport() <= 10;
    }

    private void reset(double decay) {
        frames.clear();
        analysesSinceReset = 0;
        decreaseBufferBy(decay);
    }

    private String format(double value) {
        return String.format(java.util.Locale.US, "%.5f", value);
    }

    private static final class GridFit {
        private static final GridFit EMPTY = new GridFit(0.0D, Double.MAX_VALUE, 0);

        private final double quantum;
        private final double error;
        private final int samples;

        private GridFit(double quantum, double error, int samples) {
            this.quantum = quantum;
            this.error = error;
            this.samples = samples;
        }
    }

    @Override
    public void handle(PacketSendEvent event) {
    }
}
