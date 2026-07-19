package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

@Experimental
public class MacroB extends Check {

    private static final long MAX_WEAPON_TO_SOUP_DROP_DELAY_MS = 35L;
    private static final long SEQUENCE_EXPIRY_MS = 400L;

    private long weaponToSoupTime = -1L;
    private Material weaponMaterial;
    private int soupSlot = -1;

    public MacroB(Profile profile) {
        super(profile, CheckType.MACRO, "B", "Detects AutoShoup");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.HELD_ITEM_CHANGE)) {
            handleHeldItemChange(event);
            return;
        }

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) {
            handleDigging(event);
        }
    }

    private void handleHeldItemChange(PacketReceiveEvent event) {
        long now = event.getTimestamp();

        if (weaponToSoupTime > 0 && now - weaponToSoupTime > SEQUENCE_EXPIRY_MS) {
            resetSequence();
        }

        try {
            WrapperPlayClientHeldItemChange packet = new WrapperPlayClientHeldItemChange(event);
            int slot = packet.getSlot();

            if (slot < 0 || slot > 8) {
                resetSequence();
                return;
            }

            Material selectedMaterial = getHotbarMaterial(slot);
            Material previousMaterial = getCurrentHeldMaterial();

            if (isWeapon(previousMaterial) && isMushroomSoup(selectedMaterial)) {
                weaponToSoupTime = now;
                weaponMaterial = previousMaterial;
                soupSlot = slot;
                return;
            }

            if (!isMushroomSoup(selectedMaterial)) {
                resetSequence();
            }
        } catch (Throwable ignored) {
            resetSequence();
        }
    }

    private void handleDigging(PacketReceiveEvent event) {
        if (weaponToSoupTime <= 0) {
            return;
        }

        long now = event.getTimestamp();
        long delay = now - weaponToSoupTime;

        if (delay < 0 || delay > SEQUENCE_EXPIRY_MS) {
            resetSequence();
            return;
        }

        try {
            WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
            DiggingAction action = packet.getAction();

            if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) {
                return;
            }

            if (delay <= MAX_WEAPON_TO_SOUP_DROP_DELAY_MS) {
                fail("Impossible weapon to soup drop macro",
                        "delay " + MsgType.MAIN_THEME_COLOR.getMessage() + delay + "ms"
                                + "\nweapon " + MsgType.MAIN_THEME_COLOR.getMessage() + weaponMaterial.name()
                                + "\nsoupSlot " + MsgType.MAIN_THEME_COLOR.getMessage() + soupSlot
                                + "\ndropAction " + MsgType.MAIN_THEME_COLOR.getMessage() + action.name());
            }

            resetSequence();
        } catch (Throwable ignored) {
            resetSequence();
        }
    }

    private Material getCurrentHeldMaterial() {
        try {
            ItemStack item = Arrow.getInstance()
                    .getNmsManager()
                    .getNmsInstance()
                    .getItemInMainHand(profile.getPlayer());

            if (item != null) {
                return item.getType();
            }
        } catch (Throwable ignored) {
        }

        try {
            ItemStack item = profile.getPlayer().getItemInHand();

            if (item != null) {
                return item.getType();
            }
        } catch (Throwable ignored) {
        }

        return Material.AIR;
    }

    private Material getHotbarMaterial(int slot) {
        try {
            ItemStack item = profile.getPlayer().getInventory().getItem(slot);

            if (item != null) {
                return item.getType();
            }
        } catch (Throwable ignored) {
        }

        return Material.AIR;
    }

    private boolean isWeapon(Material material) {
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || name.equals("MACE")
                || name.equals("BOW")
                || name.equals("CROSSBOW")
                || name.equals("TRIDENT");
    }

    private boolean isMushroomSoup(Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();

        return name.equals("MUSHROOM_STEW") || name.equals("MUSHROOM_SOUP");
    }

    private void resetSequence() {
        weaponToSoupTime = -1L;
        weaponMaterial = null;
        soupSlot = -1;
    }
}
