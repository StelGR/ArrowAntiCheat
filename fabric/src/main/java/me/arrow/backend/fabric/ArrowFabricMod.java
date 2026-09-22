package me.arrow.backend.fabric;

import me.arrow.backend.fabric.FabricRuntime;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;

/** Native 26.3 Fabric entrypoint; Bukkit classes are outside this module. */
public final class ArrowFabricMod implements ModInitializer, DedicatedServerModInitializer {

    private static FabricRuntime runtime;

    @Override
    public void onInitialize() {
        start();
    }

    @Override
    public void onInitializeServer() {
        start();
    }

    public static synchronized FabricRuntime getRuntime() {
        start();
        return runtime;
    }

    private static void start() {
        if (runtime == null) {
            runtime = new FabricRuntime();
            runtime.start();
        }
    }
}
