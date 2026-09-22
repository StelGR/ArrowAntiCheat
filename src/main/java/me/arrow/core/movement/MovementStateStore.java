package me.arrow.core.movement;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shared ownership and cleanup point for player movement state. */
public final class MovementStateStore {

    private final ConcurrentMap<UUID, MovementState> states = new ConcurrentHashMap<>();

    public MovementState getOrCreate(UUID playerId) {
        return states.computeIfAbsent(playerId, ignored -> new MovementState());
    }

    public MovementState get(UUID playerId) {
        return states.get(playerId);
    }

    public void remove(UUID playerId) {
        states.remove(playerId);
    }

    public void clear() {
        states.clear();
    }
}
