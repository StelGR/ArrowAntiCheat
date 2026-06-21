package me.arrow.playerdata.processors.impl;

import me.arrow.playerdata.processors.Processor;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CollisionProcessor implements Processor {

    private static final double SCAN_RADIUS = 2.0D;

    /*
     * Slight epsilon because Minecraft can push entities away before their boxes
     * are perfectly inside each other.
     */
    private static final float INTERSECTION_EPSILON = 0.035F;

    private static final Method ENTITY_GET_BOUNDING_BOX = findMethod(Entity.class, "getBoundingBox");

    private static volatile Class<?> BUKKIT_BOX_CLASS;
    private static volatile Method BUKKIT_BOX_MIN_X;
    private static volatile Method BUKKIT_BOX_MIN_Y;
    private static volatile Method BUKKIT_BOX_MIN_Z;
    private static volatile Method BUKKIT_BOX_MAX_X;
    private static volatile Method BUKKIT_BOX_MAX_Y;
    private static volatile Method BUKKIT_BOX_MAX_Z;

    private static final Map<UUID, VirtualBox> VIRTUAL_NPCS = new ConcurrentHashMap<>();

    public static boolean isColliding(Player player, BoundingBox playerBox) {
        if (player == null || playerBox == null) {
            return false;
        }

        if (!canReadEntities(player)) {
            return false;
        }

        return liveNearbyCollision(player, playerBox)
                || virtualCollision(player, playerBox);
    }

    public static boolean isColliding(UUID playerId, BoundingBox playerBox) {
        if (playerId == null || playerBox == null) {
            return false;
        }

        Player player = Bukkit.getPlayer(playerId);

        if (player == null) {
            return false;
        }

        return isColliding(player, playerBox);
    }

    private static boolean liveNearbyCollision(Player player, BoundingBox playerBox) {
        if (player == null || playerBox == null) {
            return false;
        }

        if (!canReadEntities(player)) {
            return false;
        }

        UUID playerId;

        try {
            playerId = player.getUniqueId();
        } catch (Throwable ignored) {
            return false;
        }

        World playerWorld;

        try {
            playerWorld = player.getWorld();
        } catch (Throwable ignored) {
            return false;
        }

        if (playerWorld == null) {
            return false;
        }

        try {
            for (Entity entity : player.getNearbyEntities(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
                if (entity == null) {
                    continue;
                }

                if (!canReadEntity(entity)) {
                    continue;
                }

                UUID entityId;

                try {
                    entityId = entity.getUniqueId();
                } catch (Throwable ignored) {
                    continue;
                }

                if (playerId.equals(entityId)) {
                    continue;
                }

                if (!shouldTrack(entity)) {
                    continue;
                }

                Location location;

                try {
                    location = entity.getLocation();
                } catch (Throwable ignored) {
                    continue;
                }

                if (location == null || location.getWorld() == null) {
                    continue;
                }

                if (!location.getWorld().equals(playerWorld)) {
                    continue;
                }

                Box entityBox = getBoxForEntity(entity, location);

                if (entityBox == null) {
                    continue;
                }

                if (intersects(playerBox, entityBox)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean virtualCollision(Player player, BoundingBox playerBox) {
        if (player == null || playerBox == null) {
            return false;
        }

        if (!canReadEntities(player)) {
            return false;
        }

        World world;

        try {
            world = player.getWorld();
        } catch (Throwable ignored) {
            return false;
        }

        if (world == null) {
            return false;
        }

        UUID worldId = world.getUID();

        for (VirtualBox virtual : VIRTUAL_NPCS.values()) {
            if (virtual == null || !virtual.worldId.equals(worldId)) {
                continue;
            }

            if (intersects(playerBox, virtual.toBox())) {
                return true;
            }
        }

        return false;
    }

    private static boolean shouldTrack(Entity entity) {
        if (entity == null) {
            return false;
        }

        if (!canReadEntity(entity)) {
            return false;
        }

        try {
            if (entity.isDead()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        try {
            if (!entity.isValid()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        /*
         * Players and mobs.
         * ArmorStand is also LivingEntity, so it is included.
         */
        if (entity instanceof LivingEntity) {
            return true;
        }

        String type = getTypeName(entity);

        /*
         * Optional physical entities that can collide/push.
         */
        return type.contains("BOAT")
                || type.contains("RAFT")
                || type.contains("MINECART")
                || type.equals("INTERACTION");
    }

    private static Box getBoxForEntity(Entity entity, Location location) {
        if (entity == null || location == null) {
            return null;
        }

        if (!canReadEntity(entity)) {
            return null;
        }

        /*
         * Paper/modern Bukkit: exact live bounding box.
         * This is the accurate path.
         */
        Box exact = getBukkitBox(entity);

        if (exact != null && !isSuspiciouslyTiny(exact)) {
            return exact;
        }

        /*
         * Old Bukkit/Spigot fallback.
         */
        return fallbackBoxForEntity(entity, location);
    }

    private static Box getBukkitBox(Entity entity) {
        if (ENTITY_GET_BOUNDING_BOX == null || entity == null) {
            return null;
        }

        if (!canReadEntity(entity)) {
            return null;
        }

        try {
            Object bukkitBox = ENTITY_GET_BOUNDING_BOX.invoke(entity);

            if (bukkitBox == null) {
                return null;
            }

            initBukkitBoxMethods(bukkitBox);

            if (BUKKIT_BOX_MIN_X == null
                    || BUKKIT_BOX_MIN_Y == null
                    || BUKKIT_BOX_MIN_Z == null
                    || BUKKIT_BOX_MAX_X == null
                    || BUKKIT_BOX_MAX_Y == null
                    || BUKKIT_BOX_MAX_Z == null) {
                return null;
            }

            return new Box(
                    (float) getDouble(BUKKIT_BOX_MIN_X, bukkitBox),
                    (float) getDouble(BUKKIT_BOX_MIN_Y, bukkitBox),
                    (float) getDouble(BUKKIT_BOX_MIN_Z, bukkitBox),
                    (float) getDouble(BUKKIT_BOX_MAX_X, bukkitBox),
                    (float) getDouble(BUKKIT_BOX_MAX_Y, bukkitBox),
                    (float) getDouble(BUKKIT_BOX_MAX_Z, bukkitBox)
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void initBukkitBoxMethods(Object box) {
        Class<?> boxClass = box.getClass();

        if (BUKKIT_BOX_CLASS == boxClass) {
            return;
        }

        synchronized (CollisionProcessor.class) {
            if (BUKKIT_BOX_CLASS == boxClass) {
                return;
            }

            BUKKIT_BOX_MIN_X = findMethod(boxClass, "getMinX");
            BUKKIT_BOX_MIN_Y = findMethod(boxClass, "getMinY");
            BUKKIT_BOX_MIN_Z = findMethod(boxClass, "getMinZ");
            BUKKIT_BOX_MAX_X = findMethod(boxClass, "getMaxX");
            BUKKIT_BOX_MAX_Y = findMethod(boxClass, "getMaxY");
            BUKKIT_BOX_MAX_Z = findMethod(boxClass, "getMaxZ");

            BUKKIT_BOX_CLASS = boxClass;
        }
    }

    private static Box fallbackBoxForEntity(Entity entity, Location location) {
        if (entity == null || location == null) {
            return null;
        }

        String type = getTypeName(entity);

        double width = 0.6D;
        double height = 1.8D;

        if (type.equals("PLAYER")) {
            width = 0.6D;
            height = 1.8D;
        } else if (type.equals("ARMOR_STAND")) {
            if (isMarkerArmorStand(entity)) {
                return null;
            }

            width = 0.5D;
            height = 1.975D;
        } else if (type.equals("BAT")) {
            width = 0.5D;
            height = 0.9D;
        } else if (type.equals("BEE")) {
            width = 0.7D;
            height = 0.6D;
        } else if (type.equals("BLAZE")) {
            width = 0.6D;
            height = 1.8D;
        } else if (type.equals("BREEZE")) {
            width = 0.6D;
            height = 1.77D;
        } else if (type.equals("CAMEL")) {
            width = 1.7D;
            height = 2.375D;
        } else if (type.equals("CAT") || type.equals("OCELOT")) {
            width = 0.6D;
            height = 0.7D;
        } else if (type.equals("CAVE_SPIDER")) {
            width = 0.7D;
            height = 0.5D;
        } else if (type.equals("CHICKEN")) {
            width = 0.4D;
            height = 0.7D;
        } else if (type.equals("COD") || type.equals("SALMON") || type.equals("TROPICAL_FISH")) {
            width = 0.5D;
            height = 0.4D;
        } else if (type.equals("COW") || type.equals("MUSHROOM_COW") || type.equals("MOOSHROOM")) {
            width = 0.9D;
            height = 1.4D;
        } else if (type.equals("CREEPER")) {
            width = 0.6D;
            height = 1.7D;
        } else if (type.equals("DOLPHIN")) {
            width = 0.9D;
            height = 0.6D;
        } else if (type.equals("DONKEY")
                || type.equals("MULE")
                || type.equals("HORSE")
                || type.equals("SKELETON_HORSE")
                || type.equals("ZOMBIE_HORSE")) {
            width = 1.3965D;
            height = 1.6D;
        } else if (type.equals("ELDER_GUARDIAN")) {
            width = 1.9975D;
            height = 1.9975D;
        } else if (type.equals("ENDER_DRAGON")) {
            width = 16.0D;
            height = 8.0D;
        } else if (type.equals("ENDERMAN")) {
            width = 0.6D;
            height = 2.9D;
        } else if (type.equals("ENDERMITE") || type.equals("SILVERFISH")) {
            width = 0.4D;
            height = 0.3D;
        } else if (type.equals("EVOKER")
                || type.equals("ILLUSIONER")
                || type.equals("PILLAGER")
                || type.equals("VILLAGER")
                || type.equals("VINDICATOR")
                || type.equals("WANDERING_TRADER")
                || type.equals("WITCH")) {
            width = 0.6D;
            height = 1.95D;
        } else if (type.equals("FOX")) {
            width = 0.6D;
            height = 0.7D;
        } else if (type.equals("FROG")) {
            width = 0.5D;
            height = 0.5D;
        } else if (type.equals("GHAST") || type.equals("HAPPY_GHAST")) {
            width = 4.0D;
            height = 4.0D;
        } else if (type.equals("GIANT")) {
            width = 3.6D;
            height = 10.8D;
        } else if (type.equals("GOAT")) {
            width = 0.9D;
            height = 1.3D;
        } else if (type.equals("GUARDIAN")) {
            width = 0.85D;
            height = 0.85D;
        } else if (type.equals("HOGLIN") || type.equals("ZOGLIN")) {
            width = 1.3965D;
            height = 1.4D;
        } else if (type.equals("IRON_GOLEM")) {
            width = 1.4D;
            height = 2.7D;
        } else if (type.equals("LLAMA") || type.equals("TRADER_LLAMA")) {
            width = 0.9D;
            height = 1.87D;
        } else if (type.equals("PANDA") || type.equals("POLAR_BEAR")) {
            width = 1.3D;
            height = 1.25D;
        } else if (type.equals("PARROT")) {
            width = 0.5D;
            height = 0.9D;
        } else if (type.equals("PHANTOM")) {
            width = 0.9D;
            height = 0.5D;
        } else if (type.equals("PIG")) {
            width = 0.9D;
            height = 0.9D;
        } else if (type.equals("PIGLIN")
                || type.equals("PIGLIN_BRUTE")
                || type.equals("ZOMBIFIED_PIGLIN")) {
            width = 0.6D;
            height = 1.95D;
        } else if (type.equals("PUFFERFISH")) {
            width = 0.7D;
            height = 0.7D;
        } else if (type.equals("RABBIT")) {
            width = 0.4D;
            height = 0.5D;
        } else if (type.equals("RAVAGER")) {
            width = 1.95D;
            height = 2.2D;
        } else if (type.equals("SHEEP")) {
            width = 0.9D;
            height = 1.3D;
        } else if (type.equals("SHULKER")) {
            width = 1.0D;
            height = 1.0D;
        } else if (type.equals("SLIME") || type.equals("MAGMA_CUBE")) {
            int size = getSlimeSize(entity);
            width = Math.max(0.51D * size, 0.51D);
            height = Math.max(0.51D * size, 0.51D);
        } else if (type.equals("SNIFFER")) {
            width = 1.9D;
            height = 1.75D;
        } else if (type.equals("SNOWMAN") || type.equals("SNOW_GOLEM")) {
            width = 0.7D;
            height = 1.9D;
        } else if (type.equals("SPIDER")) {
            width = 1.4D;
            height = 0.9D;
        } else if (type.equals("SQUID") || type.equals("GLOW_SQUID")) {
            width = 0.8D;
            height = 0.8D;
        } else if (type.equals("STRIDER")) {
            width = 0.9D;
            height = 1.7D;
        } else if (type.equals("TADPOLE")) {
            width = 0.4D;
            height = 0.3D;
        } else if (type.equals("TURTLE")) {
            width = 1.2D;
            height = 0.4D;
        } else if (type.equals("VEX")) {
            width = 0.4D;
            height = 0.8D;
        } else if (type.equals("WARDEN")) {
            width = 0.9D;
            height = 2.9D;
        } else if (type.equals("WITHER")) {
            width = 0.9D;
            height = 3.5D;
        } else if (type.equals("WITHER_SKELETON")) {
            width = 0.7D;
            height = 2.4D;
        } else if (type.equals("WOLF")) {
            width = 0.6D;
            height = 0.85D;
        } else if (type.contains("MINECART")) {
            width = 0.98D;
            height = 0.7D;
        } else if (type.contains("BOAT") || type.contains("RAFT")) {
            width = 1.375D;
            height = 0.5625D;
        }

        if (width <= 0.0D || height <= 0.0D) {
            return null;
        }

        if (isBaby(entity)) {
            width *= 0.5D;
            height *= 0.5D;
        }

        float minX = (float) (location.getX() - width * 0.5D);
        float minY = (float) location.getY();
        float minZ = (float) (location.getZ() - width * 0.5D);
        float maxX = (float) (location.getX() + width * 0.5D);
        float maxY = (float) (location.getY() + height);
        float maxZ = (float) (location.getZ() + width * 0.5D);

        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean intersects(BoundingBox a, Box b) {
        if (a == null || b == null) {
            return false;
        }

        return b.maxX > a.minX - INTERSECTION_EPSILON
                && b.minX < a.maxX + INTERSECTION_EPSILON
                && b.maxY > a.minY - INTERSECTION_EPSILON
                && b.minY < a.maxY + INTERSECTION_EPSILON
                && b.maxZ > a.minZ - INTERSECTION_EPSILON
                && b.minZ < a.maxZ + INTERSECTION_EPSILON;
    }

    private static boolean canReadEntities(Player player) {
        if (player == null) {
            return false;
        }

        if (TaskUtils.isFoliaServer()) {
            return TaskUtils.isOwnedByCurrentRegion(player);
        }

        return Bukkit.isPrimaryThread();
    }

    private static boolean canReadEntity(Entity entity) {
        if (entity == null) {
            return false;
        }

        if (TaskUtils.isFoliaServer()) {
            return TaskUtils.isOwnedByCurrentRegion(entity);
        }

        return Bukkit.isPrimaryThread();
    }

    private static boolean isBaby(Entity entity) {
        if (entity == null || !canReadEntity(entity)) {
            return false;
        }

        try {
            if (entity instanceof Ageable) {
                return !((Ageable) entity).isAdult();
            }
        } catch (Throwable ignored) {
        }

        try {
            Method isAdult = entity.getClass().getMethod("isAdult");
            Object result = isAdult.invoke(entity);

            if (result instanceof Boolean) {
                return !((Boolean) result);
            }
        } catch (Throwable ignored) {
        }

        try {
            Method isBaby = entity.getClass().getMethod("isBaby");
            Object result = isBaby.invoke(entity);

            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean isMarkerArmorStand(Entity entity) {
        if (entity == null || !canReadEntity(entity)) {
            return false;
        }

        try {
            Method method = entity.getClass().getMethod("isMarker");
            Object result = method.invoke(entity);

            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int getSlimeSize(Entity entity) {
        if (entity == null || !canReadEntity(entity)) {
            return 1;
        }

        try {
            Method method = entity.getClass().getMethod("getSize");
            Object result = method.invoke(entity);

            if (result instanceof Number) {
                return Math.max(1, ((Number) result).intValue());
            }
        } catch (Throwable ignored) {
        }

        return 1;
    }

    private static boolean isSuspiciouslyTiny(Box box) {
        if (box == null) {
            return true;
        }

        float widthX = box.maxX - box.minX;
        float widthZ = box.maxZ - box.minZ;
        float height = box.maxY - box.minY;

        return widthX < 0.05F || widthZ < 0.05F || height < 0.05F;
    }

    private static String getTypeName(Entity entity) {
        if (entity == null || !canReadEntity(entity)) {
            return "";
        }

        try {
            return entity.getType().name().toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Method findMethod(Class<?> clazz, String name) {
        try {
            Method method = clazz.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double getDouble(Method method, Object object) throws Exception {
        Object result = method.invoke(object);

        if (result instanceof Number) {
            return ((Number) result).doubleValue();
        }

        return 0.0D;
    }

    public static void registerVirtualNpc(UUID uuid, World world, double x, double y, double z, double width, double height) {
        if (uuid == null || world == null || width <= 0.0D || height <= 0.0D) {
            return;
        }

        VIRTUAL_NPCS.put(uuid, new VirtualBox(
                uuid,
                world.getUID(),
                (float) (x - width * 0.5D),
                (float) y,
                (float) (z - width * 0.5D),
                (float) (x + width * 0.5D),
                (float) (y + height),
                (float) (z + width * 0.5D)
        ));
    }

    public static void unregisterVirtualNpc(UUID uuid) {
        if (uuid != null) {
            VIRTUAL_NPCS.remove(uuid);
        }
    }

    public static void clearVirtualNpcs() {
        VIRTUAL_NPCS.clear();
    }

    @Override
    public void process() {
    }

    private static final class Box {
        private final float minX;
        private final float minY;
        private final float minZ;
        private final float maxX;
        private final float maxY;
        private final float maxZ;

        private Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    private static final class VirtualBox {
        private final UUID uuid;
        private final UUID worldId;
        private final float minX;
        private final float minY;
        private final float minZ;
        private final float maxX;
        private final float maxY;
        private final float maxZ;

        private VirtualBox(UUID uuid, UUID worldId, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.uuid = uuid;
            this.worldId = worldId;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        private Box toBox() {
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}