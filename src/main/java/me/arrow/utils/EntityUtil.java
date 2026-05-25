package me.arrow.utils;

import me.arrow.managers.profile.Profile;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.*;

public class EntityUtil {

    private static final Set<String> BOAT_NAMES = new HashSet<>();
    private static final Set<String> GHAST = new HashSet<>();
    private static final Set<String> SHULKER = new HashSet<>();

    static {
        // 1.8 boat
        addBoat("BOAT");

        // Modern boats
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

        // Newer experimental boats
        addBoat("BAMBOO_RAFT");
        addBoat("BAMBOO_CHEST_RAFT");
        addBoat("PALE_OAK_BOAT");
        addBoat("PALE_OAK_CHEST_BOAT");

        // Optional non-boat entities (if you need them)
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
        if (type == null) return false;
        return BOAT_NAMES.contains(type.name());
    }

    public static boolean isShulker(EntityType type) {
        if (type == null) return false;
        return SHULKER.contains(type.name());
    }

    public static boolean isGhast(EntityType type) {
        if (type == null) return false;
        return GHAST.contains(type.name());
    }




    public static boolean isOnBoat(Profile user) {
        double offset = user.getMovementData().getLocation().getY() % 0.015625;

        if (user.getMovementData().isOnGround()) {
            return getEntitiesWithinRadius(user.getPlayer().getLocation(), 2).stream()
                    .anyMatch(entity -> isBoat(entity.getType()));
        }
        return false;
    }

    public static boolean isNearBoat(Profile user) {
        try {
            return getEntitiesWithinRadius(user.getPlayer().getLocation(), 4).stream()
                    .anyMatch(entity -> isBoat(entity.getType()));
        } catch (Exception exception) {
            exception.printStackTrace();
            return false;
        }
    }

    public static boolean isNearShulker(Profile user) {
        try {
            return getEntitiesWithinRadius(user.getPlayer().getLocation(), 4).stream()
                    .anyMatch(entity -> isShulker(entity.getType()));
        } catch (Exception exception) {
            exception.printStackTrace();
            return false;
        }
    }

    public static boolean isNearGhast(Profile user) {
        try {
            return getEntitiesWithinRadius(user.getPlayer().getLocation(), 8).stream()
                    .anyMatch(entity -> isGhast(entity.getType()));
        } catch (Exception exception) {
            exception.printStackTrace();
            return false;
        }
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
