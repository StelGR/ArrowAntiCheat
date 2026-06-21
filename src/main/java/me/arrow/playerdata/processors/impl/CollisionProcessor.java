package me.arrow.playerdata.processors.impl;

import me.arrow.playerdata.processors.Processor;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CollisionProcessor implements Processor {

    private static final double SCAN_RADIUS = 2.0D;

    private static final float HORIZONTAL_EPSILON = 0.075F;
    private static final float VERTICAL_EPSILON = 0.10F;

    private static final int MAX_CACHE_AGE_TICKS = 3;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private static volatile int tick;

    private static final Method ENTITY_GET_BOUNDING_BOX = findMethod(Entity.class, "getBoundingBox");

    private static volatile Class<?> BUKKIT_BOX_CLASS;
    private static volatile Method BUKKIT_BOX_MIN_X;
    private static volatile Method BUKKIT_BOX_MIN_Y;
    private static volatile Method BUKKIT_BOX_MIN_Z;
    private static volatile Method BUKKIT_BOX_MAX_X;
    private static volatile Method BUKKIT_BOX_MAX_Y;
    private static volatile Method BUKKIT_BOX_MAX_Z;

    /*
     * Call this once in onEnable.
     * It is also safe if called more than once.
     */
    public static void start() {
        if (STARTED.get()) {
            return;
        }

        if (TaskUtils.isFoliaServer()) {
            STARTED.set(true);
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            TaskUtils.task(CollisionProcessor::start);
            return;
        }

        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        TaskUtils.taskTimer(CollisionProcessor::tickCache, 1L, 1L);
    }

    /*
     * Safe to call from packet thread.
     * This does NOT touch Bukkit entity/world lookup.
     */
    public static boolean isColliding(Player player, BoundingBox playerBox) {
        if (player == null || playerBox == null) {
            return false;
        }

        start();

        UUID playerId;

        try {
            playerId = player.getUniqueId();
        } catch (Throwable ignored) {
            return false;
        }

        return isColliding(playerId, playerBox);
    }

    /*
     * Best packet-thread path. Prefer this from MovementData.
     */
    public static boolean isColliding(UUID playerId, BoundingBox playerBox) {
        if (playerId == null || playerBox == null) {
            return false;
        }

        start();

        CacheEntry entry = CACHE.get(playerId);

        if (entry == null) {
            return false;
        }

        int age = tick - entry.tick;

        if (age < 0 || age > MAX_CACHE_AGE_TICKS) {
            return false;
        }

        for (Box entityBox : entry.boxes) {
            if (entityBox != null && intersects(playerBox, entityBox)) {
                return true;
            }
        }

        return false;
    }

    /*
     * Main-thread only.
     */
    private static void tickCache() {
        if (TaskUtils.isFoliaServer()) {
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            return;
        }

        tick++;

        Set<UUID> seenPlayers = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }

            UUID playerId;

            try {
                playerId = player.getUniqueId();
            } catch (Throwable ignored) {
                continue;
            }

            seenPlayers.add(playerId);

            List<Box> boxes = scanNearbyEntityBoxes(player, playerId);

            CACHE.put(playerId, new CacheEntry(tick, boxes));
        }

        CACHE.keySet().removeIf(uuid -> !seenPlayers.contains(uuid));
    }

    /*
     * Main-thread only.
     */
    private static List<Box> scanNearbyEntityBoxes(Player player, UUID playerId) {
        if (player == null || playerId == null) {
            return Collections.emptyList();
        }

        World world;
        Location location;

        try {
            world = player.getWorld();
            location = player.getLocation();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }

        if (world == null || location == null) {
            return Collections.emptyList();
        }

        Collection<Entity> nearby;

        try {
            nearby = world.getNearbyEntities(
                    location,
                    SCAN_RADIUS,
                    SCAN_RADIUS,
                    SCAN_RADIUS
            );
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }

        if (nearby == null || nearby.isEmpty()) {
            return Collections.emptyList();
        }

        List<Box> boxes = new ArrayList<>(nearby.size());

        for (Entity entity : nearby) {
            if (entity == null) {
                continue;
            }

            try {
                if (playerId.equals(entity.getUniqueId())) {
                    continue;
                }

                if (entity.isDead() || !entity.isValid()) {
                    continue;
                }

                Location entityLocation = entity.getLocation();

                if (entityLocation == null
                        || entityLocation.getWorld() == null
                        || !entityLocation.getWorld().equals(world)) {
                    continue;
                }

                Box box = getBoxForEntity(entity);

                if (box == null || isSuspiciouslyTiny(box)) {
                    continue;
                }

                boxes.add(box);
            } catch (Throwable ignored) {
            }
        }

        return boxes.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(boxes);
    }

    /*
     * Main-thread only.
     */
    private static Box getBoxForEntity(Entity entity) {
        if (entity == null) {
            return null;
        }

        Box exact = getBukkitBox(entity);

        if (exact != null) {
            return exact;
        }

        /*
         * Fallback. Mostly irrelevant on modern Paper/Purpur, because getBoundingBox exists.
         */
        try {
            Location location = entity.getLocation();

            if (location == null) {
                return null;
            }

            double width = 0.6D;
            double height = 1.8D;

            float minX = (float) (location.getX() - width * 0.5D);
            float minY = (float) location.getY();
            float minZ = (float) (location.getZ() - width * 0.5D);
            float maxX = (float) (location.getX() + width * 0.5D);
            float maxY = (float) (location.getY() + height);
            float maxZ = (float) (location.getZ() + width * 0.5D);

            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /*
     * Main-thread only.
     */
    private static Box getBukkitBox(Entity entity) {
        if (ENTITY_GET_BOUNDING_BOX == null || entity == null) {
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

    private static boolean intersects(BoundingBox playerBox, Box entityBox) {
        return entityBox.maxX > playerBox.minX - HORIZONTAL_EPSILON
                && entityBox.minX < playerBox.maxX + HORIZONTAL_EPSILON
                && entityBox.maxY > playerBox.minY - VERTICAL_EPSILON
                && entityBox.minY < playerBox.maxY + VERTICAL_EPSILON
                && entityBox.maxZ > playerBox.minZ - HORIZONTAL_EPSILON
                && entityBox.minZ < playerBox.maxZ + HORIZONTAL_EPSILON;
    }

    private static boolean isSuspiciouslyTiny(Box box) {
        if (box == null) {
            return true;
        }

        float widthX = box.maxX - box.minX;
        float widthZ = box.maxZ - box.minZ;
        float height = box.maxY - box.minY;

        return widthX < 0.025F || widthZ < 0.025F || height < 0.025F;
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

    @Override
    public void process() {
    }

    private static final class CacheEntry {
        private final int tick;
        private final List<Box> boxes;

        private CacheEntry(int tick, List<Box> boxes) {
            this.tick = tick;
            this.boxes = boxes;
        }
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
}