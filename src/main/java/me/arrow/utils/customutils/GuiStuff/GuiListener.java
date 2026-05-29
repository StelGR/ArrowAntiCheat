package me.arrow.utils.customutils.GuiStuff;

import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.utils.customutils.animationSystem.Animation;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static me.arrow.Arrow.*;
import static me.arrow.utils.customutils.OtherUtility.translate;

public class GuiListener implements Listener {

    private static final int[] BAN_ANIMATION_INNER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String inventoryTitle = getInventoryTitle(event);
        if (inventoryTitle == null) {
            return;
        }

        Inventory topInventory = getTopInventory(event);
        int topSize = topInventory != null ? topInventory.getSize() : event.getInventory().getSize();

        ItemStack clickedItem = event.getCurrentItem();
        int rawSlot = event.getRawSlot();

        if (inventoryTitle.contains(translate("&7Info for "))) {
            event.setCancelled(true);
            return;
        }

        if (inventoryTitle.contains(translate("&7Main Menu"))) {
            handleMainMenu(event, player, rawSlot);
            return;
        }

        if (inventoryTitle.contains(translate("&7Ban Animations"))) {
            handleBanAnimationsMenu(event, player, rawSlot);
            return;
        }

        if (inventoryTitle.contains(translate("&7Settings"))) {
            handleSettingsMenu(event, player, rawSlot);
            return;
        }

        if (inventoryTitle.contains(translate("&7Select Category"))) {
            handleSelectCategoryMenu(event, player, rawSlot);
            return;
        }

        if (inventoryTitle.contains(translate("&7Misc Checks"))) {
            handleMiscChecksMenu(event, player, rawSlot);
            return;
        }

        if (inventoryTitle.contains(translate("&7Movement Checks"))) {
            handleMovementChecksMenu(event, player, rawSlot);
            return;
        }

        if (inventoryTitle.contains(translate("&7Combat Checks"))) {
            handleCombatChecksMenu(event, player, rawSlot);
            return;
        }

        handleBackButtons(event, player, inventoryTitle, clickedItem, rawSlot, topSize);
    }

    private void handleMainMenu(InventoryClickEvent event, Player player, int rawSlot) {
        event.setCancelled(true);

        if (rawSlot == 11) {
            getGuiManager().openChecksGUI(player);
            return;
        }

        if (rawSlot == 15 && player.hasPermission(Permissions.ADMIN.getPermission())) {
            getGuiManager().openSettingsGUI(player);
        }
    }

    private void handleSettingsMenu(InventoryClickEvent event, Player player, int rawSlot) {
        event.setCancelled(true);

        switch (rawSlot) {
            case 10:
                Config.Setting.TOGGLE_ALERTS_ON_JOIN.setValue(!Config.Setting.TOGGLE_ALERTS_ON_JOIN.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 12:
                Config.Setting.CHECK_SETTINGS_ALERT_CONSOLE.setValue(!Config.Setting.CHECK_SETTINGS_ALERT_CONSOLE.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 14:
                Config.Setting.LOGS_ENABLED.setValue(!Config.Setting.LOGS_ENABLED.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 16:
                Config.Setting.PUNISH_ENABLED.setValue(!Config.Setting.PUNISH_ENABLED.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 28:
                Config.Setting.TEST_SERVER_MODE_ENABLED.setValue(!Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 30:
                Config.Setting.DEBUG.setValue(!Config.Setting.DEBUG.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 32:
                Config.Setting.IGNORE_BEDROCK.setValue(!Config.Setting.IGNORE_BEDROCK.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 34:
                if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
                    getGuiManager().openBanAnimationGUI(player);
                    return;
                }

                Config.Setting.BAN_ANIMATION_ENABLED.setValue(!Config.Setting.BAN_ANIMATION_ENABLED.getBoolean());
                getGuiManager().openSettingsGUI(player);
                break;

            case 40:
                getGuiManager().openArrowGUI(player);
                break;
        }
    }

    private void handleBanAnimationsMenu(InventoryClickEvent event, Player player, int rawSlot) {
        event.setCancelled(true);

        if (rawSlot == 49) {
            getGuiManager().openSettingsGUI(player);
            return;
        }

        Animation.Type animationType = getAnimationBySlot(rawSlot);

        if (animationType == null) {
            return;
        }

        Config.Setting.BAN_ANIMATION_CURRENT.setValue(animationType.name());
        getGuiManager().openBanAnimationGUI(player);
    }

    private void handleSelectCategoryMenu(InventoryClickEvent event, Player player, int rawSlot) {
        event.setCancelled(true);

        switch (rawSlot) {
            case 22:
                getGuiManager().openArrowGUI(player);
                break;

            case 10:
                getGuiManager().openCombatChecksGUI(player);
                break;

            case 13:
                getGuiManager().openMovementChecksGUI(player);
                break;

            case 16:
                getGuiManager().openMiscChecksGUI(player);
                break;
        }
    }

    private void handleMiscChecksMenu(InventoryClickEvent event, Player player, int rawSlot) {
        event.setCancelled(true);

        switch (rawSlot) {
            case 22:
                getGuiManager().openChecksGUI(player);
                break;

            case 10:
                getGuiManager().openArrowCheckGUI(player, "BadPackets", "Badpackets");
                break;

            case 11:
                getGuiManager().openArrowCheckGUI(player, "Interact", "Interact");
                break;

            case 12:
                getGuiManager().openArrowCheckGUI(player, "Inventory", "Inventory");
                break;

            case 13:
                getGuiManager().openArrowCheckGUI(player, "NoSlow", "No Slowdown");
                break;

            case 14:
                getGuiManager().openArrowCheckGUI(player, "Scaffold", "Scaffold");
                break;

            case 15:
                getGuiManager().openArrowCheckGUI(player, "Timer", "Timer");
                break;

            case 16:
                getGuiManager().openArrowCheckGUI(player, "Macro", "Macro");
                break;
        }
    }

    private void handleMovementChecksMenu(InventoryClickEvent event, Player player, int rawSlot) {
        event.setCancelled(true);

        switch (rawSlot) {
            case 22:
                getGuiManager().openChecksGUI(player);
                break;

            case 10:
                getGuiManager().openArrowCheckGUI(player, "Fly", "Fly");
                break;

            case 11:
                getGuiManager().openArrowCheckSpeedGUI(player, "Speed", "OmniSprint", "Speed");
                break;

            case 12:
                getGuiManager().openArrowCheckGUI(player, "Motion", "Motion");
                break;

            case 13:
                getGuiManager().openArrowCheckGUI(player, "Analysis", "Analysis");
                break;

            case 14:
                getGuiManager().openArrowCheckGUI(player, "Ground", "Ground");
                break;

            case 15:
                getGuiManager().openArrowCheckGUI(player, "Elytra", "Elytra");
                break;
            case 16:
                getGuiManager().openArrowCheckGUI(player, "IllegalMove", "IllegalMove");
                break;
        }
    }

    private void handleCombatChecksMenu(InventoryClickEvent event, Player player, int rawSlot) {
        event.setCancelled(true);

        switch (rawSlot) {
            case 22:
                getGuiManager().openChecksGUI(player);
                break;

            case 10:
                getGuiManager().openArrowCheckGUI(player, "Aim", "Aim Assist");
                break;

            case 11:
                getGuiManager().openArrowCheckGUI(player, "BackTrack", "BackTrack");
                break;

            case 12:
                getGuiManager().openArrowCheckGUI(player, "AutoClicker", "AutoClicker");
                break;

            case 13:
                getGuiManager().openArrowCheckGUI(player, "Hitbox", "Hitbox");
                break;

            case 14:
                getGuiManager().openArrowCheckGUI(player, "Reach", "Reach");
                break;

            case 15:
                getGuiManager().openArrowCheckGUI(player, "Velocity", "Velocity");
                break;

            case 16:
                getGuiManager().openArrowCheckGUI(player, "Killaura", "Killaura");
                break;
        }
    }

    private void handleBackButtons(InventoryClickEvent event, Player player, String inventoryTitle, ItemStack clickedItem, int rawSlot, int topSize) {
        if (rawSlot >= topSize || clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        if (!clickedItem.hasItemMeta() || !clickedItem.getItemMeta().hasDisplayName()) {
            return;
        }

        if (rawSlot == 26 && clickedItem.getType() == Material.BARRIER) {
            event.setCancelled(true);

            if (inventoryTitle.contains("Fly")
                    || inventoryTitle.contains("Speed")
                    || inventoryTitle.contains("Motion")
                    || inventoryTitle.contains("Analysis")
                    || inventoryTitle.contains("Ground")
                    || inventoryTitle.contains("Elytra")
                    || inventoryTitle.contains("IllegalMove")) {
                getGuiManager().openMovementChecksGUI(player);
            } else if (inventoryTitle.contains("Aim")
                    || inventoryTitle.contains("BackTrack")
                    || inventoryTitle.contains("AutoClicker")
                    || inventoryTitle.contains("Hitbox")
                    || inventoryTitle.contains("Killaura")
                    || inventoryTitle.contains("Reach")
                    || inventoryTitle.contains("Velocity")) {
                getGuiManager().openCombatChecksGUI(player);
            } else if (inventoryTitle.contains("Badpackets")
                    || inventoryTitle.contains("Interact")
                    || inventoryTitle.contains("Inventory")
                    || inventoryTitle.contains("Slowdown")
                    || inventoryTitle.contains("Scaffold")
                    || inventoryTitle.contains("Macro")
                    || inventoryTitle.contains("Timer")) {
                getGuiManager().openMiscChecksGUI(player);
            }
        }

        if (rawSlot == 49 && clickedItem.getType() == Material.BARRIER) {
            event.setCancelled(true);

            if (inventoryTitle.contains("Checks")) {
                getGuiManager().openArrowGUI(player);
            }
        }
    }

    private Animation.Type getAnimationBySlot(int rawSlot) {
        return getAnimationSlots().get(rawSlot);
    }

    private Map<Integer, Animation.Type> getAnimationSlots() {
        Map<Integer, Animation.Type> slots = new LinkedHashMap<>();
        Animation.Type[] animations = Animation.Type.values();

        for (int i = 0; i < animations.length && i < BAN_ANIMATION_INNER_SLOTS.length; i++) {
            slots.put(BAN_ANIMATION_INNER_SLOTS[i], animations[i]);
        }

        return slots;
    }

    private String getInventoryTitle(InventoryClickEvent event) {
        Object view = getInventoryView(event);

        if (view != null) {
            String title = invokeString(view, "getTitle");
            if (title != null) return title;
        }

        Inventory topInventory = getTopInventory(event);

        if (topInventory != null) {
            String title = invokeString(topInventory, "getTitle");
            if (title != null) return title;

            title = invokeString(topInventory, "getName");
            return title;
        }

        return null;
    }

    private Inventory getTopInventory(InventoryClickEvent event) {
        Object view = getInventoryView(event);

        if (view != null) {
            try {
                Method method = view.getClass().getMethod("getTopInventory");
                Object result = method.invoke(view);

                if (result instanceof Inventory) {
                    return (Inventory) result;
                }
            } catch (Throwable ignored) {
            }
        }

        return event.getInventory();
    }

    private Object getInventoryView(InventoryClickEvent event) {
        try {
            Method method = event.getClass().getMethod("getView");
            Object result = method.invoke(event);

            if (result != null) {
                return result;
            }
        } catch (Throwable ignored) {
        }

        try {
            Method method = event.getWhoClicked().getClass().getMethod("getOpenInventory");
            return method.invoke(event.getWhoClicked());
        } catch (Throwable ignored) {
        }

        return null;
    }

    private String invokeString(Object object, String methodName) {
        try {
            Method method = object.getClass().getMethod(methodName);
            Object result = method.invoke(object);

            if (result instanceof String) {
                return (String) result;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }
}