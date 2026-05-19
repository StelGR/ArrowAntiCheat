package me.arrow.playerdata.data.impl.teleport;

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

// this is our teleport handler, not very good, falses on very high ping and exempts for a bit too long
// needs improvements, but it does a decent job.

@Getter
public class TeleportData implements Data {

    private final Profile profile;

    public final LinkedList<Teleport> locations = new LinkedList<>();

    public int teleportAmount;
    public int zeroAmount;
    public int teleportTicks = 1000;
    public int teleportsPending;
    public int trackedTps;

    private static final double TELEPORT_MATCH_DIST = 0.03125D;
    private static final double TELEPORT_EXACT_DIST = 1.0E-4D;

    private static final int MIN_TELEPORT_TIMEOUT_TICKS = 6;
    private static final int MAX_TELEPORT_TIMEOUT_TICKS = 60;

    private static final int MIN_POST_TELEPORT_GRACE_TICKS = 2;
    private static final int MAX_POST_TELEPORT_GRACE_TICKS = 10;

    private boolean teleportPhaseActive;
    private boolean possiblyTeleporting;

    private int postTeleportGraceTicks;
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

        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        handleFlying(packet);
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
        postTeleportGraceTicks = 0;

        teleportPhaseActive = true;
        possiblyTeleporting = true;

        profile.getExempt().setTeleports(true);

        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log(profile.getPlayer().getName()
                    + " Teleport queued. pending=" + teleportsPending
                    + " pos=" + serverPos
                    + " timeout=" + getTeleportTimeoutTicks()
                    + " grace=" + getPostTeleportGraceTicks());
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
                if (packet.isRelativeFlag(RelativeFlag.X)) {
                    x += current.getX();
                }

                if (packet.isRelativeFlag(RelativeFlag.Y)) {
                    y += current.getY();
                }

                if (packet.isRelativeFlag(RelativeFlag.Z)) {
                    z += current.getZ();
                }
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

        if (positionChanged) {
            Location location = packet.getLocation();

            Vector currentPosition = new Vector(
                    location.getPosition().getX(),
                    location.getPosition().getY(),
                    location.getPosition().getZ()
            );

            Vector previousPosition = null;

            if (fromLocation != null) {
                previousPosition = new Vector(
                        fromLocation.getPosition().getX(),
                        fromLocation.getPosition().getY(),
                        fromLocation.getPosition().getZ()
                );
            }

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
                        postTeleportGraceTicks = getPostTeleportGraceTicks();

                        zeroAmount++;
                        trackedTps++;

                        if (Config.Setting.DEBUG.getBoolean()) {
                            OtherUtility.log(profile.getPlayer().getName()
                                    + " Teleport matched. pending=" + teleportsPending
                                    + " currentDist=" + currentDistance
                                    + " previousDist=" + previousDistance
                                    + " grace=" + postTeleportGraceTicks);
                        }

                        break;
                    }

                    if (teleport.ageTicks > getTeleportTimeoutTicks()) {
                        iterator.remove();
                        teleportsPending = locations.size();

                        if (Config.Setting.DEBUG.getBoolean()) {
                            OtherUtility.log(profile.getPlayer().getName()
                                    + " Teleport expired. pending=" + teleportsPending
                                    + " age=" + teleport.ageTicks
                                    + " timeout=" + getTeleportTimeoutTicks());
                        }
                    }
                }
            }

            fromLocation = location;
        } else {
            if (!locations.isEmpty()) {
                for (Teleport teleport : locations) {
                    teleport.ageTicks++;
                }

                locations.removeIf(teleport -> teleport.ageTicks > getTeleportTimeoutTicks());
                teleportsPending = locations.size();
            }
        }

        possiblyTeleporting = exactMatched || matched;

        if (matched) {
            teleportPhaseActive = !locations.isEmpty() || postTeleportGraceTicks > 0;
        } else if (locations.isEmpty() && postTeleportGraceTicks <= 0) {
            teleportPhaseActive = false;
            possiblyTeleporting = false;
        }

        if (teleportTicks > getTeleportTimeoutTicks()) {
            locations.clear();
            teleportsPending = 0;
            teleportPhaseActive = false;
            possiblyTeleporting = false;
            postTeleportGraceTicks = 0;
        }

        updateExemptState();

        if (postTeleportGraceTicks > 0) {
            postTeleportGraceTicks--;
        }

        if (Config.Setting.DEBUG.getBoolean()) {
            OtherUtility.log(profile.getPlayer().getName()
                    + " Flying tick: position=" + positionChanged
                    + " matched=" + matched
                    + " teleporting=" + isTeleporting()
                    + " phase=" + teleportPhaseActive
                    + " possible=" + possiblyTeleporting
                    + " teleportTicks=" + teleportTicks
                    + " postGrace=" + postTeleportGraceTicks
                    + " sinceMatch=" + ticksSinceTeleportMatch
                    + " pending=" + teleportsPending
                    + " timeout=" + getTeleportTimeoutTicks());
        }
    }

    private void updateExemptState() {
        profile.getExempt().setTeleports(isTeleporting());
    }

    public boolean isTeleporting() {
        return teleportPhaseActive
                || possiblyTeleporting
                || !locations.isEmpty()
                || postTeleportGraceTicks > 0
                || ticksSinceTeleportMatch <= getPostTeleportGraceTicks();
    }

    public void removeLocation(Teleport teleport) {
        if (locations.remove(teleport)) {
            teleportsPending = locations.size();
        }

        if (locations.isEmpty()) {
            postTeleportGraceTicks = Math.max(postTeleportGraceTicks, getPostTeleportGraceTicks());
            teleportPhaseActive = postTeleportGraceTicks > 0;
        }

        updateExemptState();
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

        postTeleportGraceTicks = 0;
        ticksSinceTeleportMatch = 1000;

        fromLocation = null;

        updateExemptState();
    }

    private int getTeleportTimeoutTicks() {
        int pingTicks = getPingTicks();

        return Math.min(
                MAX_TELEPORT_TIMEOUT_TICKS,
                Math.max(MIN_TELEPORT_TIMEOUT_TICKS, 10 + pingTicks * 4)
        );
    }

    private int getPostTeleportGraceTicks() {
        int pingTicks = getPingTicks();

        return Math.min(
                MAX_POST_TELEPORT_GRACE_TICKS,
                Math.max(MIN_POST_TELEPORT_GRACE_TICKS, 8 + pingTicks)
        );
    }

    private int getPingTicks() {
        int pingTicks;

        pingTicks = 1 + (profile.getConnectionData().getClientTickTrans() * 2);

        return pingTicks;
    }

    public static class Teleport {

        public final Vector position;
        public int ageTicks;

        public Teleport(Vector position) {
            this.position = position;
        }
    }
}