package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;

import java.util.List;

// Detects auto armor, auto totem (post-pop re-equip), and impossible axe→mace weapon macro.
//
// All inventory state is tracked purely through PacketEvents (SET_SLOT, WINDOW_ITEMS,
// HELD_ITEM_CHANGE) — no Bukkit inventory queries.
//
// Version-specific design:
//
//  ── Legacy (≤1.12) ──────────────────────────────────────────────────
//  The client fires InventoryOpenEvent when opening their own inventory,
//  so actionData.isInInventory() is reliable.  If we receive CLICK_WINDOW
//  on window 0 (player inventory) while the inventory was never opened,
//  that is a dead giveaway for auto-armor / auto-totem mods.
//
//  ── Modern (≥1.13) ──────────────────────────────────────────────────
//  There is NO open-inventory packet for the player's own inventory, so
//  isInInventory() cannot be used.  Detection relies on:
//    • Death (entity status 3) + RESPAWN packet → grace window
//    • sinceDeathTimer / isDead exempt → suppress false positives
//    • Require recent damage evidence for an armor-empty SET_SLOT to
//      count as a "break" (prevents false on death/respawn/admin /clear)
//    • Reaction-time analysis after a validated break
//    • Combat-context-aware thresholds
//    • Multi-slot burst detection

@Experimental
public class MacroA extends Check {

    // ── Tuning constants ──────────────────────────────────────────────────

    private static final long REACTION_THRESHOLD_MS = 160L;
    private static final long REACTION_THRESHOLD_COMBAT_MS = 120L;
    private static final long TRIGGER_EXPIRY_MS = 3000L;
    private static final long DEATH_GRACE_MS = 2000L;
    private static final long RESPAWN_GRACE_MS = 2000L;
    private static final long RECENT_ATTACK_WINDOW_MS = 1500L;

    private static final int  BURST_CLICK_THRESHOLD = 3;
    private static final long BURST_WINDOW_MS = 200L;

    private static final long AXE_TO_MACE_HIT_MAX_DELAY_MS = 50L;
    private static final long AXE_TO_MACE_EXPIRE_MS = 250L;

    // ── Inventory slot mapping (window 0) ────────────────────────────────
    // Slots 5-8:   armor (helmet, chest, legs, boots)
    // Slots 36-44: hotbar (maps to hotbar index 0-8)
    // Slot 45:     off-hand

    private static final int HOTBAR_START_SLOT = 36;
    private static final int HOTBAR_END_SLOT   = 44;

    // ── Packet-tracked inventory state ───────────────────────────────────

    /** Hotbar item types tracked via SET_SLOT / WINDOW_ITEMS. Index 0-8. */
    private final ItemType[] hotbarTypes = new ItemType[9];

    /** Currently selected hotbar slot (from HELD_ITEM_CHANGE). */
    private int heldSlot = 0;

    // ── Per-player state ──────────────────────────────────────────────────

    private long armorBreakTime = -1L;
    private long totemPopTime   = -1L;
    private String triggerReason = "";

    private long deathTime   = -1L;
    private long respawnTime = -1L;

    private long lastDamageTime = -1L;

    final long[] recentEquipClicks = new long[BURST_CLICK_THRESHOLD + 2];
    int equipClickHead = 0;
    int equipClickCount = 0;

    // Axe → Mace macro
    long lastAxeAttackTime = -1L;
    boolean switchedToMaceAfterAxe;
    int switchedMaceSlot = -1;
    ItemType lastAxeType;
    ItemType switchedMaceType;

    public MacroA(Profile profile) {
        super(profile, CheckType.MACRO, "A",
                "Detects auto armor, auto totem, and impossible reaction speed.");
    }

    private boolean isLegacyClient() {
        ClientVersion ver = profile.getVersion();
        return ver != null && ver.isOlderThanOrEquals(ClientVersion.V_1_12_2);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SERVER → CLIENT
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void handle(PacketSendEvent event) {

        // ── Entity status ────────────────────────────────────────────────
        if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_STATUS)) {
            try {
                WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
                if (status.getEntityId() != profile.getPlayer().getEntityId()) return;

                int code = status.getStatus();

                if (code == 3) { // death
                    deathTime = event.getTimestamp();
                    resetTriggers();
                    return;
                }
                if (code == 2) { // hurt
                    lastDamageTime = event.getTimestamp();
                    return;
                }
                if (code == 35) { // totem pop
                    if (!isInDeathGrace(event.getTimestamp())) {
                        totemPopTime = event.getTimestamp();
                        triggerReason = "totem_pop";
                    }
                    return;
                }
            } catch (Throwable ignored) { }
            return;
        }

        // ── Respawn ──────────────────────────────────────────────────────
        if (event.getPacketType().equals(PacketType.Play.Server.RESPAWN)) {
            respawnTime = event.getTimestamp();
            resetTriggers();
            clearHotbarCache();
            return;
        }

        // ── SET_SLOT: track hotbar items + detect armor break ────────────
        if (event.getPacketType().equals(PacketType.Play.Server.SET_SLOT)) {
            handleSetSlot(event);
            return;
        }

        // ── WINDOW_ITEMS: bulk inventory update → populate hotbar cache ──
        if (event.getPacketType().equals(PacketType.Play.Server.WINDOW_ITEMS)) {
            handleWindowItems(event);
        }
    }

    private void handleSetSlot(PacketSendEvent event) {
        try {
            WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(event);

            if (setSlot.getWindowId() != 0) return;

            int slot = setSlot.getSlot();
            ItemStack peItem = setSlot.getItem();
            ItemType type = peItem.getType();

            // ── Update hotbar cache (slots 36-44) ────────────────────────
            if (slot >= HOTBAR_START_SLOT && slot <= HOTBAR_END_SLOT) {
                hotbarTypes[slot - HOTBAR_START_SLOT] = type;
            }

            // ── Armor break detection (slots 5-8) ───────────────────────
            boolean isArmorSlot = slot >= 5 && slot <= 8;
            if (!isArmorSlot) return;

            long now = event.getTimestamp();

            if (isInDeathGrace(now)) return;

            boolean isEmpty = type == ItemTypes.AIR;
            if (!isEmpty) return;

            if (profile.isExempt().isDead()) return;

            // On modern we can't track previous inventory state, so require
            // recent damage evidence before treating this as a real break.
            boolean hadRecentDamage = lastDamageTime > 0
                    && (now - lastDamageTime) < RECENT_ATTACK_WINDOW_MS;
            boolean profileDamage = profile.getDamageData().getLastCause(20) != null;
            boolean attackedRecently = profile.getLastAttackByEntityTimer().hasNotPassed(20);

            if (!hadRecentDamage && !profileDamage && !attackedRecently) {
                return;
            }

            armorBreakTime = now;
            triggerReason  = "armor_break(slot=" + slot
                    + ",dmgPacket=" + hadRecentDamage
                    + ",dmgProfile=" + profileDamage
                    + ",attacked=" + attackedRecently + ")";

        } catch (Throwable ignored) { }
    }

    private void handleWindowItems(PacketSendEvent event) {
        try {
            WrapperPlayServerWindowItems windowItems = new WrapperPlayServerWindowItems(event);

            if (windowItems.getWindowId() != 0) return;

            List<ItemStack> items = windowItems.getItems();

            // Populate hotbar cache from the bulk update
            for (int slot = HOTBAR_START_SLOT; slot <= HOTBAR_END_SLOT; slot++) {
                if (slot < items.size()) {
                    ItemStack item = items.get(slot);
                    hotbarTypes[slot - HOTBAR_START_SLOT] =
                            (item != null) ? item.getType() : ItemTypes.AIR;
                }
            }
        } catch (Throwable ignored) { }
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

        if (isAttackPacket(event)) {
            handleAttack(event);
            return;
        }

        if (!event.getPacketType().equals(PacketType.Play.Client.CLICK_WINDOW)) return;

        long now = event.getTimestamp();

        if (isInDeathGrace(now) || profile.isExempt().isDead()) {
            resetTriggers();
            return;
        }

        expireTriggers(now);

        try {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);

            if (click.getWindowId() != 0) return;

            int clickedSlot = click.getSlot();

            boolean fromInventory = clickedSlot >= 9 && clickedSlot <= 44;
            boolean toEquipSlot   = (clickedSlot >= 5 && clickedSlot <= 8) || clickedSlot == 45;

            if (!fromInventory && !toEquipSlot) return;

            // ═════════════════════════════════════════════════════════════
            //  LEGACY CHECK (≤1.12): inventory-not-open detection
            // ═════════════════════════════════════════════════════════════
            // On ≤1.12 the client sends InventoryOpenEvent for their own
            // inventory, so isInInventory() is reliable.  CLICK_WINDOW on
            // equip slots without opening inventory = auto-armor.
            if (isLegacyClient() && toEquipSlot) {
                if (!profile.getActionData().isInInventory()) {
                    fail("Inventory click without opening inventory",
                            "slot " + MsgType.MAIN_THEME_COLOR.getMessage() + clickedSlot
                                    + "\nversion " + MsgType.MAIN_THEME_COLOR.getMessage() + "legacy"
                                    + "\ninInventory " + MsgType.MAIN_THEME_COLOR.getMessage() + "false");
                    return;
                }
            }

            // ═════════════════════════════════════════════════════════════
            //  REACTION TIME CHECK (all versions)
            // ═════════════════════════════════════════════════════════════

            boolean armorTriggered = armorBreakTime > 0;
            boolean totemTriggered = totemPopTime   > 0;

            if (!armorTriggered && !totemTriggered) return;

            recordEquipClick(now);

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

            if (reactionTime < 0) return;

            boolean inCombat = lastDamageTime > 0
                    && (now - lastDamageTime) < RECENT_ATTACK_WINDOW_MS;
            if (profile.getLastAttackByEntityTimer().hasNotPassed(20)) {
                inCombat = true;
            }

            long threshold = inCombat ? REACTION_THRESHOLD_COMBAT_MS : REACTION_THRESHOLD_MS;

            if (reactionTime < threshold) {
                fail("Impossible reaction: " + reason,
                        "reaction " + MsgType.MAIN_THEME_COLOR.getMessage() + reactionTime + "ms"
                                + "\nslot " + MsgType.MAIN_THEME_COLOR.getMessage() + clickedSlot
                                + "\nthreshold " + MsgType.MAIN_THEME_COLOR.getMessage() + threshold + "ms"
                                + "\ninCombat " + MsgType.MAIN_THEME_COLOR.getMessage() + inCombat);
            }

            int burstCount = countRecentEquipClicks(now, BURST_WINDOW_MS);
            if (burstCount >= BURST_CLICK_THRESHOLD && reactionTime < REACTION_THRESHOLD_MS) {
                fail("Auto armor burst equip: " + reason,
                        "burstClicks " + MsgType.MAIN_THEME_COLOR.getMessage() + burstCount
                                + "\nwindow " + MsgType.MAIN_THEME_COLOR.getMessage() + BURST_WINDOW_MS + "ms"
                                + "\nreaction " + MsgType.MAIN_THEME_COLOR.getMessage() + reactionTime + "ms");
            }

        } catch (Throwable ignored) { }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Axe → Mace detection (packet-tracked inventory)
    // ══════════════════════════════════════════════════════════════════════

    private void handleHeldItemChange(PacketReceiveEvent event) {
        try {
            WrapperPlayClientHeldItemChange wrapper = new WrapperPlayClientHeldItemChange(event);
            int slot = wrapper.getSlot();

            if (slot < 0 || slot > 8) return;

            // Update tracked held slot
            heldSlot = slot;

            long now = event.getTimestamp();

            if (lastAxeAttackTime <= 0) return;

            if (now - lastAxeAttackTime > AXE_TO_MACE_EXPIRE_MS) {
                resetAxeMaceSequence();
                return;
            }

            ItemType type = getHotbarType(slot);

            if (!isMace(type)) return;

            switchedToMaceAfterAxe = true;
            switchedMaceSlot = slot;
            switchedMaceType = type;
        } catch (Throwable ignored) { }
    }

    private void handleAttack(PacketReceiveEvent event) {
        long now = event.getTimestamp();

        if (lastAxeAttackTime > 0 && now - lastAxeAttackTime > AXE_TO_MACE_EXPIRE_MS) {
            resetAxeMaceSequence();
        }

        ItemType held = getHeldType();

        if (isAxe(held)) {
            lastAxeAttackTime = now;
            switchedToMaceAfterAxe = false;
            switchedMaceSlot = -1;
            switchedMaceType = null;
            lastAxeType = held;
            return;
        }

        if (!isMace(held)) return;
        if (lastAxeAttackTime <= 0 || !switchedToMaceAfterAxe) return;

        long axeToMaceHit = now - lastAxeAttackTime;

        if (axeToMaceHit < 0) {
            resetAxeMaceSequence();
            return;
        }

        if (axeToMaceHit < AXE_TO_MACE_HIT_MAX_DELAY_MS) {
            fail("Impossible axe to mace macro",
                    "axeToMaceHit " + MsgType.MAIN_THEME_COLOR.getMessage() + axeToMaceHit + "ms"
                            + "\naxe " + MsgType.MAIN_THEME_COLOR.getMessage() + typeName(lastAxeType)
                            + "\nmace " + MsgType.MAIN_THEME_COLOR.getMessage() + typeName(held)
                            + "\nswitchedMaceSlot " + MsgType.MAIN_THEME_COLOR.getMessage() + switchedMaceSlot);
        }

        resetAxeMaceSequence();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private boolean isInDeathGrace(long now) {
        if (deathTime > 0 && (now - deathTime) < DEATH_GRACE_MS) return true;
        if (respawnTime > 0 && (now - respawnTime) < RESPAWN_GRACE_MS) return true;
        return !profile.getSinceDeathTimer().passed(40);
    }

    private void expireTriggers(long now) {
        if (armorBreakTime > 0 && (now - armorBreakTime) > TRIGGER_EXPIRY_MS) armorBreakTime = -1L;
        if (totemPopTime > 0 && (now - totemPopTime) > TRIGGER_EXPIRY_MS) totemPopTime = -1L;
    }

    private void resetTriggers() {
        armorBreakTime  = -1L;
        totemPopTime    = -1L;
        triggerReason   = "";
        equipClickHead  = 0;
        equipClickCount = 0;
    }

    // ── Packet-tracked inventory access ──────────────────────────────────

    /** Get the ItemType in the given hotbar slot (0-8) from our packet cache. */
    private ItemType getHotbarType(int slot) {
        if (slot < 0 || slot > 8) return ItemTypes.AIR;
        ItemType t = hotbarTypes[slot];
        return t != null ? t : ItemTypes.AIR;
    }

    /** Get the ItemType the player is currently holding (from packet cache). */
    private ItemType getHeldType() {
        return getHotbarType(heldSlot);
    }

    private void clearHotbarCache() {
        for (int i = 0; i < hotbarTypes.length; i++) {
            hotbarTypes[i] = ItemTypes.AIR;
        }
    }

    // ── Burst click tracking ─────────────────────────────────────────────

    private void recordEquipClick(long now) {
        recentEquipClicks[equipClickHead % recentEquipClicks.length] = now;
        equipClickHead++;
        if (equipClickCount < recentEquipClicks.length) equipClickCount++;
    }

    private int countRecentEquipClicks(long now, long windowMs) {
        int count = 0;
        int len = Math.min(equipClickCount, recentEquipClicks.length);
        for (int i = 0; i < len; i++) {
            int idx = ((equipClickHead - 1 - i) % recentEquipClicks.length
                    + recentEquipClicks.length) % recentEquipClicks.length;
            if ((now - recentEquipClicks[idx]) <= windowMs) count++;
        }
        return count;
    }

    // ── ItemType helpers ─────────────────────────────────────────────────

    private boolean isAttackPacket(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.ATTACK)) return true;
        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) return true;
        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) return false;
        try {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            return interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String typeKey(ItemType type) {
        if (type == null || type == ItemTypes.AIR) return "air";
        try {
            return type.getName().getKey();
        } catch (Throwable ignored) {
            return type.toString().toLowerCase();
        }
    }

    private static boolean isAxe(ItemType type) {
        if (type == null || type == ItemTypes.AIR) return false;
        String key = typeKey(type);
        return key.endsWith("_axe");
    }

    private static boolean isMace(ItemType type) {
        if (type == null || type == ItemTypes.AIR) return false;
        String key = typeKey(type);
        return key.equals("mace") || key.endsWith("_mace");
    }

    private void resetAxeMaceSequence() {
        lastAxeAttackTime = -1L;
        switchedToMaceAfterAxe = false;
        switchedMaceSlot = -1;
        lastAxeType = null;
        switchedMaceType = null;
    }

    private static String typeName(ItemType type) {
        if (type == null) return "null";
        return typeKey(type);
    }
}
