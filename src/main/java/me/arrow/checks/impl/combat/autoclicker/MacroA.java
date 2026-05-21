package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

// this is an experimental check, it is meant to detect auto armor, totem pop swap, and in the future
// instant mace swap from axe

@Experimental
public class MacroA extends Check {

    // Reaction threshold in milliseconds; anything below this is inhuman.
    private static final long REACTION_THRESHOLD_MS = 160L;

    private long armorBreakTime = -1L;
    private long totemPopTime   = -1L;

    private String triggerReason = "";

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
                boolean isEmpty = peItem == null || peItem.getType() == ItemTypes.AIR;

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
}

