package me.arrow.playerdata.data.impl.worldcomp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.Getter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.processors.Processor;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.TaskUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.PotentSulfur;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public class GeysersTracker implements Processor {

    int PUSH_GRACE_TICKS = 2;
    int SCAN_DOWN_BLOCKS = 32;

    Profile profile;

    @Getter
    boolean beingPushedByGeyser;

    int trackerTick;
    int lastPushedTick = Integer.MIN_VALUE;

    public GeysersTracker(Profile profile) {
        this.profile = profile;
        start(profile.getPlayer());
    }

    public void start(Player player) {
        if (player == null) {
            return;
        }

        TaskUtils.playerTimer(player, 1L, 1L, () -> tick(player));
    }

    public void stop() {
        beingPushedByGeyser = false;
        lastPushedTick = Integer.MIN_VALUE;
        trackerTick = 0;
    }

    public boolean isBeingPushed() {
        int diff = trackerTick - lastPushedTick;
        return diff >= 0 && diff <= PUSH_GRACE_TICKS;
    }

    private void tick(Player player) {
        trackerTick++;

        if (player == null || !player.isOnline() || player.isDead()) {
            beingPushedByGeyser = false;
            return;
        }

        boolean pushed = isInsideActiveGeyserColumn(player);

        beingPushedByGeyser = pushed;

        if (pushed) {
            lastPushedTick = trackerTick;
        }
    }

    private boolean isInsideActiveGeyserColumn(Player player) {
        try {
            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_26_2)) {

                World world = player.getWorld();
                BoundingBox box = player.getBoundingBox();

                int minX = floor(box.getMinX() + 1.0E-7D);
                int maxX = floor(box.getMaxX() - 1.0E-7D);
                int minZ = floor(box.getMinZ() + 1.0E-7D);
                int maxZ = floor(box.getMaxZ() - 1.0E-7D);

                int topY = Math.min(world.getMaxHeight() - 1, floor(box.getMinY()));
                int bottomY = Math.max(world.getMinHeight(), topY - SCAN_DOWN_BLOCKS);

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = topY; y >= bottomY; y--) {

                            if (!CollisionUtils.isChunkLoaded(new Location(world, x, y, z))) continue;
                            Block block = world.getBlockAt(x, y, z);

                            if (!(block.getBlockData() instanceof PotentSulfur sulfur)) {
                                continue;
                            }

                            if (!isActiveGeyserState(sulfur.getPotentSulfurState())) {
                                continue;
                            }

                            if (intersectsGeyserColumn(box, x, y, z)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (NoSuchFieldError ignored) {
            return false;
        }
    }

    private boolean isActiveGeyserState(PotentSulfur.State state) {
        if (state == null) {
            return false;
        }

        String name = state.name();

        return name.equals("ERUPTING")
                || name.equals("CONTINUOUS");
    }

    private boolean intersectsGeyserColumn(BoundingBox playerBox, int geyserX, int geyserY, int geyserZ) {
        double maxX = geyserX + 1.0D;
        double maxZ = geyserZ + 1.0D;
        double minY = geyserY + 1.0D;

        return playerBox.getMaxX() > (double) geyserX
                && playerBox.getMinX() < maxX
                && playerBox.getMaxZ() > (double) geyserZ
                && playerBox.getMinZ() < maxZ
                && playerBox.getMaxY() > minY;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    @Override
    public void process() {

    }
}