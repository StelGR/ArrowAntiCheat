package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import lombok.Getter;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.LinkedList;

@Getter
public class TeleportData implements Data {

    private final Profile profile;

    public final LinkedList<Teleport> locations = new LinkedList<>();

    public int teleportAmount;
    public int zeroAmount;
    public int teleportTicks = 1000;
    public int teleportsPending;
    public int trackedTps;

    /**
     * Match window for the client position to equal the server teleport position.
     * Keep small: this is strict matching, not an "exempt because maybe".
     */
    private static final double TELEPORT_MATCH_DIST = 0.00125D;
    private static final double TELEPORT_EXACT_DIST = 1.0E-4D;

    /**
     * If the client applies a large teleport (e.g. /spawn), sometimes you won't hit the tiny match dist
     * due to relative flags / rounding / chunk load / interpolation.
     * This is ONLY used when a pending teleport exists and the player's per-tick displacement is huge.
     */
    private static final double FAR_APPLY_MAX_DIST = 12.0D; // blocks to accept closest pending target
    private static final double FAR_APPLY_MIN_STEP = 2.25D; // must have moved at least this much in one tick

    /**
     * SECURITY: teleport exemption is ALWAYS 1 tick, regardless of ping.
     * We do NOT extend exemption based on timeout/ping.
     */
    private static final int EXEMPT_TICKS_ON_SEND = 1;
    private static final int EXEMPT_TICKS_ON_MATCH = 1;

    /**
     * How long we keep pending teleports around for matching (NOT exemption).
     * This can be higher without creating the slow-timer exploit, because we don't exempt while waiting.
     */
    private static final int MIN_TELEPORT_TIMEOUT_TICKS = 2;
    private static final int MAX_TELEPORT_TIMEOUT_TICKS = 40;

    private boolean teleportPhaseActive;     // "exempt active" (1-tick windows)
    private boolean possiblyTeleporting;     // same as above, kept for compatibility

    private int exemptTicksRemaining;        // counts down; when > 0 -> exempt

    private int ticksSinceTeleportMatch = 1000;

    private Location fromLocation;

    public TeleportData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        PacketTypeCommon pkt = event.getPacketType();

        if (!(pkt.equals(PacketType.Play.Client.PLAYER_FLYING)
                || pkt.equals(PacketType.Play.Client.PLAYER_POSITION)
                || pkt.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || pkt.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION))) {
            return;
        }

        handleFlying(new WrapperPlayClientPlayerFlying(event));
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            return;
        }

        WrapperPlayServerPlayerPositionAndLook packet = new WrapperPlayServerPlayerPositionAndLook(event);
        Vector serverPos = getVector(packet);

        if (serverPos == null) {
            return;
        }

        locations.add(new Teleport(serverPos));
        teleportsPending = locations.size();
        teleportAmount++;

        teleportTicks = 0;
        ticksSinceTeleportMatch = 1000;

        // 1 tick exempt immediately on send (regardless of ping)
        exemptTicksRemaining = Math.max(exemptTicksRemaining, EXEMPT_TICKS_ON_SEND);
        teleportPhaseActive = true;
        possiblyTeleporting = true;

        updateExemptState();

        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log(profile.getPlayer().getName()
                    + " Teleport queued. pending=" + teleportsPending
                    + " pos=" + serverPos
                    + " timeout=" + getTeleportTimeoutTicks()
                    + " exemptTicks=" + exemptTicksRemaining);
        }
    }

    private Vector getVector(WrapperPlayServerPlayerPositionAndLook packet) {
        try {
            double x = packet.getX();
            double y = packet.getY();
            double z = packet.getZ();

            org.bukkit.Location current = null;

            if (profile.getPlayer() != null && profile.getPlayer().isOnline()) {
                current = profile.getPlayer().getLocation();
            }

            if (current != null) {
                if (packet.isRelativeFlag(RelativeFlag.X)) x += current.getX();
                if (packet.isRelativeFlag(RelativeFlag.Y)) y += current.getY();
                if (packet.isRelativeFlag(RelativeFlag.Z)) z += current.getZ();
            }

            return new Vector(x, y, z);
        } catch (Throwable ignored) {
            if (profile.getPlayer() != null && profile.getPlayer().isOnline()) {
                org.bukkit.Location location = profile.getPlayer().getLocation();
                return new Vector(location.getX(), location.getY(), location.getZ());
            }
        }

        return null;
    }

    private void handleFlying(WrapperPlayClientPlayerFlying packet) {
        teleportTicks++;

        if (ticksSinceTeleportMatch < 1000) {
            ticksSinceTeleportMatch++;
        }

        boolean positionChanged = packet.hasPositionChanged();
        boolean matched = false;
        boolean exactMatched = false;

        Vector currentPosition;
        Vector previousPosition = null;

        if (positionChanged) {
            Location location = packet.getLocation();
            currentPosition = new Vector(
                    location.getPosition().getX(),
                    location.getPosition().getY(),
                    location.getPosition().getZ()
            );

            if (fromLocation != null) {
                previousPosition = new Vector(
                        fromLocation.getPosition().getX(),
                        fromLocation.getPosition().getY(),
                        fromLocation.getPosition().getZ()
                );
            }

            // Normal strict matching
            if (!locations.isEmpty()) {
                Iterator<Teleport> iterator = locations.iterator();
                while (iterator.hasNext()) {
                    Teleport teleport = iterator.next();
                    teleport.ageTicks++;

                    double currentDistance = teleport.position.distance(currentPosition);
                    double previousDistance = previousPosition != null
                            ? teleport.position.distance(previousPosition)
                            : Double.MAX_VALUE;

                    if (currentDistance <= TELEPORT_MATCH_DIST || previousDistance <= TELEPORT_MATCH_DIST) {
                        iterator.remove();

                        matched = true;
                        exactMatched = currentDistance <= TELEPORT_EXACT_DIST || previousDistance <= TELEPORT_EXACT_DIST;

                        teleportsPending = locations.size();

                        teleportTicks = 0;
                        ticksSinceTeleportMatch = 0;

                        // 1 tick exempt on confirmed apply (regardless of ping)
                        exemptTicksRemaining = Math.max(exemptTicksRemaining, EXEMPT_TICKS_ON_MATCH);

                        zeroAmount++;
                        trackedTps++;

                        if (Config.Setting.DEBUG.getBoolean()) {
                            OtherUtility.log(profile.getPlayer().getName()
                                    + " Teleport matched. pending=" + teleportsPending
                                    + " currentDist=" + currentDistance
                                    + " previousDist=" + previousDistance
                                    + " exemptTicks=" + exemptTicksRemaining);
                        }

                        break;
                    }

                    if (teleport.ageTicks > getTeleportTimeoutTicks()) {
                        iterator.remove();
                        teleportsPending = locations.size();
                    }
                }
            }

            // Fallback for large teleports (/spawn, etc) where strict dist might miss due to relative/rounding
            if (!matched && previousPosition != null && !locations.isEmpty()) {
                double step = currentPosition.distance(previousPosition);

                if (step >= FAR_APPLY_MIN_STEP) {
                    Teleport closest = null;
                    double best = Double.MAX_VALUE;

                    for (Teleport t : locations) {
                        double d = t.position.distance(currentPosition);
                        if (d < best) {
                            best = d;
                            closest = t;
                        }
                    }

                    if (closest != null && best <= FAR_APPLY_MAX_DIST) {
                        locations.remove(closest);
                        teleportsPending = locations.size();

                        matched = true;
                        exactMatched = best <= TELEPORT_MATCH_DIST;

                        teleportTicks = 0;
                        ticksSinceTeleportMatch = 0;

                        exemptTicksRemaining = Math.max(exemptTicksRemaining, EXEMPT_TICKS_ON_MATCH);

                        zeroAmount++;
                        trackedTps++;

                        if (Config.Setting.DEBUG.getBoolean()) {
                            OtherUtility.log(profile.getPlayer().getName()
                                    + " Teleport FAR-applied. pending=" + teleportsPending
                                    + " step=" + step
                                    + " dist=" + best
                                    + " exemptTicks=" + exemptTicksRemaining);
                        }
                    }
                }
            }

            fromLocation = packet.getLocation();

        } else {
            // No position change this tick: just age / expire pending teleports (no exemption)
            if (!locations.isEmpty()) {
                for (Teleport teleport : locations) {
                    teleport.ageTicks++;
                }
                locations.removeIf(tp -> tp.ageTicks > getTeleportTimeoutTicks());
                teleportsPending = locations.size();
            }
        }

        // Expire very old queue (safety)
        if (teleportTicks > getTeleportTimeoutTicks()) {
            locations.clear();
            teleportsPending = 0;
        }

        // Exemption state is ONLY controlled by exemptTicksRemaining
        possiblyTeleporting = matched || exactMatched || exemptTicksRemaining > 0;
        teleportPhaseActive = exemptTicksRemaining > 0;

        if (exemptTicksRemaining > 0) {
            exemptTicksRemaining--;
        }

        updateExemptState();

        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log(profile.getPlayer().getName()
                    + " Flying tick: position=" + positionChanged
                    + " matched=" + matched
                    + " teleporting=" + isTeleporting()
                    + " pending=" + teleportsPending
                    + " teleportTicks=" + teleportTicks
                    + " exemptTicks=" + exemptTicksRemaining
                    + " timeout=" + getTeleportTimeoutTicks());
        }
    }

    private void updateExemptState() {
        profile.getExempt().setTeleports(isTeleporting());
    }

    /**
     * IMPORTANT:
     * This returns true ONLY for the 1-tick exemption windows (send tick and confirmed-apply tick).
     * Pending teleports do NOT mean exemption.
     */
    public boolean isTeleporting() {
        return teleportPhaseActive || possiblyTeleporting || exemptTicksRemaining > 0;
    }

    public void removeLocation(Teleport teleport) {
        if (locations.remove(teleport)) {
            teleportsPending = locations.size();
        }
    }

    public void reset() {
        locations.clear();

        teleportAmount = 0;
        zeroAmount = 0;
        teleportTicks = 1000;
        teleportsPending = 0;
        trackedTps = 0;

        teleportPhaseActive = false;
        possiblyTeleporting = false;

        exemptTicksRemaining = 0;
        ticksSinceTeleportMatch = 1000;

        fromLocation = null;

        updateExemptState();
    }

    private int getTeleportTimeoutTicks() {
        int pingTicks = getPingTicks();

        // Keep queue longer for high ping, but this does NOT grant exemption.
        // Clamp hard so the queue can't grow unbounded.
        int calculated = 8 + (pingTicks * 6);
        return Math.min(MAX_TELEPORT_TIMEOUT_TICKS, Math.max(MIN_TELEPORT_TIMEOUT_TICKS, calculated));
    }

    private int getPingTicks() {
        return 1 + (profile.getConnectionData().getClientTickTrans() * 2);
    }

    public static class Teleport {
        public final Vector position;
        public int ageTicks;

        public Teleport(Vector position) {
            this.position = position;
        }
    }
}