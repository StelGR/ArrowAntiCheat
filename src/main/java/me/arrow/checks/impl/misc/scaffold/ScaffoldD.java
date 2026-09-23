package me.arrow.checks.impl.misc.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.core.check.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.CustomLocation;
import org.bukkit.Material;
import org.bukkit.util.Vector;

/**
 * Detects bridge placements that are well outside the player's normal footprint.
 * It intentionally only handles repeated, obvious expansion while air-bridging.
 */
@Experimental
public class ScaffoldD extends Check {

    private static final double JAVA_MAX_EXPAND = 1.75D;
    private static final double BEDROCK_MAX_EXPAND = 2.20D;

    public ScaffoldD(Profile profile) {
        super(profile, CheckType.SCAFFOLD, "D", "Checks for expanded scaffold placements");
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            return;
        }

        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);
        int clickedX = packet.getBlockPosition().getX();
        int clickedY = packet.getBlockPosition().getY();
        int clickedZ = packet.getBlockPosition().getZ();
        int face = face(packet);

        if (face < 0 || (clickedX == -1 && clickedY == -1 && clickedZ == -1)) {
            return;
        }

        MovementData movement = profile.getMovementData();
        Material placedMaterial = profile.getBlockProcessor().getBlockPlaceMaterial();
        Material clickedMaterial = profile.getBlockProcessor().getServerMaterial(clickedX, clickedY, clickedZ);

        if (!isBridgeContext(movement)
                || placedMaterial == null || !placedMaterial.isBlock()
                || clickedMaterial == null || clickedMaterial == Material.AIR) {
            decay();
            return;
        }

        int placeX = clickedX + offsetX(face);
        int placeY = clickedY + offsetY(face);
        int placeZ = clickedZ + offsetZ(face);
        Material placeMaterial = profile.getBlockProcessor().getServerMaterial(placeX, placeY, placeZ);

        if (placeMaterial == null || !placeMaterial.name().contains("AIR")
                || !matchesTrackedPlacement(placeX, placeY, placeZ)) {
            decay();
            return;
        }

        CustomLocation location = movement.getLocation();
        int feetY = (int) Math.floor(location.getY());

        // A scaffold support sits at, or just below, the player's feet.
        if (placeY > feetY || placeY < feetY - 2) {
            decay();
            return;
        }

        double distance = horizontalDistanceToBlock(location, placeX, placeZ);
        double limit = (profile.isBedrockPlayer() ? BEDROCK_MAX_EXPAND : JAVA_MAX_EXPAND)
                + Math.min(0.20D, profile.getConnectionData().getClientTickTrans() * 0.05D);

        if (distance <= limit) {
            decay();
            return;
        }

        if (increaseBufferBy(1.0D) > 3.0D) {
            fail("Expanded scaffold",
                    "distance " + MsgType.MAIN_THEME_COLOR.getMessage() + round(distance)
                            + "\nlimit " + MsgType.MAIN_THEME_COLOR.getMessage() + round(limit)
                            + "\nplace " + MsgType.MAIN_THEME_COLOR.getMessage() + placeX + ", " + placeY + ", " + placeZ
                            + "\nbedrock " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.isBedrockPlayer());
            decreaseBufferBy(1.0D);
        }
    }

    private boolean isBridgeContext(MovementData movement) {
        if (movement == null || movement.getLocation() == null || profile.shouldCancel()) {
            return false;
        }

        if (movement.getDeltaXZ() < (profile.isBedrockPlayer() ? 0.045D : 0.065D)) {
            return false;
        }

        if (movement.isNearWater() || movement.isNearLava() || movement.isNearWebs()
                || movement.isNearClimbable() || movement.isOnBoat() || movement.isNearBoat()) {
            return false;
        }

        return profile.isAirBridging(movement.getLocation().toBukkit());
    }

    private boolean matchesTrackedPlacement(int x, int y, int z) {
        Vector tracked = profile.getBlockProcessor().getCurrentBlockCords();
        return tracked != null
                && tracked.getBlockX() == x
                && tracked.getBlockY() == y
                && tracked.getBlockZ() == z;
    }

    private double horizontalDistanceToBlock(CustomLocation location, int x, int z) {
        double nearestX = clamp(location.getX(), x, x + 1.0D);
        double nearestZ = clamp(location.getZ(), z, z + 1.0D);
        return Math.hypot(location.getX() - nearestX, location.getZ() - nearestZ);
    }

    private int face(WrapperPlayClientPlayerBlockPlacement packet) {
        try {
            int value = packet.getFace().getFaceValue();
            return value >= 0 && value <= 5 ? value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int offsetX(int face) {
        return face == 4 ? -1 : face == 5 ? 1 : 0;
    }

    private int offsetY(int face) {
        return face == 0 ? -1 : face == 1 ? 1 : 0;
    }

    private int offsetZ(int face) {
        return face == 2 ? -1 : face == 3 ? 1 : 0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String round(double value) {
        return String.format("%.3f", value);
    }

    private void decay() {
        decreaseBufferBy(0.25D);
    }

    @Override
    public void handle(PacketSendEvent event) {
    }
}
