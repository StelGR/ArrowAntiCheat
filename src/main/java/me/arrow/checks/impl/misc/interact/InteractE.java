package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import org.bukkit.Material;
import com.github.retrooper.packetevents.protocol.item.ItemStack;

@Experimental
public class InteractE extends Check {

    public InteractE(Profile profile) {
        super(profile, CheckType.INTERACT, "E", "Detects air place");
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) return;

        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);
        int x = packet.getBlockPosition().getX();
        int y = packet.getBlockPosition().getY();
        int z = packet.getBlockPosition().getZ();

        int face;
        try {
            face = packet.getFace().getFaceValue();
        } catch (Throwable ignored) {
            face = -1;
        }

        if (face < 0 || face > 5 || (x == -1 && y == -1 && z == -1)) return;

        ItemStack itemStack = packet.getItemStack().orElse(null);
        org.bukkit.inventory.ItemStack bukkitStack = null;
        if (itemStack != null) {
            bukkitStack = SpigotConversionUtil.toBukkitItemStack(itemStack);
        }
        Material placedMat = (bukkitStack != null) ? bukkitStack.getType() : null;
        if (placedMat == null || !placedMat.isSolid()) {
            decreaseBufferBy(0.25);
            return;
        }

        if (hasThinPlacementSupport(x, y, z)) {
            decreaseBufferBy(0.25);
            return;
        }

        /*
         * A missing chunk-cache entry is unknown, not air. Treating it as air made
         * legitimate 1.8 fence/bar clicks look like an air placement.
         */
        if (isKnownAir(x, y, z)
                && isKnownAir(x + 1, y, z) && isKnownAir(x - 1, y, z)
                && isKnownAir(x, y + 1, z) && isKnownAir(x, y - 1, z)
                && isKnownAir(x, y, z + 1) && isKnownAir(x, y, z - 1)) {
            if (increaseBuffer() > 1.0) {
                fail("Air Place", "x " + MsgType.MAIN_THEME_COLOR.getMessage() + x
                        + "\ny " + MsgType.MAIN_THEME_COLOR.getMessage() + y
                        + "\nz " + MsgType.MAIN_THEME_COLOR.getMessage() + z
                        + "\nplacedBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + (placedMat == Material.AIR ? "air" : placedMat.toString()));
            }
        } else {
            decreaseBufferBy(0.25);
        }
    }

    private boolean isKnownAir(int x, int y, int z) {
        Material mat = profile.getBlockProcessor().getServerMaterial(x, y, z);
        return mat != null && mat.name().contains("AIR");
    }

    private boolean hasThinPlacementSupport(int x, int y, int z) {
        return isThinSupport(x, y, z)
                || isThinSupport(x + 1, y, z) || isThinSupport(x - 1, y, z)
                || isThinSupport(x, y + 1, z) || isThinSupport(x, y - 1, z)
                || isThinSupport(x, y, z + 1) || isThinSupport(x, y, z - 1);
    }

    private boolean isThinSupport(int x, int y, int z) {
        Material material = profile.getBlockProcessor().getServerMaterial(x, y, z);

        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.contains("FENCE")
                || name.contains("BARS")
                || name.contains("PANE")
                || name.endsWith("_WALL")
                || name.contains("FENCE_GATE");
    }

    @Override
    public void handle(PacketSendEvent event) {}
}
