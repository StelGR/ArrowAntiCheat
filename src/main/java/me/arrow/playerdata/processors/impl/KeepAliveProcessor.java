package me.arrow.playerdata.processors.impl;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.utils.TaskUtils;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import static me.arrow.utils.customutils.OtherUtility.*;

//unused

public class KeepAliveProcessor implements Runnable {

    @Getter @Setter
    private long timeKeepAlive = 0;
    private TaskUtils.CancellableTask bukkitTaskKeepAlive;

    public KeepAliveProcessor() {
        startKeepAlive();
    }

    public void startKeepAlive() {
        if (this.bukkitTaskKeepAlive == null) {
            this.bukkitTaskKeepAlive = TaskUtils.taskTimer( this, 0L, 20L);
        }
    }

    @Override
    public void run() {
        timeKeepAlive++;
        if (timeKeepAlive > 10000) {
            timeKeepAlive = 0;
        }
        processKeepAlives();
    }

    public void processKeepAlives() {
        Arrow.getInstance().getProfileManager().getProfileMap().forEach((uuid, user) -> {
            WrapperPlayServerKeepAlive wrappedOutKeepAlivePacket = new WrapperPlayServerKeepAlive(timeKeepAlive);
            try {
                user.sendPacket(wrappedOutKeepAlivePacket);
                //log("Sent keepalive ID: " + timeKeepAlive + " to " + user.getPlayer().getName());
                user.getSentKeepAlives().put(timeKeepAlive, System.currentTimeMillis());
            } catch (Exception e) {
                log("Failed to send keep-alive packet: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}
