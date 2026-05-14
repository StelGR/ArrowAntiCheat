package me.arrow.checks.impl.movement.motion;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.custom.MaterialType;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;

// this is a fairly simple jump height check, for jumping lower or higher than normal, it is easy to bypass though so
// it's only for terrible checks, like high jump

public class MotionA extends Check {
    public MotionA(Profile profile) {
        super(profile, CheckType.MOTION, "A", "Checks for invalid jump height");
    }

    private double buffer, buffer2;

    @Override
    public void handle(PacketSendEvent event) {

    }

    private static final Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    private static Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        addCauseIfPresent(set, "VOID");
        addCauseIfPresent(set, "POISON");
        addCauseIfPresent(set, "WITHER");
        addCauseIfPresent(set, "MAGIC");
        addCauseIfPresent(set, "SUFFOCATION");
        addCauseIfPresent(set, "LIGHTNING");
        addCauseIfPresent(set, "CONTACT");
        addCauseIfPresent(set, "CRAMMING");
        addCauseIfPresent(set, "WORLD_BORDER");
        return set;
    }

    private static void addCauseIfPresent(Set<EntityDamageEvent.DamageCause> set, String name) {
        try {
            set.add(EntityDamageEvent.DamageCause.valueOf(name));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            if (profile.getMovementData().isOnBoat()
                    || profile.getMovementData().isNearBoat()) return;

            MovementData movementData = profile.getMovementData();

            if (profile.getPlayer().getLastDamageCause() != null) {
                EntityDamageEvent.DamageCause cause = profile.getPlayer().getLastDamageCause().getCause();

                if (IGNORED_CAUSES.contains(cause)) {
                    return;
                }
            }

            if (profile.getBlockProcessor().getLastGhostLiquidWebTick() < 10 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion A: is Exempting (ghostblock liquid/web)");
                return;
            }

            double deltaY = movementData.getDeltaY();

            if (profile.shouldCancel()
                    || profile.isExempt().isTeleports()
                    || !profile.isExempt().isRespawned()
                    || profile.getLastBlockPlaceTimer().hasNotPassed(6 +(profile.getConnectionData().getClientTickTrans() * 2))
                    || profile.isBouncingOnSlime()
                    || profile.getPlayer().isInsideVehicle()
                    || movementData.isNearWater()
                    || movementData.isNearLava()
                    || movementData.isNearWebs()
                    || movementData.isNearWall()
                    || movementData.isNearBuggyBlock()
                    || movementData.isNearBed()
                    || movementData.isUnderblock()
                    || movementData.getMovingUnderblockTicks() > 0
                    || movementData.isOnSlime()
                    || profile.getMovementData().getSinceRiptidingTicks() < 20
                    || profile.getVelocityData().isTakingVelocity()) {
                return;
            }

            if (profile.getExempt().isReelingIn()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion A: is Exempting (reelingIn)");
                return;
            }

            if (movementData.elytraMomentum() > 0) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion A: elytraMomentum");
                return;
            }

            if (profile.getMovementData().getSinceOnGhostBlock() < 10 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion A: is Exempting GhostBlock");
                movementData.setCustomAirTicks(0);
                return;
            }

            if (profile.getBlockProcessor().isUnderGhostBlock()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion A: underGhostblock");
                return;
            }

            if (profile.getMovementData().getSinceGlidingTicks() < 15 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Motion A: Exempt - just gliding");
                return;
            }

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(movementData.getLocation(), true);
            final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lower = CollisionUtils.getNearbyBlocks(movementData.getLastLocation(), true);
            final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lowest = CollisionUtils.getNearbyBlocks(movementData.getLastLastLocation(), true);

            boolean onHoney0 = CollisionUtils.isStandingOnMaterial(movementData.getLocation(), nearbyBlocksResult, true, MaterialType.HONEY);
            boolean onHoney1 = CollisionUtils.isStandingOnMaterial(movementData.getLastLocation(), nearbyBlocksResult_lower, true, MaterialType.HONEY);
            boolean onHoney2 = CollisionUtils.isStandingOnMaterial(movementData.getLastLastLocation(), nearbyBlocksResult_lowest, true, MaterialType.HONEY);
            boolean onHoney = onHoney0 || onHoney1 || onHoney2;

            boolean isGround = movementData.isOnGround(),
                    lastGround = movementData.isLastOnGround();
            boolean serverGround = movementData.isServerGround(), positionGround = movementData.isPositionYGround();
            boolean lastServerGround = movementData.isLastServerGround(), lastPositionGround = movementData.isLastPositionYGround();

            double motion = MoveUtils.getJumpMotion(profile);

            double maxJumpHeight = onHoney ? motion * 0.5F : motion;



// this here was made for towering, as it used to false, i do not remember why, and it does not do it anymore, so yeah this is commented out

//            if (profile.getLastBlockPlaceTimer().hasNotPassed(15 + profile.getConnectionData().getClientTickTrans()) && (deltaY > 0.4044448 && deltaY < 0.404445) && !isGround && lastGround && !serverGround && !lastServerGround && !positionGround && !lastPositionGround) {
//                buffer = 0;
//                return;
//            }

            String data = MsgType.MAIN_THEME_COLOR.getMessage() +"* Verbose\n * deltaY "+MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                    + "\n * maxJumpHeight "+MsgType.MAIN_THEME_COLOR.getMessage() + maxJumpHeight
                    + "\n * clientGround "+MsgType.MAIN_THEME_COLOR.getMessage() + isGround
                    + "\n * lastClientGround "+MsgType.MAIN_THEME_COLOR.getMessage() + lastGround
                    + "\n * serverGround "+MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                    + "\n * lastServerGround "+MsgType.MAIN_THEME_COLOR.getMessage() + lastServerGround
                    + "\n * positionGround "+MsgType.MAIN_THEME_COLOR.getMessage() + positionGround
                    + "\n * lastPositionGround "+MsgType.MAIN_THEME_COLOR.getMessage() + lastPositionGround
                    + "\n * underblock (M) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock();

            if (deltaY != 0) verbose(this.getClass().getSimpleName(), deltaY, maxJumpHeight, data);

            if (!isGround
                    && lastGround
                    && deltaY > 0.0
                    && deltaY < maxJumpHeight) {
                if (++buffer > 2) {
                    fail("Jumping Lower Than Expected",
                            "deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nmaxJumpHeight " + MsgType.MAIN_THEME_COLOR.getMessage() + maxJumpHeight
                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + isGround
                                    + "\nlastClientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + lastGround
                                    + "\nunderblock (M) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());
                }

                verbose(this.getClass().getSimpleName(), buffer, 2, data);
            } else {
                buffer -= Math.min(buffer, 0.001);
            }

            if (!isGround
                    && lastGround
                    && deltaY > motion
                    && !movementData.isMovingUp()) {
                if (++buffer2 > 2) {
                    fail("Jumping Higher Than Expected",
                            "deltaY "+ MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nmaxJumpHeight " + MsgType.MAIN_THEME_COLOR.getMessage() + maxJumpHeight
                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + isGround
                                    + "\nlastClientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + lastGround
                                    + "\nunderblock (M) " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());
                }

                verbose(this.getClass().getSimpleName(), buffer2, 2, data);
            }
            else {
                buffer2 -= Math.min(buffer2, 0.025);
            }
        }
    }
}

