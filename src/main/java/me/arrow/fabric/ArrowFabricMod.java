package me.arrow.fabric;

import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.platform.PlatformBackend;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;

import java.io.File;

/**
 * Fabric mod entrypoint for ArrowAntiCheat.
 */
@Getter
public class ArrowFabricMod implements ModInitializer, DedicatedServerModInitializer {

    @Getter
    private static ArrowFabricMod instance;
    private Arrow arrow;

    @Override
    public void onInitialize() {
        init();
    }

    @Override
    public void onInitializeServer() {
        init();
    }

    private synchronized void init() {
        if (instance != null) {
            return;
        }
        instance = this;

        File dataFolder = new File("config/arrow");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // Initialize backend for Fabric
        PlatformBackend.initialize(null);

        // Instantiate and enable Arrow
        this.arrow = new Arrow(null, dataFolder);
        this.arrow.onEnable();

        // Hook server shutdown lifecycle if Fabric API lifecycle is present
        try {
            Class<?> lifecycleEventsClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
            Object serverStopping = lifecycleEventsClass.getField("SERVER_STOPPING").get(null);
            if (serverStopping != null) {
                // Register server stopping callback
                java.lang.reflect.Method register = serverStopping.getClass().getMethod("register", Object.class);
                Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopping");
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        callbackClass.getClassLoader(),
                        new Class<?>[]{callbackClass},
                        (p, method, args) -> {
                            if (arrow != null) {
                                arrow.onDisable();
                            }
                            return null;
                        }
                );
                register.invoke(serverStopping, proxy);
            }
        } catch (Throwable ignored) {
            // Fabric lifecycle API optional
        }
    }

}
