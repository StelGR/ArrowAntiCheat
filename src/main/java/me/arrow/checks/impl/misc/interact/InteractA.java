package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.custom.SampleList;

import static me.arrow.utils.customutils.Math.MathUtil.*;

// simple fast place check from Nik

public class InteractA extends Check {
    public InteractA(Profile profile) {
        super(profile, CheckType.INTERACT, "A", "Checks for fast place");
    }

    SampleList<Long> samples = new SampleList<>(20);
    long lastTime, lastDelta;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            final long time = event.getTimestamp();

            final long lastTime = this.lastTime;

            this.lastTime = time;

            final long delta = time - lastTime;

            final long lastDelta = this.lastDelta;

            this.lastDelta = delta;

            if (delta > 5L && lastDelta > 5L) this.samples.add(delta);

            if (!this.samples.isCollected()) return;

            final double deviation = getDevation(this.samples);

            final double average = getAverageLong(this.samples);

            if (deviation < 4D && average < 65D) {
                fail("Placing too quickly",
                        "deviation " + MsgType.MAIN_THEME_COLOR.getMessage() + deviation
                                + "\naverage " + MsgType.MAIN_THEME_COLOR.getMessage() + average
                                + "\ndelta " + MsgType.MAIN_THEME_COLOR.getMessage() + delta
                                + "\nlastDelta " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDelta);
            }
        }
    }
}