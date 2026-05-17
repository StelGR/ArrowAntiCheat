package me.arrow.checks.impl.combat.reach;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.playerdata.data.impl.reachUtils.NewTrackedEntity;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.IReachData;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Locale;

// Credits: MrPlugin (doesn't work though)

// extra info, this is meant to be transaction based reach im pretty sure,
// this does not work though as it was converted from another devs base, and clearly the conversion was poor,
// the main issue is the ReachProcessor and NewTrackedEntity.

@Experimental
public class ReachB extends Check {

    private static final double BASE_REACH = 3.0001D;

    private double threshold;

    public ReachB(Profile profile) {
        super(profile, CheckType.REACH, "B", "Detects if a player attacks from too far");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
    }

    public void onReach(IReachData reachData) {
        if (reachData == null) {
            return;
        }

        if (!reachData.isValidHitbox() || !profile.getReachProcessor().isAttack()) {
            return;
        }

        Entity entity = profile.getReachProcessor().getEntityTarget();

        if (!(entity instanceof Player entityPlayer)) {
            return;
        }

        Profile target = null;

        try {
            target = Arrow.getInstance().getProfileManager().getProfile(entityPlayer);
        } catch (Throwable ignored) {
        }

        NewTrackedEntity trackedEntity = profile.getReachProcessor()
                .getTracked()
                .get(profile.getReachProcessor().getLastEntityID());

        int txPing = profile.getConnectionData().getTransPing();
        int postPing = profile.getConnectionData().getLastTransPing();
        int txDrop = profile.getConnectionData().getTransDropTick();
        int avgOutCombat = profile.getConnectionData().getAverageTransactionPing();

        int deltaPost = Math.abs(postPing - txPing);

        if (txPing <= 0 || postPing <= 0 || deltaPost > 40 || txDrop > 120) {
            threshold = 0.0D;
            return;
        }

        double extra = 0.0D;

        if (avgOutCombat > 0 && txPing > avgOutCombat) {
            extra = Math.min(0.5D, ((double) (txPing - avgOutCombat) / 100.0D) * 0.1D);
        }

        double allowed = BASE_REACH + extra;
        double distance = reachData.getDistance();
        double over = distance - allowed;

        verbose(this.getClass().getSimpleName(), distance, allowed,
                MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose (Reach B)"
                        + "\n * distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(distance)
                        + "\n * distanceNo003 " + MsgType.MAIN_THEME_COLOR.getMessage() + format(reachData.getDistanceNo003())
                        + "\n * allowed " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowed)
                        + "\n * over " + MsgType.MAIN_THEME_COLOR.getMessage() + format(over)
                        + "\n * threshold " + MsgType.MAIN_THEME_COLOR.getMessage() + format(threshold)
                        + "\n * txPing " + MsgType.MAIN_THEME_COLOR.getMessage() + txPing
                        + "\n * postPing " + MsgType.MAIN_THEME_COLOR.getMessage() + postPing
                        + "\n * txDrop " + MsgType.MAIN_THEME_COLOR.getMessage() + txDrop
                        + "\n * avgOutCombat " + MsgType.MAIN_THEME_COLOR.getMessage() + avgOutCombat
                        + "\n * positionSize " + MsgType.MAIN_THEME_COLOR.getMessage()
                        + (trackedEntity == null ? -1 : trackedEntity.getPositions().size())
                        + "\n * confirming " + MsgType.MAIN_THEME_COLOR.getMessage()
                        + (trackedEntity != null && trackedEntity.isConfirming())
                        + "\n * usingPrePost " + MsgType.MAIN_THEME_COLOR.getMessage()
                        + (trackedEntity != null && trackedEntity.isReallyUsingPrePost())
                        + "\n * ranTimes " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getReachProcessor().getRanTimes());

        if (over >= 0.0D && !exempt(trackedEntity, target)) {
            if (over < 0.25D) {
                if (++threshold > 3.95D) {
                    fail("Increased interaction range",
                            "distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(distance)
                                    + "\nmax-distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowed)
                                    + "\nover " + MsgType.MAIN_THEME_COLOR.getMessage() + format(over)
                                    + "\npositionSize " + MsgType.MAIN_THEME_COLOR.getMessage()
                                    + trackedEntity.getPositions().size()
                                    + "\nclientVersion " + MsgType.MAIN_THEME_COLOR.getMessage() + getClientVersion());
                }
            } else {
                if (++threshold > 1.95D) {
                    fail("Increased interaction range",
                            "distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(distance)
                                    + "\nmax-distance " + MsgType.MAIN_THEME_COLOR.getMessage() + format(allowed)
                                    + "\nover " + MsgType.MAIN_THEME_COLOR.getMessage() + format(over)
                                    + "\npositionSize " + MsgType.MAIN_THEME_COLOR.getMessage()
                                    + trackedEntity.getPositions().size()
                                    + "\nclientVersion " + MsgType.MAIN_THEME_COLOR.getMessage() + getClientVersion());
                }
            }
        } else {
            threshold -= Math.min(threshold, 0.05D);
        }
    }

    private boolean exempt(NewTrackedEntity trackedEntity, Profile target) {
        if (trackedEntity == null || target == null) {
            return true;
        }

        if (!profile.getReachProcessor().isAttack()) {
            return true;
        }

        if (profile.shouldCancel()) {
            return true;
        }

        if (profile.isExempt().isTeleports()) {
            return true;
        }

        if (profile.isExempt().vehicle()) {
            return true;
        }

        if (profile.getPlayer() == null || target.getPlayer() == null) {
            return true;
        }

        if (profile.getPlayer().isSleeping()) {
            return true;
        }

        if (profile.getReachProcessor().getRanTimes() < 4) {
            return true;
        }

        if (profile.isBedrockPlayer()) {
            return true;
        }

        if (profile.getPlayer().getGameMode() == GameMode.CREATIVE
                || profile.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return true;
        }

        if (target.shouldCancel()) {
            return true;
        }

        if (target.isExempt().isTeleports()) {
            return true;
        }

        if (target.isExempt().vehicle()) {
            return true;
        }

        if (trackedEntity.trackedLocations < 1) {
            return true;
        }

        if (trackedEntity.getPositions().size() > 2) {
            return true;
        }

        if (trackedEntity.isConfirming()) {
            return true;
        }

        return !trackedEntity.isReallyUsingPrePost();
    }

    private String getClientVersion() {
        try {
            return profile.getVersion().getReleaseName();
        } catch (Throwable ignored) {
            return "Unknown";
        }
    }

    private String format(double value) {
        if (value == Double.MAX_VALUE) {
            return "MAX";
        }

        if (!Double.isFinite(value)) {
            return "NaN";
        }

        return String.format(Locale.US, "%.4f", value);
    }
}