package me.arrow.playerdata.processors.impl;

import me.arrow.Arrow;
import me.arrow.backend.bukkit.PlatformBackend;
import me.arrow.managers.profiler.Profiler;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.processors.Processor;
import me.arrow.utils.EntityUtil;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.lang.reflect.Field;
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

/**
 * Collision processor with 1:1 precision entity hitbox collision detection.
 * Works across Bukkit, Spigot, Paper, Purpur, and Folia on all Minecraft versions (1.8 - 1.21+).
 * Automatically ignores non-collidable entities (items, item frames, display entities,
 * interactions, armor stands, projectiles, clouds, cushions, seats, markers, etc.).
 */
public class CollisionProcessor implements Processor {

    private static final double SCAN_RADIUS = 2.0D;
    private static final int MAX_CACHE_AGE_TICKS = 6;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, TaskUtils.CancellableTask> FOLIA_TASKS = new ConcurrentHashMap<>();

    private static int tick;

    // Reflection for 1.13+ Bukkit getBoundingBox
    private static final Method ENTITY_GET_BOUNDING_BOX = findMethod(Entity.class, "getBoundingBox");
    private static volatile Class<?> BUKKIT_BOX_CLASS;
    private static volatile Method BUKKIT_BOX_MIN_X;
    private static volatile Method BUKKIT_BOX_MIN_Y;
    private static volatile Method BUKKIT_BOX_MIN_Z;
    private static volatile Method BUKKIT_BOX_MAX_X;
    private static volatile Method BUKKIT_BOX_MAX_Y;
    private static volatile Method BUKKIT_BOX_MAX_Z;

    // Reflection for entity.isCollidable() (1.9+ Spigot / Paper)
    private static final Method ENTITY_IS_COLLIDABLE = initIsCollidableMethod();

    // Reflection for entity.getWidth() and entity.getHeight() (1.11+)
    private static final Method ENTITY_GET_WIDTH = findMethod(Entity.class, "getWidth");
    private static final Method ENTITY_GET_HEIGHT = findMethod(Entity.class, "getHeight");

    // Reflection for legacy CraftBukkit / NMS (1.8 - 1.12)
    private static volatile Method CRAFT_ENTITY_GET_HANDLE;
    private static volatile Method NMS_GET_BOUNDING_BOX;
    private static volatile boolean AABB_INITIALIZED;
    private static volatile Field AABB_A;
    private static volatile Field AABB_B;
    private static volatile Field AABB_C;
    private static volatile Field AABB_D;
    private static volatile Field AABB_E;
    private static volatile Field AABB_F;

    public static void start() {
        if (STARTED.get()) {
            return;
        }

        if (TaskUtils.isFoliaServer()) {
            if (!STARTED.compareAndSet(false, true)) {
                return;
            }
            TaskUtils.taskTimer(CollisionProcessor::tickCache, 1L, 1L);
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

    public static void stop() {
        for (TaskUtils.CancellableTask task : FOLIA_TASKS.values()) {
            if (task != null) {
                try {
                    task.cancel();
                } catch (Throwable ignored) {}
            }
        }
        FOLIA_TASKS.clear();
        CACHE.clear();
        STARTED.set(false);
    }

    /**
     * Checks if the player's hitbox is currently colliding with any collidable entity.
     * Safe to call from packet / async threads.
     */
    public static boolean isColliding(Player player) {
        return getCollidingEntityCount(player) > 0;
    }

    /**
     * Checks if the specified player bounding box is colliding with any collidable entity.
     * Safe to call from packet / async threads.
     */
    public static boolean isColliding(Player player, BoundingBox playerBox) {
        return getCollidingEntityCount(player, playerBox) > 0;
    }

    /**
     * Checks if the specified player bounding box is colliding with any collidable entity.
     * Safe to call from packet / async threads.
     */
    public static boolean isColliding(UUID playerId, BoundingBox playerBox) {
        return getCollidingEntityCount(playerId, playerBox) > 0;
    }

    /**
     * Returns the exact number of collidable entities the player's hitbox is colliding with.
     * Safe to call from packet / async threads.
     */
    public static int getCollidingEntityCount(Player player) {
        if (player == null) {
            return 0;
        }

        BoundingBox box = null;
        try {
            Profile profile = Arrow.getInstance().getProfileManager().getProfile(player);
            if (profile != null) {
                box = profile.getBoundingBox();
            }
        } catch (Throwable ignored) {}

        if (box == null) {
            Location loc = player.getLocation();
            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();
            box = new BoundingBox(x - 0.3D, y, z - 0.3D, x + 0.3D, y + 1.8D, z + 0.3D);
        }

        return getCollidingEntityCount(player, box);
    }

    /**
     * Returns the exact number of collidable entities the specified bounding box is colliding with.
     * Safe to call from packet / async threads.
     */
    public static int getCollidingEntityCount(Player player, BoundingBox playerBox) {
        if (player == null || playerBox == null) {
            return 0;
        }

        start();

        UUID playerId;
        try {
            playerId = player.getUniqueId();
        } catch (Throwable ignored) {
            return 0;
        }

        if (TaskUtils.isFoliaServer()) {
            ensureFoliaTask(player, playerId);
        }

        return getCollidingEntityCount(playerId, playerBox);
    }

    /**
     * Returns the exact number of collidable entities the specified bounding box is colliding with.
     * Safe to call from packet / async threads.
     */
    public static int getCollidingEntityCount(UUID playerId, BoundingBox playerBox) {
        if (playerId == null || playerBox == null) {
            return 0;
        }

        long profiler = Profiler.start();
        try {
            start();

            CacheEntry entry = CACHE.get(playerId);
            if (entry == null) {
                return 0;
            }

            int age = tick - entry.tick;
            if (age < 0 || age > MAX_CACHE_AGE_TICKS) {
                return 0;
            }

            int count = 0;
            for (Box entityBox : entry.boxes) {
                if (entityBox != null && intersects(playerBox, entityBox)) {
                    count++;
                }
            }

            return count;
        } finally {
            Profiler.stop("CollisionProcessor (Collision)", profiler);
        }
    }

    /**
     * Returns whether an entity is a collidable vanilla entity with a physical collision box.
     * Filters out non-collidable entities across all Minecraft versions:
     * - Dropped items (Item)
     * - Item frames (ItemFrame, GlowItemFrame)
     * - Display entities (TextDisplay, BlockDisplay, ItemDisplay)
     * - Interaction entities
     * - Armor stands
     * - Hanging entities (Paintings, Leash knots)
     * - Projectiles (Arrows, Fireballs, Snowballs, Potions, etc.)
     * - Area effect clouds, Experience orbs
     * - Custom cushion / seat marker entities
     * - Spectator players and entities with isCollidable() == false
     */
    public static boolean isCollidableEntity(Entity entity) {
        if (entity == null) {
            return false;
        }

        try {
            if (entity.isDead() || !entity.isValid()) {
                return false;
            }

            // In vanilla Minecraft, ONLY LivingEntity (mobs, animals, players)
            // and Vehicle (boats, minecarts) have physical push/collision boxes.
            boolean isLiving = entity instanceof LivingEntity;
            boolean isVehicle = entity instanceof Vehicle;

            if (!isLiving && !isVehicle) {
                return false;
            }

            // In Bukkit, ArmorStand extends LivingEntity, but in vanilla Minecraft
            // armor stands have NO physical entity-push collision box.
            if (entity instanceof ArmorStand) {
                return false;
            }

            // Spectator mode players cannot collide with entities
            if (entity instanceof Player) {
                if (((Player) entity).getGameMode() == GameMode.SPECTATOR) {
                    return false;
                }
            }

            // Check Bukkit / Paper isCollidable() method if available (1.9+)
            if (ENTITY_IS_COLLIDABLE != null) {
                try {
                    Object collidable = ENTITY_IS_COLLIDABLE.invoke(entity);
                    if (Boolean.FALSE.equals(collidable)) {
                        return false;
                    }
                } catch (Throwable ignored) {}
            }

            // Check entity type name to filter out display entities, interactions, markers, cushions, seats
            String typeName = entity.getType().name();
            if (typeName.endsWith("_DISPLAY")
                    || typeName.equals("DISPLAY")
                    || typeName.equals("TEXT_DISPLAY")
                    || typeName.equals("BLOCK_DISPLAY")
                    || typeName.equals("ITEM_DISPLAY")
                    || typeName.equals("INTERACTION")
                    || typeName.equals("MARKER")
                    || typeName.contains("CUSHION")
                    || typeName.contains("SEAT")
                    || typeName.equals("ARMOR_STAND")
                    || typeName.equals("DROPPED_ITEM")
                    || typeName.equals("ITEM_FRAME")
                    || typeName.equals("GLOW_ITEM_FRAME")) {
                return false;
            }

            // Check class name for custom/plugin display, interaction, marker, or cushion subclasses
            String className = entity.getClass().getSimpleName();
            return !className.contains("Display")
                    && !className.contains("Interaction")
                    && !className.contains("Marker")
                    && !className.contains("ArmorStand")
                    && !className.toLowerCase().contains("cushion")
                    && !className.toLowerCase().contains("seat");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 1:1 exact AABB intersection between the player's bounding box and an entity's bounding box.
     */
    public static boolean intersects(BoundingBox playerBox, Box entityBox) {
        if (playerBox == null || entityBox == null) {
            return false;
        }
        return entityBox.maxX > playerBox.minX
                && entityBox.minX < playerBox.maxX
                && entityBox.maxY > playerBox.minY
                && entityBox.minY < playerBox.maxY
                && entityBox.maxZ > playerBox.minZ
                && entityBox.minZ < playerBox.maxZ;
    }

    /**
     * Periodic cache updater.
     * On Folia: runs on GlobalRegionScheduler and ensures active region tasks for all players.
     * On Bukkit/Spigot/Paper/Purpur: runs on the main thread and scans nearby entities for each player.
     */
    private static void tickCache() {
        long profiler = Profiler.start();
        try {
            tick++;

            Set<UUID> seenPlayers = new HashSet<>();

            for (Player player : PlatformBackend.get().getServer().getOnlinePlayers()) {
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

                if (TaskUtils.isFoliaServer()) {
                    ensureFoliaTask(player, playerId);
                } else {
                    updatePlayerCache(player, playerId);
                }
            }

            CACHE.keySet().removeIf(uuid -> !seenPlayers.contains(uuid));
            if (TaskUtils.isFoliaServer()) {
                FOLIA_TASKS.keySet().removeIf(uuid -> !seenPlayers.contains(uuid));
            }
        } finally {
            Profiler.stop("CollisionProcessor (Tick)", profiler);
        }
    }

    private static void ensureFoliaTask(Player player, UUID playerId) {
        if (FOLIA_TASKS.containsKey(playerId)) {
            return;
        }

        TaskUtils.CancellableTask task = TaskUtils.playerTimer(player, 1L, 1L, () -> {
            if (!player.isOnline()) {
                TaskUtils.CancellableTask t = FOLIA_TASKS.remove(playerId);
                if (t != null) {
                    try {
                        t.cancel();
                    } catch (Throwable ignored) {}
                }
                CACHE.remove(playerId);
                return;
            }
            updatePlayerCache(player, playerId);
        });

        FOLIA_TASKS.put(playerId, task);
    }

    private static void updatePlayerCache(Player player, UUID playerId) {
        long profiler = Profiler.start();
        try {
            List<Box> boxes = scanNearbyEntityBoxes(player, playerId);
            CACHE.put(playerId, new CacheEntry(tick, boxes));
        } finally {
            Profiler.stop("CollisionProcessor (Scan)", profiler);
        }
    }

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

        if (nearby.isEmpty()) {
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

                if (!isCollidableEntity(entity)) {
                    continue;
                }

                Location entityLocation = entity.getLocation();

                if (entityLocation.getWorld() == null || !entityLocation.getWorld().equals(world)) {
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

    private static Box getBoxForEntity(Entity entity) {
        if (entity == null) {
            return null;
        }

        // 1. Try modern Bukkit / Paper 1.13+ entity.getBoundingBox()
        Box exact = getBukkitBox(entity);
        if (exact != null) {
            return exact;
        }

        // 2. Try legacy CraftBukkit / NMS (1.8 - 1.12)
        Box nmsBox = getNmsBox(entity);
        if (nmsBox != null) {
            return nmsBox;
        }

        // 3. Fallback using entity dimensions and location
        return getFallbackBox(entity);
    }

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
                    entity,
                    getDouble(BUKKIT_BOX_MIN_X, bukkitBox),
                    getDouble(BUKKIT_BOX_MIN_Y, bukkitBox),
                    getDouble(BUKKIT_BOX_MIN_Z, bukkitBox),
                    getDouble(BUKKIT_BOX_MAX_X, bukkitBox),
                    getDouble(BUKKIT_BOX_MAX_Y, bukkitBox),
                    getDouble(BUKKIT_BOX_MAX_Z, bukkitBox)
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

    private static Box getNmsBox(Entity entity) {
        if (entity == null) {
            return null;
        }

        try {
            if (CRAFT_ENTITY_GET_HANDLE == null) {
                CRAFT_ENTITY_GET_HANDLE = entity.getClass().getMethod("getHandle");
                CRAFT_ENTITY_GET_HANDLE.setAccessible(true);
            }
            Object nmsEntity = CRAFT_ENTITY_GET_HANDLE.invoke(entity);
            if (nmsEntity == null) {
                return null;
            }

            if (NMS_GET_BOUNDING_BOX == null) {
                for (Method m : nmsEntity.getClass().getMethods()) {
                    if (m.getName().equals("getBoundingBox") && m.getParameterCount() == 0) {
                        NMS_GET_BOUNDING_BOX = m;
                        NMS_GET_BOUNDING_BOX.setAccessible(true);
                        break;
                    }
                }
            }
            if (NMS_GET_BOUNDING_BOX == null) {
                return null;
            }

            Object aabb = NMS_GET_BOUNDING_BOX.invoke(nmsEntity);
            if (aabb == null) {
                return null;
            }

            initAabbFields(aabb.getClass());
            if (AABB_A == null || AABB_B == null || AABB_C == null
                    || AABB_D == null || AABB_E == null || AABB_F == null) {
                return null;
            }

            return new Box(
                    entity,
                    AABB_A.getDouble(aabb),
                    AABB_B.getDouble(aabb),
                    AABB_C.getDouble(aabb),
                    AABB_D.getDouble(aabb),
                    AABB_E.getDouble(aabb),
                    AABB_F.getDouble(aabb)
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void initAabbFields(Class<?> clazz) {
        if (AABB_INITIALIZED) {
            return;
        }

        synchronized (CollisionProcessor.class) {
            if (AABB_INITIALIZED) {
                return;
            }

            try {
                // Try Spigot obfuscated fields: a, b, c, d, e, f
                AABB_A = findField(clazz, "a");
                AABB_B = findField(clazz, "b");
                AABB_C = findField(clazz, "c");
                AABB_D = findField(clazz, "d");
                AABB_E = findField(clazz, "e");
                AABB_F = findField(clazz, "f");

                if (AABB_A == null) {
                    // Try Mojang mapped fields: minX, minY, minZ, maxX, maxY, maxZ
                    AABB_A = findField(clazz, "minX");
                    AABB_B = findField(clazz, "minY");
                    AABB_C = findField(clazz, "minZ");
                    AABB_D = findField(clazz, "maxX");
                    AABB_E = findField(clazz, "maxY");
                    AABB_F = findField(clazz, "maxZ");
                }
            } finally {
                AABB_INITIALIZED = true;
            }
        }
    }

    private static Box getFallbackBox(Entity entity) {
        try {
            Location loc = entity.getLocation();
            double width = 0.6D;
            double height = 1.8D;

            if (ENTITY_GET_WIDTH != null && ENTITY_GET_HEIGHT != null) {
                try {
                    width = ((Number) ENTITY_GET_WIDTH.invoke(entity)).doubleValue();
                    height = ((Number) ENTITY_GET_HEIGHT.invoke(entity)).doubleValue();
                } catch (Throwable ignored) {}
            }

            double halfW = width * 0.5D;
            double minX = loc.getX() - halfW;
            double minY = loc.getY();
            double minZ = loc.getZ() - halfW;
            double maxX = loc.getX() + halfW;
            double maxY = loc.getY() + height;
            double maxZ = loc.getZ() + halfW;

            return new Box(
                    entity,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSuspiciouslyTiny(Box box) {
        if (box == null) {
            return true;
        }

        double widthX = box.maxX - box.minX;
        double widthZ = box.maxZ - box.minZ;
        double height = box.maxY - box.minY;

        return widthX < 0.01D || widthZ < 0.01D || height < 0.01D;
    }

    private static Method initIsCollidableMethod() {
        Method m = findMethod(Entity.class, "isCollidable");
        if (m == null) {
            m = findMethod(LivingEntity.class, "isCollidable");
        }
        return m;
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

    private static Field findField(Class<?> clazz, String name) {
        try {
            Field field = clazz.getField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored2) {
                return null;
            }
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

    public static boolean isOnBoat(
            final UUID playerId,
            final BoundingBox playerBox
    ) {
        if (playerId == null || playerBox == null) {
            return false;
        }

        final CacheEntry entry = CACHE.get(playerId);

        if (entry == null) {
            return false;
        }

        final int age = tick - entry.tick;

        if (age < 0 || age > MAX_CACHE_AGE_TICKS) {
            return false;
        }

        for (final Box box : entry.boxes) {
            if (box == null || box.entity == null
                    || (!EntityUtil.isBoat(box.entity.getType()) && !(box.entity instanceof Boat))) {
                continue;
            }

            // Horizontal overlap with small margin for boat edges/drift.
            double expand = 0.2D;
            if (playerBox.maxX <= box.minX - expand
                    || playerBox.minX >= box.maxX + expand
                    || playerBox.maxZ <= box.minZ - expand
                    || playerBox.minZ >= box.maxZ + expand) {
                continue;
            }

            /*
             * The player's feet can be standing on the boat hull bottom,
             * seated, or on the rim/top of the boat.
             */
            final double feetY = playerBox.minY;
            final double topY = box.maxY;
            final double bottomY = box.minY;

            if (feetY >= bottomY - 0.15D
                    && feetY <= topY + 0.35D) {
                return true;
            }
        }

        return false;
    }

    public static boolean isOnBoat(Player player) {
        if (player == null) {
            return false;
        }
        Profile profile = Arrow.getInstance().getProfileManager().getProfile(player);
        BoundingBox box = profile != null ? profile.getBoundingBox() : null;
        return isOnBoat(player.getUniqueId(), box);
    }

    public static boolean isOnBoat(Player player, BoundingBox playerBox) {
        if (player == null) {
            return false;
        }
        return isOnBoat(player.getUniqueId(), playerBox);
    }

    /**
     * Checks if a boat is above or slightly above the player's head.
     * Safe to call from packet / async threads.
     *
     * @param playerId  The UUID of the player.
     * @param playerBox The player's bounding box.
     * @return true if a boat is above or slightly above the player's head.
     */
    public static boolean isUnderBoat(
            final UUID playerId,
            final BoundingBox playerBox
    ) {
        return isUnderBoat(playerId, playerBox, 1.5D);
    }

    /**
     * Checks if a boat is above or slightly above the player's head within a given max vertical distance.
     * Safe to call from packet / async threads.
     *
     * @param playerId             The UUID of the player.
     * @param playerBox            The player's bounding box.
     * @param maxDistanceAboveHead The maximum distance between the player's head and the bottom of the boat.
     * @return true if a boat is above or slightly above the player's head within that distance.
     */
    public static boolean isUnderBoat(
            final UUID playerId,
            final BoundingBox playerBox,
            final double maxDistanceAboveHead
    ) {
        if (playerId == null || playerBox == null) {
            return false;
        }

        final CacheEntry entry = CACHE.get(playerId);

        if (entry == null) {
            return false;
        }

        final int age = tick - entry.tick;

        if (age < 0 || age > MAX_CACHE_AGE_TICKS) {
            return false;
        }

        for (final Box box : entry.boxes) {
            if (box == null || box.entity == null
                    || (!EntityUtil.isBoat(box.entity.getType()) && !(box.entity instanceof Boat))) {
                continue;
            }

            // Horizontal overlap with margin for boat edges/drift.
            double expand = 0.3D;
            if (playerBox.maxX <= box.minX - expand
                    || playerBox.minX >= box.maxX + expand
                    || playerBox.maxZ <= box.minZ - expand
                    || playerBox.minZ >= box.maxZ + expand) {
                continue;
            }

            final double headY = playerBox.maxY;
            final double boatBottomY = box.minY;
            final double boatTopY = box.maxY;

            /*
             * Player is under the boat if:
             * 1. The player's feet are below the boat (not standing on top of it).
             * 2. The boat is above the player's lower body (above waist).
             * 3. The boat's bottom is within maxDistanceAboveHead above the player's head,
             *    or the player's head penetrates into/intersects the boat.
             */
            if (playerBox.minY < boatBottomY + 0.2D
                    && boatBottomY > playerBox.minY + 0.4D
                    && boatBottomY <= headY + maxDistanceAboveHead
                    && boatTopY >= headY - 0.6D) {
                return true;
            }
        }

        return false;
    }

    public static boolean isUnderBoat(Player player) {
        if (player == null) {
            return false;
        }
        Profile profile = Arrow.getInstance().getProfileManager().getProfile(player);
        BoundingBox box = profile != null ? profile.getBoundingBox() : null;
        return isUnderBoat(player.getUniqueId(), box);
    }

    public static boolean isUnderBoat(Player player, BoundingBox playerBox) {
        if (player == null) {
            return false;
        }
        return isUnderBoat(player.getUniqueId(), playerBox);
    }

    /**
     * Checks if the player is standing next to / beside the side of a boat (similar to nearWall).
     * Safe to call from packet / async threads.
     *
     * @param playerId  The UUID of the player.
     * @param playerBox The player's bounding box.
     * @return true if the player is standing next to the side of a boat.
     */
    public static boolean isNearBoatSide(
            final UUID playerId,
            final BoundingBox playerBox
    ) {
        return isNearBoatSide(playerId, playerBox, 0.3D);
    }

    /**
     * Checks if the player is standing next to / beside the side of a boat within a horizontal margin.
     * Safe to call from packet / async threads.
     *
     * @param playerId         The UUID of the player.
     * @param playerBox        The player's bounding box.
     * @param horizontalMargin The horizontal distance from the boat edge (e.g. 0.3D).
     * @return true if the player is standing next to the side of a boat.
     */
    public static boolean isNearBoatSide(
            final UUID playerId,
            final BoundingBox playerBox,
            final double horizontalMargin
    ) {
        if (playerId == null || playerBox == null) {
            return false;
        }

        final CacheEntry entry = CACHE.get(playerId);

        if (entry == null) {
            return false;
        }

        final int age = tick - entry.tick;

        if (age < 0 || age > MAX_CACHE_AGE_TICKS) {
            return false;
        }

        for (final Box box : entry.boxes) {
            if (box == null || box.entity == null
                    || (!EntityUtil.isBoat(box.entity.getType()) && !(box.entity instanceof Boat))) {
                continue;
            }

            // Must overlap horizontally with the side margin
            if (playerBox.maxX + horizontalMargin <= box.minX
                    || playerBox.minX - horizontalMargin >= box.maxX
                    || playerBox.maxZ + horizontalMargin <= box.minZ
                    || playerBox.minZ - horizontalMargin >= box.maxZ) {
                continue;
            }

            final double feetY = playerBox.minY;
            final double headY = playerBox.maxY;
            final double boatBottomY = box.minY;
            final double boatTopY = box.maxY;

            /*
             * Side collision / adjacency:
             * 1. The boat must overlap the player's vertical body span (not far above or below).
             * 2. The player must NOT be standing on top of the boat (feet above boat top rim).
             * 3. The player must NOT be completely under the boat (head below boat bottom).
             */
            boolean verticalOverlap = feetY <= boatTopY + 0.1D && headY >= boatBottomY - 0.1D;
            boolean notStandingOnTop = feetY < boatTopY - 0.15D;
            boolean notUnderBoat = headY > boatBottomY + 0.15D;

            if (verticalOverlap && notStandingOnTop && notUnderBoat) {
                return true;
            }
        }

        return false;
    }

    public static boolean isNearBoatSide(Player player) {
        if (player == null) {
            return false;
        }
        Profile profile = Arrow.getInstance().getProfileManager().getProfile(player);
        BoundingBox box = profile != null ? profile.getBoundingBox() : null;
        return isNearBoatSide(player.getUniqueId(), box);
    }

    public static boolean isNearBoatSide(Player player, BoundingBox playerBox) {
        if (player == null) {
            return false;
        }
        return isNearBoatSide(player.getUniqueId(), playerBox);
    }

    public static boolean isBesideBoat(UUID playerId, BoundingBox playerBox) {
        return isNearBoatSide(playerId, playerBox);
    }

    public static boolean isBesideBoat(Player player) {
        return isNearBoatSide(player);
    }

    public static boolean isBesideBoat(Player player, BoundingBox playerBox) {
        return isNearBoatSide(player, playerBox);
    }

    private static class CacheEntry {
        int tick;
        List<Box> boxes;

        private CacheEntry(int tick, List<Box> boxes) {
            this.tick = tick;
            this.boxes = boxes;
        }
    }

    public static class Box {
        public Entity entity;

        public double minX;
        public double minY;
        public double minZ;
        public double maxX;
        public double maxY;
        public double maxZ;

        public Box(
                Entity entity,
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ
        ) {
            this.entity = entity;

            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public Box(
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ
        ) {
            this(null, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
