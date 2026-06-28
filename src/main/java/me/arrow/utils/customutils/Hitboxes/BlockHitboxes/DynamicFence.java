package me.arrow.utils.customutils.Hitboxes.BlockHitboxes;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.customutils.CollisionFactory;
import me.arrow.utils.customutils.Hitboxes.CollisionBox;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.ComplexCollisionBox;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.SimpleCollisionBox;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.material.Gate;
import org.bukkit.material.Stairs;

public class DynamicFence implements CollisionFactory {

    private static final double width = 0.125;
    private static final double min = .5 - width;
    private static final double max = .5 + width;

    @Override
    public CollisionBox fetch(ClientVersion version, Block b) {
        ComplexCollisionBox box = new ComplexCollisionBox(new SimpleCollisionBox(min, 0, min, max, 1.5, max));
        boolean east =  fenceConnects(version, b, BlockFace.EAST );
        boolean north = fenceConnects(version, b, BlockFace.NORTH);
        boolean south = fenceConnects(version, b, BlockFace.SOUTH);
        boolean west =  fenceConnects(version, b, BlockFace.WEST );
        if (east) box.add(new SimpleCollisionBox(max, 0, min, 1, 1.5, max));
        if (west) box.add(new SimpleCollisionBox(0, 0, min, max, 1.5, max));
        if (north) box.add(new SimpleCollisionBox(min, 0, 0, max, 1.5, min));
        if (south) box.add(new SimpleCollisionBox(min, 0, max, max, 1.5, 1));
        return box;
    }

    static boolean isBlacklisted(Material m) {
        // Use material names instead of numeric IDs
        return switch (m.name()) {
            case "BEACON", "CARROT_ON_A_STICK", "PUMPKIN_STEM", "MELON_STEM", "BARRIER" -> true;
            default -> MaterialType.isMaterial(m.name(), MaterialType.STAIRS) ||
                    MaterialType.isMaterial(m.name(), MaterialType.WALL)||
                    m.name().contains("DAYLIGHT") ||
                    MaterialType.isMaterial(m.name(), MaterialType.FENCE);
        };
    }

    private static boolean fenceConnects(ClientVersion v,Block fenceBlock, BlockFace direction) {
        Block targetBlock = fenceBlock.getRelative(direction,1);
        BlockState sFence = fenceBlock.getState();
        BlockState sTarget = targetBlock.getState();
        Material target = sTarget.getType();
        Material fence = sFence.getType();

        if (!isFence(target)&&isBlacklisted(target))
            return false;

        if(MaterialType.isMaterial(target.name(), MaterialType.STAIRS)) {
            if (v.isOlderThan(ClientVersion.V_1_12)) return false;
            Stairs stairs = (Stairs) sTarget.getData();
            return stairs.getFacing() == direction;
        } else if(target.name().contains("GATE")) {
            Gate gate = (Gate) sTarget.getData();
            BlockFace f1 = gate.getFacing();
            BlockFace f2 = f1.getOppositeFace();
            return direction == f1 || direction == f2;
        } else {
            if (fence == target) return true;
            if (isFence(target))
                return !fence.name().contains("NETHER") && !target.name().contains("NETHER");
            else return isFence(target) || (target.isSolid() && !target.isTransparent());
        }
    }

    private static boolean isFence(Material material) {
        return MaterialType.isMaterial(material.name(), MaterialType.FENCE) && material.name().contains("FENCE");
    }

}
