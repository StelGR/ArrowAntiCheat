package me.arrow.utils.customutils.GuiStuff;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionType;


import com.mojang.brigadier.*;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// a gui utility, the shitty api fails to get the skin on any version either 1.8 or 26.1.2 and chat gpt wasn't of help either, whatever, if you can fix it go ahead
public class GuiUtility {
    public static ItemStack generateItem(ItemStack itemStack, String itemName, List<String> meta) {
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(meta);
        itemMeta.setDisplayName(itemName);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generateItem(ItemStack itemStack, String itemName, List<String> meta, Boolean hideFlag) {
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(meta);
        itemMeta.setDisplayName(itemName);
        if (hideFlag) itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public static ItemStack generatePotion(PotionType potionType, boolean isSplash, String itemName, List<String> lore, int count) {
        Material potionMaterial = isSplash ? Material.SPLASH_POTION : Material.POTION;
        ItemStack itemStack = new ItemStack(potionMaterial, count);

        ItemMeta meta = itemStack.getItemMeta();
        meta.setDisplayName(itemName);
        meta.setLore(lore);
        itemStack.setItemMeta(meta);

        return itemStack;
    }

    public static ItemStack generatePotion(PotionType potionType, boolean isSplash, String itemName, List<String> lore, boolean isHideFlag, int count) {
        Material potionMaterial = isSplash ? Material.SPLASH_POTION : Material.POTION;
        ItemStack itemStack = new ItemStack(potionMaterial, count);

        ItemMeta meta = itemStack.getItemMeta();
        meta.setDisplayName(itemName);
        meta.setLore(lore);

        if (isHideFlag) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }

        itemStack.setItemMeta(meta);
        return itemStack;
    }


    public static ItemStack generateItem(ItemStack itemStack, String itemName, List<String> meta, Boolean hideFlag, boolean glowing) {
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setLore(meta);
        itemMeta.setDisplayName(itemName);
        if (hideFlag) itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (glowing) {
            Enchantment glowEnchantment = firstEnchantment("SHARPNESS", "DAMAGE_ALL");
            if (glowEnchantment != null) {
                itemMeta.addEnchant(glowEnchantment, 1, true);
            }
            itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    private static final Map<UUID, MojangSkin> SKIN_CACHE = new ConcurrentHashMap<>();

    public static ItemStack generateSkull(ItemStack itemStack, String itemName, List<String> lore, UUID uuid) {
        if (itemStack == null || uuid == null) return itemStack;

        ItemMeta rawMeta = itemStack.getItemMeta();
        if (!(rawMeta instanceof SkullMeta)) return itemStack;

        SkullMeta skullMeta = (SkullMeta) rawMeta;
        skullMeta.setDisplayName(itemName);
        skullMeta.setLore(lore);

        boolean applied = false;
        MojangSkin skin = null;

        try {
            skin = fetchSkin(uuid);

            if (skin != null && skin.textureValue != null && !skin.textureValue.isEmpty()) {
                applied = applyGameProfileTexture(skullMeta, uuid, skin.profileName, skin.textureValue, skin.signature);
            }
        } catch (Throwable ignored) {
        }

        if (!applied) {
            try {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

                try {
                    Method setOwningPlayer = skullMeta.getClass().getMethod("setOwningPlayer", OfflinePlayer.class);
                    setOwningPlayer.invoke(skullMeta, offlinePlayer);
                    applied = true;
                } catch (Throwable ignored) {
                }

                if (!applied) {
                    String name = skin != null && skin.profileName != null ? skin.profileName : offlinePlayer.getName();

                    if (name != null && !name.isEmpty()) {
                        Method setOwner = skullMeta.getClass().getMethod("setOwner", String.class);
                        setOwner.invoke(skullMeta, name);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        itemStack.setItemMeta(skullMeta);
        return itemStack;
    }

    private static MojangSkin fetchSkin(UUID uuid) {
        MojangSkin cached = SKIN_CACHE.get(uuid);
        if (cached != null) return cached;

        HttpURLConnection connection = null;

        try {
            String cleanUuid = uuid.toString().replace("-", "");
            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + cleanUuid + "?unsigned=false");

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Arrow");

            if (connection.getResponseCode() != 200) {
                return null;
            }

            JsonElement element;

            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                element = new JsonParser().parse(reader);
            }

            if (element == null || !element.isJsonObject()) return null;

            JsonObject object = element.getAsJsonObject();
            String name = object.has("name") && !object.get("name").isJsonNull()
                    ? object.get("name").getAsString()
                    : null;

            if (!object.has("properties") || !object.get("properties").isJsonArray()) return null;

            JsonArray properties = object.getAsJsonArray("properties");

            for (JsonElement propertyElement : properties) {
                if (!propertyElement.isJsonObject()) continue;

                JsonObject property = propertyElement.getAsJsonObject();

                if (!property.has("name") || !"textures".equalsIgnoreCase(property.get("name").getAsString())) {
                    continue;
                }

                String value = property.has("value") && !property.get("value").isJsonNull()
                        ? property.get("value").getAsString()
                        : null;

                String signature = property.has("signature") && !property.get("signature").isJsonNull()
                        ? property.get("signature").getAsString()
                        : null;

                if (value == null || value.isEmpty()) return null;

                MojangSkin skin = new MojangSkin(name, value, signature);
                SKIN_CACHE.put(uuid, skin);
                return skin;
            }
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return null;
    }

    private static boolean applyGameProfileTexture(SkullMeta skullMeta, UUID uuid, String profileName, String textureValue, String signature) {
        if (skullMeta == null || uuid == null || textureValue == null || textureValue.isEmpty()) {
            return false;
        }

        String safeName = profileName;

        if (safeName == null || safeName.isEmpty()) {
            safeName = uuid.toString().replace("-", "").substring(0, 16);
        }

        if (safeName.length() > 16) {
            safeName = safeName.substring(0, 16);
        }

        if (applyModernBukkitProfile(skullMeta, uuid, safeName, textureValue, signature)) {
            return true;
        }

        return applyLegacyAuthlibProfile(skullMeta, uuid, safeName, textureValue, signature);
    }

    private static boolean applyLegacyAuthlibProfile(SkullMeta skullMeta, UUID uuid, String name, String textureValue, String signature) {
        try {
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");

            Object gameProfile = gameProfileClass
                    .getConstructor(UUID.class, String.class)
                    .newInstance(uuid, name);

            Object properties = gameProfileClass
                    .getMethod("getProperties")
                    .invoke(gameProfile);

            Object property;

            if (signature != null && !signature.isEmpty()) {
                property = propertyClass
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", textureValue, signature);
            } else {
                property = propertyClass
                        .getConstructor(String.class, String.class)
                        .newInstance("textures", textureValue);
            }

            properties.getClass()
                    .getMethod("put", Object.class, Object.class)
                    .invoke(properties, "textures", property);

            Field profileField = findProfileField(skullMeta.getClass());
            if (profileField == null) {
                return false;
            }

            profileField.setAccessible(true);
            profileField.set(skullMeta, gameProfile);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean applyModernBukkitProfile(SkullMeta skullMeta, UUID uuid, String name, String textureValue, String signature) {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Class<?> playerProfileClass = Class.forName("org.bukkit.profile.PlayerProfile");
            Class<?> profilePropertyClass = Class.forName("org.bukkit.profile.ProfileProperty");

            Object playerProfile;

            try {
                playerProfile = bukkitClass
                        .getMethod("createPlayerProfile", UUID.class, String.class)
                        .invoke(null, uuid, name);
            } catch (NoSuchMethodException ignored) {
                playerProfile = bukkitClass
                        .getMethod("createProfile", UUID.class, String.class)
                        .invoke(null, uuid, name);
            }

            Object property;

            if (signature != null && !signature.isEmpty()) {
                property = profilePropertyClass
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", textureValue, signature);
            } else {
                property = profilePropertyClass
                        .getConstructor(String.class, String.class)
                        .newInstance("textures", textureValue);
            }

            try {
                playerProfileClass
                        .getMethod("setProperty", profilePropertyClass)
                        .invoke(playerProfile, property);
            } catch (NoSuchMethodException ignored) {
                Object properties = playerProfileClass
                        .getMethod("getProperties")
                        .invoke(playerProfile);

                properties.getClass()
                        .getMethod("add", Object.class)
                        .invoke(properties, property);
            }

            skullMeta.getClass()
                    .getMethod("setOwnerProfile", playerProfileClass)
                    .invoke(skullMeta, playerProfile);

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findProfileField(Class<?> clazz) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField("profile");
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }

        return null;
    }
    private static final class MojangSkin {
        private final String profileName;
        private final String textureValue;
        private final String signature;

        private MojangSkin(String profileName, String textureValue, String signature) {
            this.profileName = profileName;
            this.textureValue = textureValue;
            this.signature = signature;
        }
    }


    public static ItemStack createSpacer() {
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();

        Material mat;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_13)) {
            mat = Material.matchMaterial("BLACK_STAINED_GLASS_PANE");
            if (mat == null) {
                mat = Material.matchMaterial("GRAY_STAINED_GLASS_PANE");
            }
        } else {
            mat = Material.matchMaterial("STAINED_GLASS_PANE");
        }

        if (mat == null) {
            mat = Material.GLASS_PANE;
        }

        ItemStack itemStack = new ItemStack(mat, 1);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    public static ItemStack createSpacer(byte color) {
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();

        Material mat;
        ItemStack itemStack;

        if (version.isNewerThanOrEquals(ServerVersion.V_1_13)) {
            mat = Material.matchMaterial("BLACK_STAINED_GLASS_PANE");
            if (mat == null) {
                mat = Material.matchMaterial("GRAY_STAINED_GLASS_PANE");
            }
            if (mat == null) {
                mat = Material.GLASS_PANE;
            }
            itemStack = new ItemStack(mat, 1);
        } else {
            mat = Material.matchMaterial("STAINED_GLASS_PANE");
            if (mat == null) {
                mat = Material.GLASS_PANE;
            }
            itemStack = new ItemStack(mat, 1, color);
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }



    public static ItemStack createPlayerHeadItem(ServerVersion serverVersion) {
        Material mat = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_13)
                ? Material.matchMaterial("PLAYER_HEAD")
                : Material.matchMaterial("SKULL_ITEM");

        if (mat == null) {
            mat = Material.BOOK;
        }

        return new ItemStack(mat, 1);
    }

    public static ItemStack createToggleWool(boolean enabled, ServerVersion serverVersion) {
        boolean modern = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_13);

        Material mat;
        if (modern) {
            mat = Material.matchMaterial(enabled ? "GREEN_WOOL" : "RED_WOOL");
            if (mat == null) {
                mat = Material.matchMaterial("WHITE_WOOL");
            }
        } else {
            mat = Material.matchMaterial("WOOL");
        }

        if (mat == null) {
            mat = Material.BARRIER;
        }

        ItemStack stack = new ItemStack(mat, 1);

        if (!modern && mat.name().equalsIgnoreCase("WOOL")) {
            short data = (short) (enabled ? 5 : 14);
            stack.setDurability(data);
        }

        return stack;
    }

    public static ItemStack createItem(String... materialNames) {
        Material material = firstMaterial(materialNames);

        if (material == null) {
            material = Material.BOOK;
        }

        return new ItemStack(material, 1);
    }

    public static Material firstMaterial(String... names) {
        if (names == null) return null;

        for (String name : names) {
            if (name == null || name.isEmpty()) continue;

            Material material = Material.matchMaterial(name);

            if (material == null) {
                try {
                    material = Material.getMaterial(name);
                } catch (Throwable ignored) {
                }
            }

            if (material != null) return material;
        }

        return null;
    }

    private static Enchantment firstEnchantment(String... names) {
        if (names == null) return null;

        for (String name : names) {
            Enchantment enchantment = Enchantment.getByName(name);
            if (enchantment != null) return enchantment;
        }

        return null;
    }

}
