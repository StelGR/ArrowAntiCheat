package me.arrow.utils;

import me.arrow.managers.profile.Profile;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;

public class EntityUtil {

    private static final Set<String> BOAT_NAMES = new HashSet<>();
    private static final Set<String> GHAST = new HashSet<>();
    private static final Set<String> SHULKER = new HashSet<>();

    static {
        addBoat("BOAT");

        addBoat("OAK_BOAT");
        addBoat("OAK_CHEST_BOAT");
        addBoat("BIRCH_BOAT");
        addBoat("BIRCH_CHEST_BOAT");
        addBoat("SPRUCE_BOAT");
        addBoat("SPRUCE_CHEST_BOAT");
        addBoat("DARK_OAK_BOAT");
        addBoat("DARK_OAK_CHEST_BOAT");
        addBoat("ACACIA_BOAT");
        addBoat("ACACIA_CHEST_BOAT");
        addBoat("JUNGLE_BOAT");
        addBoat("JUNGLE_CHEST_BOAT");
        addBoat("MANGROVE_BOAT");
        addBoat("MANGROVE_CHEST_BOAT");
        addBoat("CHERRY_BOAT");
        addBoat("CHERRY_CHEST_BOAT");

        addBoat("BAMBOO_RAFT");
        addBoat("BAMBOO_CHEST_RAFT");
        addBoat("PALE_OAK_BOAT");
        addBoat("PALE_OAK_CHEST_BOAT");

        addGhast("HAPPY_GHAST");
        addGhast("GHAST");

        addShulker("SHULKER");
    }

    private static void addBoat(String name) {
        BOAT_NAMES.add(name);
    }

    private static void addGhast(String name) {
        GHAST.add(name);
    }

    private static void addShulker(String name) {
        SHULKER.add(name);
    }

    public static boolean isBoat(EntityType type) {
        return type != null && BOAT_NAMES.contains(type.name());
    }

    public static boolean isShulker(EntityType type) {
        return type != null && SHULKER.contains(type.name());
    }

    public static boolean isGhast(EntityType type) {
        return type != null && GHAST.contains(type.name());
    }

    public static boolean isOnBoat(Profile user) {
        if (user == null || user.getPlayer() == null || user.getMovementData() == null) {
            return false;
        }

        if (!user.getMovementData().isOnGround()) {
            return false;
        }

        return getEntitiesWithinRadius(user.getPlayer(), 2.0D).stream()
                .anyMatch(entity -> isBoat(entity.getType()));
    }

    public static boolean isNearBoat(Profile user) {
        if (user == null || user.getPlayer() == null) {
            return false;
        }

        return getEntitiesWithinRadius(user.getPlayer(), 4.0D).stream()
                .anyMatch(entity -> isBoat(entity.getType()));
    }

    public static boolean isNearShulker(Profile user) {
        if (user == null || user.getPlayer() == null) {
            return false;
        }

        return getEntitiesWithinRadius(user.getPlayer(), 4.0D).stream()
                .anyMatch(entity -> isShulker(entity.getType()));
    }

    public static boolean isNearGhast(Profile user) {
        if (user == null || user.getPlayer() == null) {
            return false;
        }

        return getEntitiesWithinRadius(user.getPlayer(), 8.0D).stream()
                .anyMatch(entity -> isGhast(entity.getType()));
    }

    public static List<Entity> getEntitiesWithinRadius(Player player, double radius) {
        List<Entity> entities = new ArrayList<>();

        if (player == null || radius <= 0.0D) {
            return entities;
        }

        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(player)) {
            return entities;
        }

        try {
            if (!player.isOnline()) {
                return entities;
            }

            Location location = player.getLocation();

            if (location == null || location.getWorld() == null) {
                return entities;
            }

            double radiusSquared = radius * radius;

            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (entity == null) {
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

                    if (!entityLocation.getWorld().equals(location.getWorld())) {
                        continue;
                    }

                    if (entityLocation.distanceSquared(location) <= radiusSquared) {
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

        if (TaskUtils.isFoliaServer()) {
            if (!TaskUtils.isOwnedByCurrentRegion(location)) {
                return entities;
            }

            /*
             * On Folia, do not use world.getChunkAt(...).getEntities().
             * It can cross region ownership and explode.
             * This location overload has no player anchor, so safest answer is empty.
             */
            return entities;
        }

        final double x;
        final double y;
        final double z;

        try {
            x = location.getX();
            y = location.getY();
            z = location.getZ();
        } catch (Throwable ignored) {
            return entities;
        }

        final double radiusSquared = radius * radius;

        final int minChunkX = (int) Math.floor((x - radius) / 16.0D);
        final int maxChunkX = (int) Math.floor((x + radius) / 16.0D);
        final int minChunkZ = (int) Math.floor((z - radius) / 16.0D);
        final int maxChunkZ = (int) Math.floor((z + radius) / 16.0D);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                try {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }

                    Entity[] chunkEntities = world.getChunkAt(chunkX, chunkZ).getEntities();

                    if (chunkEntities == null || chunkEntities.length == 0) {
                        continue;
                    }

                    for (Entity entity : chunkEntities) {
                        if (entity == null || !entity.isValid()) {
                            continue;
                        }

                        try {
                            if (entity.getWorld() != world) {
                                continue;
                            }

                            Location entityLocation = entity.getLocation();

                            if (entityLocation == null) {
                                continue;
                            }

                            double deltaX = entityLocation.getX() - x;
                            double deltaY = entityLocation.getY() - y;
                            double deltaZ = entityLocation.getZ() - z;

                            if ((deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ) <= radiusSquared) {
                                entities.add(entity);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return entities;
    }
}