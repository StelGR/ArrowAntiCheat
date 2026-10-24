package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.*;
import me.arrow.utils.customutils.Math.MathUtil;
import me.arrow.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;

// combat data to keep track of cps, attacked entity ID, and so on

@Getter
@Setter
public class CombatData implements Data {
    private static final int ARM_ANIMATION_SAMPLE_SIZE = 200;
    private static final int ARM_ANIMATION_MAX_MOVEMENTS = 8;
    private static final int ARM_ANIMATION_MAX_COMBAT_TICKS = 20;

    private double outlier, kurtosis, skewness, std, median, averageCps, currentCps, armAnimationCps;
    private int lastAttackedEntityID, lastLastAttackedEntityID;
    private int attackedTicks, armAnimationMovements, target, movementTicks, cancelTicks, sinceUsedSpear = 100;

    private final List<Integer> movements = new ArrayList<>();

    private SampleList<Double> cpsSamples = new SampleList<>(1000);

    Map<Integer, UUID> trackedEntities = new HashMap<>();

    private Tuple<List<Double>, List<Double>> outlierTuple;

    private PlayerLocation location;
    private final Deque<Long> clickTimestamps = new ArrayDeque<>();
    private final Queue<Integer> armAnimationClickSamples = new LinkedList<>();

    private boolean attacked, dropping, usedSpear, armAnimationSamplesReady;

    Profile profile;
    public CombatData(Profile profile) {
        this.profile = profile;
    }


    @Override
    public void processReceive(PacketReceiveEvent event) {
        long profiler = Profiler.start();
        try {


//            if (!OtherUtility.isFlying(event.getPacketType()) && !event.getPacketType().equals(PONG) && !event.getPacketType().equals(CLIENT_TICK_END)) {
//                if (isHoldingSpear()) {
//                    String packetName = event.getPacketType().getName();
//                    String debugMsg = OtherUtility.translate("&7[&bSpearPacket&7] &fPacket: &a" + packetName
//                            + " &7| Hand: &e" + getHeldItemDebug()
//                            + " &7| isSpear: &6" + isHoldingSpear()
//                            + " &7| atkTicks: &e" + attackedTicks
//                            + " &7| spearTicks: &e" + sinceUsedSpear);
//                    OtherUtility.log(debugMsg);
//                    if (profile.getPlayer() != null && profile.getPlayer().isOnline()) {
//                        profile.getPlayer().sendMessage(debugMsg);
//                    }
//                }
//            }
            if (event.getPacketType().equals(PacketType.Play.Client.ATTACK)) {
                WrapperPlayClientAttack attackPacket = new WrapperPlayClientAttack(event);
                this.lastLastAttackedEntityID = lastAttackedEntityID;
                this.lastAttackedEntityID = attackPacket.getEntityId();
                this.attackedTicks = 0;
                target = attackPacket.getEntityId();
                attacked = true;
            }
            if (event.getPacketType().equals(INTERACT_ENTITY)) {
                WrapperPlayClientInteractEntity useEntityPacket = new WrapperPlayClientInteractEntity(event);

                if (useEntityPacket.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {

                    this.lastLastAttackedEntityID = lastAttackedEntityID;
                    this.lastAttackedEntityID = useEntityPacket.getEntityId();
                    this.attackedTicks = 0;
                    target = useEntityPacket.getEntityId();
                    attacked = true;

                }
            }
            if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) {
                WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);

                if (digging.getAction().equals(DiggingAction.DROP_ITEM) || digging.getAction().equals(DiggingAction.DROP_ITEM_STACK)) {
                    dropping = true;
                }

                if (digging.getAction().equals(DiggingAction.STAB) && isHoldingSpear()) {
                    this.lastLastAttackedEntityID = lastAttackedEntityID;
                    this.lastAttackedEntityID = -1;
                    this.attackedTicks = 0;
                    target = -1;
                    attacked = true;
                    usedSpear = true;
                    sinceUsedSpear = 0;
                }
            }
            if (isAttackPacket(event.getPacketType())) {
                if (isHoldingSpear()) {
                    usedSpear = true;
                    sinceUsedSpear = 0;
                }

                // Handle block actions
                if (profile.getLastBlockBreakTimer().hasNotPassed(20) || profile.getPredictionData().isDigging() || isDropping()) {
                    movementTicks = 0;
                    movements.clear();
                    cpsSamples.clear();
                    clickTimestamps.clear();
                    return;
                }



                collectArmAnimationSample();

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

                if (cpsSamples.isCollected()) {
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

            if (OtherUtility.isFlying(event.getPacketType())) {
                sinceUsedSpear = usedSpear ? 0 : sinceUsedSpear + 1;
                this.attackedTicks++;
                attacked = false;
                movementTicks = 0;
                armAnimationMovements++;
                dropping = false;
                usedSpear = false;
            }
        } finally {
            Profiler.stop("CombatData", profiler);
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

    public boolean isAttackPacket(PacketTypeCommon packet) {
        return packet == PacketType.Play.Client.ANIMATION
                || packet == PacketType.Play.Client.ATTACK
                || packet == PacketType.Play.Client.PUNCH;
    }

    public boolean isHoldingSpear() {
        try {
            Player player = profile.getPlayer();
            if (player == null) {
                return false;
            }
            ItemStack held = null;
            try {
                held = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(player);
            } catch (Throwable ignored) {}
            if (held == null) {
                try {
                    held = player.getInventory().getItemInMainHand();
                } catch (Throwable ignored) {}
            }
            if (held == null) {
                try {
                    held = player.getItemInHand();
                } catch (Throwable ignored) {}
            }
            if (checkSpearItem(held)) {
                return true;
            }
            ItemStack offhand = null;
            try {
                offhand = player.getInventory().getItemInOffHand();
            } catch (Throwable ignored) {}
            return checkSpearItem(offhand);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean checkSpearItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        try {
            if (item.getType() != null) {
                String typeName = item.getType().name().toUpperCase(Locale.ROOT);
                if (typeName.contains("SPEAR")) {
                    return true;
                }
                try {
                    String key = item.getType().getKey().getKey().toUpperCase(Locale.ROOT);
                    if (key.contains("SPEAR")) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            String itemStr = item.toString().toUpperCase(Locale.ROOT);
            if (itemStr.contains("SPEAR")) {
                return true;
            }
        } catch (Throwable ignored) {}
        if (item.hasItemMeta()) {
            try {
                if (item.getItemMeta().hasDisplayName() && item.getItemMeta().getDisplayName().toLowerCase(Locale.ROOT).contains("spear")) {
                    return true;
                }
            } catch (Throwable ignored) {}
            try {
                if (item.getItemMeta().getItemName() != null && item.getItemMeta().getItemName().toLowerCase(Locale.ROOT).contains("spear")) {
                    return true;
                }
            } catch (Throwable ignored) {}
            try {
                if (item.getItemMeta().hasLore()) {
                    for (String line : item.getItemMeta().getLore()) {
                        if (line != null && (line.toLowerCase(Locale.ROOT).contains("spear") || line.toLowerCase(Locale.ROOT).contains("lunge"))) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            try {
                for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
                    if (entry.getKey().getName().toLowerCase(Locale.ROOT).contains("lunge")
                            || entry.getKey().getKey().getKey().toLowerCase(Locale.ROOT).contains("lunge")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public String getHeldItemDebug() {
        try {
            Player player = profile.getPlayer();
            if (player == null) return "null player";
            ItemStack held = null;
            try {
                held = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(player);
            } catch (Throwable ignored) {}
            if (held == null) {
                try {
                    held = player.getInventory().getItemInMainHand();
                } catch (Throwable ignored) {}
            }
            if (held == null) {
                try {
                    held = player.getItemInHand();
                } catch (Throwable ignored) {}
            }
            if (held == null || held.getType() == null) return "empty hand";
            String metaInfo = held.hasItemMeta() && held.getItemMeta().hasDisplayName() ? held.getItemMeta().getDisplayName() : "none";
            return held.getType().name() + " (meta=" + metaInfo + ")";
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }
}
