package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import lombok.Getter;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Transaction-based teleport tracking across Minecraft 1.7 through 26.3+.
 *
 * Protocol details:
 * 1. Server sends PLAYER_POSITION_AND_LOOK with target coordinates (and teleportId on 1.9+).
 * 2. Immediately following the teleport packet on the wire, the server attaches a transaction:
 *    - 1.17+: WrapperPlayServerPing with a dedicated negative integer ID.
 *    - 1.7 - 1.16: WrapperPlayServerWindowConfirmation (window 0) with a dedicated negative short ID.
 * 3. Client receives the teleport and transaction in TCP order.
 * 4. Client sends confirmation:
 *    - 1.9+: WrapperPlayClientTeleportConfirm (teleportId).
 *    - 1.17+: WrapperPlayClientPong (pingId).
 *    - 1.7 - 1.16: WrapperPlayClientWindowConfirmation (actionId).
 * 5. Teleport record transitions to CONFIRMED state upon receiving ANY valid confirmation.
 * 6. The subsequent movement packet landing at the target coordinates applies the teleport deterministically.
 *    - The landing tick is granted exact 1-tick exemption.
 *    - Transition deltas are cleanly zeroed out in MovementData so checks never false-flag.
 *    - No artificial ping-inflated tick extensions are required.
 * 7. Clients cannot spoof unsolicited teleports: records are created strictly by server sends,
 *    and movements to un-teleported locations will not match.
 */
@Getter
public class TeleportData implements Data {

    private final Profile profile;

    private final List<TeleportRecord> pendingTeleports = new CopyOnWriteArrayList<>();

    public int teleportAmount;
    public int zeroAmount;
    public int teleportTicks = 1000;
    public int teleportsPending;
    public int trackedTps;

    /**
     * Match tolerance for confirmed teleport landing.
     * 1.0 block is tight enough that cheaters cannot divert to another position,
     * yet accounts for Bedrock single-precision floats and landing on block edges/slabs.
     */
    private static final double TELEPORT_ACCEPT_DISTANCE = 1.0D;

    /**
     * Negative ID counters to completely prevent collision with TransactionProcessor,
     * VelocityData, or vanilla window inventory clicks.
     */
    private int nextPingId = -100000;
    private short nextTransId = (short) -10000;

    private boolean teleportAppliedThisTick;
    private int exemptTicksRemaining;
    private int ticksSinceTeleportMatch = 1000;

    private Vector lastTeleportTarget;

    public TeleportData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            return;
        }

        WrapperPlayServerPlayerPositionAndLook packet = new WrapperPlayServerPlayerPositionAndLook(event);
        Vector targetPos = resolveTargetVector(packet);

        if (targetPos == null) {
            return;
        }

        int teleportId = -1;
        try {
            teleportId = packet.getTeleportId();
        } catch (Throwable ignored) {
        }

        boolean modern = PacketEvents.getAPI()
                .getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_17);

        int modernPingId = modern ? getNextPingId() : 0;
        short legacyTransId = modern ? 0 : getNextTransId();

        TeleportRecord record = new TeleportRecord(
                teleportId,
                modernPingId,
                legacyTransId,
                targetPos,
                packet.getYaw(),
                packet.getPitch(),
                System.currentTimeMillis()
        );

        pendingTeleports.add(record);
        teleportsPending = pendingTeleports.size();
        teleportAmount++;

        // Send transaction immediately after PLAYER_POSITION_AND_LOOK is written to the channel
        event.getTasksAfterSend().add(() -> {
            if (modern) {
                profile.sendPacket(new WrapperPlayServerPing(modernPingId));
            } else {
                profile.sendPacket(new WrapperPlayServerWindowConfirmation(0, legacyTransId, false));
            }
        });

        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log(profile.getPlayer().getName()
                    + " [Teleport] Queued server teleport. pending=" + teleportsPending
                    + " target=" + targetPos
                    + " teleportId=" + teleportId
                    + " transId=" + (modern ? modernPingId : legacyTransId));
        }
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        PacketTypeCommon pkt = event.getPacketType();

        // 1. Handle 1.9+ TeleportConfirm
        if (pkt.equals(PacketType.Play.Client.TELEPORT_CONFIRM)) {
            WrapperPlayClientTeleportConfirm confirm = new WrapperPlayClientTeleportConfirm(event);
            int confirmId = confirm.getTeleportId();

            for (TeleportRecord record : pendingTeleports) {
                if (record.getTeleportId() == confirmId) {
                    record.setConfirmed(true);
                    record.setConfirmTimestamp(System.currentTimeMillis());
                    if (Config.Setting.DEBUG.getBoolean()) {
                        OtherUtility.log(profile.getPlayer().getName()
                                + " [Teleport] TeleportConfirm received for id=" + confirmId);
                    }
                    break;
                }
            }
            return;
        }

        // 2. Handle 1.17+ Pong
        if (pkt.equals(PacketType.Play.Client.PONG)) {
            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
            int pongId = pong.getId();

            if (pongId < 0) {
                for (TeleportRecord record : pendingTeleports) {
                    if (record.getModernPingId() == pongId) {
                        record.setConfirmed(true);
                        record.setConfirmTimestamp(System.currentTimeMillis());
                        if (Config.Setting.DEBUG.getBoolean()) {
                            OtherUtility.log(profile.getPlayer().getName()
                                    + " [Teleport] Pong confirmation received for pingId=" + pongId);
                        }
                        break;
                    }
                }
            }
            return;
        }

        // 3. Handle 1.7 - 1.16 Window Confirmation
        if (pkt.equals(PacketType.Play.Client.WINDOW_CONFIRMATION)) {
            WrapperPlayClientWindowConfirmation trans = new WrapperPlayClientWindowConfirmation(event);
            short actionId = trans.getActionId();

            if (actionId < 0) {
                for (TeleportRecord record : pendingTeleports) {
                    if (record.getLegacyActionId() == actionId) {
                        record.setConfirmed(true);
                        record.setConfirmTimestamp(System.currentTimeMillis());
                        if (Config.Setting.DEBUG.getBoolean()) {
                            OtherUtility.log(profile.getPlayer().getName()
                                    + " [Teleport] WindowConfirmation received for actionId=" + actionId);
                        }
                        break;
                    }
                }
            }
            return;
        }

        // 4. Handle client movement packets
        if (!(pkt.equals(PacketType.Play.Client.PLAYER_FLYING)
                || pkt.equals(PacketType.Play.Client.PLAYER_POSITION)
                || pkt.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || pkt.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION))) {
            return;
        }

        WrapperPlayClientPlayerFlying flying = profile.getWrapperPlayClientPlayerFlying(event);
        if (flying != null) {
            handleFlying(flying);
        }
    }

    private void handleFlying(WrapperPlayClientPlayerFlying packet) {
        teleportTicks++;
        teleportAppliedThisTick = false;

        if (ticksSinceTeleportMatch < 1000) {
            ticksSinceTeleportMatch++;
        }

        if (exemptTicksRemaining > 0) {
            exemptTicksRemaining--;
        }

        Vector currentPos = null;
        if (packet.hasPositionChanged()) {
            Location loc = packet.getLocation();
            currentPos = new Vector(loc.getX(), loc.getY(), loc.getZ());
        } else if (profile.getMovementData() != null && profile.getMovementData().getLocation() != null) {
            CustomLocation loc = profile.getMovementData().getLocation();
            currentPos = new Vector(loc.getX(), loc.getY(), loc.getZ());
        }

        TeleportRecord matched = null;

        if (currentPos != null && !pendingTeleports.isEmpty()) {
            for (TeleportRecord record : pendingTeleports) {
                if (!record.isConfirmed()) {
                    continue;
                }

                double dist = currentPos.distance(record.getTargetPosition());
                if (dist <= TELEPORT_ACCEPT_DISTANCE) {
                    matched = record;
                    break;
                }
            }
        }

        if (matched != null) {
            // Remove matched record and any prior records superseded by it
            int index = pendingTeleports.indexOf(matched);
            if (index >= 0) {
                for (int i = 0; i <= index; i++) {
                    if (!pendingTeleports.isEmpty()) {
                        pendingTeleports.remove(0);
                    }
                }
            } else {
                pendingTeleports.remove(matched);
            }

            teleportsPending = pendingTeleports.size();
            teleportAppliedThisTick = true;
            exemptTicksRemaining = 1;
            teleportTicks = 0;
            ticksSinceTeleportMatch = 0;
            lastTeleportTarget = matched.getTargetPosition();
            zeroAmount++;
            trackedTps++;

            if (Config.Setting.DEBUG.getBoolean()) {
                OtherUtility.log(profile.getPlayer().getName()
                        + " [Teleport] MATCHED confirmed teleport. Target=" + matched.getTargetPosition()
                        + " Pos=" + currentPos
                        + " Pending remaining=" + teleportsPending);
            }
        }

        // Age pending records and prune expired ones
        int timeoutTicks = getTeleportTimeoutTicks();
        Iterator<TeleportRecord> iterator = pendingTeleports.iterator();
        while (iterator.hasNext()) {
            TeleportRecord record = iterator.next();
            if (++record.ageFlyingTicks > timeoutTicks) {
                pendingTeleports.remove(record);
                teleportsPending = pendingTeleports.size();
            }
        }

        updateExemptState();
    }

    private Vector resolveTargetVector(WrapperPlayServerPlayerPositionAndLook packet) {
        try {
            double x = packet.getX();
            double y = packet.getY();
            double z = packet.getZ();

            CustomLocation current = profile.getMovementData() != null ? profile.getMovementData().getLocation() : null;
            org.bukkit.Location bukkitLoc = null;
            if (current == null && profile.getPlayer() != null && profile.getPlayer().isOnline()) {
                bukkitLoc = profile.getPlayer().getLocation();
            }

            double curX = current != null ? current.getX() : (bukkitLoc != null ? bukkitLoc.getX() : 0.0);
            double curY = current != null ? current.getY() : (bukkitLoc != null ? bukkitLoc.getY() : 0.0);
            double curZ = current != null ? current.getZ() : (bukkitLoc != null ? bukkitLoc.getZ() : 0.0);

            if (packet.isRelativeFlag(RelativeFlag.X)) x += curX;
            if (packet.isRelativeFlag(RelativeFlag.Y)) y += curY;
            if (packet.isRelativeFlag(RelativeFlag.Z)) z += curZ;

            return new Vector(x, y, z);
        } catch (Throwable ignored) {
            if (profile.getPlayer() != null && profile.getPlayer().isOnline()) {
                org.bukkit.Location loc = profile.getPlayer().getLocation();
                return new Vector(loc.getX(), loc.getY(), loc.getZ());
            }
        }
        return null;
    }

    private void updateExemptState() {
        if (profile.getExempt() != null) {
            profile.getExempt().setTeleports(isTeleporting());
        }
    }

    public boolean isTeleporting() {
        return teleportAppliedThisTick || exemptTicksRemaining > 0;
    }

    public boolean hasConfirmedTeleport() {
        for (TeleportRecord record : pendingTeleports) {
            if (record.isConfirmed()) {
                return true;
            }
        }
        return false;
    }

    public void reset() {
        pendingTeleports.clear();
        teleportsPending = 0;
        teleportAmount = 0;
        zeroAmount = 0;
        teleportTicks = 1000;
        trackedTps = 0;
        teleportAppliedThisTick = false;
        exemptTicksRemaining = 0;
        ticksSinceTeleportMatch = 1000;
        lastTeleportTarget = null;
        updateExemptState();
    }

    private int getNextPingId() {
        nextPingId--;
        if (nextPingId < -200000) {
            nextPingId = -100000;
        }
        return nextPingId;
    }

    private short getNextTransId() {
        nextTransId--;
        if (nextTransId < -32000) {
            nextTransId = (short) -10000;
        }
        return nextTransId;
    }

    private int getTeleportTimeoutTicks() {
        int pingTicks = profile.getConnectionData() != null ? profile.getConnectionData().getClientTickTrans() : 1;
        return Math.min(100, Math.max(20, 10 + (pingTicks * 4)));
    }

    @Getter
    public static class TeleportRecord {
        private final int teleportId;
        private final int modernPingId;
        private final short legacyActionId;
        private final Vector targetPosition;
        private final float targetYaw;
        private final float targetPitch;
        private final long sendTimestamp;

        @lombok.Setter
        private boolean confirmed;
        @lombok.Setter
        private long confirmTimestamp;
        private int ageFlyingTicks;

        public TeleportRecord(int teleportId, int modernPingId, short legacyActionId,
                              Vector targetPosition, float targetYaw, float targetPitch,
                              long sendTimestamp) {
            this.teleportId = teleportId;
            this.modernPingId = modernPingId;
            this.legacyActionId = legacyActionId;
            this.targetPosition = targetPosition;
            this.targetYaw = targetYaw;
            this.targetPitch = targetPitch;
            this.sendTimestamp = sendTimestamp;
        }
    }
}