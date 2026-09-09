package me.arrow.playerdata.cache;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Universal cross-version ChunkSnapshot adapter for Bukkit/Spigot/Paper.
 * Supports Minecraft 1.7.10 through modern (1.20+, 26.2+) without throwing
 * NoSuchMethodError on legacy versions that lack ChunkSnapshot#getBlockType(int, int, int)
 * or modern versions that lack ChunkSnapshot#getBlockTypeId(int, int, int).
 */
public class SnapshotAdapter {

    private static final MethodHandle GET_BLOCK_TYPE_MH;
    private static final MethodHandle GET_BLOCK_TYPE_ID_MH;
    private static final MethodHandle IS_SECTION_EMPTY_MH;
    private static final MethodHandle GET_BLOCK_DATA_MH;

    private static final Material[] ID_TO_MATERIAL = new Material[256];

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle getBlockType = null;
        MethodHandle getBlockTypeId = null;
        MethodHandle isSectionEmpty = null;
        MethodHandle getBlockData = null;

        try {
            // Modern (1.13 - 26.2): Material getBlockType(int, int, int)
            Method m = ChunkSnapshot.class.getMethod("getBlockType", int.class, int.class, int.class);
            getBlockType = lookup.unreflect(m);
        } catch (Throwable ignored) {}

        try {
            // Legacy (1.7 - 1.12): int getBlockTypeId(int, int, int)
            Method m = ChunkSnapshot.class.getMethod("getBlockTypeId", int.class, int.class, int.class);
            getBlockTypeId = lookup.unreflect(m);
        } catch (Throwable ignored) {}

        try {
            Method m = ChunkSnapshot.class.getMethod("isSectionEmpty", int.class);
            isSectionEmpty = lookup.unreflect(m);
        } catch (Throwable ignored) {}

        try {
            Method m = ChunkSnapshot.class.getMethod("getBlockData", int.class, int.class, int.class);
            getBlockData = lookup.unreflect(m);
        } catch (Throwable ignored) {}

        GET_BLOCK_TYPE_MH = getBlockType;
        GET_BLOCK_TYPE_ID_MH = getBlockTypeId;
        IS_SECTION_EMPTY_MH = isSectionEmpty;
        GET_BLOCK_DATA_MH = getBlockData;

        // Populate ID -> Material table for 1.7 - 1.12 legacy versions
        for (int i = 0; i < 256; i++) {
            ID_TO_MATERIAL[i] = Material.AIR;
        }

        try {
            Method getMaterialMethod = Material.class.getMethod("getMaterial", int.class);
            for (int i = 0; i < 256; i++) {
                try {
                    Material mat = (Material) getMaterialMethod.invoke(null, i);
                    if (mat != null) {
                        ID_TO_MATERIAL[i] = mat;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            for (Material m : Material.values()) {
                try {
                    Method getId = m.getClass().getMethod("getId");
                    int id = (int) getId.invoke(m);
                    if (id >= 0 && id < 256) {
                        ID_TO_MATERIAL[id] = m;
                    }
                } catch (Throwable ignored2) {}
            }
        }
    }

    /**
     * Resolves the block Material from a ChunkSnapshot across all Minecraft versions.
     */
    public static Material getMaterial(ChunkSnapshot snapshot, int x, int y, int z) {
        if (snapshot == null) return Material.AIR;

        if (GET_BLOCK_TYPE_MH != null) {
            try {
                Material m = (Material) GET_BLOCK_TYPE_MH.invoke(snapshot, x, y, z);
                return m != null ? m : Material.AIR;
            } catch (Throwable ignored) {
                return Material.AIR;
            }
        }

        if (GET_BLOCK_TYPE_ID_MH != null) {
            try {
                int id = (int) GET_BLOCK_TYPE_ID_MH.invoke(snapshot, x, y, z);
                if (id >= 0 && id < 256) {
                    return ID_TO_MATERIAL[id];
                }
            } catch (Throwable ignored) {
                return Material.AIR;
            }
        }

        return Material.AIR;
    }

    /**
     * Checks whether the 16-block section at sectionY is entirely air.
     */
    public static boolean isSectionEmpty(ChunkSnapshot snapshot, int sectionY) {
        if (snapshot == null) return false;
        if (IS_SECTION_EMPTY_MH != null) {
            try {
                return (boolean) IS_SECTION_EMPTY_MH.invoke(snapshot, sectionY);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Checks whether a block in the ChunkSnapshot is waterlogged.
     */
    public static boolean isWaterlogged(ChunkSnapshot snapshot, int x, int y, int z, Material type) {
        if (ChunkCache.isWaterMaterial(type)) {
            return true;
        }
        if (GET_BLOCK_DATA_MH != null && snapshot != null) {
            try {
                Object bd = GET_BLOCK_DATA_MH.invoke(snapshot, x, y, z);
                if (bd instanceof org.bukkit.block.data.Waterlogged) {
                    return ((org.bukkit.block.data.Waterlogged) bd).isWaterlogged();
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
