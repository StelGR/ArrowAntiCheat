package me.arrow.nms;

import lombok.Getter;
import me.arrow.playerdata.data.impl.worldcomp.Instance;
import org.bukkit.Material;
import org.bukkit.World;

// thank you nik :)

/**
 * A simple NMS Manager class
 * <p>
 * NOTE: Obviously this is not done, You should implement every single nms version yourself
 * Inside the me.nik.anticheatbase.manager.managers.nms.impl package.
 * <p>
 * NMS Can improve perfomance by a LOT even when calling simple methods such as p.getAllowFlight();
 * YourKit profiler doesn't lie!
 */
@Getter
public class NmsManager {

    private final NmsInstance nmsInstance;
    private final Instance nmsInstance2;

    public NmsManager() {
        this.nmsInstance = new InstanceDefault();
        this.nmsInstance2 = new Instance() {
            @Override
            public Material getType(World world, double x, double y, double z) {
                if (world == null) {
                    return Material.AIR;
                }

                int bx = (int) Math.floor(x);
                int by = (int) Math.floor(y);
                int bz = (int) Math.floor(z);

                return world.getBlockAt(bx, by, bz).getType();
            }
        };
    }

}