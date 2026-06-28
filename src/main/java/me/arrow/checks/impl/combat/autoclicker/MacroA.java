package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import me.arrow.Arrow;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

// this is an experimental check, it is meant to detect auto armor, totem pop swap, and in the future
// instant mace swap from axe

@Experimental
public class MacroA extends Check {

    // Reaction threshold in milliseconds; anything below this is inhuman.
    long REACTION_THRESHOLD_MS = 160L;

    long AXE_TO_MACE_HIT_MAX_DELAY_MS = 50L;
    long AXE_TO_MACE_EXPIRE_MS = 250L;

    long lastAxeAttackTime = -1L;
    boolean switchedToMaceAfterAxe;
    int switchedMaceSlot = -1;
    Material lastAxeMaterial;
    Material switchedMaceMaterial;

    long armorBreakTime = -1L;
    long totemPopTime   = -1L;

    String triggerReason = "";

    public MacroA(Profile profile) {
        super(profile, CheckType.MACRO, "A",
                "Detects auto armor, auto totem, and impossible reaction speed.");
    }

    @Override
    public void handle(PacketSendEvent event) {

        // ── Totem pop animation (entity status 35) ───────────────────────
        if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_STATUS)) {
            try {
                WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);

                if (status.getEntityId() == profile.getPlayer().getEntityId()
                        && status.getStatus() == 35) {
                    totemPopTime = event.getTimestamp();
                    triggerReason = "totem_pop";
                }
            } catch (Throwable ignored) { }
            return;
        }

        // ── Armor / totem item disappearing from equipment slots ──────────
        if (event.getPacketType().equals(PacketType.Play.Server.SET_SLOT)) {
            try {
                WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(event);

                // Window 0 = player inventory; windowId -1 is used on some
                // versions for cursor / preview, skip it.
                if (setSlot.getWindowId() != 0) return;

                int slot = setSlot.getSlot();

                // Armor slots: 5 (helmet), 6 (chestplate), 7 (leggings), 8 (boots)
                // Off-hand slot: 45 (1.9+)
                boolean isArmorSlot   = slot >= 5 && slot <= 8;
                boolean isOffhandSlot = slot == 45;

                if (!isArmorSlot && !isOffhandSlot) return;

                com.github.retrooper.packetevents.protocol.item.ItemStack peItem = setSlot.getItem();
                boolean isEmpty = peItem.getType() == ItemTypes.AIR;

                if (isEmpty) {
                    if (isArmorSlot) {
                        armorBreakTime = event.getTimestamp();
                        triggerReason  = "armor_break(slot=" + slot + ")";
                    }
//                    else {
//                        // off-hand slot became empty; only flag if there was a
//                        // totem there previously (totemPopTime handles that, but
//                        // this provides a secondary catch when the status packet
//                        // wasn't observed).
//                        if (totemPopTime <= 0) {
//                            totemPopTime = event.getTimestamp();
//                            triggerReason = "offhand_empty";
//                        }
//                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PacketType.Play.Client.HELD_ITEM_CHANGE)) {
            handleHeldItemChange(event);
            return;
        }

        if (isAttackPacket(event)) {
            handleAttack(event);
            return;
        }

        if (!event.getPacketType().equals(PacketType.Play.Client.CLICK_WINDOW)) return;

        long now = event.getTimestamp();

        // Check if either trigger is still "fresh"
        boolean armorTriggered = armorBreakTime > 0 && (now - armorBreakTime) >= 0;
        boolean totemTriggered = totemPopTime   > 0 && (now - totemPopTime)   >= 0;

        if (!armorTriggered && !totemTriggered) return;

        try {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);

            // Only care about the player inventory (window 0)
            if (click.getWindowId() != 0) return;

            int clickedSlot = click.getSlot();

            // Ignore clicks that are already in armor/off-hand destination slots;
            // we want to catch picks from the real inventory area (9-44) or
            // shift-click moves. Slot -999 is the "outside inventory" drop slot.
            boolean fromInventory = clickedSlot >= 9 && clickedSlot <= 44;

            // Also allow clicks directly on armor/off-hand destination (some
            // scripts directly drag from cursor), or the off-hand slot.
            boolean toEquipSlot = (clickedSlot >= 5 && clickedSlot <= 8) || clickedSlot == 45;

            if (!fromInventory && !toEquipSlot) return;

            long reactionTime;
            String reason;

            if (armorTriggered && (!totemTriggered || armorBreakTime >= totemPopTime)) {
                reactionTime = now - armorBreakTime;
                reason = triggerReason;
                armorBreakTime = -1L;
            } else {
                reactionTime = now - totemPopTime;
                reason = triggerReason;
                totemPopTime = -1L;
            }

            // Ignore suspiciously negative deltas (clock skew edge case)
            if (reactionTime < 0) return;

            if (reactionTime < REACTION_THRESHOLD_MS) {
                fail("Impossible reaction: " + reason,
                        "reaction " + MsgType.MAIN_THEME_COLOR.getMessage() + reactionTime + "ms"
                                + "\nslot " + MsgType.MAIN_THEME_COLOR.getMessage() + clickedSlot);
            }

        } catch (Throwable ignored) { }
    }

    private void handleHeldItemChange(PacketReceiveEvent event) {
        long now = event.getTimestamp();

        if (lastAxeAttackTime <= 0) {
            return;
        }

        if (now - lastAxeAttackTime > AXE_TO_MACE_EXPIRE_MS) {
            resetAxeMaceSequence();
            return;
        }

        try {
            WrapperPlayClientHeldItemChange wrapper = new WrapperPlayClientHeldItemChange(event);
            int slot = wrapper.getSlot();

            if (slot < 0 || slot > 8) {
                return;
            }

            Material material = getHotbarMaterial(slot);

            if (!isMace(material)) {
                return;
            }

            switchedToMaceAfterAxe = true;
            switchedMaceSlot = slot;
            switchedMaceMaterial = material;
        } catch (Throwable ignored) {
        }
    }

    private void handleAttack(PacketReceiveEvent event) {
        long now = event.getTimestamp();

        if (lastAxeAttackTime > 0 && now - lastAxeAttackTime > AXE_TO_MACE_EXPIRE_MS) {
            resetAxeMaceSequence();
        }

        Material held = getCurrentHeldMaterial();

        if (isAxe(held)) {
            lastAxeAttackTime = now;
            switchedToMaceAfterAxe = false;
            switchedMaceSlot = -1;
            switchedMaceMaterial = null;
            lastAxeMaterial = held;
            return;
        }

        if (!isMace(held)) {
            return;
        }

        if (lastAxeAttackTime <= 0 || !switchedToMaceAfterAxe) {
            return;
        }

        long axeToMaceHit = now - lastAxeAttackTime;

        if (axeToMaceHit < 0) {
            resetAxeMaceSequence();
            return;
        }

        if (axeToMaceHit < AXE_TO_MACE_HIT_MAX_DELAY_MS) {
            fail("Impossible axe to mace macro",
                    "axeToMaceHit " + MsgType.MAIN_THEME_COLOR.getMessage() + axeToMaceHit + "ms"
                            + "\naxe " + MsgType.MAIN_THEME_COLOR.getMessage() + materialName(lastAxeMaterial)
                            + "\nmace " + MsgType.MAIN_THEME_COLOR.getMessage() + materialName(held)
                            + "\nswitchedMaceSlot " + MsgType.MAIN_THEME_COLOR.getMessage() + switchedMaceSlot);
        }

        resetAxeMaceSequence();
    }

    private boolean isAttackPacket(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.ATTACK)) {
            return true;
        }

        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            return false;
        }

        try {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            return interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        } catch (Throwable ignored) {
            return false;
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

            return item.getType();
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

    private boolean isAxe(Material material) {
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.endsWith("_AXE")
                || name.equals("WOOD_AXE")
                || name.equals("GOLD_AXE");
    }

    private boolean isMace(Material material) {
        if (material == null || material == Material.AIR) {
            return false;
        }

        String name = material.name();

        return name.equals("MACE") || name.endsWith("_MACE");
    }

    private void resetAxeMaceSequence() {
        lastAxeAttackTime = -1L;
        switchedToMaceAfterAxe = false;
        switchedMaceSlot = -1;
        lastAxeMaterial = null;
        switchedMaceMaterial = null;
    }

    private String materialName(Material material) {
        return material == null ? "null" : material.name();
    }
}

