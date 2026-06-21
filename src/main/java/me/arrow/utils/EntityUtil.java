package me.arrow.utils;

import me.arrow.managers.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class EntityUtil {

    private EntityUtil() {
    }

    private static final ConcurrentHashMap<UUID, EntityCache> CACHE = new ConcurrentHashMap<>();

    public static boolean isBoat(EntityType type) {
        if (type == null) {
            return false;
        }

        String name = type.name();

        return name.equals("BOAT")
                || name.endsWith("_BOAT")
                || name.endsWith("_CHEST_BOAT")
                || name.endsWith("_RAFT")
                || name.endsWith("_CHEST_RAFT");
    }

    public static boolean isShulker(EntityType type) {
        return type != null && type.name().equals("SHULKER");
    }

    public static boolean isGhast(EntityType type) {
        return type != null && type.name().endsWith("GHAST");
    }

    public static boolean isOnBoat(Profile profile) {
        if (profile == null || profile.getPlayer() == null || profile.getMovementData() == null) {
            return false;
        }

        Player player = profile.getPlayer();
        EntityCache cache = getOrQueueRefresh(profile);

        return profile.getMovementData().isOnGround() && cache.onBoat;
    }

    public static boolean isNearBoat(Profile profile) {
        if (profile == null || profile.getPlayer() == null) {
            return false;
        }

        return getOrQueueRefresh(profile).nearBoat;
    }

    public static boolean isNearShulker(Profile profile) {
        if (profile == null || profile.getPlayer() == null) {
            return false;
        }

        return getOrQueueRefresh(profile).nearShulker;
    }

    public static boolean isNearGhast(Profile profile) {
        if (profile == null || profile.getPlayer() == null) {
            return false;
        }

        return getOrQueueRefresh(profile).nearGhast;
    }

    public static void refresh(Profile profile) {
        if (profile == null || profile.getPlayer() == null) {
            return;
        }

        Player player = profile.getPlayer();

        if (!canReadNearbyEntities(player)) {
            queueRefresh(profile);
            return;
        }

        refreshNow(profile);
    }

    private static EntityCache getOrQueueRefresh(Profile profile) {
        Player player = profile.getPlayer();

        EntityCache cache = CACHE.computeIfAbsent(player.getUniqueId(), uuid -> new EntityCache());

        if (canReadNearbyEntities(player)) {
            refreshNow(profile);
            return CACHE.getOrDefault(player.getUniqueId(), cache);
        }

        queueRefresh(profile);
        return cache;
    }

    private static void queueRefresh(Profile profile) {
        Player player = profile.getPlayer();

        if (player == null) {
            return;
        }

        EntityCache cache = CACHE.computeIfAbsent(player.getUniqueId(), uuid -> new EntityCache());

        if (cache.queued) {
            return;
        }

        cache.queued = true;

        TaskUtils.player(player, () -> {
            try {
                refreshNow(profile);
            } finally {
                cache.queued = false;
            }
        });
    }

    private static void refreshNow(Profile profile) {
        Player player = profile.getPlayer();

        if (player == null || !player.isOnline()) {
            return;
        }

        if (!canReadNearbyEntities(player)) {
            return;
        }

        EntityCache cache = CACHE.computeIfAbsent(player.getUniqueId(), uuid -> new EntityCache());

        cache.onBoat = profile.getMovementData() != null
                && profile.getMovementData().isOnGround()
                && anyNearbyNow(player, 2.0D, EntityUtil::isBoat);

        cache.nearBoat = anyNearbyNow(player, 4.0D, EntityUtil::isBoat);
        cache.nearShulker = anyNearbyNow(player, 4.0D, EntityUtil::isShulker);
        cache.nearGhast = anyNearbyNow(player, 8.0D, EntityUtil::isGhast);

        cache.lastUpdate = System.currentTimeMillis();
    }

    private static boolean anyNearbyNow(Player player, double radius, Predicate<EntityType> predicate) {
        if (player == null || radius <= 0.0D || predicate == null) {
            return false;
        }

        if (!canReadNearbyEntities(player)) {
            return false;
        }

        try {
            Location base = player.getLocation();

            if (base == null || base.getWorld() == null) {
                return false;
            }

            double radiusSquared = radius * radius;

            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (entity == null || !canReadEntity(entity)) {
                    continue;
                }

                try {
                    if (!entity.isValid()) {
                        continue;
                    }

                    EntityType type = entity.getType();

                    if (!predicate.test(type)) {
                        continue;
                    }

                    Location entityLocation = entity.getLocation();

                    if (entityLocation == null || entityLocation.getWorld() == null) {
                        continue;
                    }

                    if (!entityLocation.getWorld().equals(base.getWorld())) {
                        continue;
                    }

                    if (entityLocation.distanceSquared(base) <= radiusSquared) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    public static List<Entity> getEntitiesWithinRadius(Player player, double radius) {
        List<Entity> entities = new ArrayList<>();

        if (player == null || radius <= 0.0D || !canReadNearbyEntities(player)) {
            return entities;
        }

        try {
            Location base = player.getLocation();

            if (base == null || base.getWorld() == null) {
                return entities;
            }

            double radiusSquared = radius * radius;

            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (entity == null || !canReadEntity(entity)) {
                    continue;
                }

                try {
                    if (!entity.isValid()) {
                        continue;
                    }

                    Location entityLocation = entity.getLocation();

                    if (entityLocation == null || entityLocation.getWorld() == null) {
                        continue;
                    }

                    if (!entityLocation.getWorld().equals(base.getWorld())) {
                        continue;
                    }

                    if (entityLocation.distanceSquared(base) <= radiusSquared) {
                        entities.add(entity);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return entities;
    }

    public static List<Entity> getEntitiesWithinRadius(Location location, double radius) {
        List<Entity> entities = new ArrayList<>();

        if (location == null || radius <= 0.0D) {
            return entities;
        }

        World world = location.getWorld();

        if (world == null) {
            return entities;
        }

        if (TaskUtils.isFoliaServer() || !Bukkit.isPrimaryThread()) {
            return entities;
        }

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        double radiusSquared = radius * radius;

        int minChunkX = (int) Math.floor((x - radius) / 16.0D);
        int maxChunkX = (int) Math.floor((x + radius) / 16.0D);
        int minChunkZ = (int) Math.floor((z - radius) / 16.0D);
        int maxChunkZ = (int) Math.floor((z + radius) / 16.0D);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                try {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }

                    for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                        if (entity == null || !entity.isValid()) {
                            continue;
                        }

                        Location entityLocation = entity.getLocation();

                        if (entityLocation == null || entityLocation.getWorld() == null) {
                            continue;
                        }

                        if (!entityLocation.getWorld().equals(world)) {
                            continue;
                        }

                        double dx = entityLocation.getX() - x;
                        double dy = entityLocation.getY() - y;
                        double dz = entityLocation.getZ() - z;

                        if ((dx * dx) + (dy * dy) + (dz * dz) <= radiusSquared) {
                            entities.add(entity);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return entities;
    }

    public static void clear(Player player) {
        if (player != null) {
            CACHE.remove(player.getUniqueId());
        }
    }

    private static boolean canReadNearbyEntities(Player player) {
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

    private static final class EntityCache {
        private volatile boolean queued;
        private volatile boolean onBoat;
        private volatile boolean nearBoat;
        private volatile boolean nearShulker;
        private volatile boolean nearGhast;
        private volatile long lastUpdate;
    }
}