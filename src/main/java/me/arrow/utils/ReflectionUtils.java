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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    // Safe Bukkit Attribute Reflection (1.8 - 1.21+)
    // ==========================================
    private static Class<?> ATTRIBUTE_CLASS;
    private static Method GET_ATTRIBUTE_METHOD;
    private static Method GET_VALUE_METHOD;
    private static Method GET_BASE_VALUE_METHOD;
    private static boolean ATTRIBUTE_INITIALIZED = false;
    private static final Map<String, Object> ATTRIBUTE_ENUM_CACHE = new HashMap<>();

    private static synchronized void initAttributes() {
        if (ATTRIBUTE_INITIALIZED) return;
        ATTRIBUTE_INITIALIZED = true;

        try {
            ATTRIBUTE_CLASS = Class.forName("org.bukkit.attribute.Attribute");
            GET_ATTRIBUTE_METHOD = Player.class.getMethod("getAttribute", ATTRIBUTE_CLASS);
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
        String upper = attributeName.toUpperCase();
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

                String cleanName = attributeName.toLowerCase().replace("generic_", "").replace('.', '_');
                String[] keyCandidates = new String[] {
                        cleanName,
                        "generic." + cleanName,
                        attributeName.toLowerCase().replace('_', '.')
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

        // Fallback for 1.8.8 NMS
        return getAttribute1_8(player, attributeName);
    }

    private static Object getAttribute1_8(Player player, String attributeName) {
        if (player == null) return null;
        try {
            Method getHandle = getMethod(player.getClass(), "getHandle");
            if (getHandle == null) return null;
            Object nmsPlayer = getHandle.invoke(player);
            if (nmsPlayer == null) return null;

            Method getAttributeMap = getMethod(nmsPlayer.getClass(), "getAttributeMap");
            if (getAttributeMap == null) return null;
            Object attributeMap = getAttributeMap.invoke(nmsPlayer);
            if (attributeMap == null) return null;

            Method aMethod = getMethod(attributeMap.getClass(), "a", String.class);
            if (aMethod == null) return null;

            String nmsName;
            String upper = attributeName.toUpperCase();
            if (upper.contains("SPEED") || upper.contains("MOVEMENT")) {
                nmsName = "generic.movementSpeed";
            } else if (upper.contains("HEALTH")) {
                nmsName = "generic.maxHealth";
            } else if (upper.contains("KNOCKBACK")) {
                nmsName = "generic.knockbackResistance";
            } else if (upper.contains("DAMAGE") || upper.contains("ATTACK")) {
                nmsName = "generic.attackDamage";
            } else if (upper.contains("FOLLOW")) {
                nmsName = "generic.followRange";
            } else {
                nmsName = attributeName;
            }

            return aMethod.invoke(attributeMap, nmsName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static double getAttributeValue(Player player, String attributeName, double defaultValue) {
        if (player == null) return defaultValue;

        if (!ATTRIBUTE_INITIALIZED) {
            initAttributes();
        }

        try {
            Object instance = getAttribute(player, attributeName);
            if (instance != null) {
                Method valMethod = GET_VALUE_METHOD;
                if (valMethod == null) {
                    valMethod = instance.getClass().getMethod("getValue");
                }
                Object val = valMethod.invoke(instance);
                if (val instanceof Number) {
                    double dVal = ((Number) val).doubleValue();
                    if (Double.isFinite(dVal)) {
                        return dVal;
                    }
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
                Method baseMethod = GET_BASE_VALUE_METHOD;
                if (baseMethod == null) {
                    try {
                        baseMethod = instance.getClass().getMethod("getBaseValue");
                    } catch (NoSuchMethodException e) {
                        try {
                            baseMethod = instance.getClass().getMethod("b");
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
                if (baseMethod != null) {
                    Object val = baseMethod.invoke(instance);
                    if (val instanceof Number) {
                        double dVal = ((Number) val).doubleValue();
                        if (Double.isFinite(dVal)) {
                            return dVal;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (attributeName.toUpperCase().contains("SPEED") || attributeName.toUpperCase().contains("MOVEMENT")) {
            return 0.1D;
        }

        return defaultValue;
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