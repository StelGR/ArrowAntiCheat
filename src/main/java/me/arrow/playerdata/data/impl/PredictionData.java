package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.custom.MaterialType;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.EXPLOSION;

// this is my data for handling riptide on java properly, as there's a delay before the animation starts
// it also handles other use item and block digging and whatever
// the riptide handler waits based on ur ping before resetting, you as not riptiding,
// it waits for NMS animation to go off, this isn't an issue from bedrock on java for some reason but we need to use this
// on java edition, either way, you can't really bypass since this is only in water or when raining, you can technically though use this to go at the tridents
// riptide launch speed without launching your self, so it's not perfect
// and it does not fix the issue with java being able to launch ur self outside of water but cancelling the animation
// as to get launch without riptiding, and so it falses Speed A and C
// also, getting launched and hitting a wall at 30 degrees cancels the animation and so this also falses Speed A
// a more experienced dev may be able to fix that, but i know alot of devs are too lazy to handle this
// look at spartan, you  can spam right click the normal trident and disable the entire anticheat.
// i do not wish to be that lazy though, i want to handle alot of scenarios properly

@Getter
@Setter
public class PredictionData implements Data {
    private boolean hit = false, lastUseSword, lastUseItem, useSword, useItem, dropItem, useShield;
    private boolean isRiptiding = false;
    private int riptideCheckPackets = -1;

    private double lastExpX, lastExpZ, explosionSpeed, lastDeltaXZ, lastDeltaX, lastDeltaZ, expX, expZ, expY;
    private float blockFriction = 0.91F;

    private long lastMovePacket = 0L;

    private boolean digging;
    private long lastDiggingUpdate;
    private boolean activelyDigging;

    private boolean riptideCharging;
    private boolean riptidePending;
    private boolean riptideAnimationConfirmed;

    private int riptideChargePackets;
    private int riptidePendingPackets = -1;
    private int riptideActivePackets = -1;
    private int riptideFailedCooldownPackets = 0;



    Profile profile;

    public PredictionData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PLAYER_DIGGING)) {
            WrapperPlayClientPlayerDigging wrappedInBlockDigPacket = new WrapperPlayClientPlayerDigging(event);

            //profile.getPlayer().sendMessage("Packet: " + event.getPacketType() + " action " + wrappedInBlockDigPacket.getAction());
            if (wrappedInBlockDigPacket.getAction() == DiggingAction.RELEASE_USE_ITEM) {
                releaseRiptideCharge();

                useSword = false;
                useItem = false;
                useShield = false;
            }
            if (wrappedInBlockDigPacket.getAction() == DiggingAction.DROP_ITEM) {
                dropItem = true;
                useItem = false;
                useSword = false;
                useShield = false;
            }

            if (wrappedInBlockDigPacket.getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                useSword = false;
                useItem = false;
                useShield = false;
            }

            if (wrappedInBlockDigPacket.getAction() == DiggingAction.START_DIGGING) {
                activelyDigging = true;
                lastDiggingUpdate = System.currentTimeMillis();
                digging = true;
            } else if (wrappedInBlockDigPacket.getAction() == DiggingAction.FINISHED_DIGGING
                    || wrappedInBlockDigPacket.getAction() == DiggingAction.CANCELLED_DIGGING) {
                activelyDigging = false;
                lastDiggingUpdate = System.currentTimeMillis();
                digging = true;
            }
        }
        if (event.getPacketType().equals(SLOT_STATE_CHANGE)) {
            //profile.getPlayer().sendMessage("Packet: " + event.getPacketType());
            useSword = false;
            useItem = false;
            useShield = false;
        }
        if (event.getPacketType().equals(INTERACT_ENTITY)) {
            WrapperPlayClientInteractEntity wrappedInUseEntityPacket = new WrapperPlayClientInteractEntity(event);

            if (wrappedInUseEntityPacket.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                hit = true;
            }
        }
        if (event.getPacketType().equals(PLAYER_BLOCK_PLACEMENT)) {
            WrapperPlayClientPlayerBlockPlacement wrappedInBlockPlacePacket = new WrapperPlayClientPlayerBlockPlacement(event);

            Material mainHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer()).getType();
            Material offHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer()).getType();

            ItemStack main = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer());
            ItemStack off = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer());

            if (!mainHand.isBlock() && !offHand.isBlock()) {

                if (wrappedInBlockPlacePacket.getBlockPosition().getX() == -1
                        && wrappedInBlockPlacePacket.getBlockPosition().getY() == -1 && wrappedInBlockPlacePacket.getBlockPosition().getZ() == -1) {

                    if (profile.isSword(main) || profile.isSword(off)) {
                        useSword  = true;
                    }

                    if (MaterialType.isMaterial(mainHand.name(), MaterialType.SHIELD) || MaterialType.isMaterial(offHand.name(), MaterialType.SHIELD)) {
                        useShield  = true;
                    }

                }
                if (MaterialType.isMaterial(mainHand.name(), MaterialType.BOW) ||  MaterialType.isMaterial(offHand.name(), MaterialType.BOW)
                        || isFood(Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer())) || isFood(Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer()))) {
                    useItem = true;
                }



            }
            if (isHoldingRiptideTrident() && isRiptideEnvironment()) {
                useItem = true;
                startRiptideCharge();
            }

        }
        if (event.getPacketType().equals(USE_ITEM)) {
            WrapperPlayClientUseItem wrapperPlayClientUseItem = new WrapperPlayClientUseItem(event);

            Material mainHandMat = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer()).getType();
            Material offHandMat = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer()).getType();

            ItemStack main = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer());
            ItemStack off = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer());

            if (!mainHandMat.isBlock() && !offHandMat.isBlock()) {


                if (profile.isSword(main) || profile.isSword(off)) {
                    useSword = true;
                }

                if (MaterialType.isMaterial(mainHandMat.name(), MaterialType.SHIELD)
                        || MaterialType.isMaterial(offHandMat.name(), MaterialType.SHIELD)) {
                    useShield = true;
                }

                if (MaterialType.isMaterial(mainHandMat.name(), MaterialType.BOW)
                        || MaterialType.isMaterial(offHandMat.name(), MaterialType.BOW)
                        || isFood(main)
                        || isFood(off)) {
                    useItem = true;
                }


            }

            if (isHoldingRiptideTrident() && isRiptideEnvironment()) {
                useItem = true;
                startRiptideCharge();
            }
        }

        if (event.getPacketType().equals(CLIENT_STATUS)) {

            WrapperPlayClientClientStatus clientCommand = new WrapperPlayClientClientStatus(event);

            //profile.getPlayer().sendMessage("PacketAction: " + clientCommand.getAction());

            if (clientCommand.getAction() == WrapperPlayClientClientStatus.Action.OPEN_INVENTORY_ACHIEVEMENT) {
                hit = false;
                useItem = false;
                useSword = false;
                useShield = false;
            }
        }
        if (event.getPacketType().equals(CLOSE_WINDOW)) {
            hit = false;
            useSword = false;
            useItem = false;
            useShield = false;

        }

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            updateDiggingState();

            handleRiptideTick();

        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType().equals(EXPLOSION)) {
            WrapperPlayServerExplosion explosionPacket = new WrapperPlayServerExplosion(event);

            expX = explosionPacket.getKnockback() != null ? explosionPacket.getKnockback().getX() : 0;
            expZ = explosionPacket.getKnockback() != null ? explosionPacket.getKnockback().getZ() : 0;
            expY = explosionPacket.getKnockback() != null ? explosionPacket.getKnockback().getY() : 0;

            double expDeltaX = Math.abs(Math.abs(expX)
                    - Math.abs(lastExpX));
            double expDeltaZ = Math.abs(Math.abs(expZ)
                    - Math.abs(lastExpZ));

            explosionSpeed = Math.hypot(expDeltaX, expDeltaZ);

            this.lastExpX = expX;
            this.lastExpZ = expZ;
        }
    }

    public static boolean isFood(ItemStack item) {
        return item != null && item.getType().isEdible();
    }

    private void startRiptideCharge() {
        if (riptideFailedCooldownPackets > 0) {
            return;
        }

        if (isRiptiding || riptidePending || riptideAnimationConfirmed) {
            return;
        }

        if (!isHoldingRiptideTrident() || !isRiptideEnvironment()) {
            return;
        }

        if (isServerRiptiding()) {
            riptideCharging = false;
            riptidePending = false;
            riptideAnimationConfirmed = true;
            isRiptiding = true;

            riptideChargePackets = 0;
            riptidePendingPackets = -1;
            riptideActivePackets = getRiptideActivePackets();
            riptideCheckPackets = riptideActivePackets;
            return;
        }

        riptideCharging = true;
    }

    private void releaseRiptideCharge() {
        boolean validRelease = riptideCharging
                && isHoldingRiptideTrident()
                && isRiptideEnvironment();

        riptideCharging = false;
        riptideChargePackets = 0;

        if (!validRelease) {
            return;
        }

        if (riptideFailedCooldownPackets > 0) {
            return;
        }

        if (isRiptiding || riptidePending || riptideAnimationConfirmed) {
            return;
        }

        if (isServerRiptiding()) {
            riptidePending = false;
            riptideAnimationConfirmed = true;
            isRiptiding = true;

            riptidePendingPackets = -1;
            riptideActivePackets = getRiptideActivePackets();
            riptideCheckPackets = riptideActivePackets;
            return;
        }

        int waitPackets = getRiptideStartWaitPackets();

        riptidePending = true;
        riptideAnimationConfirmed = false;
        isRiptiding = true;

        riptidePendingPackets = waitPackets;
        riptideActivePackets = -1;
        riptideCheckPackets = waitPackets;
    }

    private void handleRiptideTick() {
        if (riptideFailedCooldownPackets > 0) {
            riptideFailedCooldownPackets--;
        }

        boolean serverRiptiding = isServerRiptiding();

        if (serverRiptiding) {
            riptideCharging = false;
            riptidePending = false;
            riptideAnimationConfirmed = true;
            isRiptiding = true;

            riptideChargePackets = 0;
            riptidePendingPackets = -1;
            riptideActivePackets = getRiptideActivePackets();
            riptideCheckPackets = riptideActivePackets;
            riptideFailedCooldownPackets = 0;
            return;
        }

        if (riptideCharging) {
            riptideChargePackets++;

            if (!isHoldingRiptideTrident() || !isRiptideEnvironment()) {
                resetRiptideState();
                return;
            }

            if (riptideChargePackets > 100) {
                riptideCharging = false;
                riptideChargePackets = 0;
            }
        }

        if (riptidePending) {
            if (!isHoldingRiptideTrident() || !isRiptideEnvironment()) {
                resetRiptideState();
                return;
            }

            if (riptidePendingPackets > 0) {
                riptidePendingPackets--;
                riptideCheckPackets = riptidePendingPackets;
            }

            if (riptidePendingPackets <= 0) {
                riptideCharging = false;
                riptidePending = false;
                riptideAnimationConfirmed = false;
                isRiptiding = false;

                riptideChargePackets = 0;
                riptidePendingPackets = -1;
                riptideActivePackets = -1;
                riptideCheckPackets = -1;

                riptideFailedCooldownPackets = Math.max(4, getRiptideStartWaitPackets() / 2);
            }

            return;
        }

        if (riptideAnimationConfirmed && isRiptiding) {
            if (riptideActivePackets > 0) {
                riptideActivePackets--;
                riptideCheckPackets = riptideActivePackets;
            }

            if (riptideActivePackets <= 0) {
                resetRiptideState();
            }
        }
    }

    private void resetRiptideState() {
        riptideCharging = false;
        riptidePending = false;
        riptideAnimationConfirmed = false;
        isRiptiding = false;

        riptideChargePackets = 0;
        riptidePendingPackets = -1;
        riptideActivePackets = -1;
        riptideCheckPackets = -1;
    }

    private boolean isRiptideTrident(ItemStack stack) {
        return getRiptideLevel(stack) > 0;
    }

    public int getRiptideLevel(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return 0;
        }

        if (!MaterialType.isMaterial(stack.getType().name(), MaterialType.TRIDENT)) {
            return 0;
        }

        try {
            Enchantment riptide = Enchantment.RIPTIDE;

            if (!stack.containsEnchantment(riptide)) {
                return 0;
            }

            return stack.getEnchantmentLevel(riptide);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean isServerRiptiding() {
        try {
            return Arrow.getInstance()
                    .getNmsManager()
                    .getNmsInstance()
                    .isRiptiding(profile.getPlayer());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int getRiptideStartWaitPackets() {
        int pingTicks = 0;

        pingTicks = Math.max(pingTicks, (int) Math.ceil(profile.getConnectionData().getTransPing() / 50.0D));

        pingTicks = Math.max(1, pingTicks);

        return Math.min(40, pingTicks * 4);
    }

    private int getRiptideActivePackets() {
        int level = riptideLevel();

        int base = switch (level) {
            case 3 -> 24;
            case 2 -> 20;
            default -> 16;
        };

        return Math.min(50, base + getRiptideStartWaitPackets());
    }

    public boolean isRiptideEnvironment() {
        Player player = profile.getPlayer();

        if (profile.getMovementData().isNearWater()) {
            return true;
        }

        if (!player.getWorld().hasStorm()) {
            return false;
        }

        return player.getEyeLocation().getBlock().getLightFromSky() > 0;
    }

    public int riptideLevel() {
        ItemStack main = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer());
        ItemStack off = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer());

       // profile.getPlayer().sendMessage(getRiptideLevel(main) + " |"+"| " + getRiptideLevel(off));

        int riptideLevel = 0;
        if (getRiptideLevel(main) > 0) riptideLevel = getRiptideLevel(main);
        else if (getRiptideLevel(off) > 0) riptideLevel = getRiptideLevel(off);
        return riptideLevel;
    }

    public boolean isHoldingRiptideTrident() {
        ItemStack main = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer());
        ItemStack off = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer());

        //profile.getPlayer().sendMessage(getRiptideLevel(main) + " |"+"| " + getRiptideLevel(off));

        return getRiptideLevel(main) > 0 || getRiptideLevel(off) > 0;
    }

    private void updateDiggingState() {
        long now = System.currentTimeMillis();

        digging = activelyDigging || now - lastDiggingUpdate <= 250L;
    }
}
