package me.arrow.checks.impl.combat.backtrack;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.Math.MathUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.Deque;

// MrPlugin's idea for transaction based backtrack delay, works well but slow.

@Experimental
public class BackTrackA extends Check {

    public BackTrackA(Profile profile) {
        super(profile, CheckType.BACKTRACK, "A", "Generic connection delay flaw (Credits: MrPlugin)");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    int SAFE_SAMPLES = 50;
    int SAMPLES_NEEDED = 100;

    boolean SAFE_MODE = true;
    double MAX_BUFFER = 4.0D;

    double DIFF_REQ_CHECK_2 = 75.0D;

    Deque<Long> transPingCombat = new ArrayDeque<>(SAMPLES_NEEDED);
    Deque<Long> transPingNoCombat = new ArrayDeque<>(SAMPLES_NEEDED);

    double buffer2;

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)) {
            if (SAFE_MODE) {
                if (profile.getTick() < 60) {
                    return;
                }

                if (profile.isExempt().isTeleports()) {
                    return;
                }

                if (profile.isExempt().vehicle()) {
                    return;
                }

                if (profile.shouldCancel()) {
                    return;
                }
            }

            Player target = getPlayerByEntityId(profile.getCombatData().getTarget());

            if (target == null || !target.isOnline() || target == profile.getPlayer()) {
                return;
            }
            Profile targetProfile = Arrow.getInstance().getProfileManager().getProfile(target);

            if (targetProfile == null || targetProfile.getMovementData() == null) {
                return;
            }

            boolean inCombat = profile.getCombatData().getAttackedTicks() < 3;

            if (inCombat) {
                addSample(transPingCombat, (long) profile.getConnectionData().getTransPing());
            } else {
                addSample(transPingNoCombat, (long) profile.getConnectionData().getTransPing());
            }

            if (SAFE_MODE && (
                    transPingCombat.size() < SAFE_SAMPLES || transPingNoCombat.size() < SAFE_SAMPLES)) {
                return;
            }

            double avgTransCombat = MathUtil.getAverage(transPingCombat);
            double avgTransNoCombat = MathUtil.getAverage(transPingNoCombat);

            double diffTrans = avgTransCombat - avgTransNoCombat;

            if (diffTrans > DIFF_REQ_CHECK_2) {
                if (++buffer2 > MAX_BUFFER) {
                    fail("Delayed transactions in combat but not out of combat",
                            "avgTransCombat " + MsgType.MAIN_THEME_COLOR.getMessage() + format(avgTransCombat) +
                                    "\navgTransNoCombat " + MsgType.MAIN_THEME_COLOR.getMessage() + format(avgTransNoCombat) +
                                    "\ndiffTrans " + MsgType.MAIN_THEME_COLOR.getMessage() + format(diffTrans)
                    );
                    clearSamples();
                    buffer2 = MAX_BUFFER / 2.0D;
                }
            } else {
                buffer2 = Math.max(0.0D, buffer2 - 0.15D);
            }
        }
    }

    private <T> void addSample(Deque<T> deque, T value) {
        if (deque.size() >= SAMPLES_NEEDED) {
            deque.pollFirst();
        }
        deque.addLast(value);
    }

    private void clearSamples() {
        transPingCombat.clear();
        transPingNoCombat.clear();
    }

    public String format(double input) {
        return new DecimalFormat("###.###").format(input);
    }

    private Player getPlayerByEntityId(int entityId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getEntityId() == entityId) {
                return player;
            }
        }

        return null;
    }
}
