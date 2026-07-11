package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class DamageData implements Data {

    private static final int DEFAULT_MEMORY_TICKS = 20;
    private static final int STALE_TICK = Integer.MIN_VALUE / 2;

    private final Profile profile;
    private final AtomicIntegerArray damageTicks;

    private volatile EntityDamageEvent.DamageCause lastCause;
    private volatile int lastTick = STALE_TICK;
    private volatile int lastSelfProjectileTick = STALE_TICK;
    private volatile int lastSelfBowTick = STALE_TICK;
    private volatile int lastSelfBowPunchLevel;

    public DamageData(Profile profile) {
        this.profile = profile;
        this.damageTicks = new AtomicIntegerArray(EntityDamageEvent.DamageCause.values().length);

        for (int i = 0; i < this.damageTicks.length(); i++) {
            this.damageTicks.set(i, STALE_TICK);
        }
    }

    public void record(EntityDamageEvent.DamageCause cause) {
        if (cause == null) {
            return;
        }

        int tick = profile.getTick();
        this.damageTicks.set(cause.ordinal(), tick);
        this.lastCause = cause;
        this.lastTick = tick;
    }

    public void record(EntityDamageEvent event) {
        if (event == null) {
            return;
        }

        record(event.getCause());

        if (event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE
                || !(event instanceof EntityDamageByEntityEvent damageByEntity)
                || !(damageByEntity.getDamager() instanceof Projectile projectile)) {
            return;
        }

        Object shooter = projectile.getShooter();

        if (shooter instanceof Player shooterPlayer
                && profile.getPlayer() != null
                && shooterPlayer.getUniqueId().equals(profile.getPlayer().getUniqueId())) {
            this.lastSelfProjectileTick = profile.getTick();

            if (projectile instanceof Arrow arrow) {
                this.lastSelfBowTick = profile.getTick();
                this.lastSelfBowPunchLevel = getArrowKnockbackStrength(arrow);
            }
        }
    }

    public boolean hasSelfProjectile(int ticks) {
        return hasNotPassed(this.lastSelfProjectileTick, ticks);
    }

    public boolean hasSelfBowBoost(int ticks) {
        return hasNotPassed(this.lastSelfBowTick, ticks);
    }

    public int getSelfBowPunchLevel(int ticks) {
        return hasSelfBowBoost(ticks) ? this.lastSelfBowPunchLevel : -1;
    }

    private static int getArrowKnockbackStrength(Arrow arrow) {
        try {
            Method method = arrow.getClass().getMethod("getKnockbackStrength");
            Object value = method.invoke(arrow);
            int level = value instanceof Number ? ((Number) value).intValue() : 0;
            return Math.max(0, Math.min(2, level));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public boolean hasCause(EntityDamageEvent.DamageCause cause) {
        return hasCause(cause, DEFAULT_MEMORY_TICKS);
    }

    public boolean hasCause(EntityDamageEvent.DamageCause cause, int ticks) {
        return getCause(cause, ticks) != null;
    }

    public boolean hasAnyCause(Set<EntityDamageEvent.DamageCause> causes) {
        return hasAnyCause(causes, DEFAULT_MEMORY_TICKS);
    }

    public boolean hasAnyCause(Set<EntityDamageEvent.DamageCause> causes, int ticks) {
        if (causes == null || causes.isEmpty()) {
            return false;
        }

        for (EntityDamageEvent.DamageCause cause : causes) {
            if (hasCause(cause, ticks)) {
                return true;
            }
        }

        return false;
    }

    public EntityDamageEvent.DamageCause getCause(EntityDamageEvent.DamageCause cause, int ticks) {
        if (cause == null) {
            return null;
        }

        int causeTick = this.damageTicks.get(cause.ordinal());
        return hasNotPassed(causeTick, ticks) ? cause : null;
    }

    public EntityDamageEvent.DamageCause getLastCause() {
        return getLastCause(DEFAULT_MEMORY_TICKS);
    }

    public EntityDamageEvent.DamageCause getLastCause(int ticks) {
        return hasNotPassed(this.lastTick, ticks) ? this.lastCause : null;
    }

    public int getTicksSince(EntityDamageEvent.DamageCause cause) {
        if (cause == null) {
            return Integer.MAX_VALUE;
        }

        int causeTick = this.damageTicks.get(cause.ordinal());
        if (causeTick == STALE_TICK) {
            return Integer.MAX_VALUE;
        }

        return Math.max(0, profile.getTick() - causeTick);
    }

    private boolean hasNotPassed(int tick, int requestedTicks) {
        if (tick == STALE_TICK) {
            return false;
        }

        int ticks = Math.min(Math.max(requestedTicks, 0), DEFAULT_MEMORY_TICKS);
        return profile.getTick() - tick <= ticks;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {

    }

    @Override
    public void processSend(PacketSendEvent event) {

    }
}
