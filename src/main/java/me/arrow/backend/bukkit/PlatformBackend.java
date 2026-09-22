package me.arrow.backend.bukkit;

/**
 * Bukkit-only compatibility name for the runtime bridge.
 *
 * <p>Keeping the short name makes the Bukkit migration mechanical while the
 * package prevents Fabric code from accidentally importing Bukkit APIs.</p>
 */
public abstract class PlatformBackend extends BukkitPlatformBackend {
}
