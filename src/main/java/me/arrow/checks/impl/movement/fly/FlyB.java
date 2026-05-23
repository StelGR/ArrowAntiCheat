package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.PotionType;
import me.arrow.utils.custom.SampleList;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.event.entity.EntityDamageEvent;

import static me.arrow.utils.customutils.Math.MathUtil.getAverage;
import static me.arrow.utils.customutils.Math.MathUtil.getDevation;

// my completely custom Fly B check, i don't know if anyone else already does this, but i came up with the idea
// by my self, to have an air time check, because.. yk. it makes sense?
// it does not use client air ticks, although they are in the code in here, cus they work differently
// but i do plan in the future to start using them as well in cases where you can completely bypass the air tick limit
// from the server side, but so far that hasn't been an issue

public class FlyB extends Check {

    public FlyB(Profile profile) {
        super(profile, CheckType.FLY, "B", "Checks for improbable air time by using world material");
    }

    double airTickLimit;
    double clientAirTickLimit;

    SampleList<Double> samples = new SampleList<>(40);


    SampleList<Double> underBlockSamples = new SampleList<>(20);

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {


            MovementData movementData = profile.getMovementData();
            int serverAirTicks = movementData.getCustomAirTicks();

            ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

            if (world.shouldExemptMovementChecks()
                    || world.physicsMismatch
                    || world.onGhostBlock
                    || world.nearGhostBlock
                    || world.insideGhostBlock) {
                return;
            }

            if (profile.shouldCancel()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (shouldCancel)");
                return;
            }

            if (profile.getLastBlockPlaceTimer().hasNotPassed(8 + profile.getConnectionData().getClientTickTrans())
                    && CollisionUtils.isNearEdge(movementData.getLocation())) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (nearEdge + blockplace)");
                return;
            }

            if (profile.isBouncingOnSlime()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (bouncing on slime)");
                return;
            }

            if (movementData.isOnBoat()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (on boat)");
                return;
            }

            if (movementData.isNearBoat()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (near boat)");
                return;
            }

            int ghostLiquidWebTicks = Math.min(
                    profile.getBlockProcessor().getLastGhostLiquidWebTick(),
                    profile.getBlockProcessor().getLastPendingPhysicsPlaceTick()
            );

            if (ghostLiquidWebTicks < 10 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (ghostblock liquid/web/pending physics place)");
                return;
            }

            if (movementData.isNearShulkerBox()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (near shulker box)");
                return;
            }

            if (movementData.isNearGhast()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (near ghast)");
                return;
            }

            if (movementData.isNearShulker()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (near shulker)");
                return;
            }

            if (profile.isExempt().isTeleports()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (teleports)");
                return;
            }

            if (!profile.isExempt().isRespawned()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (not respawned)");
                return;
            }

            if (movementData.getSinceNearWaterTicks() < 12 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (inside water)");
                return;
            }

            if (profile.getPlayer().isDead()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (player dead)");
                return;
            }

            if (profile.getVehicleData().getSinceVehicleTicks() < 5 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (recent vehicle)");
                return;
            }

            if (profile.isExempt().vehicle()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (vehicle exempt)");
                return;
            }

            if (profile.getMovementData().getSinceOnGhostBlock() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (ghost block)");
                return;
            }

            if (profile.getExempt().isReelingIn()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (reeling in)");
                return;
            }

            if (profile.getMovementData().getSinceGlidingTicks() < 25 + profile.getConnectionData().getClientTickTrans()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (elytra glide)");
                return;
            }

            if (profile.getMovementData().isNearHoney()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (honey)");
                return;
            }

            if (profile.getMovementData().isNearShulkerBox()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (shulker box)");
                return;
            }

            if (movementData.getSincePowderSnowTicks() < 10) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (powder snow)");
                return;
            }

            if (movementData.getSinceNearGhastTicks() < 10) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: was Near Ghast");
                return;
            }

            if (movementData.elytraMomentum() > 0) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: elytraMomentum");
                return;
            }

            if (profile.getPotionData().isHasSlowFalling()) {
                if (Config.Setting.DEBUG.getBoolean()) OtherUtility.log("Fly B: is Exempting (slow falling)");
                movementData.setCustomAirTicks(0);
            }

            int clientAirTicks = movementData.getCustomAirTicks();

            double deltaY = movementData.getDeltaY();
            double fallDistance = profile.getPlayer().getFallDistance();
            double deltaXZ = movementData.getDeltaXZ();
            boolean inAir = movementData.isCustomInAir();
            boolean serverGround = movementData.isServerGround();
            boolean clientGround = movementData.isOnGround();

            if (movementData.getSinceLevitationEffectTicks() < 10
                    || movementData.isNearShulker()
                    || movementData.isNearShulkerBox()
                    || movementData.getSinceRiptidingTicks() < 30
                    || movementData.getSinceBubbleTicks() < 25 + profile.getConnectionData().getClientTickTrans()) {
                //serverAirTicks = 0;
                return;
            }

//          double explosionSpeed = user.getPredictionProcessor().getExplosionSpeed();

            // user.getPlayer().sendMessage("airTicks: "+serverAirTicks+", deltaY: "+deltaY+", falldistance: "+fallDistance+", inAir: "+inAir+", serverGround: "+serverGround+", clientGround: "+clientGround);

            boolean hasJumpBoost = profile.getPotionData().isHasJump();
            double jumpLevel = hasJumpBoost
                    ? profile.getPotionData().getPotionEffectLevel(PotionType.JUMP_BOOST)
                    + (4 + (profile.getPotionData().getJumpAmplifier()))
                    : 0;

            int clientTickTrans = profile.getConnectionData().getClientTickTrans();
            int transPing = profile.getConnectionData().getTransPing();
            boolean blockInHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInMainHand(profile.getPlayer()).getType().isBlock();
            boolean blockInOffHand = Arrow.getInstance().getNmsManager().getNmsInstance().getItemInOffHand(profile.getPlayer()).getType().isBlock();
            boolean holdingBlock = blockInHand || blockInOffHand;

            int blockPlaceLimit = clientTickTrans == 0 ? 5 : Math.min(5 + transPing / clientTickTrans, 30);
            boolean recentlyPlaced = profile.getLastBlockPlaceTimer().hasNotPassed(blockPlaceLimit);

            if (hasJumpBoost) {
                if (recentlyPlaced && holdingBlock) {
                    airTickLimit = (16 + clientTickTrans) + jumpLevel;
                    clientAirTickLimit = (16 + clientTickTrans) + jumpLevel;
                } else {
                    airTickLimit = 10 + jumpLevel;
                    clientAirTickLimit = 10 + jumpLevel;
                }
            } else {
                airTickLimit = (recentlyPlaced && holdingBlock) ? 16 + clientTickTrans : 10;
                clientAirTickLimit = (recentlyPlaced && holdingBlock) ? 20 + clientTickTrans : 12;
            }

            if (deltaXZ != 0) airTickLimit += (recentlyPlaced && holdingBlock) ? 6 + clientTickTrans : 2;

            clientAirTickLimit = 4 + jumpLevel;

            if (profile.getLastBlockBreakTimer().hasNotPassed(
                    profile.getConnectionData().getClientTickTrans() == 0 ? 3 :
                            Math.min(3 + ((profile.getConnectionData().getTransPing() / 20) / profile.getConnectionData().getClientTickTrans()), 8))) {
                airTickLimit += 1;
                clientAirTickLimit += 1;
            }

            boolean exempt = movementData.isInsideLiquid()
                    || movementData.isNearWebs()
                    || (profile.getMovementData().getSinceGlidingTicks() < 10);

//            if (Double.isNaN(airTickLimit) || Double.isInfinite(airTickLimit)) {
//                airTickLimit = 10.0;
//            }

            if (profile.getPlayer().getLastDamageCause() != null) {
                EntityDamageEvent.DamageCause cause = profile.getPlayer().getLastDamageCause().getCause();
                if (cause == EntityDamageEvent.DamageCause.VOID
                        || cause == EntityDamageEvent.DamageCause.SUFFOCATION
                        || cause == EntityDamageEvent.DamageCause.LIGHTNING
                        || cause == EntityDamageEvent.DamageCause.CONTACT) {

                    clientAirTickLimit += 2;
                    airTickLimit += 2;
                }
            }



            double horizontal = profile.getVelocityData().getTotalHorizontalVelocitySustain();
            double vertical = profile.getVelocityData().getTotalVerticalVelocitySustain();
            double velMag = horizontal + (vertical * 2);

            double baseTicksVel = 6;
            double baseVelocity = 0.0005;
            double scale = 15.5;
            double maxExtra = 225;

            double extraFromVel = velMag <= baseVelocity ? 0 : baseTicksVel + Math.min(scale * (velMag - baseVelocity), maxExtra);

//            double connectionAdj = Math.min(
//                    0,
//                    1.0D + (profile.getConnectionData().getTransPing() / 50.0D)
//            );

            //double velocityTickExempt = extraFromVel;
            airTickLimit += extraFromVel;

            if (movementData.isNearFence()) airTickLimit += 4;

//            if (profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)
//                    || PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_12_2)) {
//                airTickLimit += 2;
//            }

//            airTickLimit = Math.max(airTickLimit, 10);
//            clientAirTickLimit = Math.max(clientAirTickLimit, 9);

            boolean invalidNormal =
                    (
                            (serverAirTicks > airTickLimit)
                            //|| (clientAirTicks > clientAirTickLimit)
                    )
                    && deltaY > -0.301
                    && inAir;

            verbose(this.getClass().getSimpleName(), serverAirTicks, airTickLimit, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                    + "\n * clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                    + "\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                    + "\n * serverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerYGround()
                    + "\n * serverPositionYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPositionYGround()
                    + "\n * inAir " + MsgType.MAIN_THEME_COLOR.getMessage() + "true\n * serverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                    + "\n * clientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                    + "\n * deltaY&b " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                    + "\n * fallDistance&b " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance
                    + "\n * underBlock&b " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock()
                    + "\n * sAirTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit
                    + "\n * cAirTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTickLimit
                    + "\n * extraTicksVel " + MsgType.MAIN_THEME_COLOR.getMessage() + extraFromVel
                    + "\n * velMag " + MsgType.MAIN_THEME_COLOR.getMessage() + velMag
                    + "\n * placeTimer " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getLastBlockPlaceTimer().getTick()
                    + "\n * velocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalHorizontalVelocity()
                    + "\n * velocityV " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalVerticalVelocity()
                    + "\n * velocityH Sustain " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalHorizontalVelocitySustain()
                    + "\n * velocityV Sustain " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalVerticalVelocitySustain()
                    + "\n * jumpAmplifierMath " + MsgType.MAIN_THEME_COLOR.getMessage() + (0.42 + ((profile.getPotionData().getJumpAmplifier() + 1) * 0.1)));

            int maxTicks = Math.min(15, profile.getConnectionData().getClientTickTrans() == 0 ? 15 : 10 + (profile.getConnectionData().getTransPing() / 20) / profile.getConnectionData().getClientTickTrans());

            if (inAir
                    && profile.getLastBlockPlaceTimer().passed(2 + profile.getConnectionData().getClientTickTrans())
                    && movementData.getCustomAirTicks() > maxTicks
                    && !movementData.isNearWater()
                    && !movementData.isNearWebs()
                    && !movementData.isInsideLiquid()) {
                if (deltaY == movementData.getLastDeltaY()) {
                    samples.add(deltaY);

                    if (movementData.isNearWater()) samples.clear();

                    if (samples.isCollected()) {
                        final double deviation = getDevation(this.samples);

                        if (deviation == 0) fail("Not falling (1)",
                                "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + true
                                        + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + true
                                        + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + true
                                        + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                        + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                        + "\ndeviation " + MsgType.MAIN_THEME_COLOR.getMessage() + deviation);
                    }
                }
            }


            if (!serverGround && !clientGround && inAir && profile.getMovementData().isUnderblock()) {
                if (serverAirTicks > 10) {
                    underBlockSamples.add((double) clientAirTicks);

                    if (underBlockSamples.isCollected()) {
                        final double average = getAverage(this.underBlockSamples);

                        //debug(average);

                        if (average > 0.05 && average < 2)
                            fail("Not falling (2)",
                                    "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + false
                                            + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + false
                                            + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + true
                                            + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                            + "\nlastDeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getLastDeltaY()
                                            + "\naverage " + MsgType.MAIN_THEME_COLOR.getMessage() + average);
                    }
                }
            }

            //if (profile.getVelocityData().getVelocityTicks() < velocityTickExempt) return;

            if (invalidNormal && !exempt) {
                fail("Improbable air time (" + serverAirTicks + "/" + airTickLimit+")",
                        "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + "true\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                                + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                                + "\nairTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit + " / " + clientAirTickLimit
                                + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                + "\nfallDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance
                                + "\nunderBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());
            }
        }
    }
}