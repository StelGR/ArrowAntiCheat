package me.arrow.backend.fabric;

import com.github.retrooper.packetevents.PacketEvents;
import me.arrow.core.movement.MovementStateStore;
import me.arrow.core.thread.ThreadManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Native Fabric bootstrap. It never resolves a Bukkit type. */
public final class FabricRuntime {

    private static final Logger LOGGER = Logger.getLogger("Arrow");

    private final AtomicBoolean started = new AtomicBoolean();
    private final ThreadManager threadManager = new ThreadManager();
    private final MovementStateStore movementStates = new MovementStateStore();
    private final FabricPacketListener packetListener = new FabricPacketListener(movementStates);
    private Path dataDirectory;

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        FabricLoader loader = FabricLoader.getInstance();
        dataDirectory = loader.getConfigDir().resolve("arrow");
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Arrow's Fabric config directory", exception);
        }

        if (!loader.isModLoaded("packetevents")
                || PacketEvents.getAPI() == null
                || !PacketEvents.getAPI().isInitialized()) {
            throw new IllegalStateException("Arrow requires an initialized PacketEvents Fabric backend");
        }

        String minecraftVersion = loader.getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String packetEventsVersion = loader.getModContainer("packetevents")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        if ("26.3".equals(minecraftVersion) && !packetEventsVersion.startsWith("2.14.")) {
            throw new IllegalStateException("Minecraft 26.3 requires PacketEvents Fabric 2.14.0-SNAPSHOT or newer");
        }

        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        LOGGER.info("Arrow Fabric runtime loaded for Minecraft " + minecraftVersion
                + " with PacketEvents " + packetEventsVersion + ".");
    }

    public Path getDataDirectory() {
        if (!started.get()) {
            throw new IllegalStateException("FabricRuntime has not started");
        }
        return dataDirectory;
    }

    public boolean isPacketEventsReady() {
        return started.get() && PacketEvents.getAPI().isInitialized();
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    public MovementStateStore getMovementStates() {
        return movementStates;
    }
}
