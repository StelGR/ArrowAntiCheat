package me.arrow.utils.customutils;

import me.arrow.Arrow;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.managers.profile.Profile;
import org.anjocaido.groupmanager.GroupManager;
import org.anjocaido.groupmanager.permissions.AnjoPermissionsHandler;
import org.bukkit.*;
import org.bukkit.entity.Player;
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

    public static void setbackDebug(Profile target, String data) {

        String formatted = translate(
                "&8[&cSetback&8] "
                        + "&7[&f" + target.getPlayer().getName() + "&7] "
                        + data
        );

        for (Profile profile : Arrow.getInstance()
                .getProfileManager()
                .getProfileMap()
                .values()) {

            if (profile == null) {
                continue;
            }

            Player player = profile.getPlayer();

            if (player == null || !player.isOnline()) {
                continue;
            }

            if (!profile.isSetbackDebug()) {
                continue;
            }

            if (!player.hasPermission(Permissions.SETBACKS.getPermission())) {
                continue;
            }

            player.sendMessage(formatted);
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
        return ensureUnbreakableItem(new ItemStack(material, 1));
    }

    public static ItemStack ensureUnbreakableItem(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta == null) {
            return itemStack;
        }

        boolean applied = trySetUnbreakableCompat(itemMeta, true);

        if (applied) {
            addItemFlagCompat(itemMeta, "HIDE_UNBREAKABLE");
        }

        addItemFlagCompat(itemMeta, "HIDE_ATTRIBUTES");
        addItemFlagCompat(itemMeta, "HIDE_ENCHANTS");

        itemStack.setItemMeta(itemMeta);
        return applied ? itemStack : applyUnbreakableNbtCompat(itemStack);
    }

    private static ItemStack applyUnbreakableNbtCompat(ItemStack itemStack) {
        try {
            String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
            String version = craftPackage.substring(craftPackage.lastIndexOf('.') + 1);

            Class<?> craftItemStackClass = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
            Class<?> nbtTagCompoundClass = Class.forName("net.minecraft.server." + version + ".NBTTagCompound");

            Method asNmsCopy = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
            Object nmsItemStack = asNmsCopy.invoke(null, itemStack);

            // Reuse the item's existing tag. Creating a new compound here
            // deletes legacy enchantments (for example Infinity on 1.8 bows)
            // when setTag replaces the complete NBT payload.
            Object tag = null;

            try {
                Method getTag = nmsItemStack.getClass().getMethod("getTag");
                tag = getTag.invoke(nmsItemStack);
            } catch (Throwable ignored) {
            }

            if (tag == null) {
                tag = nbtTagCompoundClass.getConstructor().newInstance();
            }

            Method setBoolean = nbtTagCompoundClass.getMethod("setBoolean", String.class, boolean.class);
            setBoolean.invoke(tag, "Unbreakable", true);

            Method setTag = nmsItemStack.getClass().getMethod("setTag", nbtTagCompoundClass);
            setTag.invoke(nmsItemStack, tag);

            Method asBukkitCopy = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStack.getClass());
            return (ItemStack) asBukkitCopy.invoke(null, nmsItemStack);
        } catch (Throwable ignored) {
            return itemStack;
        }
    }

    private static boolean trySetUnbreakableCompat(ItemMeta meta, boolean unbreakable) {
        try {
            // Use the Bukkit API directly on modern servers. Reflection against
            // CraftMeta implementations became unreliable after item data was
            // migrated to components.
            meta.setUnbreakable(unbreakable);
            return meta.isUnbreakable() == unbreakable;
        } catch (Throwable ignored) {
            // The direct methods do not exist on legacy 1.8 APIs.
        }

        try {
            Method modern = meta.getClass().getMethod("setUnbreakable", boolean.class);
            modern.invoke(meta, unbreakable);

            try {
                Method getter = meta.getClass().getMethod("isUnbreakable");
                Object value = getter.invoke(meta);
                return value instanceof Boolean && (Boolean) value == unbreakable;
            } catch (Throwable ignored) {
                return true;
            }
        } catch (NoSuchMethodException ignored) {
            // try legacy below
        } catch (Throwable ignored) {
            return false;
        }

        try {
            Method spigot = meta.getClass().getMethod("spigot");
            Object spigotMeta = spigot.invoke(meta);

            Method legacy = spigotMeta.getClass().getMethod("setUnbreakable", boolean.class);
            legacy.invoke(spigotMeta, unbreakable);

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void addItemFlagCompat(ItemMeta meta, String flagName) {
        try {
            Class<?> itemFlagClass = Class.forName("org.bukkit.inventory.ItemFlag");
            Object flag = Enum.valueOf((Class<Enum>) itemFlagClass.asSubclass(Enum.class), flagName);

            Method addItemFlags = meta.getClass().getMethod("addItemFlags", itemFlagClass.arrayType());
            Object array = java.lang.reflect.Array.newInstance(itemFlagClass, 1);
            java.lang.reflect.Array.set(array, 0, flag);

            addItemFlags.invoke(meta, array);
        } catch (Throwable ignored) {
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


