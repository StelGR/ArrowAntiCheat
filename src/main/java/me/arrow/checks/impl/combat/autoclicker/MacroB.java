package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.List;

// Detects AutoSoup macro — the sequence of weapon → mushroom stew → drop bowl
// at inhuman speed.
//
// All inventory state tracked purely through PacketEvents (SET_SLOT,
// WINDOW_ITEMS, HELD_ITEM_CHANGE) — no Bukkit inventory queries.
//
// Improvements:
//  - Death/respawn grace window to avoid false positives
//  - Combat-aware threshold (tighter when recently attacked)
//  - Buffer system for consecutive rapid soups before flagging

@Experimental
public class MacroB extends Check {

    // ── Tuning constants ──────────────────────────────────────────────────

    private static final long MAX_WEAPON_TO_SOUP_DROP_DELAY_MS = 45L;
    private static final long MAX_WEAPON_TO_SOUP_DROP_COMBAT_DELAY_MS = 35L;
    private static final long SEQUENCE_EXPIRY_MS = 500L;
    private static final long DEATH_GRACE_MS = 2000L;
    private static final long RECENT_ATTACK_WINDOW_MS = 1500L;
    private static final double MAX_BUFFER = 1.0;

    // ── Inventory slot mapping (window 0) ────────────────────────────────
    private static final int HOTBAR_START_SLOT = 36;
    private static final int HOTBAR_END_SLOT   = 44;

    // ── Packet-tracked inventory state ───────────────────────────────────

    /** Hotbar item types tracked via SET_SLOT / WINDOW_ITEMS. Index 0-8. */
    private final ItemType[] hotbarTypes = new ItemType[9];

    /** Currently selected hotbar slot (from HELD_ITEM_CHANGE). */
    private int heldSlot = 0;

    // ── Per-player state ──────────────────────────────────────────────────

    private long weaponToSoupTime = -1L;
    private ItemType weaponType;
    private int soupSlot = -1;

    private long deathTime   = -1L;
    private long respawnTime = -1L;
    private long lastDamageTime = -1L;

    public MacroB(Profile profile) {
        super(profile, CheckType.MACRO, "B", "Detects AutoSoup");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SERVER → CLIENT
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void handle(PacketSendEvent event) {

        // ── Entity status: death (3) / hurt (2) ──────────────────────────
        if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_STATUS)) {
            try {
                WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
                if (status.getEntityId() != profile.getPlayer().getEntityId()) return;

                if (status.getStatus() == 3) { // death
                    deathTime = event.getTimestamp();
                    resetSequence();
                } else if (status.getStatus() == 2) { // hurt
                    lastDamageTime = event.getTimestamp();
                }
            } catch (Throwable ignored) { }
            return;
        }

        // ── Respawn ──────────────────────────────────────────────────────
        if (event.getPacketType().equals(PacketType.Play.Server.RESPAWN)) {
            respawnTime = event.getTimestamp();
            resetSequence();
            clearHotbarCache();
            return;
        }

        // ── SET_SLOT: track hotbar items ─────────────────────────────────
        if (event.getPacketType().equals(PacketType.Play.Server.SET_SLOT)) {
            try {
                WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(event);
                if (setSlot.getWindowId() != 0) return;

                int slot = setSlot.getSlot();
                if (slot >= HOTBAR_START_SLOT && slot <= HOTBAR_END_SLOT) {
                    ItemStack item = setSlot.getItem();
                    hotbarTypes[slot - HOTBAR_START_SLOT] =
                            (item != null) ? item.getType() : ItemTypes.AIR;
                }
            } catch (Throwable ignored) { }
            return;
        }

        // ── WINDOW_ITEMS: bulk inventory update ──────────────────────────
        if (event.getPacketType().equals(PacketType.Play.Server.WINDOW_ITEMS)) {
            try {
                WrapperPlayServerWindowItems windowItems = new WrapperPlayServerWindowItems(event);
                if (windowItems.getWindowId() != 0) return;

                List<ItemStack> items = windowItems.getItems();
                if (items == null) return;

                for (int slot = HOTBAR_START_SLOT; slot <= HOTBAR_END_SLOT; slot++) {
                    if (slot < items.size()) {
                        ItemStack item = items.get(slot);
                        hotbarTypes[slot - HOTBAR_START_SLOT] =
                                (item != null) ? item.getType() : ItemTypes.AIR;
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CLIENT → SERVER
    // ══════════════════════════════════════════════════════════════════════

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

        if (isInDeathGrace(now) || profile.isExempt().isDead()) {
            resetSequence();
            return;
        }

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

            // What the player was holding BEFORE this switch
            ItemType previousType = getHeldType();
            // What the player is switching TO
            ItemType selectedType = getHotbarType(slot);

            // Update tracked held slot
            heldSlot = slot;

            if (isWeapon(previousType) && isMushroomSoup(selectedType)) {
                weaponToSoupTime = now;
                weaponType = previousType;
                soupSlot = slot;
                return;
            }

            if (!isMushroomSoup(selectedType)) {
                resetSequence();
            }
        } catch (Throwable ignored) {
            resetSequence();
        }
    }

    private void handleDigging(PacketReceiveEvent event) {
        if (weaponToSoupTime <= 0) return;

        long now = event.getTimestamp();

        if (isInDeathGrace(now)) {
            resetSequence();
            return;
        }

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

            boolean inCombat = lastDamageTime > 0
                    && (now - lastDamageTime) < RECENT_ATTACK_WINDOW_MS;
            if (profile.getLastAttackByEntityTimer().hasNotPassed(20)) {
                inCombat = true;
            }

            long threshold = inCombat
                    ? MAX_WEAPON_TO_SOUP_DROP_COMBAT_DELAY_MS
                    : MAX_WEAPON_TO_SOUP_DROP_DELAY_MS;

            if (delay <= threshold && delay > 1) {
                if (increaseBuffer() >= MAX_BUFFER) {
                    fail("Impossible weapon to soup drop macro",
                            "delay " + MsgType.MAIN_THEME_COLOR.getMessage() + delay + "ms"
                                    + "\nweapon " + MsgType.MAIN_THEME_COLOR.getMessage() + typeName(weaponType)
                                    + "\nsoupSlot " + MsgType.MAIN_THEME_COLOR.getMessage() + soupSlot
                                    + "\ndropAction " + MsgType.MAIN_THEME_COLOR.getMessage() + action.name()
                                    + "\nthreshold " + MsgType.MAIN_THEME_COLOR.getMessage() + threshold + "ms"
                                    + "\ninCombat " + MsgType.MAIN_THEME_COLOR.getMessage() + inCombat);
                    resetBuffer();
                }
            } else {
                decreaseBuffer();
            }

            resetSequence();
        } catch (Throwable ignored) {
            resetSequence();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private boolean isInDeathGrace(long now) {
        if (deathTime > 0 && (now - deathTime) < DEATH_GRACE_MS) return true;
        if (respawnTime > 0 && (now - respawnTime) < DEATH_GRACE_MS) return true;
        return !profile.getSinceDeathTimer().passed(40);
    }

    // ── Packet-tracked inventory access ──────────────────────────────────

    private ItemType getHotbarType(int slot) {
        if (slot < 0 || slot > 8) return ItemTypes.AIR;
        ItemType t = hotbarTypes[slot];
        return t != null ? t : ItemTypes.AIR;
    }

    private ItemType getHeldType() {
        return getHotbarType(heldSlot);
    }

    private void clearHotbarCache() {
        for (int i = 0; i < hotbarTypes.length; i++) {
            hotbarTypes[i] = ItemTypes.AIR;
        }
    }

    // ── ItemType helpers ─────────────────────────────────────────────────

    private static String typeKey(ItemType type) {
        if (type == null || type == ItemTypes.AIR) return "air";
        try {
            return type.getName().getKey();
        } catch (Throwable ignored) {
            return type.toString().toLowerCase();
        }
    }

    private static boolean isWeapon(ItemType type) {
        if (type == null || type == ItemTypes.AIR) return false;
        String key = typeKey(type);
        return key.endsWith("_sword")
                || key.endsWith("_axe")
                || key.equals("mace")
                || key.equals("bow")
                || key.equals("crossbow")
                || key.equals("trident");
    }

    private static boolean isMushroomSoup(ItemType type) {
        if (type == null || type == ItemTypes.AIR) return false;
        String key = typeKey(type);
        return key.equals("mushroom_stew") || key.equals("mushroom_soup");
    }

    private static String typeName(ItemType type) {
        if (type == null) return "null";
        return typeKey(type);
    }

    private void resetSequence() {
        weaponToSoupTime = -1L;
        weaponType = null;
        soupSlot = -1;
    }
}
