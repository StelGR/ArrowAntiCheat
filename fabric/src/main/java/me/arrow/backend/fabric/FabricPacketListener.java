package me.arrow.backend.fabric;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import me.arrow.core.movement.MovementFrame;
import me.arrow.core.movement.MovementState;
import me.arrow.core.movement.MovementStateStore;
import me.arrow.core.network.PacketSanityValidator;

import java.util.UUID;

/** Fabric's native PacketEvents boundary; shared checks live above this adapter. */
final class FabricPacketListener extends PacketListenerAbstract implements PacketListener {

    private final MovementStateStore movementStates;

    FabricPacketListener(MovementStateStore movementStates) {
        this.movementStates = movementStates;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        String invalid = PacketSanityValidator.check(event);
        if (invalid != null) {
            event.setCancelled(true);
            return;
        }

        publishMovement(event);
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        User user = event.getUser();
        if (user != null && user.getUUID() != null) {
            movementStates.remove(user.getUUID());
        }
    }

    private void publishMovement(PacketReceiveEvent event) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            return;
        }

        User user = event.getUser();
        UUID playerId = user == null ? null : user.getUUID();
        if (playerId == null) {
            return;
        }

        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        MovementState state = movementStates.getOrCreate(playerId);
        if (!packet.hasPositionChanged() && state.current() == null) {
            return;
        }

        String worldName = "unknown";
        try {
            if (user.getDimension() != null && user.getDimension().getDimensionName() != null) {
                worldName = user.getDimension().getDimensionName();
            }
        } catch (Throwable ignored) {
        }

        state.accept(new MovementFrame(
                worldName,
                packet.getLocation().getX(), packet.getLocation().getY(), packet.getLocation().getZ(),
                packet.getLocation().getYaw(), packet.getLocation().getPitch(),
                packet.hasPositionChanged(), packet.hasRotationChanged(),
                packet.isOnGround(), packet.isHorizontalCollision(), event.getTimestamp()
        ));
    }
}
