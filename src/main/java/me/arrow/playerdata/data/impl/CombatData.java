package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.*;
import me.arrow.utils.customutils.Math.MathUtil;

import java.util.*;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;

// combat data to keep track of cps, attacked entity ID, and so on

@Getter
@Setter
public class CombatData implements Data {
    private static final int ARM_ANIMATION_SAMPLE_SIZE = 200;
    private static final int ARM_ANIMATION_MAX_MOVEMENTS = 8;
    private static final int ARM_ANIMATION_MAX_COMBAT_TICKS = 20;

    private double outlier, kurtosis, skewness, std, median, averageCps, currentCps;
    private int lastAttackedEntityID, lastLastAttackedEntityID;
    private int attackedTicks;
    private int target;

    private int movementTicks, cancelTicks;
    private final List<Integer> movements = new ArrayList<>();

    private SampleList<Double> cpsSamples = new SampleList<>(1000);

    Map<Integer, UUID> trackedEntities = new HashMap<>();

    private Tuple<List<Double>, List<Double>> outlierTuple;

    private PlayerLocation location;
    private final Deque<Long> clickTimestamps = new ArrayDeque<>();
    private final Queue<Integer> armAnimationClickSamples = new LinkedList<>();

    private boolean attacked, dropping;
    private boolean armAnimationSamplesReady;
    private int armAnimationMovements;
    private double armAnimationCps;

    Profile profile;
    public CombatData(Profile profile) {
        this.profile = profile;
    }


    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(INTERACT_ENTITY)) {
            WrapperPlayClientInteractEntity useEntityPacket = new WrapperPlayClientInteractEntity(event);

            if (useEntityPacket.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {

                this.lastLastAttackedEntityID = lastAttackedEntityID;
                this.lastAttackedEntityID = useEntityPacket.getEntityId();
                this.attackedTicks = 0;
                target = useEntityPacket.getEntityId();
                attacked = true;

            }
        } else if (event.getPacketType().equals(PLAYER_FLYING)
                || event.getPacketType().equals(PLAYER_POSITION)
                || event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PLAYER_ROTATION)) {
            this.attackedTicks++;
            attacked = false;
            movementTicks = 0;
            armAnimationMovements++;
            dropping = false;
        }
        else if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);

            if (digging.getAction().equals(DiggingAction.DROP_ITEM) || digging.getAction().equals(DiggingAction.DROP_ITEM_STACK)) {
                dropping = true;
            }
        }
        else if (event.getPacketType().equals(ANIMATION)) {
            collectArmAnimationSample();

            // Handle block actions
            if (profile.getLastBlockBreakTimer().hasNotPassed(20) || profile.getPredictionData().isDigging() || isDropping()) {
                movementTicks = 0;
                movements.clear();
                cpsSamples.clear();
                clickTimestamps.clear();
                return;
            }

            movements.add(movementTicks);

            long now = System.currentTimeMillis();
            clickTimestamps.addLast(now);
            while (!clickTimestamps.isEmpty() && now - clickTimestamps.getFirst() > 1000) {
                clickTimestamps.removeFirst();
            }

            this.currentCps = clickTimestamps.size();

            cpsSamples.add(currentCps);

            if (movements.size() >= 120) {
                movements.clear();
                clickTimestamps.clear();
            }

            if (cpsSamples.isCollected())
            {
                double average = MathUtil.getAverage(cpsSamples);
                double std = MathUtil.getStandardDeviation(cpsSamples);
                double median = MathUtil.getMedian(cpsSamples);
                double kurtosis = MathUtil.getKurtosis(cpsSamples);
                double skewness = MathUtil.getSkewness(cpsSamples);

                this.outlierTuple = MathUtil.getOutliers(cpsSamples);
                this.outlier = this.outlierTuple.one.size() + this.outlierTuple.two.size();

                this.std = std;
                this.median = median;
                this.kurtosis = kurtosis;
                this.skewness = skewness;
                this.averageCps = average;
                cpsSamples.clear();
            }
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {

    }

    public void clearArmAnimationClickSamples() {
        armAnimationClickSamples.clear();
        armAnimationSamplesReady = false;
        armAnimationCps = 0.0D;
    }

    private void collectArmAnimationSample() {
        if (profile.getPredictionData().isDigging()) {
            return;
        }

        if (armAnimationMovements < ARM_ANIMATION_MAX_MOVEMENTS) {
            if (attackedTicks > ARM_ANIMATION_MAX_COMBAT_TICKS) {
                return;
            }

            if (armAnimationMovements == 0) {
                return;
            }

            if (armAnimationClickSamples.add(armAnimationMovements)
                    && armAnimationClickSamples.size() == ARM_ANIMATION_SAMPLE_SIZE) {
                armAnimationCps = MathUtil.getCPS(armAnimationClickSamples);
                armAnimationSamplesReady = true;
            }
        }

        armAnimationMovements = 0;
    }
}
