package me.arrow.nms;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.arrow.utils.TaskUtils;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;

public class InstanceDefault implements NmsInstance {

    @Override
    public float getAttackCooldown(Player player) {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9) ? player.getAttackCooldown() : 1F;
    }


    @Override
    public boolean isChunkLoaded(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public Material getType(Block block) {

        if (TaskUtils.isFoliaServer() && !TaskUtils.isOwnedByCurrentRegion(block)) {
            return null;
        }

        try {
            return block.getType();
        } catch (Throwable ignored) {
            return Material.AIR;
        }
    }


    @Override
    public Entity[] getChunkEntities(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4) ? world.getChunkAt(x >> 4, z >> 4).getEntities() : new Entity[0];
    }

    @Override
    public boolean isWaterLogged(Block block) {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)
                && block.getBlockData() instanceof Waterlogged
                && ((Waterlogged) block.getBlockData()).isWaterlogged();
    }

    @Override
    public boolean isCrawling(Player player) {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)
                && player.getPose() == Pose.SWIMMING;
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
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13) && player.isSwimming();
    }

//    @Override
//    public boolean isGliding(Player player) {
//        return false;
//    }

    @Override
    public boolean isGliding(Player player) {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_8_8) && player.isGliding();
    }

    @Override
    public boolean isInsideVehicle(Player player) {
        return player.isInsideVehicle();
    }

    @Override
    public boolean isRiptiding(Player player) {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13) && player.isRiptiding();
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
            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_8_8)) {
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
            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_8_8)) {
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
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_8_8)  ? (float) player.getAttribute(Attribute.MOVEMENT_SPEED).getValue() : 0.1F;
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