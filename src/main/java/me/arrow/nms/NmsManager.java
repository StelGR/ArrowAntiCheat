package me.arrow.nms;

import lombok.Getter;
import me.arrow.playerdata.cache.ChunkCache;
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

    public NmsManager() {
        this.nmsInstance = new InstanceDefault();

    }

}