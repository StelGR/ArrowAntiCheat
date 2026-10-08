package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Checks for block reach on block break (digging) and block placement.
 * Supports vanilla survival/creative limits and modern 1.20.5+ block interaction range attributes.
 */
@Experimental
public class InteractF extends Check {

    private static final int[] NORMAL_X = {0, 0, 0, 0, -1, 1};
    private static final int[] NORMAL_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] NORMAL_Z = {0, 0, -1, 1, 0, 0};

    public InteractF(Profile profile) {
        super(profile, CheckType.INTERACT, "F", "Checks for block reach (break and place)");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) {
            handleDigging(event);
        } else if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)) {
            handlePlacement(event);
        } else if (OtherUtility.isFlying(event.getPacketType())) {
            decreaseBufferBy(0.015D);
        }
    }

    private void handleDigging(PacketReceiveEvent event) {
        WrapperPlayClientPlayerDigging packet;
        try {
            packet = new WrapperPlayClientPlayerDigging(event);
        } catch (Throwable ignored) {
            return;
        }

        DiggingAction action = packet.getAction();
        if (action != DiggingAction.START_DIGGING && action != DiggingAction.FINISHED_DIGGING) {
            return;
        }

        Vector3i pos = packet.getBlockPosition();
        if (pos.getX() == -1 && pos.getY() == -1 && pos.getZ() == -1) {
            return;
        }

        checkBlockReach("Break", pos.getX(), pos.getY(), pos.getZ(), -1);
    }

    private void handlePlacement(PacketReceiveEvent event) {
        WrapperPlayClientPlayerBlockPlacement packet;
        try {
            packet = new WrapperPlayClientPlayerBlockPlacement(event);
        } catch (Throwable ignored) {
            return;
        }

        Vector3i pos = packet.getBlockPosition();
        if (pos.getX() == -1 && pos.getY() == -1 && pos.getZ() == -1) {
            return;
        }

        int face;
        try {
            face = packet.getFace().getFaceValue();
        } catch (Throwable ignored) {
            face = -1;
        }

        checkBlockReach("Place", pos.getX(), pos.getY(), pos.getZ(), face);
    }

    private void checkBlockReach(String action, int bx, int by, int bz, int face) {
        if (profile == null || profile.getPlayer() == null || !profile.getPlayer().isOnline()) {
            return;
        }

        if (profile.shouldCancel()
                || profile.isExempt().isTeleports()
                || profile.isExempt().isRespawned()
                || profile.isExempt().isDead()) {
            decreaseBufferBy(0.2D);
            return;
        }

        // Leeway if in or recently left a vehicle
        if (profile.getVehicleData() != null && profile.getVehicleData().getSinceVehicleTicks() < 20) {
            decreaseBufferBy(0.2D);
            return;
        }

        Player player = profile.getPlayer();
        MovementData movement = profile.getMovementData();
        if (movement == null || movement.getLocation() == null) {
            return;
        }

        CustomLocation currentLoc = movement.getLocation();
        if (currentLoc.getWorld() == null) {
            return;
        }

        // Build list of candidate positions within latency window
        List<CustomLocation> candidates = new ArrayList<>();
        candidates.add(currentLoc);

        if (movement.getLastLocation() != null) {
            candidates.add(movement.getLastLocation());
        }

        int ping = profile.getConnectionData() != null ? profile.getConnectionData().getPing() : 50;
        int pingTicks = Math.max(1, (int) Math.ceil(ping / 50.0D));
        int maxHistorySamples = Math.min(25, pingTicks + 4);

        SampleList<CustomLocation> pastLocations = movement.getPastLocations();
        if (pastLocations != null && !pastLocations.isEmpty()) {
            List<CustomLocation> snapshot = snapshot(pastLocations);
            int size = snapshot.size();
            for (int i = size - 1; i >= Math.max(0, size - maxHistorySamples); i--) {
                CustomLocation loc = snapshot.get(i);
                if (loc != null) {
                    candidates.add(loc);
                }
            }
        }

        double[] possibleEyes = getPossibleEyeHeights(player);

        // Compute minimum distance to the block AABB across all candidate locations and eye heights
        double minDistance = Double.MAX_VALUE;

        for (CustomLocation loc : candidates) {
            for (double eyeHeight : possibleEyes) {
                double distClicked = distanceToBlock(loc, eyeHeight, bx, by, bz);
                double dist;

                if (face >= 0 && face <= 5) {
                    int placedX = bx + NORMAL_X[face];
                    int placedY = by + NORMAL_Y[face];
                    int placedZ = bz + NORMAL_Z[face];
                    double distPlaced = distanceToBlock(loc, eyeHeight, placedX, placedY, placedZ);
                    dist = Math.min(distClicked, distPlaced);
                } else {
                    dist = distClicked;
                }

                if (dist < minDistance) {
                    minDistance = dist;
                }
            }
        }

        double baseReach = getBaseReach(player);
        double deltaXZ = movement.getDeltaXZ();
        double motionAllowance = Math.min(0.40D, 0.15D + deltaXZ * 0.6D);
        double pingAllowance = Math.min(0.35D, Math.max(0, ping) * 0.001D);
        double maxAllowed = baseReach + motionAllowance + pingAllowance;

        if (minDistance > maxAllowed) {
            double excess = minDistance - maxAllowed;
            if (increaseBufferBy(1.0D + Math.min(excess, 2.0D)) >= 3.0D) {
                fail(action + " Block Reach",
                        "action " + MsgType.MAIN_THEME_COLOR.getMessage() + action
                                + "\ndistance " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.US, "%.2f", minDistance)
                                + "\nmaxAllowed " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.US, "%.2f", maxAllowed)
                                + "\nbaseReach " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.US, "%.2f", baseReach)
                                + "\nexcess " + MsgType.MAIN_THEME_COLOR.getMessage() + String.format(Locale.US, "%.2f", excess)
                                + "\npos " + MsgType.MAIN_THEME_COLOR.getMessage() + bx + ", " + by + ", " + bz);
            }
        } else {
            decreaseBufferBy(0.25D);
        }
    }

    private double distanceToBlock(CustomLocation loc, double eyeHeight, int bx, int by, int bz) {
        double eyeX = loc.getX();
        double eyeY = loc.getY() + eyeHeight;
        double eyeZ = loc.getZ();

        double closestX = Math.max(bx, Math.min(eyeX, bx + 1.0D));
        double closestY = Math.max(by, Math.min(eyeY, by + 1.0D));
        double closestZ = Math.max(bz, Math.min(eyeZ, bz + 1.0D));

        double dx = eyeX - closestX;
        double dy = eyeY - closestY;
        double dz = eyeZ - closestZ;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double[] getPossibleEyeHeights(Player player) {
        double currentEye = 1.62D;
        try {
            currentEye = player.getEyeHeight();
        } catch (Throwable ignored) {
        }

        if (player.isSneaking()) {
            return new double[]{currentEye, 1.54D, 1.27D, 1.62D};
        }
        return new double[]{currentEye, 1.62D, 1.54D, 1.27D, 0.4D};
    }

    private double getBaseReach(Player player) {
        if (profile.isBedrockPlayer()) {
            return (player.getGameMode() == GameMode.CREATIVE) ? 10.0D : 7.5D;
        }
        double attrReach = getAttributeBlockReach(player);
        if (attrReach > 0.0D) {
            return attrReach;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            return 5.0D;
        }
        return 4.5D;
    }

    private static double getAttributeBlockReach(Player player) {
        if (player == null) return -1.0D;
        try {
            Class<?> attrClass = Class.forName("org.bukkit.attribute.Attribute");
            Method getAttributeMethod = player.getClass().getMethod("getAttribute", attrClass);
            for (String attrName : new String[]{
                    "PLAYER_BLOCK_INTERACTION_RANGE",
                    "GENERIC_BLOCK_INTERACTION_RANGE",
                    "BLOCK_INTERACTION_RANGE"
            }) {
                try {
                    Object attrEnum = Enum.valueOf((Class<Enum>) attrClass, attrName);
                    Object attrInstance = getAttributeMethod.invoke(player, attrEnum);
                    if (attrInstance != null) {
                        Method getValueMethod = attrInstance.getClass().getMethod("getValue");
                        Object val = getValueMethod.invoke(attrInstance);
                        if (val instanceof Number) {
                            return ((Number) val).doubleValue();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return -1.0D;
    }

    private List<CustomLocation> snapshot(SampleList<CustomLocation> history) {
        if (history == null) return Collections.emptyList();
        try {
            return new ArrayList<>(history);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }
}
