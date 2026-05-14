package me.arrow.utils.customutils;

import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import org.anjocaido.groupmanager.GroupManager;
import org.anjocaido.groupmanager.permissions.AnjoPermissionsHandler;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.bukkit.Bukkit.getServer;

public class OtherUtility {

    public static void antiCheatban(Player player) {
        player.getWorld().strikeLightningEffect(player.getLocation());
    }

    public static String translate(String source) {
        return ChatColor.translateAlternateColorCodes('&', source);
    }

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9A-Fa-fK-ORk-o]");

    public static String stripColorCodes(String input) {
        return COLOR_CODE_PATTERN.matcher(input).replaceAll("");
    }

    public static void log(String info) {
        Bukkit.getConsoleSender().sendMessage(info);
    }

    public static String guiLine() {
        return "&7&m------------------------";
    }

    public static final String DASH_LINE = "&7&m                                                                     ";

    public static boolean isLocationInRegion(Location location, Location corner1, Location corner2) {
        double minX = Math.min(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());

        double maxX = Math.max(corner1.getX(), corner2.getX());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public static String calculatePercentage(double current, double max) {
        double percentage;
        if (current >= max) {
            percentage = 100.0;
        } else {
            percentage = (current / max) * 100.0;
        }
        return String.format("%.2f%%", percentage);
    }

    public static String getCallingClassName() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        if (stackTraceElements.length >= 4) {
            String className = stackTraceElements[3].getClassName();
            return className.substring(className.lastIndexOf('.') + 1);
        }
        return "Unknown";
    }

    public static void setbackDebug(Profile user, String data) {
        if (user.isSetbackDebug() && user.getPlayer().hasPermission(Permissions.SETBACKS.getPermission())) {
            user.getPlayer().sendMessage(translate(data));
        }
    }

    public static Location parseLocation(String coordinates, String world) {
        String[] parts = coordinates.split(", ");
        double x = Double.parseDouble(parts[0]);
        double y = Double.parseDouble(parts[1]);
        double z = Double.parseDouble(parts[2]);
        return new Location(Bukkit.getWorld(world), x, y, z);
    }

    public static ItemStack createUnbreakableItem(Material material) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta != null) {
            setUnbreakableCompat(itemMeta, true);
            itemMeta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            itemStack.setItemMeta(itemMeta);
        }

        return itemStack;
    }

    private static void setUnbreakableCompat(ItemMeta meta, boolean unbreakable) {
        try {
            Method modern = ItemMeta.class.getMethod("setUnbreakable", boolean.class);
            modern.invoke(meta, unbreakable);
            return;
        } catch (NoSuchMethodException ignored) {
            // Legacy 1.8 path below
        } catch (Exception e) {
            throw new RuntimeException("Failed to set unbreakable via modern ItemMeta API", e);
        }

        try {
            Method spigot = ItemMeta.class.getMethod("spigot");
            Object spigotMeta = spigot.invoke(meta);
            Method legacy = spigotMeta.getClass().getMethod("setUnbreakable", boolean.class);
            legacy.invoke(spigotMeta, unbreakable);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set unbreakable via 1.8 ItemMeta.Spigot API", e);
        }
    }

    private static GroupManager groupManager;

    public static boolean hasGroupManager() {
        if (groupManager != null) return true;

        final PluginManager pluginManager = getServer().getPluginManager();
        final Plugin GMplugin = pluginManager.getPlugin("GroupManager");

        if (GMplugin != null && GMplugin.isEnabled()) {
            groupManager = (GroupManager) GMplugin;
            return true;
        }
        return false;
    }

    public static String getPrefix(final Player player) {
        if (!hasGroupManager()) return null;

        final AnjoPermissionsHandler handler = groupManager.getWorldsHolder().getWorldPermissions(player);
        if (handler == null) return null;

        return handler.getUserPrefix(player.getName());
    }

    public static String getPunishMessage(Player player) {
        String template = MsgType.PUNISH_BROADCAST.getMessage();
        return template.replace("%player%", player.getName());
    }



}


