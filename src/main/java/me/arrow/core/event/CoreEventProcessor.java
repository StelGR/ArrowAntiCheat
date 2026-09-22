package me.arrow.core.event;

import java.util.UUID;

/** Shared event behavior; Bukkit and Fabric adapters only extract platform event data. */
public final class CoreEventProcessor {

    public void trackEachOther(CoreProfile first, CoreProfile second) {
        if (first == null || second == null || first.playerId().equals(second.playerId())) {
            return;
        }
        first.trackEntity(second.entityId(), second.playerId());
        second.trackEntity(first.entityId(), first.playerId());
    }

    public void clearTrackedEntities(CoreProfile profile) {
        if (profile != null) {
            profile.clearTrackedEntities();
        }
    }

    public void playerLeft(UUID playerId, int entityId, CoreProfile remaining) {
        if (remaining != null && !playerId.equals(remaining.playerId())) {
            remaining.untrackEntity(entityId);
        }
    }

    public void setInventoryOpen(CoreProfile profile, boolean open) {
        if (profile != null) {
            profile.setInventoryOpen(open);
        }
    }

    public void playerRespawned(CoreProfile profile) {
        if (profile != null) {
            profile.resetDeathTimer();
        }
    }
}
