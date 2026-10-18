package me.arrow.checks.impl.misc.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Scaffold (Type A) - Eagle / FastBridge Sneak Timing Standard Deviation Check.
 * Detects eagle scaffold (auto-crouch / safewalk bridge) bots by analyzing the standard deviation
 * of the movement intervals between sneaks while air-bridging and placing under-blocks.
 *
 * Compatible with modern Minecraft (1.8 - 26.3):
 * - Uses ActionData under-place tracking (confirmed and pending support) instead of raw placement packets.
 * - Uses packet-based movement location for thread-safe air-bridge checks.
 * - Filters for genuine bridging movement (deltaXZ > 0.04) so shift spam or standing still never false flags.
 */
@Experimental
public class ScaffoldA extends Check {

    private static final int SAMPLES_REQUIRED = 15;

    private final Deque<Integer> interactions = new ArrayDeque<>();
    private int flying;
    private double buffer;

    public ScaffoldA(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "A", "Checks for eagle scaffold");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {
            handleFlying();
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Client.ENTITY_ACTION)) {
            handleEntityAction(event);
        }
    }

    private void handleFlying() {
        MovementData movementData = profile.getMovementData();
        if (movementData == null) {
            return;
        }

        // Only count flying ticks when player is actually moving horizontally while bridging
        if (movementData.isMoving() && movementData.getDeltaXZ() > 0.04D) {
            this.flying++;
        }

        // If the player stopped sneaking for over 30 ticks (1.5s), they interrupted their bridge streak
        if (this.flying > 30) {
            this.interactions.clear();
            this.flying = 0;
            this.buffer = Math.max(0.0D, this.buffer - 0.5D);
        }
    }

    private void handleEntityAction(PacketReceiveEvent event) {
        WrapperPlayClientEntityAction entityAction;
        try {
            entityAction = new WrapperPlayClientEntityAction(event);
        } catch (Throwable ignored) {
            return;
        }

        if (entityAction.getAction() != WrapperPlayClientEntityAction.Action.START_SNEAKING) {
            return;
        }

        MovementData movementData = profile.getMovementData();
        if (movementData == null || movementData.getLocation() == null) {
            return;
        }

        // Transaction latency compensation for under-place support
        int trans = Math.max(0, profile.getConnectionData().getClientTickTrans());
        int maxTicks = 12 + (trans * 2);

        // 1. Verify under-place support from ActionData (confirmed or pending)
        boolean hasUnderPlace = profile.getActionData().hasRecentUnderPlaceSupport(maxTicks);

        // 2. Verify air bridging (void / open air below)
        Location loc = movementData.getLocation().toBukkit();
        boolean airBridging = profile.isAirBridging(loc);

        if (!hasUnderPlace || !airBridging) {
            return;
        }

        // 3. Ensure a realistic sneak cycle length (2 to 25 ticks between sneaks)
        if (this.flying < 2 || this.flying > 25) {
            this.flying = 0;
            return;
        }

        this.interactions.add(this.flying);
        this.flying = 0;

        if (this.interactions.size() >= SAMPLES_REQUIRED) {
            List<Double> samples = MathUtil.dequeTranslator(this.interactions);
            double std = MathUtil.getStandardDeviation(samples);
            double avg = MathUtil.getAverage(samples);

            if (std < 0.325D) {
                fail("Very Suspicious crouching",
                        "std " + MsgType.MAIN_THEME_COLOR.getMessage() + format(3, std)
                                + "\navg " + MsgType.MAIN_THEME_COLOR.getMessage() + format(1, avg) + "t"
                                + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage() + samples.size());
            } else if (std < 0.65D) {
                if (++this.buffer > 3.0D) {
                    fail("Suspicious crouching",
                            "std " + MsgType.MAIN_THEME_COLOR.getMessage() + format(3, std)
                                    + "\navg " + MsgType.MAIN_THEME_COLOR.getMessage() + format(1, avg) + "t"
                                    + "\nsamples " + MsgType.MAIN_THEME_COLOR.getMessage() + samples.size());
                }
            } else {
                this.buffer = Math.max(0.0D, this.buffer - 0.75D);
            }

            this.interactions.clear();
        }
    }

    private String format(int places, double value) {
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }
}
