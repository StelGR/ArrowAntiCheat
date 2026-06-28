package me.arrow.utils.customutils.animationSystem;

import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public abstract class Animation {

    protected static Particle<?> LAVA = new Particle<>(ParticleTypes.LAVA);
    protected static final Particle<?> FLAME = new Particle<>(ParticleTypes.FLAME);
    protected static final Particle<?> SMOKE = new Particle<>(ParticleTypes.SMOKE);
    protected static final Particle<?> CLOUD = new Particle<>(ParticleTypes.CLOUD);
    protected static final Particle<?> CRIT = new Particle<>(ParticleTypes.CRIT);
    protected static final Particle<?> EXPLOSION = new Particle<>(ParticleTypes.EXPLOSION);

    protected final AnimationManager manager;
    protected final Player player;
    protected final Location origin;

    protected int tick;
    @Getter
    private boolean finished;

    protected Animation(AnimationManager manager, Player player) {
        this.manager = manager;
        this.player = player;
        this.origin = player.getLocation().clone();
    }

    public enum Type {
        DESTROYED,
        ANVIL,
        NOISY
    }

    public static Animation create(Type type, AnimationManager manager, Player player) {
        return switch (type) {
            case ANVIL -> new AnvilAnimation(manager, player);
            case NOISY -> new NoisyAnimation(manager, player);
            default -> new DestroyedAnimation(manager, player);
        };
    }

    public Location getOrigin() {
        return origin.clone();
    }

    public Float getForcedYaw() {
        return null;
    }

    public Float getForcedPitch() {
        return null;
    }

    public void start() {
        onStart();
    }

    public void advance() {
        if (finished) return;

        if (!player.isOnline()) {
            finished = true;
            return;
        }

        onTick();

        tick++;

        if (tick >= getDurationTicks()) {
            finished = true;
        }
    }

    public void finish() {
        onFinish();
    }

    protected void markFinished() {
        this.finished = true;
    }

    protected Location center() {
        return origin.clone().add(0.0D, 0.05D, 0.0D);
    }

    protected AnimationManager.ParticlePoint point(Location center, double addX, double addY, double addZ) {
        return new AnimationManager.ParticlePoint(
                center.getX() + addX,
                center.getY() + addY,
                center.getZ() + addZ,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                1
        );
    }

    protected abstract int getDurationTicks();

    protected abstract void onStart();

    protected abstract void onTick();

    protected abstract void onFinish();

    private static final class DestroyedAnimation extends Animation {

        private DestroyedAnimation(AnimationManager manager, Player player) {
            super(manager, player);
        }

        @Override
        protected int getDurationTicks() {
            return 160;
        }

        @Override
        protected void onStart() {
            manager.sendTitle(
                    player,
                    "§4§lDESTROYED",
                    "§7That client did not save you.",
                    5,
                    150,
                    10
            );
        }

        @Override
        protected void onTick() {
            Location center = center();
            double progress = tick / (double) getDurationTicks();

            List<AnimationManager.ParticlePoint> lavaPoints = new ArrayList<>();
            List<AnimationManager.ParticlePoint> flamePoints = new ArrayList<>();

            int points = 6 + Math.min(16, tick / 7);
            double radius = 0.25D + progress * 1.45D;

            for (int i = 0; i < points; i++) {
                double angle = tick * 0.18D + ((Math.PI * 2.0D) / points) * i;
                double y = 0.10D + (((tick + i * 9) % 95) / 95.0D) * 2.45D;

                double x = Math.cos(angle) * radius;
                double z = Math.sin(angle) * radius;

                lavaPoints.add(point(center, x, y, z));

                if (tick > 35 && i % 2 == 0) {
                    flamePoints.add(point(center, x * 0.85D, y, z * 0.85D));
                }
            }

            manager.sendParticleBatch(center, LAVA, lavaPoints);

            if (!flamePoints.isEmpty()) {
                manager.sendParticleBatch(center, FLAME, flamePoints);
            }

            if (tick == 65) {
                manager.sendTitle(
                        player,
                        "§c§lNICE TRY",
                        "§7Your packets told a different story.",
                        0,
                        90,
                        10
                );
            }

            if (tick % 35 == 0) {
                manager.playSoundNearby(
                        center,
                        1.2F,
                        0.65F,
                        "BLOCK_LAVA_POP",
                        "LAVA_POP"
                );
            }
        }

        @Override
        protected void onFinish() {
            Location center = center();
            List<AnimationManager.ParticlePoint> burst = new ArrayList<>();

            for (int ring = 0; ring < 3; ring++) {
                double y = 0.35D + ring * 0.75D;

                for (int i = 0; i < 32; i++) {
                    double angle = ((Math.PI * 2.0D) / 32.0D) * i;
                    double radius = 1.35D;

                    burst.add(point(
                            center,
                            Math.cos(angle) * radius,
                            y,
                            Math.sin(angle) * radius
                    ));
                }
            }

            manager.sendParticleBatch(center, LAVA, burst);
            manager.sendParticle(center, EXPLOSION, center.getX(), center.getY() + 1.0D, center.getZ(), 0.0F, 0.0F, 0.0F, 0.0F, 1);

            manager.playSoundNearby(
                    center,
                    2.0F,
                    0.65F,
                    "ENTITY_GENERIC_EXPLODE",
                    "EXPLODE"
            );
        }
    }

    private static final class AnvilAnimation extends Animation {

        private FallingBlock anvil;

        private AnvilAnimation(AnimationManager manager, Player player) {
            super(manager, player);
        }

        @Override
        protected int getDurationTicks() {
            return 120;
        }

        @Override
        public Float getForcedYaw() {
            return origin.getYaw();
        }

        @Override
        public Float getForcedPitch() {
            return -90.0F;
        }

        @Override
        protected void onStart() {
            manager.forceLook(player, origin, origin.getYaw(), -90.0F);

            manager.sendTitle(
                    player,
                    "§c§lLOOK UP",
                    "§7Your delivery has arrived.",
                    0,
                    100,
                    5
            );

            Location spawnLocation = origin.clone().add(0.0D, 8.0D, 0.0D);
            this.anvil = spawnFallingAnvil(spawnLocation);

            if (this.anvil != null) {
                this.anvil.setVelocity(new Vector(0.0D, -0.05D, 0.0D));

                try {
                    this.anvil.setDropItem(false);
                } catch (Throwable ignored) {
                }

                try {
                    this.anvil.setHurtEntities(false);
                } catch (Throwable ignored) {
                }
            }

            manager.playSoundNearby(
                    origin,
                    2.0F,
                    0.75F,
                    "BLOCK_ANVIL_PLACE",
                    "ANVIL_LAND"
            );
        }

        @Override
        protected void onTick() {
            if (tick % 3 == 0) {
                manager.forceLook(player, origin, origin.getYaw(), -90.0F);
            }

            Location center = center();

            if (tick % 6 == 0) {
                List<AnimationManager.ParticlePoint> points = new ArrayList<>();

                for (int i = 0; i < 12; i++) {
                    double angle = ((Math.PI * 2.0D) / 12.0D) * i + tick * 0.05D;
                    points.add(point(center, Math.cos(angle) * 0.55D, 2.2D, Math.sin(angle) * 0.55D));
                }

                manager.sendParticleBatch(center, CRIT, points);
            }

            if (anvil == null || anvil.isDead()) {
                if (tick > 10) {
                    markFinished();
                }

                return;
            }

            Location anvilLocation = anvil.getLocation();

            if (anvilLocation.getY() <= origin.getY() + 2.05D) {
                markFinished();
            }
        }

        @Override
        protected void onFinish() {
            Location center = center();

            if (anvil != null && !anvil.isDead()) {
                anvil.remove();
            }

            List<AnimationManager.ParticlePoint> smoke = new ArrayList<>();

            for (int i = 0; i < 40; i++) {
                double angle = ((Math.PI * 2.0D) / 40.0D) * i;
                double radius = 0.8D + (i % 4) * 0.12D;
                double y = 0.15D + (i % 5) * 0.25D;

                smoke.add(point(center, Math.cos(angle) * radius, y, Math.sin(angle) * radius));
            }

            manager.sendParticleBatch(center, SMOKE, smoke);

            manager.playSoundNearby(
                    center,
                    3.0F,
                    0.55F,
                    "BLOCK_ANVIL_LAND",
                    "ANVIL_LAND"
            );

            manager.sendTitle(
                    player,
                    "§4§lBONK",
                    "§7That one looked personal.",
                    0,
                    30,
                    5
            );
        }

        private FallingBlock spawnFallingAnvil(Location location) {
            if (location == null || location.getWorld() == null) return null;

            World world = location.getWorld();
            Material anvilMaterial = getMaterial("ANVIL");

            if (anvilMaterial == null) return null;

            try {
                Method createBlockData = Material.class.getMethod("createBlockData");
                Object blockData = createBlockData.invoke(anvilMaterial);

                Class<?> blockDataClass = Class.forName("org.bukkit.block.data.BlockData");
                Method spawnMethod = World.class.getMethod("spawnFallingBlock", Location.class, blockDataClass);

                return (FallingBlock) spawnMethod.invoke(world, location, blockData);
            } catch (Throwable ignored) {
                try {
                    return world.spawnFallingBlock(location, anvilMaterial, (byte) 0);
                } catch (Throwable throwable) {
                    return null;
                }
            }
        }

        private Material getMaterial(String... names) {
            for (String name : names) {
                try {
                    return Material.valueOf(name);
                } catch (Throwable ignored) {
                }
            }

            return null;
        }
    }

    private static final class NoisyAnimation extends Animation {

        private NoisyAnimation(AnimationManager manager, Player player) {
            super(manager, player);
        }

        @Override
        protected int getDurationTicks() {
            return 160;
        }

        @Override
        protected void onStart() {
            manager.sendTitle(
                    player,
                    "§5§lPANIC MODE",
                    "§7Maybe blame lag. That always works.",
                    0,
                    170,
                    5
            );
        }

        @Override
        protected void onTick() {
            Location center = center();

            if (tick % 35 == 0) {
                manager.sendTitle(
                        player,
                        "§5§lPANIC MODE",
                        "§7Maybe blame lag. That always works.",
                        0,
                        50,
                        5
                );
            }

            if (tick % 2 == 0) {
                manager.playSoundNearby(
                        center,
                        2.8F,
                        ThreadLocalRandom.current().nextFloat() * 0.35F + 0.65F,
                        "BLOCK_WOODEN_DOOR_OPEN",
                        "BLOCK_IRON_DOOR_OPEN",
                        "DOOR_OPEN"
                );
            }

            if (tick % 5 == 0) {
                manager.playSoundNearby(
                        center,
                        2.4F,
                        ThreadLocalRandom.current().nextFloat() * 0.35F + 0.75F,
                        "BLOCK_WOODEN_DOOR_CLOSE",
                        "BLOCK_IRON_DOOR_CLOSE",
                        "DOOR_CLOSE"
                );
            }

            if (tick % 12 == 0) {
                for (int i = 0; i < 3; i++) {
                    double angle = ThreadLocalRandom.current().nextDouble(0.0D, Math.PI * 2.0D);
                    double radius = ThreadLocalRandom.current().nextDouble(1.8D, 4.5D);

                    Location strike = center.clone().add(
                            Math.cos(angle) * radius,
                            0.0D,
                            Math.sin(angle) * radius
                    );

                    manager.lightningEffect(strike);
                }
            }

            if (tick % 3 == 0) {
                List<AnimationManager.ParticlePoint> points = new ArrayList<>();

                for (int i = 0; i < 20; i++) {
                    double angle = ((Math.PI * 2.0D) / 20.0D) * i + tick * 0.25D;
                    double radius = 1.1D + Math.sin((tick + i) * 0.15D) * 0.25D;
                    double y = 0.25D + (i % 8) * 0.25D;

                    points.add(point(
                            center,
                            Math.cos(angle) * radius,
                            y,
                            Math.sin(angle) * radius
                    ));
                }

                manager.sendParticleBatch(center, CLOUD, points);
            }

            if (tick == 75) {
                manager.sendTitle(
                        player,
                        "§d§lTOO LOUD?",
                        "§7The server heard your packets first.",
                        0,
                        80,
                        5
                );
            }
        }

        @Override
        protected void onFinish() {
            Location center = center();

            manager.sendParticle(center, EXPLOSION, center.getX(), center.getY() + 1.0D, center.getZ(), 0.0F, 0.0F, 0.0F, 0.0F, 1);

            manager.playSoundNearby(
                    center,
                    3.0F,
                    0.6F,
                    "ENTITY_LIGHTNING_BOLT_THUNDER",
                    "AMBIENCE_THUNDER"
            );
        }
    }
}
