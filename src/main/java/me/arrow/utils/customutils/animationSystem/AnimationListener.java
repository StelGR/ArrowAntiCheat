package me.arrow.utils.customutils.animationSystem;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class AnimationListener implements Listener {

    private final AnimationManager manager;

    public AnimationListener(AnimationManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!manager.isInAnimation(uuid)) return;

        Location to = event.getTo();

        if (to == null) {
            event.setCancelled(true);
            return;
        }

        Location lock = manager.getLockLocation(uuid);

        if (lock == null || lock.getWorld() == null) {
            event.setCancelled(true);
            return;
        }

        if (to.getWorld() == null || !to.getWorld().equals(lock.getWorld())) {
            event.setCancelled(true);
            return;
        }

        Float forcedYaw = manager.getForcedYaw(uuid);
        Float forcedPitch = manager.getForcedPitch(uuid);

        Location fixed = lock.clone();

        if (forcedYaw != null) {
            fixed.setYaw(forcedYaw);
        } else {
            fixed.setYaw(to.getYaw());
        }

        if (forcedPitch != null) {
            fixed.setPitch(forcedPitch);
        } else {
            fixed.setPitch(to.getPitch());
        }

        event.setTo(fixed);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!manager.isInAnimation(event.getPlayer())) return;

        event.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();

        if (entity instanceof Player && manager.isInAnimation((Player) entity)) {
            event.setDamage(0.0D);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (isAnimationEntity(event.getEntity()) || isAnimationDamager(event.getDamager())) {
            event.setDamage(0.0D);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!manager.isInAnimation(event.getPlayer())) return;

        manager.finish(event.getPlayer().getUniqueId(), true);
    }

    private boolean isAnimationEntity(Entity entity) {
        return entity instanceof Player && manager.isInAnimation((Player) entity);
    }

    private boolean isAnimationDamager(Entity entity) {
        if (entity instanceof Player) {
            return manager.isInAnimation((Player) entity);
        }

        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();

            if (source instanceof Player) {
                return manager.isInAnimation((Player) source);
            }
        }

        return false;
    }
}
