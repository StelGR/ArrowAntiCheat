package me.arrow.checks.impl.misc.inventory;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.Math.MathUtil;

import java.util.Collection;

@Experimental
public class InventoryB extends Check {

    public InventoryB(Profile profile) {
        super(profile, CheckType.INVENTORY, "B", "Checks for check stealer/auto armor using samples");
    }

    SampleList<Long> samples = new SampleList<>(50);
    long lastTime, lastDelta;

    @Override
    public void handle( PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.CLICK_WINDOW)) {
            final long time = event.getTimestamp();

            final long lastTime= this.lastTime;

            this.lastTime = time;

            final long delta = time - lastTime;

            final long lastDelta = this.lastDelta;

            this.lastDelta = delta;

            this.samples.add(delta);

            if (!this.samples.isCollected()) return;

            final double deviation = getDevation(this.samples);

            final double average = getAverageLong(this.samples);

            final double std = MathUtil.getStandardDeviation(this.samples);

            String verboseInfo = "deviation " + MsgType.MAIN_THEME_COLOR.getMessage() + deviation
                    + "\naverage " + MsgType.MAIN_THEME_COLOR.getMessage() + average
                    + "\nstd " + MsgType.MAIN_THEME_COLOR.getMessage() + std
                    + "\ndelta " + MsgType.MAIN_THEME_COLOR.getMessage() + delta
                    + "\nlastDelta " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDelta;

            verbose(this.getClass().getSimpleName(), deviation, 5.5, verboseInfo);

            if (deviation > 0D && deviation < 5.5D && average < 65D) {
                fail("Clicking in inventory too fast",
                        verboseInfo);
            }


            if (std < 0.1)
            {
                fail("Impossible inventory clicks",
                        verboseInfo);
            }
        }
    }

    public double getDevation(final Collection<? extends Number> nums){
        if (nums.isEmpty()) return 0D;

        return Math.sqrt((getVariance(nums) / (nums.size() - 1)));
    }

    public long getAverageLong(final Collection<Long> nums){
        if (nums.isEmpty()) return 0L;

        return getSumLong(nums) / nums.size();
    }

    public long getSumLong(final Collection<Long> nums){
        if (nums.isEmpty()) return 0L;

        long sum = 0;

        for (long num : nums) sum += num;

        return sum;
    }

    public double getVariance(final Collection<? extends Number> data) {
        if (data.isEmpty()) return 0D;

        int count = 0;

        double sum = 0.0;
        double varience = 0.0;

        double average;

        for (final Number number : data){
            sum += number.doubleValue();
            ++count;
        }
        average =  sum / count;

        for (final Number number : data){
            varience += Math.pow(number.doubleValue() - average, 2.0);
        }

        return varience;
    }
}
