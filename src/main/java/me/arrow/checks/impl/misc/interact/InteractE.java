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
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.utils.custom.materials.MaterialType;
import org.bukkit.Material;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import me.arrow.Arrow;
import org.bukkit.entity.Player;

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

        Player player = profile.getPlayer();
        if (player == null) return;

        org.bukkit.inventory.ItemStack handItem = null;
        try {
            if (packet.getHand() == InteractionHand.OFF_HAND) {
                handItem = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(player);
            } else {
                handItem = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(player);
            }
        } catch (Throwable ignored) {
            try {
                handItem = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(player);
            } catch (Throwable ignored2) {}
        }

        // Ignore if holding air or null (nothing in hand)
        if (handItem == null || isAir(handItem.getType())) {
            return;
        }

        ItemStack itemStack = packet.getItemStack().orElse(null);
        org.bukkit.inventory.ItemStack bukkitStack = (itemStack != null) ? SpigotConversionUtil.toBukkitItemStack(itemStack) : handItem;

        if (bukkitStack == null || isAir(bukkitStack.getType())) {
            return;
        }

        Material placedMat = bukkitStack.getType();
        if (isAir(placedMat) || !placedMat.isBlock()) {
            return;
        }

        if (isAir(x, y, z)
                && isAir(x + 1, y, z) && isAir(x - 1, y, z)
                && isAir(x, y + 1, z) && isAir(x, y - 1, z)
                && isAir(x, y, z + 1) && isAir(x, y, z - 1)) {
            fail("Air Place", "x " + MsgType.MAIN_THEME_COLOR.getMessage() + x
                    + "\ny " + MsgType.MAIN_THEME_COLOR.getMessage() + y
                    + "\nz " + MsgType.MAIN_THEME_COLOR.getMessage() + z
                    + "\nplacedBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + placedMat.name());
        }
    }

    private boolean isAir(Material mat) {
        return mat == null || mat == Material.AIR || mat.name().contains("AIR");
    }

    private boolean isAir(int x, int y, int z) {
        Material mat = ChunkCache.get().getBlock(profile.getMovementData().getLocation().getWorld(), x, y, z);
        return isAir(mat);
    }

    @Override
    public void handle(PacketSendEvent event) {}
}
