package me.arrow.utils.customutils.animationSystem;


import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import me.arrow.files.Config;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class AnimationManager {

    private static final double VIEW_DISTANCE = 50.0D;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;

    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, ActiveAnimation> activeAnimations = new ConcurrentHashMap<>();

    public AnimationManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(new AnimationListener(this), plugin);
    }

    public boolean play(Animation.Type type, Player player, Runnable finished) {
        if (type == null || player == null) return false;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> play(type, player, finished));
            return true;
        }

        if (!Config.Setting.BAN_ANIMATION_ENABLED.getBoolean()) {
            if (finished != null) {
                finished.run();
            }
            return true;
        }

        if (!player.isOnline()) return false;

        UUID uuid = player.getUniqueId();

        if (activeAnimations.containsKey(uuid)) {
            return false;
        }

        Animation animation = Animation.create(type, this, player);
        ActiveAnimation activeAnimation = new ActiveAnimation(animation, finished);

        ActiveAnimation existing = activeAnimations.putIfAbsent(uuid, activeAnimation);
        if (existing != null) return false;

        animation.start();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ActiveAnimation current = activeAnimations.get(uuid);

            if (current == null || current != activeAnimation) {
                return;
            }

            try {
                animation.advance();

                if (animation.isFinished()) {
                    finish(uuid, true);
                }
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE, "Animation failed for " + player.getName(), throwable);
                finish(uuid, true);
            }
        }, 0L, 1L);

        activeAnimation.setTask(task);
        return true;
    }

    public void finish(UUID uuid, boolean runCallback) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> finish(uuid, runCallback));
            return;
        }

        ActiveAnimation activeAnimation = activeAnimations.remove(uuid);
        if (activeAnimation == null) return;

        if (activeAnimation.task != null) {
            activeAnimation.task.cancel();
        }

        try {
            activeAnimation.animation.finish();
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.SEVERE, "Animation finish failed", throwable);
        }

        if (runCallback && activeAnimation.finished != null) {
            try {
                activeAnimation.finished.run();
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE, "Animation callback failed", throwable);
            }
        }
    }

    public boolean isInAnimation(Player player) {
        return player != null && isInAnimation(player.getUniqueId());
    }

    public boolean isInAnimation(UUID uuid) {
        return uuid != null && activeAnimations.containsKey(uuid);
    }

    public Location getLockLocation(UUID uuid) {
        ActiveAnimation activeAnimation = activeAnimations.get(uuid);
        if (activeAnimation == null) return null;
        return activeAnimation.animation.getOrigin();
    }

    public Float getForcedYaw(UUID uuid) {
        ActiveAnimation activeAnimation = activeAnimations.get(uuid);
        if (activeAnimation == null) return null;
        return activeAnimation.animation.getForcedYaw();
    }

    public Float getForcedPitch(UUID uuid) {
        ActiveAnimation activeAnimation = activeAnimations.get(uuid);
        if (activeAnimation == null) return null;
        return activeAnimation.animation.getForcedPitch();
    }

    public void forceLook(Player player, Location location, float yaw, float pitch) {
        if (player == null || !player.isOnline() || location == null) return;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> forceLook(player, location, yaw, pitch));
            return;
        }

        Location fixed = location.clone();
        fixed.setYaw(yaw);
        fixed.setPitch(pitch);
        player.teleport(fixed);
        player.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
    }

    public void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (player == null || !player.isOnline()) return;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> sendTitle(player, title, subtitle, fadeIn, stay, fadeOut));
            return;
        }

        try {
            Method method = Player.class.getMethod(
                    "sendTitle",
                    String.class,
                    String.class,
                    int.class,
                    int.class,
                    int.class
            );

            method.invoke(player, title, subtitle, fadeIn, stay, fadeOut);
        } catch (Throwable ignored) {
            player.sendMessage(title);

            if (subtitle != null && !subtitle.isEmpty()) {
                player.sendMessage(subtitle);
            }
        }
    }

    public void sendParticle(Location center, Particle<?> particle, double x, double y, double z, float offsetX, float offsetY, float offsetZ, float speed, int count) {
        List<ParticlePoint> points = new ArrayList<>();
        points.add(new ParticlePoint(x, y, z, offsetX, offsetY, offsetZ, speed, count));
        sendParticleBatch(center, particle, points);
    }

    public void sendParticleBatch(Location center, Particle<?> particle, List<ParticlePoint> points) {
        if (center == null || center.getWorld() == null || particle == null || points == null || points.isEmpty()) return;

        List<Player> viewers = getNearbyViewers(center);
        if (viewers.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (ParticlePoint point : points) {
                WrapperPlayServerParticle packet = new WrapperPlayServerParticle(
                        particle,
                        false,
                        new Vector3d(point.x, point.y, point.z),
                        new Vector3f(point.offsetX, point.offsetY, point.offsetZ),
                        point.speed,
                        point.count
                );

                for (Player viewer : viewers) {
                    if (viewer == null || !viewer.isOnline()) continue;
                    PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
                }
            }
        });
    }

    public void playSoundNearby(Location location, float volume, float pitch, String... soundNames) {
        if (location == null || location.getWorld() == null || soundNames == null || soundNames.length == 0) return;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> playSoundNearby(location, volume, pitch, soundNames));
            return;
        }

        for (Player viewer : getNearbyViewers(location)) {
            playSoundCompat(viewer, location, volume, pitch, soundNames);
        }
    }

    public void lightningEffect(Location location) {
        if (location == null || location.getWorld() == null) return;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> lightningEffect(location));
            return;
        }

        location.getWorld().strikeLightningEffect(location);
    }

    public List<Player> getNearbyViewers(Location center) {
        List<Player> viewers = new ArrayList<>();

        World world = center.getWorld();
        if (world == null) return viewers;

        for (Player viewer : world.getPlayers()) {
            if (viewer == null || !viewer.isOnline()) continue;

            Location viewerLocation = viewer.getLocation();
            if (viewerLocation.getWorld() == null || !viewerLocation.getWorld().equals(world)) continue;

            if (viewerLocation.distanceSquared(center) <= VIEW_DISTANCE_SQUARED) {
                viewers.add(viewer);
            }
        }

        return viewers;
    }

    private void playSoundCompat(Player player, Location location, float volume, float pitch, String... soundNames) {
        if (player == null || !player.isOnline()) return;

        for (String soundName : soundNames) {
            try {
                Sound sound = Sound.valueOf(soundName);
                player.playSound(location, sound, volume, pitch);
                return;
            } catch (Throwable ignored) {
            }
        }

        for (String soundName : soundNames) {
            try {
                Method method = Player.class.getMethod(
                        "playSound",
                        Location.class,
                        String.class,
                        float.class,
                        float.class
                );

                method.invoke(player, location, soundName.toLowerCase(Locale.ROOT), volume, pitch);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    public static final class ParticlePoint {

        private final double x;
        private final double y;
        private final double z;
        private final float offsetX;
        private final float offsetY;
        private final float offsetZ;
        private final float speed;
        private final int count;

        public ParticlePoint(double x, double y, double z, float offsetX, float offsetY, float offsetZ, float speed, int count) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.speed = speed;
            this.count = count;
        }
    }

    private static final class ActiveAnimation {

        private final Animation animation;
        private final Runnable finished;
        private BukkitTask task;

        private ActiveAnimation(Animation animation, Runnable finished) {
            this.animation = animation;
            this.finished = finished;
        }

        private void setTask(BukkitTask task) {
            this.task = task;
        }
    }
}
