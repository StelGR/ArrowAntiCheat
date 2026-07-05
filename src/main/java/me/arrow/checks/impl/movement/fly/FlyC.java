package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.VelocityData;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;

// simple accel limit check, seems to false on pistons, and slimes specifically for some reason, when you crouch and get pushed by a piston. otherwise decent

@Experimental
public class FlyC extends Check {

    public FlyC(Profile profile) {
        super(profile, CheckType.FLY, "C", "Checks for too much acceleration");
    }

    private static final double JUMP_TOL = 0.06; // tolerance for matching jump-start

    @Override
    public void handle(PacketSendEvent event) {

    }

    private static final Set<EntityDamageEvent.DamageCause> IGNORED_CAUSES = buildIgnoredCauses();

    private static Set<EntityDamageEvent.DamageCause> buildIgnoredCauses() {
        EnumSet<EntityDamageEvent.DamageCause> set = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        addCauseIfPresent(set, "VOID");
        addCauseIfPresent(set, "SUFFOCATION");
        addCauseIfPresent(set, "LIGHTNING");
        addCauseIfPresent(set, "CONTACT");
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

            MovementData movementData = profile.getMovementData();

            if (profile.getDamageData().hasAnyCause(IGNORED_CAUSES, 6 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                return;
            }

            if (profile.isExempt().isTeleports()) {
                resetBuffer();
                return;
            }

            if (profile.shouldCancel()
                    || profile.isExempt().isVehicle()
                    || profile.isBouncingOnSlime()
                    || movementData.isOnBoat()
                    || movementData.isNearBoat()
                    || movementData.isNearGhast()
                    || movementData.isNearWebs()
                    || profile.getActionData().hasRecentConfirmedUnderPlace(5 + (profile.getConnectionData().getClientTickTrans() * 2))
                    || movementData.isNearBed()
                    || movementData.isNearLava()
                    || movementData.getSinceNearWaterTicks() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)
                    || movementData.isNearClimbable()
                    || movementData.getSlimeTicks() > 0
                    || movementData.isMovingUp()
                    || movementData.isMovingDown()
                    || movementData.getSinceRiptidingTicks() < 30
                    || movementData.getSinceBubbleTicks() < 10 + (profile.getConnectionData().getClientTickTrans() * 2)
                    || profile.getPotionData().isHasLevitation()) {
                return;
            }

            if (profile.getGeysersTracker().isBeingPushed()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: Exempt - geysers (26.2+)");
                return;
            }

            if (movementData.getSinceTeleportTicks() < 20 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: is Exempting (SinceTeleports)");
                return;
            }

            if (movementData.getSinceGlidingTicks() < 20) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: is Exempting (Gliding)");
                return;
            }

            if (profile.getVehicleData().getSinceVehicleTicks() < 1 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: Exempt - vehicle");
                return;
            }

            int ghostPhysicsTicks = 10 + (profile.getConnectionData().getClientTickTrans() * 4);

            if (profile.getBlockProcessor().isGhostPhysicsPlacementExempt(ghostPhysicsTicks)) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: is Exempting (ghostblock liquid/web/pending physics place)");
                return;
            }

            if (profile.getMovementData().getSinceOnGhostBlock() < 15 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: is Exempting (ghostblock)");
                return;
            }

            if (movementData.getSinceInsideWaterTicks() < 15 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: is Exempting (sinceInsideWater)");
                return;
            }

            if (profile.getExempt().isReelingIn()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly C: is Exempting (fishingRod)");
                return;
            }

            final int serverAirTicks = movementData.getServerAirTicks();
            final int clientAirTicks = movementData.getClientAirTicks();

            final double deltaY = movementData.getDeltaY();
            final double lastDeltaY = movementData.getLastDeltaY();

            double acceleration = deltaY - lastDeltaY;

//            acceleration = Math.abs(acceleration);

            double jumpStart = MoveUtils.getJumpMotion(profile);
            double totalVerticalVelocity = profile.getVelocityData().getTotalVerticalVelocity();


            final boolean isNotJumpStart = Math.abs(deltaY - jumpStart) > JUMP_TOL;

            VelocityData vd = profile.getVelocityData();

            double expectedY = 1.01D + jumpStart;

            expectedY += movementData.getSinceRiptidingTicks() < 10 + profile.getConnectionData().getClientTickTrans() ? 4.15 : 0;


            final boolean invalidY =
                    deltaY > expectedY
                            && (movementData.isLastOnGround() || serverAirTicks > 0 || clientAirTicks > 0)
                            && isNotJumpStart
                            && !vd.isTakingVelocity();

            final boolean invalidNegativeY = acceleration < -expectedY && (clientAirTicks == 1 || movementData.isServerGround())
                    && !vd.isTakingVelocity()
                    && !movementData.isUnderblock()
                    && movementData.getMovingUnderblockTicks() == 0;

            final boolean invalid = (acceleration > 0.0 && !vd.isTakingVelocity()) && (serverAirTicks > 8 || clientAirTicks > 8) && !profile.getPotionData().isHasSlowFalling();

            //final boolean invalidY2 = deltaY > (profile.getVelocityData().getTotalVerticalVelocity() * 2.5) && serverAirTicks > 8;

            String verboseInfo = "acceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + acceleration
                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                    + "\n+expected " + MsgType.MAIN_THEME_COLOR.getMessage() + expectedY
                    + "\n-expected " + MsgType.MAIN_THEME_COLOR.getMessage() + (-expectedY)
                    + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                    + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                    + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                    + "\njumpStart " + MsgType.MAIN_THEME_COLOR.getMessage() + jumpStart
                    + "\nvelocity " + MsgType.MAIN_THEME_COLOR.getMessage() + totalVerticalVelocity;
            if ((invalid
             && (!movementData.isNearWater()
                || !movementData.isInsideLiquid()))
                    || invalidY) {
                if (increaseBuffer() > 2 || invalidY) {
                    fail("Impossible vertical speed " + (invalid ? "(1)" : "(2)"),
                            verboseInfo);
                    setBuffer(getBuffer() > 6 ? 6 : getBuffer());
                }
            } else {
                decreaseBufferBy(0.25);
            }

            if (invalidNegativeY) {
                fail("Impossible negative vertical speed",
                        verboseInfo);
            }

            final double deltaXZ = movementData.getDeltaXZ();
            final double lastDeltaXZ = movementData.getLastDeltaXZ();

            final double accelerationXZ = deltaXZ - lastDeltaXZ;

            final boolean invalid2 = accelerationXZ > 8;

            if (invalid2) {
                if (increaseBuffer() > 2) {
                    fail("Impossible horizontal speed",
                            "accelerationXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + accelerationXZ
                                    + "\ndeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage()+ deltaXZ
                                    + "\nlastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage()+ lastDeltaXZ
                                    + "\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage()+ serverAirTicks
                                    + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks);
                    setBuffer(getBuffer() > 6 ? 6 : getBuffer());
                }

            } else {
                decreaseBufferBy(0.25);
            }

            verbose(this.getClass().getSimpleName(), accelerationXZ, acceleration, "* Verbose\n * acceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + acceleration
                    + "\n * deltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                    + "\n * lastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + lastDeltaY
                    + "\n * accelerationXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + accelerationXZ
                    + "\n * deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage()+ deltaXZ
                    + "\n * lastDeltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage()+ lastDeltaXZ
                    + "\n * serverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                    + "\n * clientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                    + "\n * jumpStart " + MsgType.MAIN_THEME_COLOR.getMessage() + jumpStart);
        }
    }
}
