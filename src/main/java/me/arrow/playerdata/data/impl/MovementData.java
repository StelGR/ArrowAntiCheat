package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.nms.NmsInstance;
import me.arrow.playerdata.data.Data;
import me.arrow.playerdata.processors.impl.CollisionProcessor;
import me.arrow.playerdata.processors.impl.SetbackProcessor;
import me.arrow.playerdata.processors.impl.SlimeProcessor;
import me.arrow.utils.*;
import me.arrow.utils.custom.*;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;
import static me.arrow.utils.custom.materials.MaterialType.*;

// this is the entire main data of the anticheat, there's alot of crap thrown in here, and some of them should be in other data classes
// there will be a big recode to organize stuff in the future.

@Getter
@Setter
public class MovementData implements Data {


    @Getter
    @Setter
    double BEDROCK_JUMP_MOTION;

    Profile profile;

    @Getter
    Equipment equipment;

    @Getter
    SetbackProcessor setbackProcessor;

    @Getter
    double deltaX, lastDeltaX, deltaZ, lastDeltaZ, deltaY, lastDeltaY, deltaXZ, lastDeltaXZ,
            accelXZ, lastAccelXZ, accelY, lastAccelY;

    @Getter
    float fallDistance, lastFallDistance,
            baseGroundSpeed, baseAirSpeed,
            frictionFactor = MoveUtils.FRICTION_FACTOR, lastFrictionFactor = MoveUtils.FRICTION_FACTOR,
            dolphinGraceBoost;

    @Getter
    CustomLocation location, lastLocation, lastLastLocation, lastSetBackLocation;


    @Getter
    @Setter
    SampleList<CustomLocation> pastLocations = new SampleList<>(40, true);

    /*
     * Precision-only timeline for reach render-time reconstruction. This is
     * intentionally separate from the configured Reach A sample window: 100
     * references cover five seconds at 20 Hz (including 2000 ms RTT, entity
     * interpolation and jitter) without making every other history consumer
     * scan a larger list.
     */
    @Getter
    SampleList<CustomLocation> reachPastLocations = new SampleList<>(100, true);

    @Getter
    @Setter
    CustomLocation lastGroundLocation;


    @Getter
    boolean onGround, lastOnGround, lastLastOnGround, serverGround, lastServerGround, serverYGround, positionYGround, lastPositionYGround, lastServerYGround,
        nearWater, nearBubble, nearLava, nearContact, nearSlime, nearWebs, lastLastNearWall, lastNearWall, nearWall, nearClimbable, nearBuggyBlock, nearBed, nearHoney, nearShulkerBox, nearDripLeaf, customInAir, underblock, insideLiquid, climb, moving, isInsideWater, isOnTopOfWater, isBottomOfWater, isColliding, nearBoat, nearGhast, nearShulker, nearFence, onBoat, onIce, onSlime, onExtendedHitboxSlime, onHoney, onSoulSand, movingUp, nearStepMaterial, movingDown, isRiptiding, nearPiston, nearBlocksSlime, nearPowderSnow, nearSoulBlock;


    @Getter
    @Setter
    int clientAirTicks, serverAirTicks, serverGroundTicks, serverGroundTicksPlus, lastServerGroundTicks, nearGroundTicks, lastNearGroundTicks,
            clientGroundTicks, lastNearWallTicks,
            lastFrictionFactorUpdateTicks, lastNearEdgeTicks,
            customAirTicks, nearWallTicks, sinceExplosionTicks, sinceCollideTicks, sinceGlidingTicks, sincePowderSnowTicks, sinceElytraEquipTicks,
            sinceOnGhostBlock, sinceGlitchedInsideBlockTicks, sinceOnGround, sinceRiptidingTicks, sinceBubbleTicks, sincePredictUpwardsTicks, sincePredictDownwardsTicks, sincePredictUpwardsTicksWithoutMaterial, sincePredictDownwardsTicksWithoutMaterial, sinceSpeedPotionEffectTicks, sinceNearGhastTicks, movingOnSoulTicks, movingOnSoulBlocksTicks, movingTicks, sinceMovingOnSlimeTicks, sinceMovingOnIceTicks, movingOnHoneyTicks, sinceMovingOnHoneyTicks, slimeTicks, soulTicks, honeyTicks, sinceSlimeTicks, sinceSoulTicks, sinceHoneyTicks, iceTicks, sinceIceTicks, sinceMovingUpTicks, sinceMovingDownTicks, sinceDolphinGraceTicks, dolphinGraceTicks, ladderTicks, sinceInsideWaterTicks, sinceNearWaterTicks, sinceLevitationEffectTicks, sinceJumpBoostEffectTicks, sinceSlowFallingEffectTicks, tick, sinceTeleportTicks, sinceNearSlimeTicks, sinceNearPistonTicks, sinceMovingUnderBlockTicks;

    @Getter
    @Setter
    float movingUnderblockTicks, movingOnIceTicks, movingOnSlimeTicks;

    @Getter
    @Setter
    boolean packetNearWall;

    boolean packetMoving;

    /** Authoritative fall-flying flag from the player's own metadata packet. */
    boolean metadataGliding;
    float elytraMomentumBonus;
    int glideStartTransitionTicks;

    @Getter
    CollisionUtils.NearbyBlocksResult nearbyBlocksResult;

    @Getter
    SlimeProcessor slimeProcessor;

    @Getter
    public MovementPredictionUtil.RelativeMove relative;

    @Getter
    public MovementPredictionUtil.VerticalMove verticalMove;


    private long lastDecayTick = -1L;


    public MovementData(Profile profile) {
        this.profile = profile;

        this.equipment = new Equipment();
        this.setbackProcessor = new SetbackProcessor(profile);
        this.slimeProcessor = new SlimeProcessor(profile);

        /*
        Initialize the current location.
         */
        this.location = this.lastLocation = this.lastLastLocation = new CustomLocation(profile.getPlayer().getLocation());
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        final long currentTime = normalizePacketTimestamp(event.getTimestamp());

        if (event.getPacketType().equals(ENTITY_ACTION)) {
            handleElytraStartAction(event);
        }

        if (event.getPacketType().equals(PLAYER_FLYING)) {
            WrapperPlayClientPlayerFlying move = new WrapperPlayClientPlayerFlying(event);

            this.lastLastOnGround = this.lastOnGround;
            this.lastOnGround = this.onGround;
            this.onGround = move.isOnGround();

            this.packetNearWall = move.isHorizontalCollision();
            this.packetMoving = move.hasPositionChanged();

            this.clientAirTicks = this.onGround ? 0 : this.clientAirTicks + 1;
            this.clientGroundTicks = this.onGround ? this.clientGroundTicks + 1 : 0;

            this.lastLastLocation = this.lastLocation;
            this.lastLocation = this.location;

            processLocationData();
        }
        else if (event.getPacketType().equals(PLAYER_POSITION)) {
            WrapperPlayClientPlayerPosition move = new WrapperPlayClientPlayerPosition(event);

            this.lastLastOnGround = this.lastOnGround;
            this.lastOnGround = this.onGround;
            this.onGround = move.isOnGround();

            this.packetNearWall = move.isHorizontalCollision();
            this.packetMoving = move.hasPositionChanged();

            this.clientAirTicks = this.onGround ? 0 : this.clientAirTicks + 1;
            this.clientGroundTicks = this.onGround ? this.clientGroundTicks + 1 : 0;

            this.lastLastLocation = this.lastLocation;
            this.lastLocation = this.location;
            this.location = new CustomLocation(
                    profile.getPlayer().getWorld(),
                    move.getLocation().getX(), move.getLocation().getY(), move.getLocation().getZ(),
                    move.getLocation().getYaw(), move.getLocation().getPitch(),
                    currentTime
            );

            processLocationData();
        }

        else if (event.getPacketType().equals(PLAYER_ROTATION)) {
            final WrapperPlayClientPlayerRotation look = new WrapperPlayClientPlayerRotation(event);

            this.lastOnGround = this.onGround;
            this.onGround = look.isOnGround();

            this.packetNearWall = look.isHorizontalCollision();
            this.packetMoving = look.hasPositionChanged();

            this.clientAirTicks = this.onGround ? 0 : this.clientAirTicks + 1;
            this.clientGroundTicks = this.onGround ? this.clientGroundTicks + 1 : 0;

            this.lastLastLocation = this.lastLocation;
            this.lastLocation = this.location;

            processLocationData();
        }

        else if (event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)) {
            final WrapperPlayClientPlayerPositionAndRotation posLook = new WrapperPlayClientPlayerPositionAndRotation(event);

            this.lastLastOnGround = this.lastOnGround;
            this.lastOnGround = this.onGround;
            this.onGround = posLook.isOnGround();

            this.packetNearWall = posLook.isHorizontalCollision();
            this.packetMoving = posLook.hasPositionChanged();

            this.clientAirTicks = this.onGround ? 0 : this.clientAirTicks + 1;
            this.clientGroundTicks = this.onGround ? this.clientGroundTicks + 1 : 0;

            this.lastLastLocation = this.lastLocation;
            this.lastLocation = this.location;
            this.location = new CustomLocation(
                    profile.getPlayer().getWorld(),
                    posLook.getLocation().getX(), posLook.getLocation().getY(), posLook.getLocation().getZ(),
                    posLook.getYaw(), posLook.getPitch(),
                    currentTime
            );

            processLocationData();
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Server.ENTITY_METADATA)
                || profile.getPlayer() == null) {
            return;
        }

        WrapperPlayServerEntityMetadata metadata;

        try {
            metadata = new WrapperPlayServerEntityMetadata(event);
        } catch (Throwable ignored) {
            return;
        }

        if (metadata.getEntityId() != profile.getPlayer().getEntityId()) {
            return;
        }

        try {
            for (EntityData<?> entry : metadata.getEntityMetadata()) {
                if (entry.getIndex() != 0 || !(entry.getValue() instanceof Byte flags)) {
                    continue;
                }

                metadataGliding = (flags & 0x80) == 0x80;

                if (metadataGliding) {
                    // Preserve the transition even if Bukkit's pose changes
                    // back before the next movement packet is processed.
                    sinceGlidingTicks = 0;
                    glideStartTransitionTicks = Math.max(glideStartTransitionTicks, getGlideTransitionTicks());
                    captureElytraMomentum();
                }

                break;
            }
        } catch (Throwable ignored) {
        }
    }

    private long normalizePacketTimestamp(long timestamp) {
        long nowMillis = System.currentTimeMillis();

        if (timestamp <= 0L) {
            return nowMillis;
        }

        if (Math.abs(nowMillis - timestamp) <= 60_000L) {
            return timestamp;
        }

        long nanoAge = System.nanoTime() - timestamp;

        if (nanoAge >= 0L && nanoAge <= TimeUnit.SECONDS.toNanos(60L)) {
            return nowMillis - TimeUnit.NANOSECONDS.toMillis(nanoAge);
        }

        return nowMillis;
    }

    float bedrockDeltaY, bedrockLastDeltaY;

    private volatile boolean locationProcessQueued;


    private void processLocationData() {

        final double lastDeltaX = this.deltaX;
        final double deltaX = this.location.getX() - this.lastLocation.getX();

        this.lastDeltaX = lastDeltaX;
        this.deltaX = deltaX;

        final double lastDeltaZ = this.deltaZ;
        final double deltaZ = this.location.getZ() - this.lastLocation.getZ();

        this.lastDeltaZ = lastDeltaZ;
        this.deltaZ = deltaZ;

        final double lastDeltaXZ = this.deltaXZ;
        final double deltaXZ = Math.hypot(deltaX, deltaZ);

        this.lastDeltaXZ = lastDeltaXZ;
        this.deltaXZ = deltaXZ;

        final double lastAccelXZ = this.accelXZ;
        final double accelXZ = Math.abs(lastDeltaXZ - deltaXZ);

        this.lastAccelXZ = lastAccelXZ;
        this.accelXZ = accelXZ;

        final double lastDeltaY = this.deltaY;
        final double deltaY = this.location.getY() - this.lastLocation.getY();

        this.lastDeltaY = lastDeltaY;
        this.deltaY = deltaY;

        final double lastAccelY = this.accelY;
        final double accelY = Math.abs(lastDeltaY - deltaY);

        this.lastAccelY = lastAccelY;
        this.accelY = accelY;

        lastServerYGround = serverYGround;

        serverYGround = getLocation().getY() % 0.015625 == 0.0
                || getLocation().getY() % 0.015625 <= 0.009;

        lastPositionYGround = positionYGround;
        positionYGround = getLocation().getY() % 0.015625 < 0.009;

        if (onGround && serverGround && !customInAir) {
            setLastGroundLocation(getLocation());
        }

        predictPlayerMovement();

        // very poor attempt at syncing the randomized jump height to prevent falses on the checks.
        // there should be a better way right... anyway, bedrock is cancer but i must support bedrock
        // no matter what, as you can spoof your client to be on bedrock, or yk cheats exist on bedrock
        if (profile.isBedrockPlayer()) {
            boolean groundTransition = !isOnGround() && isLastOnGround();

            boolean possibleJump =
                    deltaY > 0.4198
                            && deltaY < 0.422;

            if (groundTransition
                    && possibleJump
            ) {
                BEDROCK_JUMP_MOTION = deltaY;
            }
            if (Config.Setting.DEBUG.getBoolean()) {
                OtherUtility.log("[Bedrock Jump Calibration] "
                        + profile.getPlayer().getName()
                        + " possibleJump " + possibleJump
                        + " deltaY=" + deltaY
                        + " lastGround=" + isLastOnGround()
                        + " ground=" + isOnGround());
            }
        }

        //Process data

        updateNearWallState();
        processBlocks();

        profile.setBouncingOnSlime(getSlimeProcessor().isBouncing(this, profile.getPotionData()));
        if (Config.Setting.DEBUG.getBoolean()) {
            profile.getPlayer().sendMessage(OtherUtility.translate("Bouncing on Slime: &c" + profile.isBouncingOnSlime()));
        }

        if (profile.getPlayer().isFlying()) {
            profile.getLastFlightToggleTimer().reset();
        }

        nearBoat = EntityUtil.isNearBoat(profile);
        nearShulker = EntityUtil.isNearShulker(profile);
        nearGhast = EntityUtil.isNearGhast(profile);
        onBoat = EntityUtil.isOnBoat(profile);

        sinceOnGround = onGround ? 0 : sinceOnGround + 1;

        processPlayerData();
    }

    private void updateNearWallState() {
        lastLastNearWall = lastNearWall;
        lastNearWall = nearWall;

        nearWall = packetNearWall || isNearWallScanner(location);
    }

    private void predictPlayerMovement() {
        this.verticalMove =
                MovementPredictionUtil.predictVerticalMove(
                        profile.getMovementData()
                );

        this.relative =
                MovementPredictionUtil.predictRelativeMove(
                        profile.getMovementData(),
                        profile.getRotationData()
                );


        //profile.getPlayer().sendMessage("Vertical Movement: " + vertical + ", Relative movement " + relative);
    }

    private void handleNearbyBlocks() {
        boolean async = true;

        /*
        Handle collisions
        NOTE: You should ALWAYS use NMS if you plan on supporting 1.9+
        For a production server, DO NOT use spigot's api. It's slow. (Especially for Blocks, Chunks, Materials)
         */
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(getLocation(), async);
//        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult2 = CollisionUtils.getNearbyBlocks(
//                getLocation().clone().add(0, 1, 0),
//                async
//        );

        this.nearbyBlocksResult = nearbyBlocksResult;

        customInAir = !nearbyBlocksResult.isNearGround()
//                && !nearbyBlocksResult2.isNearGround()
                && !profile.isExempt().isFlight()
                && !profile.shouldCancel()
                && !profile.getPlayer().isInsideVehicle()
                && !isNearBoat()
                && !isOnBoat()
                && !isClimb()
                && !isNearWebs()
                && !profile.isBouncingOnSlime();

        isColliding = supportsEntityCollisionCheck() && CollisionProcessor.isColliding(profile.getPlayer(), profile.getBoundingBox());
    }

    void processBlocks() {

        long profiler = Profiler.start();

        try {


            //conditions
            NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();
            boolean async = true;

            CustomLocation loc1 = location;
            CustomLocation loc2 = loc1.clone().subtract(0, 1, 0);
            CustomLocation loc3 = loc1.clone().add(0, 1, 0);

            CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(loc1, async);
            CollisionUtils.NearbyBlocksResult nearbyBlocksResultLow = CollisionUtils.getNearbyBlocks(loc2, async);
            CollisionUtils.NearbyBlocksResult nearbyBlocksResultHigh = CollisionUtils.getNearbyBlocks(loc3, async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lower = CollisionUtils.getNearbyBlocks(this.lastLocation, async);
            final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lowest = CollisionUtils.getNearbyBlocks(this.lastLastLocation, async);
            nearPowderSnow = containsMaterial(nearbyBlocksResult.getBlockTypes(), POWDER_SNOW);
            boolean onIce0 = CollisionUtils.isStandingOnMaterial(loc1, nearbyBlocksResult, ICE);
            boolean onIce1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, ICE);
            boolean onIce2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, ICE);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelow_lower =
                    CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 1, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelow_lowest =
                    CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 1, 0), async);


            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow =
                    CollisionUtils.getNearbyBlocks(loc1.clone().subtract(0, 2, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lower =
                    CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 2, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lowest =
                    CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 2, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow1 =
                    CollisionUtils.getNearbyBlocks(loc1.clone().subtract(0, 3, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lower1 =
                    CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 3, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lowest1 =
                    CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 3, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove =
                    CollisionUtils.getNearbyBlocks(loc3, async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove_lower =
                    CollisionUtils.getNearbyBlocks(this.lastLocation.clone().add(0, 1, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove_lowest =
                    CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().add(0, 1, 0), async);

            final CollisionUtils.NearbyBlocksResult nearbyBlocksBelow2 =
                    CollisionUtils.getNearbyBlocks(loc1.clone().subtract(0, 2, 0), async);


            boolean slimeBelow0 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultLow, SLIME);
            boolean slimeBelow1 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelow_lower, SLIME);
            boolean slimeBelow2 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelow_lowest, SLIME);

            boolean slimeBelowBelow0 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelowBelow, SLIME);
            boolean slimeBelowBelow1 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelowBelow_lower, SLIME);
            boolean slimeBelowBelow2 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelowBelow_lowest, SLIME);

            boolean slimeBelowBelow3 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelowBelow1, SLIME);
            boolean slimeBelowBelow4 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelowBelow_lower1, SLIME);
            boolean slimeBelowBelow5 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultBelowBelow_lowest1, SLIME);

            boolean slimeAbove0 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultAbove, SLIME);
            boolean slimeAbove1 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultAbove_lower, SLIME);
            boolean slimeAbove2 = CollisionUtils.isStandingOnSlime(loc1, nearbyBlocksResultAbove_lowest, SLIME);

            boolean onSlime0 = CollisionUtils.isStandingOnMaterial(loc1, nearbyBlocksResult, SLIME);
            boolean onSlime1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, SLIME);
            boolean onSlime2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, SLIME);
            boolean onSoul0 = CollisionUtils.isStandingOnMaterial(loc1, nearbyBlocksResult, SOUL_SAND);
            boolean onSoul1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, SOUL_SAND);
            boolean onSoul2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, SOUL_SAND);
            boolean onSoulBlock0 = CollisionUtils.isStandingOnMaterial(loc1, nearbyBlocksResult, SOUL_BLOCK);
            boolean onSoulBlock1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, SOUL_BLOCK);
            boolean onSoulBlock2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, SOUL_BLOCK);

            boolean onHoney0 = CollisionUtils.isStandingOnMaterial(loc1, nearbyBlocksResult, HONEY);
            boolean onHoney1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, HONEY);
            boolean onHoney2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, HONEY);

            nearSoulBlock = (onSoulBlock0 || onSoulBlock1 || onSoulBlock2);
            onIce = onIce0 || onIce1 || onIce2;
            onSlime = onSlime0 || onSlime1 || onSlime2;
            onSoulSand = onSoul0 || onSoul1 || onSoul2;
            onHoney = onHoney0 || onHoney1 || onHoney2;

            nearBlocksSlime = slimeBelow0 || slimeBelow1 || slimeBelow2 || slimeBelowBelow0 || slimeBelowBelow1 || slimeBelowBelow2 || slimeBelowBelow3 || slimeBelowBelow4 || slimeBelowBelow5 || slimeAbove0 || slimeAbove1 || slimeAbove2;

            nearPiston =
                    containsMaterial(nearbyBlocksResult.getBlockTypes(), PISTON)
                            || containsMaterial(nearbyBlocksResultLow.getBlockTypes(), PISTON)
                            || containsMaterial(nearbyBlocksBelow2.getBlockTypes(), PISTON)
                            || containsMaterial(nearbyBlocksResultAbove.getBlockTypes(), PISTON);

            List<Material> blockTypes = nearbyBlocksResult.getBlockTypes();

            nearWater = containsMaterial(blockTypes, WATER) || nearbyBlocksResult.isNearWaterLogged();
            nearLava = containsMaterial(blockTypes, LAVA);
            nearClimbable = containsMaterial(blockTypes, CLIMBABLE) || containsMaterial(blockTypes, SCAFFOLDING);
            nearWebs = containsMaterial(blockTypes, WEB);
            nearBubble = containsMaterial(blockTypes, BUBBLE);
            nearBuggyBlock = containsMaterial(blockTypes, BUGGY_BLOCK);
            nearBed = containsMaterial(blockTypes, BED);
            nearHoney = containsMaterial(blockTypes, HONEY);
            nearShulker = containsMaterial(blockTypes, SHULKER);
            nearDripLeaf = containsMaterial(blockTypes, DRIP_LEAF);
            nearFence = containsMaterial(blockTypes, FENCE);
            nearSlime = containsMaterial(blockTypes, SLIME);

            isOnTopOfWater = CollisionUtils.isStandingOnWater(this.location, nearbyBlocksResult, WATER);

            isInsideWater = false;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    CustomLocation checkLoc = location.clone();
                    checkLoc.setX(checkLoc.getX() + x);
                    checkLoc.setZ(checkLoc.getZ() + z);
                    checkLoc.setY(checkLoc.getY() + 0.5);
                    Material m = CollisionUtils.getMaterial(checkLoc);
                    if (m != null && (isMaterialEqual(m.name(), WATER) || CollisionUtils.isWaterLogged(checkLoc))) {
                        isInsideWater = true;
                        break;
                    }
                }
                if (isInsideWater) break;
            }

            isBottomOfWater = isInsideWater && isServerGround();
            //nearWall = CollisionUtils.isNearWall(getLocation());

            boolean flag_underblock = false;

            for (int x2 = -1; x2 <= 1; x2++) {
                for (int z2 = -1; z2 <= 1; z2++) {
                    Material m = CollisionUtils.getMaterial(getLocation().clone().add(x2, 2, z2));
                    flag_underblock = flag_underblock || !isTransparent(m);
                }
            }

            for (int x2 = -1; x2 <= 1; x2++) {
                for (int z2 = -1; z2 <= 1; z2++) {
                    Material m = CollisionUtils.getMaterial(getLocation().clone().add(x2, 1, z2));
                    flag_underblock = flag_underblock || !isTransparent(m);
                }
            }

            if (profile.isCrawling()) {
                for (int x2 = -1; x2 <= 1; x2++) {
                    for (int z2 = -1; z2 <= 1; z2++) {
                        Material m = CollisionUtils.getMaterial(getLocation().clone().add(x2, 3, z2));
                        flag_underblock = flag_underblock || !isTransparent(m);
                    }
                }

                for (int x2 = -1; x2 <= 1; x2++) {
                    for (int z2 = -1; z2 <= 1; z2++) {
                        Material m = CollisionUtils.getMaterial(getLocation().clone().add(x2, 0, z2));
                        flag_underblock = flag_underblock || !isTransparent(m);
                    }
                }
            }

            underblock = flag_underblock;

            Material mLoc3 = CollisionUtils.getMaterial(loc3);
            Material mLoc1 = CollisionUtils.getMaterial(loc1);
            Material mLoc2 = CollisionUtils.getMaterial(loc2);

            insideLiquid = (mLoc3 != null && isMaterialEqual(mLoc3.name(), LIQUID))
                    || (mLoc1 != null && isMaterialEqual(mLoc1.name(), LIQUID))
                    || CollisionUtils.isWaterLogged(loc3)
                    || CollisionUtils.isWaterLogged(loc1);

            climb = (mLoc2 != null && isMaterialEqual(mLoc2.name(), CLIMBABLE))
                    || (mLoc1 != null && isMaterialEqual(mLoc1.name(), CLIMBABLE));

            nearStepMaterial =
                    containsStepMaterial(nearbyBlocksResult)
                            || containsStepMaterial(nearbyBlocksResultLow)
                            || containsStepMaterial(nearbyBlocksResultHigh);


        }  finally {
            Profiler.stop("MovementData (Blocks)", profiler);
        }
    }

    private static boolean isStepMaterial(Material m) {
        String name = m.name();

        return isMaterialEqual(name, HALF_BLOCK)
                || isMaterialEqual(name, HEIGHT_CHANGE)
                || isMaterial(name, SNOW)
                || isMaterial(name, SOUL_SAND)
                || isMaterial(name, BED)
                || isSlab(m)
                || isBed(m)
                || isCarpet(m)
                || isTrapdoor(m)
                || isFence(m)
                || isFenceGate(m)
                || isStair(m)
                || isWall(m);
    }



    private boolean isNearWallScanner(CustomLocation location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        Player player = profile.getPlayer();

        if (player == null) {
            return false;
        }

        PlayerBoxSize size = getPlayerBoxSize(player);
        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();
        World world = location.getWorld();

        /*
         * Player width is 0.6, half is 0.3.
         * Extra 0.24 detects a wall close to the side without requiring intersection.
         */
        double halfWidth = size.width * 0.5D;
        double expand = 0.24D;

        double minX = location.getX() - halfWidth - expand;
        double maxX = location.getX() + halfWidth + expand;
        double minZ = location.getZ() - halfWidth - expand;
        double maxZ = location.getZ() + halfWidth + expand;

        int minBlockX = floor(minX);
        int maxBlockX = floor(maxX);
        int minBlockZ = floor(minZ);
        int maxBlockZ = floor(maxZ);

        /*
         * Do not check below legs.
         * Feet/body/head only.
         */
        int minBlockY = floor(location.getY() + 0.001D);
        int maxBlockY = floor(location.getY() + Math.max(0.6D, size.height) - 0.001D);

        for (int y = minBlockY; y <= maxBlockY; y++) {
            for (int x = minBlockX; x <= maxBlockX; x++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    if (!CollisionUtils.isChunkLoaded(new Location(world, x, y, z))) continue;

                    Material material = me.arrow.playerdata.cache.ChunkCache.get().getBlock(world, x, y, z);

                    if (isWallMaterial(material)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isWallMaterial(Material material) {
        if (material == null || isTransparent(material)) {
            return false;
        }

        String name = material.name();

        return !isMaterial(name, LIQUID)
                && !isMaterialEqual(name, WEB)
                && !isMaterial(name, BUBBLE)
                && !isMaterialEqual(name, WATER_PLANT);
    }

    private int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    public boolean isTransparent(Material material) {
        if (!material.isBlock()) return false;
        String name = material.name();

        if (isMaterialEqual(name, AIR)) return true;
        if (isMaterialEqual(name, WATER_PLANT)) return true;
        if (isMaterialEqual(name, LIQUID)) return true;
        if (isMaterial(name, BUBBLE)) return true;
        if (isMaterialEqual(name, TRANSPARENT)) return true;

        return switch (name) {
            case "TORCH", "SOUL_TORCH", "FIRE", "SOUL_FIRE", "REDSTONE", "WHEAT", "RAIL", "LEVER", "REDSTONE_TORCH",
                 "STONE_BUTTON", "OAK_BUTTON", "ACTIVATOR_RAIL", "TALL_GRASS", "LARGE_FERN", "LEAF_LITTER", "LIGHT", "LONG_GRASS" -> true;
            default -> false;
        };
    }

    private boolean supportsEntityCollisionCheck() {
        try {
            return !PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8)
                    && !profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void processPlayerData() {

        long profiler = Profiler.start();

        try {
            final Player p = profile.getPlayer();

            NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();

            profile.setBoundingBox(createPlayerBox());

            handleNearbyBlocks();

            //Friction Factor

            this.frictionFactor = CollisionUtils.getBlockSlipperiness(
                    CollisionUtils.getMaterial(this.location.clone().subtract(0D, .825D, 0D))
            );

            this.lastFrictionFactorUpdateTicks = this.frictionFactor != this.lastFrictionFactor ? 0 : this.lastFrictionFactorUpdateTicks + 1;

            this.lastFrictionFactor = this.frictionFactor;


            //Near Wall

            this.lastNearWallTicks = (this.nearWall || this.lastNearWall || this.packetNearWall)
                    ? 0
                    : this.lastNearWallTicks + 1;

            //Near Edge

            this.lastNearEdgeTicks = this.lastNearGroundTicks == 0 && CollisionUtils.isNearEdge(this.location) ? 0 : this.lastNearEdgeTicks + 1;

            //Server Ground

            final boolean lastServerGround = this.serverGround;

            final boolean serverGround = CollisionUtils.isServerGround(this.location.getY());

            this.lastServerGround = lastServerGround;

            this.serverGround = serverGround;

            this.serverGroundTicks = serverGround ? this.serverGroundTicks + 1 : 0;

            this.lastServerGroundTicks = lastServerGround ? this.lastServerGroundTicks + 1 : 0;

            //Equipment

            this.equipment.handle(p);

            //Fall Distance

            this.lastFallDistance = this.fallDistance;

            this.fallDistance = nms.getFallDistance(p);

            //Base Speed

            this.baseGroundSpeed = MoveUtils.getBaseGroundSpeed(profile);

            this.baseAirSpeed = MoveUtils.getBaseAirSpeed(profile);

            this.pastLocations.add(getLocation());
            this.reachPastLocations.add(getLocation());

            moving = (deltaXZ != 0.0D && deltaXZ != lastDeltaXZ) || (deltaY != 0.0D && deltaY != lastDeltaY);

        }  finally {
            Profiler.stop("MovementData (playerdata without ticks)", profiler);
            updateTicks();
        }
    }

    private BoundingBox createPlayerBox() {
        Player player = profile.getPlayer();

        CustomLocation location = profile.getMovementData().getLocation();

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        PlayerBoxSize size = getPlayerBoxSize(player);

        double width = size.width;
        double height = size.height;

        double halfWidth = width * 0.5D;

        return new BoundingBox(
                (float) (x - halfWidth),
                (float) y,
                (float) (z - halfWidth),
                (float) (x + halfWidth),
                (float) (y + height),
                (float) (z + halfWidth)
        );
    }

    private PlayerBoxSize getPlayerBoxSize(Player player) {
        if (player == null) {
            return PlayerBoxSize.STANDING;
        }

        String pose = ReflectionUtils.getPoseName(player);

        // Sleeping hitbox
        if (pose.equals("SLEEPING")) {
            return PlayerBoxSize.SLEEPING;
        }

        // Swimming, crawling, elytra gliding, trident spin attack
        if (pose.equals("SWIMMING")
                || pose.equals("CRAWLING")
                || pose.equals("FALL_FLYING")
                || pose.equals("SPIN_ATTACK")
                || ReflectionUtils.isSwimming(player)
                || ReflectionUtils.isGliding(player)) {
            return PlayerBoxSize.FLAT;
        }

        // 1.14+ sneaking/crouching hitbox
        if (hasModernSneakingDimensions()
                && (player.isSneaking()
                || pose.equals("CROUCHING")
                || pose.equals("SNEAKING"))) {
            return PlayerBoxSize.SNEAKING;
        }

        return PlayerBoxSize.STANDING;
    }


    private boolean hasModernSneakingDimensions() {
        try {
            return PacketEvents.getAPI()
                    .getServerManager()
                    .getVersion()
                    .isNewerThanOrEquals(ServerVersion.V_1_14);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static class PlayerBoxSize {
        static PlayerBoxSize STANDING = new PlayerBoxSize(0.6D, 1.8D);
        static PlayerBoxSize SNEAKING = new PlayerBoxSize(0.6D, 1.5D);
        static PlayerBoxSize FLAT = new PlayerBoxSize(0.6D, 0.6D);
        static PlayerBoxSize SLEEPING = new PlayerBoxSize(0.2D, 0.2D);

        double width;
        double height;

        private PlayerBoxSize(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }


    int tickTime;

    void updateTicks() {
        long profiler = Profiler.start();

        try {
            this.tick++;
            PotionData potion = profile.getPotionData();

            //conditions

            boolean exempt = (isNearLava() || isNearWater() || isNearWebs())
                    || profile.getPlayer().isInsideVehicle();

            boolean riptiding = profile.getPredictionData().isRiptiding() || Arrow.getInstance()
                    .getNmsManager()
                    .getNmsInstance()
                    .isRiptiding(profile.getPlayer());

            boolean glidingNow = metadataGliding
                    || glideStartTransitionTicks > 0
                    //|| ReflectionUtils.isGliding(profile.getPlayer())
                    ;

            boolean predictUp = verticalMove == MovementPredictionUtil.VerticalMove.UP;
            boolean predictDown = verticalMove == MovementPredictionUtil.VerticalMove.DOWN;

            //ticks

            sincePowderSnowTicks = nearPowderSnow ? 0 : sincePowderSnowTicks + 1;
            movingOnIceTicks = (moving && onIce) ? Math.max(movingOnIceTicks + 1, 20) : Math.max(movingOnIceTicks - 1, 0);
            iceTicks = onIce ? Math.max(iceTicks + 1, 20) : Math.max(iceTicks - 1, 0);

            // this is a temporary, test fix, for piston movable slime blocks, it may not work properly in all scenarios, but it will do for now
            // assuming that it even works...
            onExtendedHitboxSlime = onSlime || nearBlocksSlime
                    || (getMovingOnSlimeTicks() < 11 && getMovingOnSlimeTicks() > 0) || getSinceMovingOnSlimeTicks() < 10;

            movingOnSlimeTicks = (moving && onSlime) ? Math.max(movingOnSlimeTicks + 1, 20) : Math.max(movingOnSlimeTicks - 1, 0);
            slimeTicks = onSlime ? Math.max(slimeTicks + 1, 20) : Math.max(slimeTicks - 1, 0);
            sinceNearSlimeTicks = isNearSlime() ? 0 : sinceNearSlimeTicks + 1;
            sinceNearPistonTicks = isNearPiston() ? 0 : sinceNearPistonTicks + 1;
            movingOnSoulTicks = (moving && onSoulSand) ? Math.max(movingOnSoulTicks + 1, 20) : Math.max(movingOnSoulTicks - 1, 0);
            soulTicks = onSoulSand ? Math.max(soulTicks + 1, 20) : Math.max(soulTicks - 1, 0);
            movingOnSoulBlocksTicks = (moving && nearSoulBlock) ? Math.max(movingOnSoulBlocksTicks + 1, 20) : Math.max(movingOnSoulBlocksTicks - 1, 0);
            movingOnHoneyTicks = (moving && onHoney) ? Math.max(movingOnHoneyTicks + 1, 20) : Math.max(movingOnHoneyTicks - 1, 0);
            honeyTicks = onHoney ? Math.max(honeyTicks + 1, 20) : Math.max(honeyTicks - 1, 0);
            sinceMovingOnIceTicks = movingOnIceTicks > 0 ? 0 : sinceMovingOnIceTicks + 1;
            sinceMovingOnSlimeTicks = movingOnSlimeTicks > 0 ? 0 : sinceMovingOnSlimeTicks + 1;
            movingUnderblockTicks = (moving && isUnderblock()) ? Math.max(movingUnderblockTicks + 1, 20) : Math.max(movingUnderblockTicks - 1, 0);

            sinceMovingUnderBlockTicks = movingUnderblockTicks > 0 ? 0 : sinceMovingUnderBlockTicks + 1;

            movingTicks = moving ? movingTicks + 1 : 0;
            customAirTicks = customInAir ? customAirTicks + 1 : 0;
            nearWallTicks = nearWall && !exempt ? nearWallTicks + 1 : 0;
            sinceRiptidingTicks = riptiding ? 0 : sinceRiptidingTicks + 1;

            tickTime++;
            if (tickTime >= 20 && isOnGround() && !isCustomInAir()) {
                this.lastSetBackLocation = getLocation();
                tickTime = 0;  // Reset only when condition is met
            }

            Vector velocity = profile.getVelocityData().getExplosionKnockback();
            sinceExplosionTicks = OtherUtility.isZero(velocity) ? sinceExplosionTicks + 1 : 0;

            sinceCollideTicks = isColliding ? 0 : sinceCollideTicks + 1;
            sinceGlidingTicks = glidingNow ? 0 : sinceGlidingTicks + 1;

            updateElytraMomentum(glidingNow);

            if (glideStartTransitionTicks > 0) {
                glideStartTransitionTicks--;
            }

            sinceElytraEquipTicks = profile.isWearingFunctionalElytra() ?  0 : sinceElytraEquipTicks + 1;
            serverAirTicks = isServerGround() ? 0 : serverAirTicks + 1;
            serverGroundTicksPlus = isServerGround() ? serverGroundTicksPlus + 1 : 0;

            if (profile.getPlayer().isInsideVehicle()) {
                if (profile.getVehicleData().getVehicleTicks() < 20) {
                    profile.getVehicleData().setVehicleTicks(profile.getVehicleData().getVehicleTicks() + 1);
                }
            } else {
                if (profile.getVehicleData().getVehicleTicks() > 0) {
                    profile.getVehicleData().setVehicleTicks(profile.getVehicleData().getVehicleTicks() - 1);
                }
            }

            movingUp = (deltaY > 0 || lastDeltaY > 0) && isNearStepMaterial();
            movingDown = (deltaY < 0 || lastDeltaY < 0) && isNearStepMaterial();

            sincePredictUpwardsTicksWithoutMaterial = predictUp ? 0 : sincePredictUpwardsTicksWithoutMaterial + 1;
            sincePredictDownwardsTicksWithoutMaterial = predictDown ? 0 : sincePredictDownwardsTicksWithoutMaterial + 1;
            sincePredictDownwardsTicks = movingDown || (predictDown && isNearStepMaterial()) ? 0 : sincePredictDownwardsTicks + 1;
            sincePredictUpwardsTicks = movingUp || (predictUp && isNearStepMaterial())? 0 : sincePredictUpwardsTicks + 1;
            sinceNearGhastTicks = nearGhast ? 0 : sinceNearGhastTicks + 1;
            sinceTeleportTicks = profile.getExempt().isTeleports() ? 0 : sinceTeleportTicks + 1;
            isRiptiding = sinceRiptidingTicks < 20;
            ladderTicks = isClimb() ? ladderTicks + 1 : 0;
            sinceBubbleTicks = nearBubble ? 0 : sinceBubbleTicks + 1;
            sinceInsideWaterTicks = isInsideWater() ? 0 : sinceInsideWaterTicks + 1;
            sinceNearWaterTicks = isNearWater() ? 0 : sinceNearWaterTicks + 1;
            sinceLevitationEffectTicks = potion.getLevitationTicks() > 0 ? 0 : sinceLevitationEffectTicks + 1;
            sinceJumpBoostEffectTicks = potion.getJumpTicks() > 0 ? 0 : sinceJumpBoostEffectTicks + 1;
            sinceSlowFallingEffectTicks = potion.getSlowFallingTicks() > 0 ? 0 : sinceSlowFallingEffectTicks + 1;
            sinceSpeedPotionEffectTicks = potion.getSpeedTicks() > 0 ? 0 : sinceSpeedPotionEffectTicks + 1;
            sinceOnGhostBlock = profile.isOnGhostBlock() ? 0 : sinceOnGhostBlock + 1;

            dolphinGraceBoost = dolphinGraceMomentum();

            profile.getConnectionData().setFlyingTick(profile.getConnectionData().getFlyingTick() + 1);

            profile.getConnectionData().setTransDropTick(profile.getConnectionData().getTransDropTick() + 1);
            try {
                if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
                    if (potion.getPotionEffectLevel(PotionType.DOLPHINS_GRACE) > 0) {
                        dolphinGraceTicks++;
                        sinceDolphinGraceTicks = 0;
                    } else {
                        dolphinGraceTicks = 0;
                        sinceDolphinGraceTicks++;
                    }
                }
            } catch (NoSuchMethodError ignored) {

            }
        } finally {
            Profiler.stop("MovementData (Ticks)", profiler);
        }
    }

    public float elytraMomentum() {
        return Math.max(0.0F, elytraMomentumBonus);
    }

    private void handleElytraStartAction(PacketReceiveEvent event) {
        WrapperPlayClientEntityAction action;

        try {
            action = new WrapperPlayClientEntityAction(event);
        } catch (Throwable ignored) {
            return;
        }

        if (action.getAction() != WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA
                || !profile.isWearingFunctionalElytra()
                || profile.getPlayer().isInsideVehicle()
                || isOnGround()
                || getClientAirTicks() <= 0
                || getDeltaY() >= 0.0D
                || isInsideWater()
                || isNearWebs()
                || isNearClimbable()) {
            return;
        }

        /*
         * This packet is the earliest reliable indication of a real client
         * glide. A jump-glide can land and clear Bukkit pose before the next
         * movement packet, especially with transaction delay.
         */
        glideStartTransitionTicks = Math.max(glideStartTransitionTicks, getGlideTransitionTicks());
        sinceGlidingTicks = 0;
        captureElytraMomentum();
    }

    private int getGlideTransitionTicks() {
        int transactionTicks = 0;
        int pingTicks = 0;

        try {
            transactionTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());
            pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        } catch (Throwable ignored) {
        }

        return Math.max(3, Math.min(12, 3 + Math.max(transactionTicks, pingTicks)));
    }

    private void captureElytraMomentum() {
        // Speed A adds this value on top of its normal movement allowance, so
        // retain only the velocity above ordinary air movement.
        float carried = (float) Math.max(0.0D, Math.min(4.0D, deltaXZ - 0.30D));
        elytraMomentumBonus = Math.max(elytraMomentumBonus, carried);
    }

    private void updateElytraMomentum(boolean gliding) {
        if (gliding) {
            captureElytraMomentum();
            return;
        }

        if ((isOnGround() || isServerGround()) && sinceGlidingTicks > 2) {
            elytraMomentumBonus = 0.0F;
            return;
        }

        // Preserve post-glide/unequip velocity, then smoothly decay it instead
        // of replacing it with a ping-derived constant.
        elytraMomentumBonus *= 0.98F;

        if (elytraMomentumBonus < 0.005F || sinceGlidingTicks > 160) {
            elytraMomentumBonus = 0.0F;
        }
    }

    private float dolphinGraceMomentum;
    private boolean dolphinGraceWasActive;

    private float dolphinGraceMomentum() {
        final float start = 0.225f;
        final float step = 0.03f;
        final float stepAfterWater = 0.03025f;
        final float stepAfterAir = 0.0105f;

        try {
            if (!PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
                dolphinGraceMomentum = 0f;
                dolphinGraceWasActive = false;
                return 0f;
            }

            int graceLevel = profile.getPotionData().getPotionEffectLevel(PotionType.DOLPHINS_GRACE);
            boolean hasGrace = graceLevel > 0;
            int depthStrider = SpeedUtilities.getDepthStriderLevel(profile);

            float cap = getDolphinGraceBonusCap(depthStrider);

            if (hasGrace && (isNearWater())) {
                if (!dolphinGraceWasActive) {
                    dolphinGraceMomentum = Math.max(dolphinGraceMomentum, start);
                    dolphinGraceWasActive = true;
                    return dolphinGraceMomentum;
                }

                if (moving) {
                    float gain = step * graceLevel;
                    dolphinGraceMomentum = Math.min(cap, dolphinGraceMomentum + gain);
                } else {
                    dolphinGraceMomentum = Math.max(0f, dolphinGraceMomentum - stepAfterWater);
                }

                dolphinGraceMomentum = Math.min(dolphinGraceMomentum, cap);
                return dolphinGraceMomentum;
            }

            dolphinGraceWasActive = false;
            dolphinGraceMomentum = Math.max(0f, dolphinGraceMomentum - (isNearWater() ? stepAfterWater : stepAfterAir));
            return dolphinGraceMomentum;
        } catch (NoSuchMethodError exception) {
            dolphinGraceMomentum = 0f;
            dolphinGraceWasActive = false;
            return 0f;
        }
    }

    private float getDolphinGraceBonusCap(int depthStriderLevel) {
        return switch (depthStriderLevel) {
            case 1 -> 0.602f;
            case 2 -> 1.049f;
            case 3 -> 1.494f;
            default -> 0.146f;
        };
    }

    private boolean containsStepMaterial(CollisionUtils.NearbyBlocksResult result) {
        for (Material material : result.getBlockTypes()) {
            if (isStepMaterial(material)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsMaterial(
            Collection<Material> blocks,
            MaterialType type
    ) {
        for (Material mat : blocks) {
            if (isMaterialEqual(mat.name(), type)) {
                return true;
            }
        }
        return false;
    }
}
