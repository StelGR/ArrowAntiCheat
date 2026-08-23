package me.arrow.utils.custom.desync;

import me.arrow.Arrow;
import me.arrow.managers.profile.Profile;
import me.arrow.tasks.TickTask;
import me.arrow.utils.MathUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;

// slightly useful, although only for omnisprint or noslow really

/**
 * A simple class that is going to help us handle and fix any type of desync with the client
 */
public class Desync {

    private static Inventory cachedInventory;
    private final Profile profile;
    private int lastFixedTicks;

    public Desync(Profile profile) {
        this.profile = profile;
    }

    private static Inventory getCachedInventory() {
        if (cachedInventory != null) return cachedInventory;
        if (me.arrow.platform.PlatformBackend.get().isFabric()) return null;
        try {
            cachedInventory = Bukkit.createInventory(null, InventoryType.PLAYER);
        } catch (Throwable ignored) {
        }
        return cachedInventory;
    }

    public void fix(DesyncType desyncType) {

        //Make sure this method isn't being spammed by any check that hasn't updated its status yet
        if (MathUtils.elapsedTicks(this.lastFixedTicks) < 15) return;

        final Player player = profile.getPlayer();

        switch (desyncType) {

            case BLOCKING:

                final PlayerInventory inventory = player.getInventory();

                final int currentSlot = inventory.getHeldItemSlot();

                final int nextSlot = currentSlot == 0 ? currentSlot + 1 : currentSlot - 1;

                inventory.setHeldItemSlot(nextSlot);

                break;

            case SNEAKING:
            case SPRINTING:

                /*
                We need to do this on the main thread due to async catchers
                The reason we're doing it one by one after a tick
                Is due to certain clients not properly un-sneaking, un-sprinting
                If this gets executed instantly.
                 */
                final int[] state = {0};
                final me.arrow.utils.TaskUtils.CancellableTask[] taskHolder = new me.arrow.utils.TaskUtils.CancellableTask[1];
                taskHolder[0] = me.arrow.utils.TaskUtils.taskTimer(() -> {
                    switch (state[0]++) {
                        case 1:
                            player.closeInventory();
                            break;
                        case 2:
                            Inventory inv = getCachedInventory();
                            if (inv != null) {
                                player.openInventory(inv);
                            }
                            break;
                        case 3:
                            player.closeInventory();
                            if (taskHolder[0] != null) {
                                taskHolder[0].cancel();
                            }
                            break;
                    }
                }, 0L, 1L);

                break;
        }

        this.lastFixedTicks = TickTask.getCurrentTick();
    }
}