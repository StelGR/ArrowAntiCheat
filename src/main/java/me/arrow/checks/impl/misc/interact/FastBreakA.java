package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import me.arrow.checks.types.Check;
import me.arrow.core.check.CheckType;
import me.arrow.core.check.annotation.Experimental;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.custom.BlockBreakHelper;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Locale;

// Made By Gemini 3.8

/**
 * FastBreak (Type A) - Instant Break Check.
 * Detects players breaking blocks instantly (< 2 ticks / < 75ms) when the block
 * cannot be broken instantly with their current tool, enchantments, and effects.
 */
@Experimental
public class FastBreakA extends Check {

    private long startDigTime;
    private int startBlockX = Integer.MIN_VALUE;
    private int startBlockY = Integer.MIN_VALUE;
    private int startBlockZ = Integer.MIN_VALUE;

    private int lastFinishedBlockX = Integer.MIN_VALUE;
    private int lastFinishedBlockY = Integer.MIN_VALUE;
    private int lastFinishedBlockZ = Integer.MIN_VALUE;
    private long lastFinishedTime;

    private int lastCancelledBlockX = Integer.MIN_VALUE;
    private int lastCancelledBlockY = Integer.MIN_VALUE;
    private int lastCancelledBlockZ = Integer.MIN_VALUE;
    private long lastCancelledTime;

    public FastBreakA(Profile profile) {
        super(profile, CheckType.FASTBREAK, "A", "Checks for instant block break");
    }

    @Override
    public void handle(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            try {
                WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
                Vector3i pos = packet.getBlockPosition();
                this.lastCancelledBlockX = pos.getX();
                this.lastCancelledBlockY = pos.getY();
                this.lastCancelledBlockZ = pos.getZ();
                this.lastCancelledTime = System.currentTimeMillis();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_DIGGING)) {
            handleDigging(event);
        } else if (OtherUtility.isFlying(event.getPacketType())) {
            decreaseBufferBy(0.01D);
        }
    }

    private void handleDigging(PacketReceiveEvent event) {
        WrapperPlayClientPlayerDigging packet;
        try {
            packet = new WrapperPlayClientPlayerDigging(event);
        } catch (Throwable ignored) {
            return;
        }

        Player player = profile.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }

        // Creative and spectator players legitimately instant-break blocks
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            resetDigging();
            return;
        }

        DiggingAction action = packet.getAction();
        Vector3i pos = packet.getBlockPosition();
        if (pos.getX() == -1 && pos.getY() == -1 && pos.getZ() == -1) {
            return;
        }

        int bx = pos.getX();
        int by = pos.getY();
        int bz = pos.getZ();
        long now = System.currentTimeMillis();

        if (action == DiggingAction.START_DIGGING) {
            this.startBlockX = bx;
            this.startBlockY = by;
            this.startBlockZ = bz;
            this.startDigTime = now;
            return;
        }

        if (action == DiggingAction.CANCELLED_DIGGING) {
            if (this.startBlockX == bx && this.startBlockY == by && this.startBlockZ == bz) {
                resetDigging();
            }
            return;
        }

        if (action == DiggingAction.FINISHED_DIGGING) {
            // If the player is continuously holding left-click on a block that was just finished or
            // cancelled by the server (e.g. WorldGuard / protected zone), ignore repeated finished packets
            boolean isRepeatedFinished = (bx == this.lastFinishedBlockX && by == this.lastFinishedBlockY && bz == this.lastFinishedBlockZ)
                    && (now - this.lastFinishedTime < 3000L);
            boolean isRepeatedCancelled = (bx == this.lastCancelledBlockX && by == this.lastCancelledBlockY && bz == this.lastCancelledBlockZ)
                    && (now - this.lastCancelledTime < 3000L);

            if (isRepeatedFinished || isRepeatedCancelled) {
                resetDigging();
                return;
            }

            World world = player.getWorld();
            Block block = world.getBlockAt(bx, by, bz);

            float progress = BlockBreakHelper.getBreakProgress(player, block);

            // Block is naturally instantly breakable with the held tool/effects (progress >= 1.0F)
            if (progress >= 1.0F) {
                this.lastFinishedBlockX = bx;
                this.lastFinishedBlockY = by;
                this.lastFinishedBlockZ = bz;
                this.lastFinishedTime = now;
                resetDigging();
                decreaseBufferBy(0.2D);
                return;
            }

            boolean matchingPos = (this.startBlockX == bx && this.startBlockY == by && this.startBlockZ == bz);
            long elapsedMillis = (matchingPos && this.startDigTime > 0) ? (now - this.startDigTime) : 0;
            int elapsedTicks = (int) Math.round(elapsedMillis / 50.0);

            int minTicks = BlockBreakHelper.getMinimumBreakTicks(player, block);

            // Instant break: finished in <= 1 tick or < 75ms on a block that requires >= 2 ticks
            if (!matchingPos || this.startDigTime <= 0 || elapsedTicks <= 1 || elapsedMillis < 75L) {
                if (increaseBufferBy(1.0D) >= 1.0D) {
                    fail("Instant Break",
                            "block " + MsgType.MAIN_THEME_COLOR.getMessage() + block.getType().name()
                                    + "\nelapsed " + MsgType.MAIN_THEME_COLOR.getMessage() + elapsedTicks + "t (" + elapsedMillis + "ms)"
                                    + "\nmin " + MsgType.MAIN_THEME_COLOR.getMessage() + minTicks + "t"
                                    + "\nprogress " + MsgType.MAIN_THEME_COLOR.getMessage() + format(3, progress)
                                    + "\npos " + MsgType.MAIN_THEME_COLOR.getMessage() + bx + ", " + by + ", " + bz);
                }
            } else {
                decreaseBufferBy(0.25D);
            }

            this.lastFinishedBlockX = bx;
            this.lastFinishedBlockY = by;
            this.lastFinishedBlockZ = bz;
            this.lastFinishedTime = now;
            resetDigging();
        }
    }

    private void resetDigging() {
        this.startDigTime = 0;
        this.startBlockX = Integer.MIN_VALUE;
        this.startBlockY = Integer.MIN_VALUE;
        this.startBlockZ = Integer.MIN_VALUE;
    }

    private String format(int places, double value) {
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }
}
