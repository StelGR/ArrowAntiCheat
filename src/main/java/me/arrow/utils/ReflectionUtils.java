package me.arrow.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.arrow.Arrow;
import me.arrow.utils.custom.BoundingBox;
import me.arrow.utils.custom.exception.AnticheatException;
import me.arrow.utils.custom.materials.MaterialType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A reflection utility class that we'll be using for certain things
 * <p>
 * In this class we'll also use caching making reflection less heavy
 */
public class ReflectionUtils {

    private static final Map<String, Class<?>> NMS_CLASSES = new HashMap<>();
    private static final Map<String, Class<?>> OBC_CLASSES = new HashMap<>();
    private static final Map<Class<?>, Map<String, Method>> METHODS = new HashMap<>();
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new HashMap<>();
    private static String VERSION;

    public ReflectionUtils() {
    }

    public static void clear() {
        VERSION = null;
        NMS_CLASSES.clear();
        OBC_CLASSES.clear();
        METHODS.clear();
        FIELDS.clear();
        ATTRIBUTE_ENUM_CACHE.clear();
        LEGACY_ATTRIBUTE_CACHE.clear();
        ATTRIBUTE_CLASS = null;
        GET_ATTRIBUTE_METHOD = null;
        GET_VALUE_METHOD = null;
        GET_BASE_VALUE_METHOD = null;
        ATTRIBUTE_INITIALIZED = false;
    }

    public static String getVersion() {
        if (VERSION == null) {

            String name = Bukkit.getServer().getClass().getPackage().getName();

            VERSION = name.substring(name.lastIndexOf('.') + 1) + ".";
        }

        return VERSION;
    }

    public static Class<?> getNMSClass(final String nmsClassName) {
        if (NMS_CLASSES.containsKey(nmsClassName)) {

            return NMS_CLASSES.get(nmsClassName);
        }

        String clazzName = "net.minecraft.server." + getVersion() + nmsClassName;

        Class<?> clazz;

        try {

            clazz = Class.forName(clazzName);

        } catch (Throwable t) {

            t.printStackTrace();

            return NMS_CLASSES.put(nmsClassName, null);
        }

        NMS_CLASSES.put(nmsClassName, clazz);
        return clazz;
    }

    public static Class<?> getOBCClass(final String obcClassName) {
        if (OBC_CLASSES.containsKey(obcClassName)) {

            return OBC_CLASSES.get(obcClassName);
        }

        String clazzName = "org.bukkit.craftbukkit." + getVersion() + obcClassName;

        Class<?> clazz;

        try {

            clazz = Class.forName(clazzName);

        } catch (Throwable t) {

            t.printStackTrace();

            OBC_CLASSES.put(obcClassName, null);

            return null;
        }

        OBC_CLASSES.put(obcClassName, clazz);

        return clazz;
    }

    public static Object getConnection(final Player player) {

        Method getHandleMethod = getMethod(player.getClass(), "getHandle");

        if (getHandleMethod != null) {
            try {
                Object nmsPlayer = getHandleMethod.invoke(player);

                Field playerConField = getField(nmsPlayer.getClass(), "playerConnection");

                return playerConField.get(nmsPlayer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    public static void sendPacket(final Player player, final Object packet) {

        Object playerConnection = getConnection(player);

        Method sendPacketMethod = getMethod(playerConnection.getClass(), "sendPacket",
                getNMSClass("Packet"));

        if (sendPacketMethod != null) {
            try {
                sendPacketMethod.invoke(playerConnection, packet);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }

    public static Object craftEntity(final Entity entity) {
        try {
            return getMethod(getOBCClass("entity.CraftEntity"), "getHandle").invoke(entity);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        return null;
    }

    public static BoundingBox getBoundingBox(final Entity entity) {

        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_13)) {
            try {
                Object nmsBoundingBox = getMethod(getNMSClass("Entity"), "getBoundingBox")
                        .invoke(craftEntity(entity));

                return toBoundingBox(nmsBoundingBox);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        } else {
            return BoundingBox.fromBukkit(entity);
        }

        return null;
    }

    public static BoundingBox toBoundingBox(final Object aaBB) {

        final Vector min = getBoxMin(aaBB);
        final Vector max = getBoxMax(aaBB);

        return BoundingBox.of(min, max);
    }

    private static Vector getBoxMin(final Object box) {

        double x = 0D;
        double y = 0D;
        double z = 0D;

        Class<?> boxClass = box.getClass();

        try {
            if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_13)) {
                x = (double) getField(boxClass, "a").get(box);
                y = (double) getField(boxClass, "b").get(box);
                z = (double) getField(boxClass, "c").get(box);
            } else {
                x = (double) getField(boxClass, "minX").get(box);
                y = (double) getField(boxClass, "minY").get(box);
                z = (double) getField(boxClass, "minZ").get(box);
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        return new Vector(x, y, z);
    }

    private static Vector getBoxMax(final Object box) {

        double x = 0D;
        double y = 0D;
        double z = 0D;

        Class<?> boxClass = box.getClass();

        try {

            if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_13)) {
                x = (double) getField(boxClass, "d").get(box);
                y = (double) getField(boxClass, "e").get(box);
                z = (double) getField(boxClass, "f").get(box);
            } else {
                x = (double) getField(boxClass, "maxX").get(box);
                y = (double) getField(boxClass, "maxY").get(box);
                z = (double) getField(boxClass, "maxZ").get(box);
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        return new Vector(x, y, z);
    }

    public static Constructor<?> getConstructor(final Class<?> clazz, final Class<?>... params) {
        try {
            return clazz.getConstructor(params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public static Method getMethod(final Class<?> clazz, final String methodName, final Class<?>... params) {
        if (!METHODS.containsKey(clazz)) {
            METHODS.put(clazz, new HashMap<>());
        }

        Map<String, Method> methods = METHODS.get(clazz);

        if (methods.containsKey(methodName)) {
            return methods.get(methodName);
        }

        try {
            Method method = clazz.getMethod(methodName, params);

            methods.put(methodName, method);

            METHODS.put(clazz, methods);

            return method;
        } catch (Exception e) {

            e.printStackTrace();

            methods.put(methodName, null);

            METHODS.put(clazz, methods);

            throw new AnticheatException("Couldn't find method at class " + clazz.getSimpleName());
        }
    }

    public static Field getField(final Class<?> clazz, final String fieldName) {
        if (!FIELDS.containsKey(clazz)) {
            FIELDS.put(clazz, new HashMap<>());
        }

        Map<String, Field> fields = FIELDS.get(clazz);

        if (fields.containsKey(fieldName)) {
            return fields.get(fieldName);
        }

        try {
            Field field = clazz.getField(fieldName);
            fields.put(fieldName, field);
            FIELDS.put(clazz, fields);
            return field;
        } catch (Exception e) {
            e.printStackTrace();
            fields.put(fieldName, null);
            FIELDS.put(clazz, fields);
        }

        throw new AnticheatException("Couldn't find field at class " + clazz.getSimpleName());
    }

    public static Method findMethod(final Class<?> clazz, final int requires, final int excludes, final Class<?> result,
                                    final Class<?>... params) {
        int paramsCount = params.length;

        for (Method m : clazz.getMethods()) {

            if (m.getParameterCount() == paramsCount) {

                if (m.getReturnType() == result) {

                    int modifier = m.getModifiers();

                    if ((modifier & requires) == requires && (modifier & excludes) == 0) {

                        if (Arrays.equals(m.getParameterTypes(), params)) {
                            return m;
                        }
                    }
                }
            }
        }
        throw new AnticheatException("Couldn't find method at class " + clazz.getSimpleName());
    }



    public static String getPoseName(Player player) {
        try {
            Method getPose = player.getClass().getMethod("getPose");
            Object pose = getPose.invoke(player);

            if (pose != null) {
                return pose.toString().toUpperCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }

        return "STANDING";
    }

    public static boolean isSwimming(Player player) {
        try {
            Method isSwimming = player.getClass().getMethod("isSwimming");
            Object result = isSwimming.invoke(player);

            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isGliding(Player player) {
        if (player == null) return false;

        try {
            if (Arrow.getInstance().getNmsManager().getNmsInstance().isGliding(player)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Method isGliding = player.getClass().getMethod("isGliding");
            Object result = isGliding.invoke(player);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            String pose = getPoseName(player);
            if ("FALL_FLYING".equalsIgnoreCase(pose) || "GLIDING".equalsIgnoreCase(pose)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }


    public static boolean isWaterOrWaterlogged(Block block) {
        if (block == null) {
            return false;
        } else {
            block.getType();
        }

        Material material = block.getType();
        String name = material.name();

        if (MaterialType.isMaterial(name, MaterialType.WATER)) {
            return true;
        }

        if (isWaterPlantOrFluid(name)) {
            return true;
        }

        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
            try {
                Object blockData = block.getClass().getMethod("getBlockData").invoke(block);

                if (blockData == null) {
                    return false;
                }

                try {
                    Object value = blockData.getClass().getMethod("isWaterlogged").invoke(blockData);

                    if (value instanceof Boolean) {
                        return (Boolean) value;
                    }
                } catch (NoSuchMethodException ignored) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }

        return false;
    }

    private static boolean isWaterPlantOrFluid(String name) {
        if (name == null) {
            return false;
        }

        return name.equals("KELP")
                || name.equals("KELP_PLANT")
                || name.equals("SEAGRASS")
                || name.equals("TALL_SEAGRASS")
                || name.equals("BUBBLE_COLUMN")
                || name.equals("WATER_CAULDRON")
                || name.equals("LEGACY_STATIONARY_WATER")
                || name.equals("LEGACY_WATER");
    }

    // ==========================================
    // Safe Bukkit/NMS attribute bridge (1.7 - current)
    // ==========================================
    private static Class<?> ATTRIBUTE_CLASS;
    private static Method GET_ATTRIBUTE_METHOD;
    private static Method GET_VALUE_METHOD;
    private static Method GET_BASE_VALUE_METHOD;
    private static boolean ATTRIBUTE_INITIALIZED = false;
    /*
     * Attribute was added to Bukkit in 1.9 and changed from an enum to a
     * registry-backed type in newer releases.  Keep the Bukkit lookup separate
     * from the legacy NMS lookup: a failed modern lookup must never poison the
     * 1.7/1.8 fallback cache.
     */
    private static final Map<String, Object> ATTRIBUTE_ENUM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Object> LEGACY_ATTRIBUTE_CACHE = new ConcurrentHashMap<>();

    private static synchronized void initAttributes() {
        if (ATTRIBUTE_INITIALIZED) return;
        ATTRIBUTE_INITIALIZED = true;

        try {
            ATTRIBUTE_CLASS = Class.forName("org.bukkit.attribute.Attribute");
            GET_ATTRIBUTE_METHOD = findCompatibleMethod(Player.class, "getAttribute", ATTRIBUTE_CLASS);
            Class<?> instanceClass = Class.forName("org.bukkit.attribute.AttributeInstance");
            GET_VALUE_METHOD = instanceClass.getMethod("getValue");
            try {
                GET_BASE_VALUE_METHOD = instanceClass.getMethod("getBaseValue");
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveAttribute(String attributeName) {
        if (ATTRIBUTE_CLASS == null) return null;

        List<String> candidates = new ArrayList<>();
        candidates.add(attributeName);
        if (!attributeName.startsWith("GENERIC_")) {
            candidates.add("GENERIC_" + attributeName);
        } else {
            candidates.add(attributeName.substring("GENERIC_".length()));
        }
        String upper = attributeName.toUpperCase(Locale.ROOT);
        if (!candidates.contains(upper)) candidates.add(upper);
        if (!upper.startsWith("GENERIC_")) {
            String genUpper = "GENERIC_" + upper;
            if (!candidates.contains(genUpper)) candidates.add(genUpper);
        } else {
            String noGen = upper.substring("GENERIC_".length());
            if (!candidates.contains(noGen)) candidates.add(noGen);
        }

        // 1. Check if ATTRIBUTE_CLASS is a Java Enum (1.9 - 1.20.4)
        if (ATTRIBUTE_CLASS.isEnum()) {
            for (String cand : candidates) {
                try {
                    Object res = Enum.valueOf((Class<Enum>) ATTRIBUTE_CLASS, cand);
                    if (res != null) return res;
                } catch (Throwable ignored) {}
            }
        }

        // 2. Static valueOf(String) method (available on modern 1.21+ Attribute interface)
        try {
            Method valueOfMethod = ATTRIBUTE_CLASS.getMethod("valueOf", String.class);
            for (String cand : candidates) {
                try {
                    Object res = valueOfMethod.invoke(null, cand);
                    if (res != null) return res;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 3. Static public field on ATTRIBUTE_CLASS (e.g. Attribute.MOVEMENT_SPEED, Attribute.GENERIC_MOVEMENT_SPEED)
        for (String cand : candidates) {
            try {
                Field f = ATTRIBUTE_CLASS.getField(cand);
                if (Modifier.isStatic(f.getModifiers())) {
                    Object res = f.get(null);
                    if (res != null) return res;
                }
            } catch (Throwable ignored) {}
        }

        // 4. Case-insensitive search across all public static fields of ATTRIBUTE_CLASS
        try {
            for (Field f : ATTRIBUTE_CLASS.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) && ATTRIBUTE_CLASS.isAssignableFrom(f.getType())) {
                    for (String cand : candidates) {
                        if (f.getName().equalsIgnoreCase(cand)) {
                            try {
                                Object res = f.get(null);
                                if (res != null) return res;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 5. Registry lookup via org.bukkit.Registry.ATTRIBUTE (Paper / Spigot 1.20.5+ / 1.21+)
        try {
            Class<?> registryClass = Class.forName("org.bukkit.Registry");
            Field attrRegistryField = registryClass.getField("ATTRIBUTE");
            Object registry = attrRegistryField.get(null);
            if (registry != null) {
                Method getMethod = registry.getClass().getMethod("get", Class.forName("org.bukkit.NamespacedKey"));
                Class<?> keyClass = Class.forName("org.bukkit.NamespacedKey");
                Method minecraftKeyMethod = keyClass.getMethod("minecraft", String.class);

                String cleanName = registryAttributeKey(attributeName);
                String[] keyCandidates = new String[] {
                        cleanName,
                        "generic." + cleanName,
                        attributeName.toLowerCase(Locale.ROOT).replace('_', '.')
                };
                for (String keyStr : keyCandidates) {
                    try {
                        Object key = minecraftKeyMethod.invoke(null, keyStr);
                        Object res = getMethod.invoke(registry, key);
                        if (res != null) return res;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static Object getAttribute(Player player, String attributeName) {
        if (player == null) return null;

        if (!ATTRIBUTE_INITIALIZED) {
            initAttributes();
        }

        try {
            if (ATTRIBUTE_CLASS != null && GET_ATTRIBUTE_METHOD != null) {
                Object attributeObj = ATTRIBUTE_ENUM_CACHE.computeIfAbsent(attributeName, ReflectionUtils::resolveAttribute);
                if (attributeObj != null) {
                    Object instance = GET_ATTRIBUTE_METHOD.invoke(player, attributeObj);
                    if (instance != null) {
                        return instance;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Bukkit did not expose attributes before 1.9. Use the live NMS
        // attribute map on 1.7/1.8, and retain it as a recovery path for forks
        // whose Bukkit attribute registry is incomplete.
        return getLegacyAttribute(player, attributeName);
    }

    private static Object getLegacyAttribute(Player player, String attributeName) {
        if (player == null || attributeName == null) return null;

        try {
            Method getHandle = findCompatibleMethod(player.getClass(), "getHandle");
            if (getHandle == null) return null;

            Object nmsPlayer = getHandle.invoke(player);
            if (nmsPlayer == null) return null;

            Object attributeMap = invokeNoArg(nmsPlayer, "getAttributeMap", "aV", "eE");
            String nmsName = nmsAttributeName(attributeName);

            /* 1.7/1.8 AttributeMapBase exposes a(String) on many mappings. */
            Object byName = invokeSingleString(attributeMap, nmsName);
            if (byName != null) return byName;

            Object nmsAttribute = LEGACY_ATTRIBUTE_CACHE.computeIfAbsent(
                    canonicalAttributeName(attributeName),
                    ignored -> resolveLegacyNmsAttribute(nmsPlayer, attributeName)
            );
            if (nmsAttribute == null) return null;

            Object instance = invokeAttributeLookup(nmsPlayer, nmsAttribute);
            return instance != null ? instance : invokeAttributeLookup(attributeMap, nmsAttribute);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolveLegacyNmsAttribute(Object nmsPlayer, String requestedName) {
        String[] classNames = {
                "net.minecraft.server." + legacyCraftVersion() + "GenericAttributes",
                "net.minecraft.world.entity.ai.attributes.Attributes"
        };

        for (String className : classNames) {
            if (className.contains("..")) continue;

            try {
                Class<?> attributes = Class.forName(className);
                Object resolved = findStaticAttribute(attributes, requestedName);
                if (resolved != null) return resolved;
            } catch (Throwable ignored) {
            }
        }

        /* Some hybrid 1.7/1.8 servers expose GenericAttributes through the
         * player's class loader but use a relocated NMS package. */
        for (Class<?> type = nmsPlayer.getClass(); type != null; type = type.getSuperclass()) {
            Package pkg = type.getPackage();
            if (pkg == null) continue;

            try {
                Class<?> attributes = Class.forName(pkg.getName().replace(".server.level", "") + ".GenericAttributes");
                Object resolved = findStaticAttribute(attributes, requestedName);
                if (resolved != null) return resolved;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static Object findStaticAttribute(Class<?> attributes, String requestedName) {
        String expected = canonicalAttributeName(requestedName);

        for (Field field : attributes.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;

            try {
                field.setAccessible(true);
                Object candidate = field.get(null);
                if (candidate == null) continue;

                String name = getNmsAttributeName(candidate);
                if (expected.equals(canonicalAttributeName(name))
                        || expected.equals(canonicalAttributeName(field.getName()))) {
                    return candidate;
                }
            } catch (Throwable ignored) {
            }
        }

        /* Obfuscated 1.7/1.8 GenericAttributes field names. These are only a
         * final fallback after the self-describing attribute-name scan above. */
        String fallbackField = legacyAttributeField(expected);
        if (fallbackField == null) return null;

        try {
            Field field = attributes.getDeclaredField(fallbackField);
            if (!Modifier.isStatic(field.getModifiers())) return null;
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeAttributeLookup(Object owner, Object attribute) {
        if (owner == null || attribute == null) return null;

        for (String name : new String[]{"getAttributeInstance", "a"}) {
            for (Method method : allInstanceMethods(owner.getClass(), name, 1)) {
                Class<?> parameter = method.getParameterTypes()[0];
                if (!parameter.isAssignableFrom(attribute.getClass())) continue;

                try {
                    method.setAccessible(true);
                    Object result = method.invoke(owner, attribute);
                    if (result != null) return result;
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    private static Object invokeSingleString(Object owner, String value) {
        if (owner == null || value == null) return null;

        for (String name : new String[]{"getAttributeInstance", "a"}) {
            for (Method method : allInstanceMethods(owner.getClass(), name, 1)) {
                if (method.getParameterTypes()[0] != String.class) continue;

                try {
                    method.setAccessible(true);
                    Object result = method.invoke(owner, value);
                    if (result != null) return result;
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    private static Object invokeNoArg(Object owner, String... names) {
        if (owner == null) return null;

        for (String name : names) {
            Method method = findCompatibleMethod(owner.getClass(), name);
            if (method == null) continue;

            try {
                Object result = method.invoke(owner);
                if (result != null) return result;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static String getNmsAttributeName(Object attribute) {
        if (attribute == null) return null;

        for (String name : new String[]{"getName", "a", "c"}) {
            Method method = findCompatibleMethod(attribute.getClass(), name);
            if (method == null || method.getReturnType() != String.class) continue;

            try {
                Object value = method.invoke(attribute);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static String legacyCraftVersion() {
        try {
            String packageName = Bukkit.getServer().getClass().getPackage().getName();
            String suffix = packageName.substring(packageName.lastIndexOf('.') + 1);
            return suffix.startsWith("v1_") ? suffix + "." : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String legacyAttributeField(String canonicalName) {
        if ("maxhealth".equals(canonicalName)) return "a";
        if ("followrange".equals(canonicalName)) return "b";
        if ("knockbackresistance".equals(canonicalName)) return "c";
        if ("movementspeed".equals(canonicalName)) return "d";
        if ("attackdamage".equals(canonicalName)) return "e";
        return null;
    }

    private static String nmsAttributeName(String attributeName) {
        String canonical = canonicalAttributeName(attributeName);
        if ("movementspeed".equals(canonical)) return "generic.movementSpeed";
        if ("maxhealth".equals(canonical)) return "generic.maxHealth";
        if ("knockbackresistance".equals(canonical)) return "generic.knockbackResistance";
        if ("attackdamage".equals(canonical)) return "generic.attackDamage";
        if ("followrange".equals(canonical)) return "generic.followRange";
        return attributeName;
    }

    private static String canonicalAttributeName(String name) {
        if (name == null) return "";

        return name.toLowerCase(Locale.ROOT)
                .replace("generic", "")
                .replaceAll("[^a-z0-9]", "");
    }

    /** Bukkit's modern registry uses snake_case keys, while both the legacy
     * NMS names and Bukkit's historical enum use generic.movementSpeed /
     * GENERIC_MOVEMENT_SPEED. */
    private static String registryAttributeKey(String name) {
        String canonical = canonicalAttributeName(name);
        if ("movementspeed".equals(canonical)) return "movement_speed";
        if ("maxhealth".equals(canonical)) return "max_health";
        if ("followrange".equals(canonical)) return "follow_range";
        if ("knockbackresistance".equals(canonical)) return "knockback_resistance";
        if ("attackdamage".equals(canonical)) return "attack_damage";
        if ("attackspeed".equals(canonical)) return "attack_speed";
        if ("armortoughness".equals(canonical)) return "armor_toughness";
        if ("luck".equals(canonical)) return "luck";
        if ("flyingspeed".equals(canonical)) return "flying_speed";
        if ("scale".equals(canonical)) return "scale";
        if ("gravity".equals(canonical)) return "gravity";
        if ("safefalldistance".equals(canonical)) return "safe_fall_distance";
        if ("stepheight".equals(canonical)) return "step_height";
        if ("jumpstrength".equals(canonical)) return "jump_strength";

        String key = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replace("generic_", "")
                .replace("generic.", "")
                .replace('.', '_');
        return key.isEmpty() ? canonical : key;
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?>... parameters) {
        if (type == null) return null;

        for (Method method : allInstanceMethods(type, name, parameters.length)) {
            Class<?>[] actual = method.getParameterTypes();
            boolean matches = true;

            for (int i = 0; i < actual.length; i++) {
                if (!actual[i].isAssignableFrom(parameters[i])) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return method;
            }
        }

        return null;
    }

    private static List<Method> allInstanceMethods(Class<?> type, String name, int parameterCount) {
        List<Method> methods = new ArrayList<>();

        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    methods.add(method);
                }
            }
        }

        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount
                    && !methods.contains(method)) {
                methods.add(method);
            }
        }

        return methods;
    }

    public static double getAttributeValue(Player player, String attributeName, double defaultValue) {
        if (player == null) return defaultValue;

        if (!ATTRIBUTE_INITIALIZED) {
            initAttributes();
        }

        try {
            Object instance = getAttribute(player, attributeName);
            if (instance != null) {
                Double value = readAttributeNumber(instance, false);
                if (value != null) {
                    return value;
                }
            }
        } catch (Throwable ignored) {}

        // If it's movement speed and instance wasn't found, fallback to walkSpeed / 2.0
        if (attributeName.toUpperCase().contains("SPEED") || attributeName.toUpperCase().contains("MOVEMENT")) {
            try {
                double walkSpeedVal = player.getWalkSpeed() / 2.0D;
                if (walkSpeedVal > 0.0D && Double.isFinite(walkSpeedVal)) {
                    return walkSpeedVal;
                }
            } catch (Throwable ignored) {}
        }

        return defaultValue;
    }

    public static double getAttributeBaseValue(Player player, String attributeName, double defaultValue) {
        if (player == null) return defaultValue;

        if (!ATTRIBUTE_INITIALIZED) {
            initAttributes();
        }

        try {
            Object instance = getAttribute(player, attributeName);
            if (instance != null) {
                Double value = readAttributeNumber(instance, true);
                if (value != null) {
                    return value;
                }
            }
        } catch (Throwable ignored) {}

        if (attributeName.toUpperCase().contains("SPEED") || attributeName.toUpperCase().contains("MOVEMENT")) {
            return 0.1D;
        }

        return defaultValue;
    }

    /**
     * Bukkit instances expose getValue/getBaseValue. Their 1.7/1.8 NMS
     * equivalents are normally e()/b(); retaining both makes callers such as
     * movement prediction work on legacy servers without special branches.
     */
    private static Double readAttributeNumber(Object instance, boolean baseValue) {
        if (instance == null) return null;

        Method preferred = baseValue ? GET_BASE_VALUE_METHOD : GET_VALUE_METHOD;
        if (preferred != null && preferred.getDeclaringClass().isInstance(instance)) {
            Double value = invokeFiniteNumber(preferred, instance);
            if (value != null) return value;
        }

        String[] names = baseValue
                ? new String[]{"getBaseValue", "b"}
                : new String[]{"getValue", "e"};
        for (String name : names) {
            Method method = findCompatibleMethod(instance.getClass(), name);
            Double value = invokeFiniteNumber(method, instance);
            if (value != null) return value;
        }

        return null;
    }

    private static Double invokeFiniteNumber(Method method, Object instance) {
        if (method == null) return null;

        try {
            Object value = method.invoke(instance);
            if (value instanceof Number) {
                double result = ((Number) value).doubleValue();
                return Double.isFinite(result) ? result : null;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    public static double getPlayerMovementSpeed(Player player) {
        if (player == null) return 0.1D;
        double attrVal = getAttributeValue(player, "MOVEMENT_SPEED", -1.0D);
        double walkSpeedVal = player.getWalkSpeed() / 2.0D;

        if (attrVal > 0.0D && Double.isFinite(attrVal)) {
            float ws = player.getWalkSpeed();
            if (Math.abs(ws - 0.2f) > 0.001f && ws > 0.0f) {
                double scale = ws / 0.2f;
                return attrVal * scale;
            }
            return attrVal;
        }

        if (walkSpeedVal > 0.0D && Double.isFinite(walkSpeedVal)) {
            return walkSpeedVal;
        }

        return 0.1D;
    }

    /**
     * Returns the movement-speed attribute with Minecraft's sprint modifier
     * removed.  Prediction must enumerate the possible sprint states itself;
     * using AttributeInstance#getValue directly would apply sprint once here
     * and once again in the simulation.
     *
     * Bukkit exposes the modifier list on modern servers.  The reflective
     * path also handles the corresponding legacy NMS accessors.  If a fork
     * does not expose modifiers, use the effective value and remove the
     * currently applied sprint multiplier as a conservative fallback.
     */
    public static double getPlayerMovementSpeedWithoutSprint(Player player) {
        if (player == null) return 0.1D;

        final double walkSpeedFallback = safeWalkSpeed(player);
        final Object instance = getAttribute(player, "MOVEMENT_SPEED");
        final Double baseValue = instance == null ? null : readAttributeNumber(instance, true);

        if (baseValue != null && baseValue > 0.0D) {
            Double modified = getMovementSpeedWithoutSprintModifiers(instance, baseValue);
            if (modified != null && modified > 0.0D && Double.isFinite(modified)) {
                return modified;
            }

            // A base value is still preferable to an effective value that is
            // known to contain the sprint modifier.  This is the normal
            // 1.7/1.8 fallback when the modifier collection is obfuscated.
            return baseValue;
        }

        double effective = getAttributeValue(player, "MOVEMENT_SPEED", walkSpeedFallback);
        if (effective > 0.0D && Double.isFinite(effective)) {
            try {
                if (player.isSprinting()) {
                    effective /= 1.3D;
                }
            } catch (Throwable ignored) {
            }
            return effective;
        }

        return walkSpeedFallback;
    }

    private static double safeWalkSpeed(Player player) {
        try {
            double walkSpeed = player.getWalkSpeed() / 2.0D;
            if (walkSpeed > 0.0D && Double.isFinite(walkSpeed)) {
                return walkSpeed;
            }
        } catch (Throwable ignored) {
        }
        return 0.1D;
    }

    private static Double getMovementSpeedWithoutSprintModifiers(Object instance, double baseValue) {
        Object modifiersObject = invokeNoArg(instance, "getModifiers", "c", "a");
        if (!(modifiersObject instanceof Collection<?> modifiers)) {
            return null;
        }

        double additive = 0.0D;
        double multiplyBase = 0.0D;
        double multiplyTotal = 1.0D;

        for (Object modifier : modifiers) {
            if (modifier == null || isSprintingSpeedModifier(modifier)) {
                continue;
            }

            Double amount = readModifierAmount(modifier);
            int operation = readModifierOperation(modifier);
            if (amount == null || !Double.isFinite(amount) || operation < 0) {
                continue;
            }

            switch (operation) {
                case 0 -> additive += amount;
                case 1 -> multiplyBase += amount;
                case 2 -> multiplyTotal *= 1.0D + amount;
                default -> {
                }
            }
        }

        return (baseValue + additive) * (1.0D + multiplyBase) * multiplyTotal;
    }

    private static Double readModifierAmount(Object modifier) {
        // 1.7/1.8 AttributeModifier uses c() for the amount and d()
        // for the operation; keep those positions distinct.
        for (String name : new String[]{"getAmount", "c"}) {
            Method method = findCompatibleMethod(modifier.getClass(), name);
            Double value = invokeFiniteNumber(method, modifier);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int readModifierOperation(Object modifier) {
        Object operation = invokeNoArg(modifier, "getOperation", "d");
        if (operation instanceof Number number) {
            int value = number.intValue();
            return value >= 0 && value <= 2 ? value : -1;
        }
        if (operation instanceof Enum<?> operationEnum) {
            return modifierOperationFromName(operationEnum.name(), operationEnum.ordinal());
        }
        if (operation != null) {
            return modifierOperationFromName(String.valueOf(operation), -1);
        }
        return -1;
    }

    private static int modifierOperationFromName(String name, int fallback) {
        String normalized = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (normalized.contains("ADD_NUMBER") || normalized.equals("ADDITION")) return 0;
        if (normalized.contains("ADD_SCALAR") || normalized.contains("MULTIPLY_BASE")) return 1;
        if (normalized.contains("MULTIPLY_SCALAR_1") || normalized.contains("MULTIPLY_TOTAL")) return 2;
        return fallback >= 0 && fallback <= 2 ? fallback : -1;
    }

    private static boolean isSprintingSpeedModifier(Object modifier) {
        final UUID sprintingUuid = UUID.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D");
        Object id = invokeNoArg(modifier, "getUniqueId", "getUUID", "a");
        if (sprintingUuid.equals(id)) {
            return true;
        }

        for (String name : new String[]{"getName", "getKey", "b"}) {
            Object value = invokeNoArg(modifier, name);
            if (value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains("sprint")) {
                return true;
            }
        }
        return false;
    }

    public static double getExtraReachModifier(Object attributeInstance, String modifierName) {
        if (attributeInstance == null) return 0.0D;
        try {
            Method getModifiersMethod = attributeInstance.getClass().getMethod("getModifiers");
            Object modifiers = getModifiersMethod.invoke(attributeInstance);
            if (modifiers instanceof java.util.Collection) {
                for (Object mod : (java.util.Collection<?>) modifiers) {
                    String name = null;
                    try {
                        Method getNameMethod = mod.getClass().getMethod("getName");
                        name = (String) getNameMethod.invoke(mod);
                    } catch (Throwable ignored) {}

                    String key = null;
                    try {
                        Method getKeyMethod = mod.getClass().getMethod("getKey");
                        Object keyObj = getKeyMethod.invoke(mod);
                        if (keyObj != null) key = keyObj.toString();
                    } catch (Throwable ignored) {}

                    if ((name != null && modifierName.equalsIgnoreCase(name))
                            || (key != null && modifierName.equalsIgnoreCase(key))) {
                        Method getAmountMethod = mod.getClass().getMethod("getAmount");
                        return ((Number) getAmountMethod.invoke(mod)).doubleValue();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return 0.0D;
    }
}
