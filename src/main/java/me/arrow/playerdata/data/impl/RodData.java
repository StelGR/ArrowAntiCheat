package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import me.arrow.Arrow;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

// this is my favorite class honestly, back when i started porting to modern (1.21.6 at the time), i told gpt
// to make me a rod calculator, and it pulled out this after 5 prompts?
// isn't it neat how i don't really have to code stuff i don't know and we can make good projects without
// fighting each other and instead helping
// GPT shows the entire point of that, that we don't need to act like little childern when someone makes something better than us
// there's always someone you can learn from, without turning into toxic nonsense, every anticheat has it's place
// and i simply wish to make this one compatible with alot of servers, even if it's not the best

/**
 * Centralized Rod handling: listener, pending registry, and packet consumer.
 */
public class RodData implements Data {

    // ---------------- per-profile fields ----------------
    private final Profile profile;
    private volatile Vector rodVelocity = new Vector(0, 0, 0);
    private volatile long rodExemptUntil = 0L;

    private static final long EXEMPT_MS = 1000L; // how long to exempt after rod velocity arrives
    private static final long PENDING_WINDOW_MS = 1000L; // valid ms for a REEL_IN pending entry
    private static final long AUTO_REMOVE_TICKS = 20L; // ~1s cleanup

    // ---------------- static registry & listener management ----------------
    private static final ConcurrentMap<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicBoolean INITED = new AtomicBoolean(false);
    private static Plugin PLUGIN;

    // Toggle if your wrapper returns fixed-point ints (divide by 8000)
    private static final boolean SCALE_VELOCITY_BY_8000 = false;

    public RodData(Profile profile) {
        this.profile = profile;
    }

    /**
     * Call once from onEnable()
     */
    public static void init(Plugin plugin) {
        if (INITED.compareAndSet(false, true)) {
            PLUGIN = plugin;
            plugin.getServer().getPluginManager().registerEvents(new FishListener(), plugin);
            //OtherUtility.log("[RodData] initialized (listener registered).");
        }
    }

    // ---------------- Bukkit listener ----------------
    private static final class FishListener implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onPlayerFish(PlayerFishEvent event) {
            // Accept both CAUGHT_ENTITY and REEL_IN to be robust
            Player owner = event.getPlayer();
            Entity hooked = event.getHook() == null ? null : event.getHook().getHookedEntity();

            if (hooked instanceof Player target && (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY || event.getState() == PlayerFishEvent.State.REEL_IN)) {
                int id = target.getEntityId();
                UUID uuid = target.getUniqueId();

                if (target == owner) return;

                PENDING.put(id, new Pending(uuid, System.currentTimeMillis()));
                if (Config.Setting.DEBUG.getBoolean()) if (PLUGIN != null) OtherUtility.log("[RodData] REEL_IN/CAUGHT registered for target=" + target.getName() + " id=" + id + " owner=" + owner.getName());

                // immediate reset on target profile (helps some race cases)
                Profile p = Arrow.getInstance().getProfileManager().getProfile(target);
                if (p != null) {
                    try { p.getReelingTicks().reset(); } catch (Throwable ignored) {}
                }

                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("[RodData] REEL_IN/CAUGHT reset ticks for target=" + target.getName() + " id=" + id + " owner=" + owner.getName());

                // schedule cleanup
                new BukkitRunnable() {
                    @Override
                    public void run() { PENDING.remove(id); }
                }.runTaskLater(Arrow.getInstance().getHost(), AUTO_REMOVE_TICKS);
            }
        }
    }

    // ---------------- static packet consumer ----------------

    /**
     * Call this from VelocityData when you see an outgoing ENTITY_VELOCITY packet.
     * Returns true if the packet was identified as a rod-pull and applied to a profile.
     */
    public boolean handleEntityVelocityPacket(WrapperPlayServerEntityVelocity wrapper) {
        if (wrapper == null) return false;
        int packetEntityId = wrapper.getEntityId();

        Pending pending = PENDING.remove(packetEntityId);
        if (pending == null) {
            if (PLUGIN != null) PLUGIN.getLogger().finest("[RodData] no pending REEL for id=" + packetEntityId);
            return false;
        }
        if (System.currentTimeMillis() - pending.timestamp > PENDING_WINDOW_MS) {
            if (Config.Setting.DEBUG.getBoolean()) if (PLUGIN != null) OtherUtility.log("[RodData] pending REEL expired for id=" + packetEntityId);
            return false;
        }

        Player target = Bukkit.getPlayer(pending.uuid);
        if (target == null) {
            if (Config.Setting.DEBUG.getBoolean()) if (PLUGIN != null) OtherUtility.log("[RodData] pending player offline for uuid=" + pending.uuid);
            return false;
        }

        Profile profile = Arrow.getInstance().getProfileManager().getProfile(target);
        if (profile == null) {
            if (Config.Setting.DEBUG.getBoolean()) if (PLUGIN != null) OtherUtility.log("[RodData] no profile for player=" + target.getName());
            return false;
        }

        // Build vector
        double vx = wrapper.getVelocity().getX();
        double vy = wrapper.getVelocity().getY();
        double vz = wrapper.getVelocity().getZ();
        if (SCALE_VELOCITY_BY_8000) { vx /= 8000.0; vy /= 8000.0; vz /= 8000.0; }
        Vector rodVel = new Vector(vx, vy, vz);

        // get roddata instance and apply
        RodData rd = profile.getRodData();
        if (rd == null) {
            if (Config.Setting.DEBUG.getBoolean()) if (PLUGIN != null) OtherUtility.log("[RodData] profile has no RodData for player=" + target.getName());
            return false;
        }

        rd.applyRodVelocity(rodVel);

        if (Config.Setting.DEBUG.getBoolean()) if (PLUGIN != null) OtherUtility.log("[RodData] applied rod velocity to " + target.getName() + " id=" + packetEntityId + " vel=" + prettyVec(rodVel));
        return true;
    }

    // ---------------- per-profile apply & api ----------------

    public void applyRodVelocity(Vector vel) {
        if (vel == null) return;
        this.rodVelocity = vel.clone();
        this.rodExemptUntil = System.currentTimeMillis() + EXEMPT_MS;

        // schedule reelingTicks reset on main thread
        TaskUtils.task(() -> {
            try { if (profile != null) profile.getReelingTicks().reset(); }
            catch (Throwable t) { OtherUtility.log("[Arrow] error resetting reeling ticks: " + t); }
        });
    }

    public Vector getRodVelocity() { return rodVelocity.clone(); }
    public boolean isRodExempt() { return System.currentTimeMillis() < rodExemptUntil; }

    // no-op PacketEvents hooks (we use static consumer instead)
    @Override
    public void processSend(PacketSendEvent event) {

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            // Existing velocity handling
            WrapperPlayServerEntityVelocity wrapper = new WrapperPlayServerEntityVelocity(event);
            handleEntityVelocityPacket(wrapper);
        }
    }
    @Override
    public void processReceive(PacketReceiveEvent event) {

    }

    private static String prettyVec(Vector v) {
        return String.format("(%.3f, %.3f, %.3f)", v.getX(), v.getY(), v.getZ());
    }

    private static final class Pending {
        final UUID uuid;
        final long timestamp;
        Pending(UUID u, long t) { this.uuid = u; this.timestamp = t; }
    }
}
