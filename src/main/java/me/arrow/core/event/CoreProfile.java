package me.arrow.core.event;

import java.util.UUID;

/** Platform-neutral profile operations needed by server lifecycle events. */
public interface CoreProfile {

    UUID playerId();

    int entityId();

    void trackEntity(int entityId, UUID playerId);

    void untrackEntity(int entityId);

    void clearTrackedEntities();

    void setInventoryOpen(boolean open);

    void resetDeathTimer();
}
