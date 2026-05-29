package me.arrow.playerdata.processors.impl;

import me.arrow.Arrow;
import me.arrow.playerdata.processors.Processor;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CollisionProcessor implements Processor {

    private static volatile Snapshot SNAPSHOT = Snapshot.EMPTY;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static final Method ENTITY_GET_BOUNDING_BOX = findMethod(Entity.class, "getBoundingBox");

    private static final float INTERSECTION_EPSILON = 0.045F;

    /*
     * Only needed for packet-only NPCs.
     * Bukkit-backed NPCs such as Citizens/Shopkeepers should be detected automatically.
     */
    private static final Map<UUID, VirtualBox> VIRTUAL_NPCS = new ConcurrentHashMap<>();

    private static volatile Class<?> BUKKIT_BOX_CLASS;
    private static volatile Method BUKKIT_BOX_MIN_X;
    private static volatile Method BUKKIT_BOX_MIN_Y;
    private static volatile Method BUKKIT_BOX_MIN_Z;
    private static volatile Method BUKKIT_BOX_MAX_X;
    private static volatile Method BUKKIT_BOX_MAX_Y;
    private static volatile Method BUKKIT_BOX_MAX_Z;

    public static boolean isColliding(Player player, BoundingBox playerBox) {
        if (!STARTED.get()) {
            startUpdater();
        }

        if (player == null || playerBox == null) {
            return false;
        }

        Snapshot snapshot = SNAPSHOT;

        Box self = snapshot.byId.get(player.getUniqueId());
        Integer worldIndex = snapshot.worldIndexes.get(player.getWorld().getUID());

        if (worldIndex == null) return false;

        if (snapshotCollides(player.getUniqueId(), playerBox, snapshot, self != null ? self.worldIndex : worldIndex)) {
            return true;
        }

        /*
         * If the snapshot is one tick behind, try a live nearby fallback.
         * This must only run on the primary thread because Bukkit entity access is not async-safe.
         */
        if (Bukkit.isPrimaryThread() && liveNearbyCollision(player, playerBox)) {
            return true;
        }

        return virtualCollision(player, playerBox);
    }

    public static boolean isColliding(UUID playerId, BoundingBox playerBox) {
        if (!STARTED.get()) {
            startUpdater();
        }

        if (playerId == null || playerBox == null) {
            return false;
        }

        Snapshot snapshot = SNAPSHOT;
        Box self = snapshot.byId.get(playerId);

        if (self == null) {
            return false;
        }

        return snapshotCollides(playerId, playerBox, snapshot, self.worldIndex);
    }

    private static boolean snapshotCollides(UUID playerId, BoundingBox playerBox, Snapshot snapshot, Integer worldIndex) {
        if (playerId == null || playerBox == null || snapshot == null || worldIndex == null) {
            return false;
        }

        int minChunkX = Math.floorDiv(floor(playerBox.minX), 16);
        int maxChunkX = Math.floorDiv(floor(playerBox.maxX), 16);
        int minChunkZ = Math.floorDiv(floor(playerBox.minZ), 16);
        int maxChunkZ = Math.floorDiv(floor(playerBox.maxZ), 16);

        for (int chunkX = minChunkX - 1; chunkX <= maxChunkX + 1; chunkX++) {
            for (int chunkZ = minChunkZ - 1; chunkZ <= maxChunkZ + 1; chunkZ++) {
                Box[] boxes = snapshot.chunks.get(chunkKey(worldIndex, chunkX, chunkZ));

                if (boxes == null) {
                    continue;
                }

                for (Box box : boxes) {
                    if (box == null || box.uuid.equals(playerId)) {
                        continue;
                    }

                    if (intersects(playerBox, box)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean liveNearbyCollision(Player player, BoundingBox playerBox) {
        try {
            for (Entity entity : player.getNearbyEntities(3.0D, 3.0D, 3.0D)) {
                if (entity == null || entity.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }

                if (!shouldTrack(entity)) {
                    continue;
                }

                Location location = entity.getLocation();

                if (location == null || location.getWorld() == null || !location.getWorld().equals(player.getWorld())) {
                    continue;
                }

                Box box = getBoxForEntity(-1, entity, location);

                if (box != null && intersects(playerBox, box)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean virtualCollision(Player player, BoundingBox playerBox) {
        try {
            UUID worldId = player.getWorld().getUID();

            for (VirtualBox virtual : VIRTUAL_NPCS.values()) {
                if (virtual == null || !virtual.worldId.equals(worldId)) {
                    continue;
                }

                Box box = virtual.toBox(-1);

                if (intersects(playerBox, box)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    public static void startUpdater() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Plugin plugin = Arrow.getInstance().getHost();

        TaskUtils.taskTimer(() -> {
            Map<UUID, Box> byId = new HashMap<>();
            Map<Long, List<Box>> chunkBuilder = new HashMap<>();
            Map<UUID, Integer> worldIndexes = new HashMap<>();

            int worldIndexCounter = 0;

            for (World world : Bukkit.getWorlds()) {
                if (world == null) {
                    continue;
                }

                Integer existing = worldIndexes.get(world.getUID());

                int worldIndex;

                if (existing == null) {
                    worldIndex = worldIndexCounter++;
                    worldIndexes.put(world.getUID(), worldIndex);
                } else {
                    worldIndex = existing;
                }

                for (Entity entity : world.getEntities()) {
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

                    Box box = getBoxForEntity(worldIndex, entity, location);

                    if (box == null) {
                        continue;
                    }

                    byId.put(entity.getUniqueId(), box);
                    addBoxToChunks(chunkBuilder, box);
                }
            }

            for (VirtualBox virtual : VIRTUAL_NPCS.values()) {
                if (virtual == null) {
                    continue;
                }

                Integer worldIndex = worldIndexes.get(virtual.worldId);

                if (worldIndex == null) {
                    continue;
                }

                Box box = virtual.toBox(worldIndex);
                byId.put(box.uuid, box);
                addBoxToChunks(chunkBuilder, box);
            }

            SNAPSHOT = new Snapshot(
                    Collections.unmodifiableMap(byId),
                    Collections.unmodifiableMap(worldIndexes),
                    LongBoxMap.from(chunkBuilder)
            );
        }, 0L, 1L);
    }

    private static void addBoxToChunks(Map<Long, List<Box>> chunkBuilder, Box box) {
        if (box == null) {
            return;
        }

        int minChunkX = Math.floorDiv(floor(box.minX), 16);
        int maxChunkX = Math.floorDiv(floor(box.maxX), 16);
        int minChunkZ = Math.floorDiv(floor(box.minZ), 16);
        int maxChunkZ = Math.floorDiv(floor(box.maxZ), 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = chunkKey(box.worldIndex, chunkX, chunkZ);
                chunkBuilder.computeIfAbsent(key, ignored -> new ArrayList<>()).add(box);
            }
        }
    }

    private static boolean shouldTrack(Entity entity) {
        if (entity == null || entity.isDead()) {
            return false;
        }

        /*
         * Important:
         * Check NPC metadata before entity.isValid().
         * Some NPC plugins use wrapped/fake entities that can behave strangely with validity.
         */
        if (isNpcLike(entity)) {
            return true;
        }

        try {
            if (!entity.isValid()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        if (entity instanceof LivingEntity) {
            return true;
        }

        String type = getTypeName(entity);

        return type.contains("BOAT")
                || type.contains("MINECART")
                || type.contains("NPC")
                || type.equals("INTERACTION");
    }

    private static boolean isNpcLike(Entity entity) {
        if (entity == null) {
            return false;
        }

        String type = getTypeName(entity);

        if (type.contains("NPC")) {
            return true;
        }

        try {
            if (entity.hasMetadata("NPC")
                    || entity.hasMetadata("npc")
                    || entity.hasMetadata("CitizensNPC")
                    || entity.hasMetadata("citizensnpc")
                    || entity.hasMetadata("SHOPKEEPER")
                    || entity.hasMetadata("shopkeeper")
                    || entity.hasMetadata("SHOPKEEPERS_NPC")
                    || entity.hasMetadata("arrow_npc")) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Collection<String> tags = entity.getScoreboardTags();

            for (String tag : tags) {
                if (tag == null) {
                    continue;
                }

                String lower = tag.toLowerCase(Locale.ROOT);

                if (lower.contains("npc")
                        || lower.contains("citizens")
                        || lower.contains("shopkeeper")
                        || lower.contains("shop_keeper")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            String name = entity.getCustomName();

            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.contains("npc")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static Box getBoxForEntity(int worldIndex, Entity entity, Location location) {
        Box exact = getBukkitBox(worldIndex, entity);

        if (exact != null && (!isNpcLike(entity) || !isSuspiciouslyTiny(exact))) {
            return exact;
        }

        return fallbackBoxForEntity(worldIndex, entity, location);
    }

    private static Box getBukkitBox(int worldIndex, Entity entity) {
        if (ENTITY_GET_BOUNDING_BOX == null) {
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
                    entity.getUniqueId(),
                    worldIndex,
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

    private static Box fallbackBoxForEntity(int worldIndex, Entity entity, Location location) {
        String type = getTypeName(entity);

        double width = 0.6D;
        double height = 1.8D;

        if (type.equals("PLAYER")) {
            width = 0.6D;
            height = 1.8D;
        } else if (type.equals("ARMOR_STAND")) {
            width = isMarkerArmorStand(entity) ? 0.0D : 0.5D;
            height = isMarkerArmorStand(entity) ? 0.0D : 1.975D;
        } else if (type.equals("BAT")) {
            width = 0.5D;
            height = 0.9D;
        } else if (type.equals("BEE")) {
            width = 0.7D;
            height = 0.6D;
        } else if (type.equals("BLAZE")) {
            width = 0.6D;
            height = 1.8D;
        } else if (type.equals("BOGGED")) {
            width = 0.6D;
            height = 1.99D;
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
        } else if (type.equals("GHAST")) {
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
        } else if (type.contains("BOAT")) {
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

        return new Box(entity.getUniqueId(), worldIndex, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean isMarkerArmorStand(Entity entity) {
        try {
            Method method = entity.getClass().getMethod("isMarker");
            Object result = method.invoke(entity);

            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Throwable ignored) {
        }

        return false;
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

    private static boolean isBaby(Entity entity) {
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

    private static int getSlimeSize(Entity entity) {
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

    private static String getTypeName(Entity entity) {
        try {
            return entity.getType().name();
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

    private static int floor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static long chunkKey(int worldIndex, int chunkX, int chunkZ) {
        return (((long) worldIndex & 0xFFFFL) << 48)
                | (((long) chunkX & 0xFFFFFFL) << 24)
                | ((long) chunkZ & 0xFFFFFFL);
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
        startUpdater();
    }

    private static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(Collections.emptyMap(), Collections.emptyMap(), LongBoxMap.EMPTY);

        private final Map<UUID, Box> byId;
        private final Map<UUID, Integer> worldIndexes;
        private final LongBoxMap chunks;

        private Snapshot(Map<UUID, Box> byId, Map<UUID, Integer> worldIndexes, LongBoxMap chunks) {
            this.byId = byId;
            this.worldIndexes = worldIndexes;
            this.chunks = chunks;
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

        private Box toBox(int worldIndex) {
            return new Box(uuid, worldIndex, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class Box {
        private final UUID uuid;
        private final int worldIndex;
        private final float minX;
        private final float minY;
        private final float minZ;
        private final float maxX;
        private final float maxY;
        private final float maxZ;

        private Box(UUID uuid, int worldIndex, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.uuid = uuid;
            this.worldIndex = worldIndex;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    private static final class LongBoxMap {
        private static final LongBoxMap EMPTY = new LongBoxMap(new long[0], new Box[0][], new boolean[0], 0);

        private final long[] keys;
        private final Box[][] values;
        private final boolean[] used;
        private final int mask;

        private LongBoxMap(long[] keys, Box[][] values, boolean[] used, int mask) {
            this.keys = keys;
            this.values = values;
            this.used = used;
            this.mask = mask;
        }

        private static LongBoxMap from(Map<Long, List<Box>> source) {
            if (source.isEmpty()) {
                return EMPTY;
            }

            int capacity = 1;

            while (capacity < source.size() * 2) {
                capacity <<= 1;
            }

            long[] keys = new long[capacity];
            Box[][] values = new Box[capacity][];
            boolean[] used = new boolean[capacity];
            int mask = capacity - 1;

            LongBoxMap map = new LongBoxMap(keys, values, used, mask);

            for (Map.Entry<Long, List<Box>> entry : source.entrySet()) {
                List<Box> list = entry.getValue();

                if (list == null || list.isEmpty()) {
                    continue;
                }

                map.put(entry.getKey(), list.toArray(new Box[0]));
            }

            return map;
        }

        private void put(long key, Box[] value) {
            int index = mix(key) & mask;

            while (used[index]) {
                if (keys[index] == key) {
                    values[index] = value;
                    return;
                }

                index = (index + 1) & mask;
            }

            used[index] = true;
            keys[index] = key;
            values[index] = value;
        }

        private Box[] get(long key) {
            if (used.length == 0) {
                return null;
            }

            int index = mix(key) & mask;

            while (used[index]) {
                if (keys[index] == key) {
                    return values[index];
                }

                index = (index + 1) & mask;
            }

            return null;
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return (int) value;
        }
    }
}