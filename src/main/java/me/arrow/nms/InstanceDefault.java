package me.arrow.nms;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.utils.ReflectionUtils;
import me.arrow.utils.TaskUtils;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public class InstanceDefault implements NmsInstance {

    private static final ServerVersion VERSION =
            PacketEvents.getAPI()
                    .getServerManager()
                    .getVersion();

    private static final boolean HAS_1_9 =
            VERSION.isNewerThan(ServerVersion.V_1_8_8);

    private static final boolean HAS_1_13 =
            VERSION.isNewerThanOrEquals(ServerVersion.V_1_13);

    private static final boolean HAS_1_14 =
            VERSION.isNewerThanOrEquals(ServerVersion.V_1_14);

    @Override
    public float getAttackCooldown(Player player) {
        return HAS_1_9 ? player.getAttackCooldown() : 1F;
    }


    @Override
    public boolean isChunkLoaded(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public Material getType(Block block) {
        if (block == null) {
            return Material.AIR;
        }
        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(block)) {
            return Material.AIR;
        }

        try {
            return block.getType();
        } catch (Throwable ignored) {
            return Material.AIR;
        }
    }

    @Override
    public Material getType(World world, double x, double y, double z) {
        if (world == null) {
            return Material.AIR;
        }

        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

        return ChunkCache.get().getBlock(world, bx, by, bz);
    }

    @Override
    public Entity[] getChunkEntities(World world, int x, int z) {
        if (world == null) return new Entity[0];
        try {
            return world.isChunkLoaded(x >> 4, z >> 4) ? world.getChunkAt(x >> 4, z >> 4).getEntities() : new Entity[0];
        } catch (Throwable ignored) {
            return new Entity[0];
        }
    }

    @Override
    public boolean isWaterLogged(Block block) {
        if (!HAS_1_13 || block == null) {
            return false;
        }

        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(block)) {
            return false;
        }

        try {
            BlockData data = block.getBlockData();

            return (data instanceof Waterlogged && ((Waterlogged) data).isWaterlogged())
                    || ReflectionUtils.isWaterOrWaterlogged(block);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean isCrawling(Player player) {
        return HAS_1_13 && player.getPose() == Pose.SWIMMING;
    }


    @Override
    public boolean isDead(Player player) {
        return player.isDead();
    }

    @Override
    public boolean isSleeping(Player player) {
        return player.isSleeping();
    }

    @Override
    public boolean isSwimming(Player player) {
        return HAS_1_13 && player.isSwimming();
    }

    @Override
    public boolean isGliding(Player player) {
        if (!VERSION.isNewerThan(ServerVersion.V_1_8_8)) {
            return false;
        }

        if (HAS_1_14) {
            return player.getPose() == Pose.FALL_FLYING;
        }

        return player.isGliding();
    }

    @Override
    public boolean isInsideVehicle(Player player) {
        return player.isInsideVehicle();
    }

    @Override
    public boolean isRiptiding(Player player) {
        return HAS_1_13 && player.isRiptiding();
    }

    @Override
    public boolean isBlocking(Player player) {
        return player.isBlocking();
    }

    @Override
    public boolean isSneaking(Player player) {
        return player.isSneaking();
    }

    @Override
    public ItemStack getItemInMainHand(Player player) {
        try {
            if (VERSION.isNewerThan(ServerVersion.V_1_8_8)) {
                return player.getInventory().getItemInMainHand(); // safe on 1.9+
            } else {
                return player.getItemInHand(); // 1.8
            }
        } catch (NoSuchMethodError e) {
            return player.getItemInHand(); // fallback for 1.8
        }
    }

    @Override
    public ItemStack getItemInOffHand(Player player) {
        try {
            if (VERSION.isNewerThan(ServerVersion.V_1_8_8)) {
                return player.getInventory().getItemInOffHand(); // safe on 1.9+
            } else {
                return new ItemStack(Material.AIR); // 1.8 has no offhand
            }
        } catch (NoSuchMethodError e) {
            return new ItemStack(Material.AIR); // fallback for 1.8
        }
    }


    @Override
    public float getWalkSpeed(Player player) {
        return player.getWalkSpeed();
    }


    @Override
    public float getAttributeSpeed(Player player) {
        return VERSION.isNewerThan(ServerVersion.V_1_8_8) ? (float) Objects.requireNonNull(player.getAttribute(Attribute.MOVEMENT_SPEED)).getValue() : 0.1F;
    }

    @Override
    public boolean getAllowFlight(Player player) {
        return player.getAllowFlight();
    }

    @Override
    public boolean isFlying(Player player) {
        return player.isFlying();
    }

    @Override
    public float getFallDistance(Player player) {
        return player.getFallDistance();
    }

}