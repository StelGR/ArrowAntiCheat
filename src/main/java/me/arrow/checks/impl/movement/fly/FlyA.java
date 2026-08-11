package me.arrow.checks.impl.movement.fly;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.PotionData;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.PotionType;
import me.arrow.utils.custom.SampleList;

import static me.arrow.utils.customutils.Math.MathUtil.getAverage;
import static me.arrow.utils.customutils.Math.MathUtil.getDevation;

// my completely custom air time check, i don't know if anyone else already does this, but i came up with the idea
// by my self, to have an air time check, because.. yk. it makes sense?
// it does not use client air ticks, although they are in the code in here, cus they work differently
// but i do plan in the future to start using them as well in cases where you can completely bypass the air tick limit
// from the server side, but so far that hasn't been an issue

public class FlyA extends Check {

    public FlyA(Profile profile) {
        super(profile, CheckType.FLY, "A", "Checks whether a player stays airborne longer than the world allows.");
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

            long profiler = Profiler.start();

            try {

                MovementData movementData = profile.getMovementData();
                PotionData potionData = profile.getPotionData();

                if (isExempt(movementData, potionData)) return;

                int serverAirTicks = movementData.getCustomAirTicks();
                int clientAirTicks = movementData.getCustomAirTicks();

                double deltaY = movementData.getDeltaY();
                double fallDistance = profile.getPlayer().getFallDistance();
                double deltaXZ = movementData.getDeltaXZ();
                boolean inAir = movementData.isCustomInAir();
                boolean serverGround = movementData.isServerGround();
                boolean clientGround = movementData.isOnGround();

                if (movementData.isNearShulker()
                        || movementData.isNearShulkerBox()
                        || movementData.isNearLava()
                        || movementData.isNearWater()
                        || movementData.getSinceRiptidingTicks() < 30 + (profile.getConnectionData().getClientTickTrans() * 2)
                        || movementData.getSinceBubbleTicks() < 25 + (profile.getConnectionData().getClientTickTrans() * 2)
                        || profile.getBlockProcessor().isCancelledBlockPlacementExempt(12 + (profile.getConnectionData().getClientTickTrans() * 2))
                        || profile.getActionData().getLastConfirmedUnderBreakTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 2)) {
                    return;
                }

                boolean hasJumpBoost = SpeedUtilities.getJumpBoostPotionLevel(profile) > 0;
                double jumpLevel = hasJumpBoost
                        ? potionData.getPotionEffectLevel(PotionType.JUMP_BOOST)
                        + (4 + (potionData.getJumpAmplifier()))
                        : 0;

                int clientTickTrans = profile.getConnectionData().getClientTickTrans();

                boolean recentlyPlaced = profile.getActionData().getLastConfirmedUnderPlaceTicks() < 5 + (clientTickTrans * 2);

                if (hasJumpBoost) {
                    if (recentlyPlaced) {
                        airTickLimit = (16 + clientTickTrans) + jumpLevel;
                        clientAirTickLimit = (18 + clientTickTrans) + jumpLevel;
                    } else {
                        airTickLimit = 8 + jumpLevel;
                        clientAirTickLimit = 12 + jumpLevel;
                    }
                } else {
                    airTickLimit = recentlyPlaced ? 16 + clientTickTrans : 8;
                    clientAirTickLimit = recentlyPlaced ? 20 + clientTickTrans : 12;
                }

                if (deltaXZ != 0) airTickLimit += recentlyPlaced ? 6 + clientTickTrans : 2;

                clientAirTickLimit = 4 + jumpLevel;

                boolean exempt = movementData.isInsideLiquid()
                        || movementData.isNearWebs();

                double vel = Math.max(
                        profile.getVelocityData().getTotalVerticalVelocitySustain(),
                        profile.getVelocityData().getStackedVerticalVelocity()
                );

                double velMag = Math.max(
                        vel,
                        profile.getVelocityData().getTotalVerticalVelocity()
                );

                double horizo = profile.getVelocityData().getTotalHorizontalVelocity();

                velMag += horizo;

                double baseTicksVel = 10;
                double baseVelocity = 0.00001;
                double scale = 28;

                double extraFromVel = velMag <= baseVelocity ? 0 : baseTicksVel + (scale * (velMag - baseVelocity));
                airTickLimit += Math.ceil(extraFromVel);

                if (movementData.isNearFence()) airTickLimit += 4;

                //temporary piston fix
                if (movementData.getSinceNearSlimeTicks() <= (40 + (profile.getConnectionData().getClientTickTrans() * 2))
                        && movementData.getSinceNearPistonTicks() <= (40 + (profile.getConnectionData().getClientTickTrans() * 2))) {
                    airTickLimit += 8;
                    ChatUtils.debugExempt("pistonFix", "FlyA");
                }

                airTickLimit = Math.max(airTickLimit, 12);

                boolean invalidNormal =
                        serverAirTicks > airTickLimit
                                && deltaY > -0.37
                                && inAir;

                verbose(this.getClass().getSimpleName(), serverAirTicks, airTickLimit, MsgType.MAIN_THEME_COLOR.getMessage() + "* Verbose\n * serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                        + "\n * clientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                        + "\n * serverYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isServerYGround()
                        + "\n * serverPositionYGround " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isPositionYGround()
                        + "\n * inAir " + MsgType.MAIN_THEME_COLOR.getMessage() + inAir
                        + "\n * serverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                        + "\n * clientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                        + "\n * deltaY&b " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                        + "\n * fallDistance&b " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance
                        + "\n * underBlock&b " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock()
                        + "\n * sAirTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit
                        + "\n * cAirTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTickLimit
                        + "\n * extraTicksVel " + MsgType.MAIN_THEME_COLOR.getMessage() + extraFromVel
                        + "\n * sinceNearSlimeTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getSinceNearSlimeTicks()
                        + "\n * velMag " + MsgType.MAIN_THEME_COLOR.getMessage() + velMag
                        + "\n * velocityH " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalHorizontalVelocity()
                        + "\n * velocityV " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalVerticalVelocity()
                        + "\n * velocityH Sustain " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalHorizontalVelocitySustain()
                        + "\n * velocityV Sustain " + MsgType.MAIN_THEME_COLOR.getMessage() + profile.getVelocityData().getTotalVerticalVelocitySustain()
                        + "\n * jumpAmplifierMath " + MsgType.MAIN_THEME_COLOR.getMessage() + (0.42 + ((potionData.getJumpAmplifier() + 1) * 0.1)));

                int maxTicks = Math.min(15, profile.getConnectionData().getClientTickTrans() == 0 ? 15 : 10 + (profile.getConnectionData().getTransPing() / 20) / profile.getConnectionData().getClientTickTrans());

                boolean yLevelBelowBedrock = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_18) ? movementData.getLocation().getY() < -64 : movementData.getLocation().getY() < 0;

                if (inAir
                        && !profile.getActionData().hasRecentConfirmedBlockUpdateUnder(5 + (profile.getConnectionData().getClientTickTrans() * 2))
                        && movementData.getCustomAirTicks() > maxTicks
                        && !movementData.isNearWater()
                        && !movementData.isNearWebs()
                        && !yLevelBelowBedrock
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


                if (!serverGround && !clientGround && inAir && movementData.isUnderblock()) {
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

                if (invalidNormal && !exempt && movementData.getSinceLevitationEffectTicks() > 20) {
                    fail("Improbable air time (" + serverAirTicks + "/" + airTickLimit + ")",
                            "serverGround " + MsgType.MAIN_THEME_COLOR.getMessage() + serverGround
                                    + "\nclientGround " + MsgType.MAIN_THEME_COLOR.getMessage() + clientGround
                                    + "\ninAir " + MsgType.MAIN_THEME_COLOR.getMessage() + "true\nserverAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + serverAirTicks
                                    + "\nclientAirTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + clientAirTicks
                                    + "\nairTickLimit " + MsgType.MAIN_THEME_COLOR.getMessage() + airTickLimit + " / " + clientAirTickLimit
                                    + "\ndeltaY " + MsgType.MAIN_THEME_COLOR.getMessage() + deltaY
                                    + "\nfallDistance " + MsgType.MAIN_THEME_COLOR.getMessage() + fallDistance
                                    + "\nunderBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.isUnderblock());
                }
            } finally {
                Profiler.stop("Fly A", profiler);
            }
        }
    }


    boolean isExempt(MovementData movementData, PotionData potionData) {
        ClientWorldTracker.CollisionResult world = profile.getClientWorldTracker().getCollisionResult();

        if (world.shouldExemptMovementChecks()
                || world.physicsMismatch
                || world.onGhostBlock
                || world.nearGhostBlock
                || world.insideGhostBlock
                || profile.getBlockProcessor().isCancelledBlockPlacementExempt(10 + (profile.getConnectionData().getClientTickTrans() * 2))) {
            movementData.setCustomAirTicks(0);
            return true;
        }

        if (profile.shouldCancel()) {
            ChatUtils.debugExempt("shouldCancel", "FlyA");
            return true;
        }

        if (profile.getGeysersTracker().isBeingPushed()) {
            ChatUtils.debugExempt("geysers (26.2+)", "FlyA");
            movementData.setCustomAirTicks(0);
            return true;
        }

        if ((movementData.getSinceLevitationEffectTicks() < 10 && potionData.getLevitationTicks() > 0)
                && ((movementData.getDeltaY() != 0 && movementData.getLastDeltaY() != 0) || movementData.isUnderblock())) {
            movementData.setCustomAirTicks(0);
            ChatUtils.debugExempt("is Resetting AirTicks for levitation", "FlyA");
        }

        if (profile.isBouncingOnSlime()) {
            ChatUtils.debugExempt("slimeBounce", "FlyA");
            return true;
        }

        if (movementData.isOnBoat()) {
            ChatUtils.debugExempt("onboat", "FlyA");
            return true;
        }

        if (movementData.isNearBoat()) {
            ChatUtils.debugExempt("nearboat", "FlyA");
            return true;
        }

        int ghostPhysicsTicks = 10 + (profile.getConnectionData().getClientTickTrans() * 4);

        if (profile.getBlockProcessor().isGhostPhysicsPlacementExempt(ghostPhysicsTicks)) {
            ChatUtils.debugExempt("ghostphysics + resetting airticks", "FlyA");
            movementData.setCustomAirTicks(0);
            return true;
        }

        if (movementData.isNearShulkerBox()) {
            ChatUtils.debugExempt("nearShulkerBox", "FlyA");
            return true;
        }

        if (movementData.isNearGhast()) {
            ChatUtils.debugExempt("nearGhast", "FlyA");
            return true;
        }

        if (movementData.isNearShulker()) {
            ChatUtils.debugExempt("nearShulker", "FlyA");
            return true;
        }

        if (movementData.getSinceTeleportTicks() < 5 + (profile.getConnectionData().getClientTickTrans() * 4)) {
            ChatUtils.debugExempt("teleports", "FlyA");
            return true;
        }

        if (!profile.isExempt().isRespawned()) {
            ChatUtils.debugExempt("notAlive", "FlyA");
            return true;
        }

        if (movementData.getSinceBubbleTicks() < 15 + profile.getConnectionData().getClientTickTrans()
                || movementData.getSinceNearWaterTicks() < 8 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            ChatUtils.debugExempt("nearWater", "FlyA");
            return true;
        }

        if (profile.getPlayer().isDead()) {
            ChatUtils.debugExempt("notAlive(Dead)", "FlyA");
            return true;
        }

        if (profile.isExempt().vehicle()) {
            ChatUtils.debugExempt("vehicle", "FlyA");
            return true;
        }

        if (movementData.getSinceOnGhostBlock() < 15 + (profile.getConnectionData().getClientTickTrans() * 2)) {
            ChatUtils.debugExempt("ghostblock", "FlyA");
            return true;
        }

        if (profile.getExempt().isReelingIn()) {
            ChatUtils.debugExempt("reeling(fishing)", "FlyA");
            return true;
        }

        if (movementData.getSinceGlidingTicks() < 25 + profile.getConnectionData().getClientTickTrans()) {
            ChatUtils.debugExempt("elytraGlide", "FlyA");
            return true;
        }

        if (movementData.isNearHoney()) {
            ChatUtils.debugExempt("honey", "FlyA");
            return true;
        }

        if (movementData.getSincePowderSnowTicks() < 10) {
            ChatUtils.debugExempt("powderSnow", "FlyA");
            return true;
        }

        if (movementData.getSinceNearGhastTicks() < 10) {
            ChatUtils.debugExempt("wasNearGhast", "FlyA");
            return true;
        }

        if (movementData.elytraMomentum() > 0) {
            ChatUtils.debugExempt("elytraMomentum", "FlyA");
            return true;
        }

        if (potionData.getSlowFallingTicks() > 0 && movementData.getSinceSlowFallingEffectTicks() < 10) {
            ChatUtils.debugExempt("slowFalling", "FlyA");
            return true;
        }

        CustomLocation loc = movementData.getLocation();

        if (!CollisionUtils.isChunkLoaded(loc)) {
            ChatUtils.debugExempt("unloaded chunk", "FlyA");
            return true;
        }

        return false;

    }
}
