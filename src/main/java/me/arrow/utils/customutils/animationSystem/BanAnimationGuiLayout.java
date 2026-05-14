package me.arrow.utils.customutils.animationSystem;


import java.util.LinkedHashMap;
import java.util.Map;

public final class BanAnimationGuiLayout {

    private static final int[] INNER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private BanAnimationGuiLayout() {
    }

    public static Map<Integer, Animation.Type> getAnimationSlots() {
        Map<Integer, Animation.Type> slots = new LinkedHashMap<>();

        Animation.Type[] animations = Animation.Type.values();

        for (int i = 0; i < animations.length && i < INNER_SLOTS.length; i++) {
            slots.put(INNER_SLOTS[i], animations[i]);
        }

        return slots;
    }

    public static Animation.Type getAnimationBySlot(int rawSlot) {
        return getAnimationSlots().get(rawSlot);
    }
}
