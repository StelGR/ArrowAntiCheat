package me.arrow.checks.impl.combat.hitbox;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ConnectionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.RotationData;
import me.arrow.utils.custom.BoundingBox;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.SampleList;
import org.bukkit.GameMode;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Player-only hitbox validation. Distance belongs to Reach A; this check only
 * handles attacks for which no valid client look vector intersects any target
 * position the attacker could have rendered.
 */
public class HitboxA extends Check {

    private double missBuffer;

    public HitboxA(Profile profile) {
        super(profile, CheckType.HITBOX, "A", "Attacked outside the target hitbox");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            handleAttack(event);
        } else if (isMovement(event.getPacketType())) {
            missBuffer = Math.max(0.0D, missBuffer - 0.035D);
            decreaseBufferBy(0.02D);
        }
    }

    private void handleAttack(PacketReceiveEvent event) {
        WrapperPlayClientInteractEntity packet;

        try {
            packet = new WrapperPlayClientInteractEntity(event);
        } catch (Throwable ignored) {
            return;
        }

        if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        Profile target = resolveTarget(packet.getEntityId());

        if (target == null || isExempt(profile, target)) {
            return;
        }

        MovementData movement = profile.getMovementData();
        RotationData rotation = profile.getRotationData();
        List<CustomLocation> history = snapshot(target.getMovementData().getPastLocations());

        if (movement == null || rotation == null || movement.getLocation() == null || history.size() < 4) {
            return;
        }

        int pingTicks = getPingTicks(profile);
        List<CustomLocation> positions = getRenderedPositions(history, pingTicks);

        if (positions.size() < 16) {
            return;
        }

        List<Vector> directions = getPossibleDirections(rotation);
        double[] eyes = getPossibleEyeHeights();
        double margin = getProtocolMargin();
        double forgivingMargin = margin + Math.min(0.075D, 0.025D + pingTicks * 0.004D);
        CustomLocation attacker = movement.getLocation();

        boolean rayHit = false;
        boolean forgivingHit = false;
        double bestCenterLine = Double.MAX_VALUE;
        double bestCenterAngle = Double.MAX_VALUE;

        for (CustomLocation position : positions) {
            BoundingBox box = createTargetBox(position, margin, margin);
            BoundingBox forgivingBox = createTargetBox(position, forgivingMargin, forgivingMargin * 0.75D);
            Vector center = new Vector(position.getX(), position.getY() + 0.9D, position.getZ());

            for (double eyeHeight : eyes) {
                Vector eye = new Vector(attacker.getX(), attacker.getY() + eyeHeight, attacker.getZ());

                if (inside(eye, box)) {
                    rayHit = true;
                    break;
                }

                for (Vector direction : directions) {
                    double hit = box.rayTrace(eye, direction, 6.0D);
                    double forgiving = forgivingBox.rayTrace(eye, direction, 6.0D);

                    if (hit >= 0.0D && hit <= 6.0D) {
                        rayHit = true;
                    }

                    if (forgiving >= 0.0D && forgiving <= 6.0D) {
                        forgivingHit = true;
                    }

                    bestCenterLine = Math.min(bestCenterLine, distancePointToRay(eye, direction, center));
                    bestCenterAngle = Math.min(bestCenterAngle, angleToPoint(eye, direction, center));
                }
            }

            if (rayHit) {
                break;
            }
        }

        verbose(this.getClass().getSimpleName(), missBuffer, 3.0D,
                "rayHit " + MsgType.MAIN_THEME_COLOR.getMessage() + rayHit
                        + "\nforgivingHit " + MsgType.MAIN_THEME_COLOR.getMessage() + forgivingHit
                        + "\ncenterLine " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestCenterLine)
                        + "\ncenterAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestCenterAngle)
                        + "\nmargin " + MsgType.MAIN_THEME_COLOR.getMessage() + format(margin)
                        + "\npositions " + MsgType.MAIN_THEME_COLOR.getMessage() + positions.size()
                        + "\npingTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + pingTicks);

        if (rayHit) {
            missBuffer = Math.max(0.0D, missBuffer - 0.40D);
            decreaseBufferBy(0.20D);
            return;
        }

        boolean recentFlick = Math.abs(rotation.getDeltaYaw()) >= 18.0D
                || Math.abs(rotation.getDeltaPitch()) >= 8.0D;
        boolean softMiss = forgivingHit
                || bestCenterLine <= 0.28D
                || bestCenterAngle <= 5.5D;

        if (softMiss) {
            missBuffer = Math.max(0.0D, missBuffer - 0.10D);
            decreaseBufferBy(0.05D);
            return;
        }

        double evidence = pingTicks >= 5 ? 0.45D : recentFlick ? 0.60D : 1.0D;
        missBuffer = Math.min(8.0D, missBuffer + evidence);

        if (increaseBufferBy(evidence) > 3.0D && missBuffer > 3.0D && bestCenterAngle > 26) {
            fail("Invalid Hitbox Interaction",
                    "centerLine " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestCenterLine)
                            + "\ncenterAngle " + MsgType.MAIN_THEME_COLOR.getMessage() + format(bestCenterAngle)
                            + "\nmargin " + MsgType.MAIN_THEME_COLOR.getMessage() + format(margin)
                            + "\nforgivingMargin " + MsgType.MAIN_THEME_COLOR.getMessage() + format(forgivingMargin)
                            + "\nrecentFlick " + MsgType.MAIN_THEME_COLOR.getMessage() + recentFlick
                            + "\npingTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + pingTicks
                            + "\npositions " + MsgType.MAIN_THEME_COLOR.getMessage() + positions.size());

            missBuffer = Math.max(1.5D, missBuffer - 1.5D);
            decreaseBufferBy(1.5D);
        }
    }

    private Profile resolveTarget(int entityId) {
        UUID uuid = profile.getCombatData().getTrackedEntities().get(entityId);
        return uuid == null ? null : Arrow.getInstance().getProfileManager().getProfile(uuid);
    }

    private boolean isExempt(Profile attacker, Profile target) {
        if (attacker.getPlayer() == null
                || target.getPlayer() == null
                || attacker.shouldCancel()
                || target.shouldCancel()
                || attacker.isBedrockPlayer()
                || target.isBedrockPlayer()
                || attacker.isExempt().isTeleports()
                || target.isExempt().isTeleports()
                || attacker.getMovementData() == null
                || target.getMovementData() == null
                || attacker.getPlayer().isInsideVehicle()
                || target.getPlayer().isInsideVehicle()
                || target.getPlayer().isDead()) {
            return true;
        }

        GameMode attackerMode = attacker.getActionData().getGameMode();
        GameMode targetMode = target.getActionData().getGameMode();

        if (attackerMode == GameMode.CREATIVE || attackerMode == GameMode.SPECTATOR
                || targetMode == GameMode.CREATIVE || targetMode == GameMode.SPECTATOR) {
            return true;
        }

        MovementData attackerMovement = attacker.getMovementData();
        MovementData targetMovement = target.getMovementData();

        return attacker.isSwimming()
                || attacker.isCrawling()
                || target.isSwimming()
                || target.isCrawling()
                || attackerMovement.isRiptiding()
                || targetMovement.isRiptiding()
                || attackerMovement.getSinceGlidingTicks() < 20
                || targetMovement.getSinceGlidingTicks() < 20;
    }

    private List<CustomLocation> snapshot(SampleList<CustomLocation> history) {
        try {
            return new ArrayList<>(history);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private List<CustomLocation> getRenderedPositions(List<CustomLocation> history, int pingTicks) {
        int newest = history.size() - 1;
        int expectedAge = Math.min(newest, Math.max(0, pingTicks + 1));
        int youngest = Math.max(0, expectedAge - 1);
        int oldest = Math.min(newest, expectedAge + 3);
        List<CustomLocation> result = new ArrayList<>();

        for (int age = youngest; age <= oldest; age++) {
            CustomLocation current = history.get(newest - age);

            if (current == null) continue;
            result.add(current);

            if (age >= oldest) continue;
            CustomLocation next = history.get(newest - age - 1);

            if (next != null && current.getWorld() == next.getWorld()) {
                result.add(interpolate(current, next, 1.0D / 3.0D));
                result.add(interpolate(current, next, 2.0D / 3.0D));
            }
        }

        return result;
    }

    private CustomLocation interpolate(CustomLocation from, CustomLocation to, double amount) {
        return new CustomLocation(
                from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * amount,
                from.getY() + (to.getY() - from.getY()) * amount,
                from.getZ() + (to.getZ() - from.getZ()) * amount
        );
    }

    private List<Vector> getPossibleDirections(RotationData rotation) {
        List<Vector> directions = new ArrayList<>(3);
        directions.add(direction(rotation.getYaw(), rotation.getPitch()));

        if (profile.getVersion() == null
                || profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_8)) {
            directions.add(direction(rotation.getLastYaw(), rotation.getPitch()));
        }

        if (profile.getVersion() == null
                || profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            directions.add(direction(rotation.getLastYaw(), rotation.getLastPitch()));
        }

        return directions;
    }

    private Vector direction(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);

        return new Vector(
                -horizontal * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians)
        ).normalize();
    }

    private double[] getPossibleEyeHeights() {
        boolean modernSneak = profile.getVersion() != null
                && profile.getVersion().isNewerThanOrEquals(ClientVersion.V_1_14);
        return new double[]{1.62D, modernSneak ? 1.27D : 1.54D};
    }

    private BoundingBox createTargetBox(CustomLocation location, double horizontal, double vertical) {
        return new BoundingBox(
                location.getX() - 0.30D - horizontal,
                location.getY() - vertical,
                location.getZ() - 0.30D - horizontal,
                location.getX() + 0.30D + horizontal,
                location.getY() + 1.80D + vertical,
                location.getZ() + 0.30D + horizontal
        );
    }

    private double getProtocolMargin() {
        boolean legacy = profile.getVersion() != null
                && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
        return legacy ? 0.1005D : 0.0305D;
    }

    private int getPingTicks(Profile source) {
        ConnectionData connection = source.getConnectionData();
        if (connection == null) return 0;

        int ticks = Math.max(0, connection.getClientTickTrans());
        ticks = Math.max(ticks, (int) Math.ceil(Math.max(0, connection.getTransPing()) / 50.0D));
        ticks = Math.max(ticks, (int) Math.ceil(Math.max(0, connection.getPing()) / 50.0D));
        return Math.min(20, ticks);
    }

    private double distancePointToRay(Vector origin, Vector direction, Vector point) {
        Vector toPoint = point.clone().subtract(origin);
        double projection = toPoint.dot(direction);
        Vector closest = projection <= 0.0D
                ? origin
                : origin.clone().add(direction.clone().multiply(projection));
        return point.distance(closest);
    }

    private double angleToPoint(Vector origin, Vector direction, Vector point) {
        Vector target = point.clone().subtract(origin);
        if (target.lengthSquared() <= 1.0E-12D) return 0.0D;

        double dot = direction.clone().normalize().dot(target.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
    }

    private boolean inside(Vector point, BoundingBox box) {
        return point.getX() >= box.minX && point.getX() <= box.maxX
                && point.getY() >= box.minY && point.getY() <= box.maxY
                && point.getZ() >= box.minZ && point.getZ() <= box.maxZ;
    }

    private boolean isMovement(PacketTypeCommon packetType) {
        return packetType.equals(PacketType.Play.Client.PLAYER_FLYING)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION);
    }

    private String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }
}
