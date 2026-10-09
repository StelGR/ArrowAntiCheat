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
import me.arrow.checks.impl.movement.speed.SpeedMath.MovementMath;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.core.movement.MovementFrame;
import me.arrow.core.movement.MovementState;
import me.arrow.core.network.PacketTimestamp;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.managers.profiler.Profiler;
import me.arrow.backend.bukkit.nms.NmsInstance;
import me.arrow.playerdata.cache.ChunkCache;
import me.arrow.playerdata.data.Data;
import me.arrow.playerdata.processors.impl.CollisionProcessor;
import me.arrow.playerdata.processors.impl.SetbackProcessor;
import me.arrow.playerdata.processors.impl.SlimeProcessor;
import me.arrow.utils.*;
import me.arrow.utils.custom.*;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.customutils.OtherUtility;
import me.arrow.utils.minecraft.MathHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.List;

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
            accelXZ, lastAccelXZ, accelY, lastAccelY, expectedWaterDeltaXZ;

    @Getter
    float fallDistance, lastFallDistance,
            baseGroundSpeed, baseAirSpeed,
            frictionFactor = MoveUtils.FRICTION_FACTOR, lastFrictionFactor = MoveUtils.FRICTION_FACTOR,
            dolphinGraceBoost;

    @Getter
    double waterMomentumBonus;

    private double lastWaterPrediction;
    private boolean wasActuallyInWater;

    @Getter
    CustomLocation location, lastLocation, lastLastLocation, lastSetBackLocation;


    @Getter
    @Setter
    SampleList<CustomLocation> pastLocations = new SampleList<>(140, true);
    SampleList<CustomLocation> pastGroundLocations = new SampleList<>(40, true);

    @Getter
    @Setter
    CustomLocation lastGroundLocation;


    @Getter
    boolean onGround, lastOnGround, lastLastOnGround, serverGround, lastServerGround, serverYGround, positionYGround, lastPositionYGround, lastServerYGround,
        nearWater, nearBubble, nearLava, nearContact, nearSlime, nearWebs, lastLastNearWall, lastNearWall, nearWall, nearClimbable, nearBuggyBlock, nearBed, nearHoney, nearShulkerBox, nearDripLeaf, customInAir, underblock, lastUnderblock, lastLastUnderblock, insideLiquid, climb, moving, isInsideWater, wasInWater, wasWasInWater, isOnTopOfWater, isBottomOfWater, isColliding, nearBoat, lastNearBoat, nearBoatSide, lastNearBoatSide, nearGhast, nearShulker, nearFence, nearPane, lastNearPane, onBoat, lastOnBoat, lastLastOnBoat, underBoat, lastUnderBoat, onIce, onSlime, onExtendedHitboxSlime, onHoney, onSoulSand, movingUp, nearStepMaterial, movingDown, isRiptiding, nearPiston, nearBlocksSlime, nearPowderSnow, nearSoulBlock, waterPredictionActive;


    @Getter
    @Setter
    int clientAirTicks, serverAirTicks, serverGroundTicks, serverGroundTicksPlus, lastServerGroundTicks, nearGroundTicks, lastNearGroundTicks,
            clientGroundTicks, lastNearWallTicks,
            lastFrictionFactorUpdateTicks, lastNearEdgeTicks,
            customAirTicks, nearWallTicks, sinceExplosionTicks, sinceCollideTicks, sinceGlidingTicks = 100000, glidingTicks, sincePowderSnowTicks, sinceElytraEquipTicks,
            sinceOnGhostBlock, sinceGlitchedInsideBlockTicks, sinceOnGround, sinceRiptidingTicks, sinceBubbleTicks, sincePredictUpwardsTicks, sincePredictDownwardsTicks, sincePredictUpwardsTicksWithoutMaterial, sincePredictDownwardsTicksWithoutMaterial, sinceSpeedPotionEffectTicks, sinceNearGhastTicks, movingOnSoulTicks, movingOnSoulBlocksTicks, movingTicks, sinceMovingOnSlimeTicks, sinceMovingOnIceTicks, movingOnHoneyTicks, sinceMovingOnHoneyTicks, slimeTicks, soulTicks, honeyTicks, sinceSlimeTicks, sinceSoulTicks, sinceHoneyTicks, iceTicks, sinceIceTicks, sinceMovingUpTicks, sinceMovingDownTicks, sinceDolphinGraceTicks, dolphinGraceTicks, ladderTicks, sinceInsideWaterTicks, sinceNearWaterTicks, sinceOnBoatTicks = 1000, sinceNearBoatTicks = 1000, sinceUnderBoatTicks = 1000, sinceNearBoatSideTicks = 1000, sinceUnderblockTicks = 1000, sinceLevitationEffectTicks, sinceJumpBoostEffectTicks, sinceSlowFallingEffectTicks, tick, sinceTeleportTicks, sinceNearSlimeTicks, sinceNearPistonTicks, sinceMovingUnderBlockTicks;

    @Getter
    @Setter
    float movingUnderblockTicks, movingOnIceTicks, movingOnSlimeTicks;

    @Getter
    @Setter
    boolean packetNearWall;

    @Getter
    private int collidingEntityCount;

    boolean packetMoving;

    /** Authoritative fall-flying flag from the player's own metadata packet. */
    boolean metadataGliding;
    float elytraMomentumBonus;
    int glideStartTransitionTicks;
    boolean lastElytraPose;

    @Getter
    CollisionUtils.NearbyBlocksResult nearbyBlocksResult;

    @Getter
    SlimeProcessor slimeProcessor;

    @Getter
    public MovementPredictionUtil.RelativeMove relative;

    @Getter
    public MovementPredictionUtil.VerticalMove verticalMove;


    private long lastDecayTick = -1L;

    /** Packet-only mirror shared with the Fabric adapter; it contains no Bukkit types. */
    @Getter
    private final MovementState coreMovementState = new MovementState();


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
        final long currentTime = PacketTimestamp.toMillis(event.getTimestamp());

        if (event.getPacketType().equals(ENTITY_ACTION)) {
            handleElytraStartAction(event);
        }

        if (event.getPacketType().equals(PLAYER_FLYING)) {
            WrapperPlayClientPlayerFlying move = (event.getLastUsedWrapper() instanceof WrapperPlayClientPlayerFlying)
                    ? (WrapperPlayClientPlayerFlying) event.getLastUsedWrapper()
                    : new WrapperPlayClientPlayerFlying(event);

            this.lastLastOnGround = this.lastOnGround;
            this.lastOnGround = this.onGround;
            this.onGround = move.isOnGround();

            this.packetNearWall = move.isHorizontalCollision();
            this.packetMoving = move.hasPositionChanged();

            this.clientAirTicks = this.onGround ? 0 : this.clientAirTicks + 1;
            this.clientGroundTicks = this.onGround ? this.clientGroundTicks + 1 : 0;

            this.lastLastLocation = this.lastLocation;
            this.lastLocation = this.location;

            publishCoreMovement(move, currentTime);
            processLocationData();
        }
        else if (event.getPacketType().equals(PLAYER_POSITION)) {
            WrapperPlayClientPlayerPosition move = (event.getLastUsedWrapper() instanceof WrapperPlayClientPlayerPosition)
                    ? (WrapperPlayClientPlayerPosition) event.getLastUsedWrapper()
                    : new WrapperPlayClientPlayerPosition(event);

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

            publishCoreMovement(move, currentTime);
            processLocationData();
        }
        else if (event.getPacketType().equals(PLAYER_ROTATION)) {
            final WrapperPlayClientPlayerRotation look = (event.getLastUsedWrapper() instanceof WrapperPlayClientPlayerRotation)
                    ? (WrapperPlayClientPlayerRotation) event.getLastUsedWrapper()
                    : new WrapperPlayClientPlayerRotation(event);

            this.lastOnGround = this.onGround;
            this.onGround = look.isOnGround();

            this.packetNearWall = look.isHorizontalCollision();
            this.packetMoving = look.hasPositionChanged();

            this.clientAirTicks = this.onGround ? 0 : this.clientAirTicks + 1;
            this.clientGroundTicks = this.onGround ? this.clientGroundTicks + 1 : 0;

            this.lastLastLocation = this.lastLocation;
            this.lastLocation = this.location;

            publishCoreMovement(look, currentTime);
            processLocationData();
        }

        else if (event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)) {
            final WrapperPlayClientPlayerPositionAndRotation posLook = (event.getLastUsedWrapper() instanceof WrapperPlayClientPlayerPositionAndRotation)
                    ? (WrapperPlayClientPlayerPositionAndRotation) event.getLastUsedWrapper()
                    : new WrapperPlayClientPlayerPositionAndRotation(event);

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

            publishCoreMovement(posLook, currentTime);
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
                if (entry.getIndex() != 0 || !(entry.getValue() instanceof Byte)) {
                    continue;
                }
                Byte flags = (Byte) entry.getValue();

                metadataGliding = (flags & 0x80) == 0x80;

                if (metadataGliding) {
                    // Preserve the transition even if Bukkit's pose changes
                    // back before the next movement packet is processed.
                    sinceGlidingTicks = 0;
                    glidingTicks = Math.max(glidingTicks, 1);
                    glideStartTransitionTicks = Math.max(glideStartTransitionTicks, getGlideTransitionTicks());
                }

                break;
            }
        } catch (Throwable ignored) {
        }
    }

    private void publishCoreMovement(WrapperPlayClientPlayerFlying packet, long timestamp) {
        CustomLocation knownLocation = this.location;
        if (knownLocation == null) {
            return;
        }

        com.github.retrooper.packetevents.protocol.world.Location packetLocation = packet.getLocation();
        boolean hasPosition = packet.hasPositionChanged();
        boolean hasRotation = packet.hasRotationChanged();

        this.coreMovementState.accept(new MovementFrame(
                knownLocation.getWorld() == null ? "unknown" : knownLocation.getWorld().getName(),
                hasPosition ? packetLocation.getX() : knownLocation.getX(),
                hasPosition ? packetLocation.getY() : knownLocation.getY(),
                hasPosition ? packetLocation.getZ() : knownLocation.getZ(),
                hasRotation ? packetLocation.getYaw() : knownLocation.getYaw(),
                hasRotation ? packetLocation.getPitch() : knownLocation.getPitch(),
                hasPosition, hasRotation, this.onGround, this.packetNearWall, timestamp
        ));
    }

    float bedrockDeltaY, bedrockLastDeltaY;

    private volatile boolean locationProcessQueued;


    private void processLocationData() {

        if (profile.getTeleportData().isTeleporting()) {
            this.lastLocation = this.location;
            this.lastLastLocation = this.location;

            this.deltaX = 0.0;
            this.deltaZ = 0.0;
            this.deltaXZ = 0.0;
            this.deltaY = 0.0;

            this.lastDeltaX = 0.0;
            this.lastDeltaZ = 0.0;
            this.lastDeltaXZ = 0.0;
            this.lastDeltaY = 0.0;

            this.accelXZ = 0.0;
            this.lastAccelXZ = 0.0;
            this.accelY = 0.0;
            this.lastAccelY = 0.0;

            this.sinceTeleportTicks = 0;

            lastServerYGround = serverYGround;

            ChunkCache.get().ensurePlayerChunkLoaded(location);

            serverYGround = getLocation().getY() % 0.015625 == 0.0
                    || getLocation().getY() % 0.015625 <= 0.009;

            lastPositionYGround = positionYGround;
            positionYGround = getLocation().getY() % 0.015625 < 0.009;

            if (onGround && serverGround && !customInAir) {
                setLastGroundLocation(getLocation());
                getPastGroundLocations().add(getLastGroundLocation());
            }

            updateNearWallState();
            wasWasInWater = wasInWater;
            wasInWater = isInsideWater;
            processBlocks();
            updateTicks();
            return;
        }

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

        ChunkCache.get().ensurePlayerChunkLoaded(location);

        serverYGround = getLocation().getY() % 0.015625 == 0.0
                || getLocation().getY() % 0.015625 <= 0.009;

        lastPositionYGround = positionYGround;
        positionYGround = getLocation().getY() % 0.015625 < 0.009;

        if (onGround && serverGround && !customInAir) {
            setLastGroundLocation(getLocation());
            getPastGroundLocations().add(getLastGroundLocation());
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

        // Snapshot the previous two water states before processBlocks replaces
        // the current state for this packet. SimulationHandler uses these
        // packet-frame values for water drag and input acceleration.
        wasWasInWater = wasInWater;
        wasInWater = isInsideWater;
        processBlocks();

        profile.setBouncingOnSlime(getSlimeProcessor().isBouncing(this, profile.getPotionData()));
        if (Config.Setting.DEBUG.getBoolean()) {
            profile.getPlayer().sendMessage(OtherUtility.translate("Bouncing on Slime: &c" + profile.isBouncingOnSlime()));
        }

        if (profile.getPlayer().isFlying()) {
            profile.getLastFlightToggleTimer().reset();
        }

        this.lastLastOnBoat = this.lastOnBoat;
        this.lastOnBoat = this.onBoat;
        this.lastNearBoat = this.nearBoat;
        this.lastUnderBoat = this.underBoat;
        this.lastNearBoatSide = this.nearBoatSide;
        nearBoat = EntityUtil.isNearBoat(profile);
        nearShulker = EntityUtil.isNearShulker(profile);
        nearGhast = EntityUtil.isNearGhast(profile);
        onBoat = EntityUtil.isOnBoat(profile);
        underBoat = EntityUtil.isUnderBoat(profile);
        nearBoatSide = EntityUtil.isNearBoatSide(profile);

        sinceOnGround = onGround ? 0 : sinceOnGround + 1;

        processPlayerData();
        if (setbackProcessor != null) {
            setbackProcessor.process();
        }


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
        long profiler = Profiler.start();
        try {
            boolean async = true;

        /*
        Handle collisions
        NOTE: You should ALWAYS use NMS if you plan on supporting 1.9+
        For a production server, DO NOT use spigot's api. It's slow. (Especially for Blocks, Chunks, Materials)
         */
            final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(
                    getLocation(), getLastLocation(), async
            );
//        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult2 = CollisionUtils.getNearbyBlocks(
//                getLocation().clone().add(0, 1, 0),
//                async
//        );

            this.nearbyBlocksResult = nearbyBlocksResult;

            /*
             * Air ticks require present support, or a downward movement proven to
             * have landed on a collision top. A wall beside the player is neither.
             */
            boolean hasGroundSupport = nearbyBlocksResult.hasExactGroundSupport()
                    || nearbyBlocksResult.hasLandingGroundSupport();

            customInAir = !hasGroundSupport
                    && !nearbyBlocksResult.hasUnresolvedCollisionShape()
//                && !nearbyBlocksResult2.isNearGround()
                    && !profile.isExempt().isFlight()
                    && !profile.isExempt().isTeleports()
                    && getSinceTeleportTicks() > 1
                    && !nearHoney
                    && !nearClimbable
                    && !nearPowderSnow
                    && !profile.shouldCancel()
                    && !profile.getPlayer().isInsideVehicle()
                    && !isNearBoat()
                    && !isOnBoat()
                    && !nearWebs
                    && !profile.isBouncingOnSlime();

            collidingEntityCount = supportsEntityCollisionCheck()
                    ? CollisionProcessor.getCollidingEntityCount(profile.getPlayer(), profile.getBoundingBox())
                    : 0;
            isColliding = collidingEntityCount > 0;
        } finally {
            Profiler.stop("MovementData (Blocks 1)", profiler);
        }
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

            boolean onIce0 = CollisionUtils.isStandingOnMaterial(loc1, nearbyBlocksResult, ICE);
            boolean onIce1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, ICE);
            boolean onIce2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, ICE);

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

            nearPowderSnow = containsMaterial(nearbyBlocksResult.getBlockTypes(), POWDER_SNOW);

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

            nearShulkerBox = containsMaterial(nearbyBlocksResult.getBlockTypes(), SHULKER)
                    || containsMaterial(nearbyBlocksResultLow.getBlockTypes(), SHULKER);

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
            lastNearPane = nearPane;
            nearPane = containsMaterial(blockTypes, PANE)
                    || containsMaterial(nearbyBlocksResultLow.getBlockTypes(), PANE)
                    || containsMaterial(nearbyBlocksResultHigh.getBlockTypes(), PANE);
            nearSlime = containsMaterial(blockTypes, SLIME);

            isOnTopOfWater = CollisionUtils.isStandingOnWater(this.location, nearbyBlocksResult, WATER);

            PlayerBoxSize boxSize = getPlayerBoxSize(profile.getPlayer());
            isInsideWater = CollisionUtils.isInsideWater(this.location, boxSize.width * 0.5D, boxSize.height);

            isBottomOfWater = isInsideWater && isServerGround();
            //nearWall = CollisionUtils.isNearWall(getLocation());

            lastLastUnderblock = lastUnderblock;
            lastUnderblock = underblock;

            boolean flag_underblock = checkUnderBlock(this.location, this.lastLocation, nearbyBlocksResult);

            if (!flag_underblock) {
                for (int x2 = -1; x2 <= 1; x2++) {
                    for (int z2 = -1; z2 <= 1; z2++) {
                        Material m = CollisionUtils.getMaterial(getLocation().clone().add(x2, 2, z2));
                        Material m2 = CollisionUtils.getMaterial(getLocation().clone().add(x2, 1, z2));
                        flag_underblock = flag_underblock || (!isTransparent(m) && isTransparent(m2));
                    }
                }

                flag_underblock = flag_underblock
                        || (!isTransparent(CollisionUtils.getMaterial(getLocation().clone().add(0, 1, 0)))
                        && isTransparent(CollisionUtils.getMaterial(getLocation())));

                if (profile.isCrawling()) {
                    for (int x2 = -1; x2 <= 1; x2++) {
                        for (int z2 = -1; z2 <= 1; z2++) {
                            Material m = CollisionUtils.getMaterial(getLocation().clone().add(x2, 1, z2));
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
            }

            underblock = flag_underblock || isUnderBoat();

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
            Profiler.stop("MovementData (Blocks 2)", profiler);
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
                || isWall(m)
                || isPane(m);
    }

    private static boolean isPane(Material m) {
        return MaterialType.isPane(m);
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

    private boolean checkUnderBlock(CustomLocation location, CustomLocation lastLocation, CollisionUtils.NearbyBlocksResult nearbyBlocksResult) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        if (nearbyBlocksResult != null && nearbyBlocksResult.hasBlockAbove()) {
            return true;
        }

        double px = location.getX();
        double py = location.getY();
        double pz = location.getZ();

        double height = 1.8D;
        if (profile.isCrawling()) {
            height = 0.6D;
        } else {
            Player player = profile.getPlayer();
            if (player != null && player.isSneaking()) {
                height = 1.5D;
            }
        }

        double deltaY = lastLocation != null ? py - lastLocation.getY() : 0.0D;
        double headBottom = py + height - 0.35D;
        double headTop = py + height + Math.max(0.42D, Math.max(0.0D, deltaY)) + 0.25D;

        double minX = px - 0.35D;
        double maxX = px + 0.35D;
        double minZ = pz - 0.35D;
        double maxZ = pz + 0.35D;

        int bMinX = floor(minX);
        int bMaxX = floor(maxX);
        int bMinZ = floor(minZ);
        int bMaxZ = floor(maxZ);
        int bMinY = floor(headBottom);
        int bMaxY = floor(headTop);

        me.arrow.playerdata.cache.ChunkCache chunkCache = me.arrow.playerdata.cache.ChunkCache.get();
        World world = location.getWorld();

        for (int x = bMinX; x <= bMaxX; x++) {
            for (int y = bMinY; y <= bMaxY; y++) {
                for (int z = bMinZ; z <= bMaxZ; z++) {
                    Material mat = chunkCache.getBlock(world, x, y, z);
                    if (mat == null || isTransparent(mat)) {
                        continue;
                    }

                    com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState state = chunkCache.getBlockState(world, x, y, z);
                    java.util.List<me.arrow.utils.custom.materials.PEMaterials.CollisionBounds> bounds = state != null
                            ? me.arrow.utils.custom.materials.PEMaterials.getCollisionBounds(state, x, y, z)
                            : me.arrow.utils.custom.materials.PEMaterials.getCollisionBounds(mat, x, y, z);

                    if (bounds == null || bounds.isEmpty()) {
                        continue;
                    }

                    for (me.arrow.utils.custom.materials.PEMaterials.CollisionBounds bound : bounds) {
                        if (bound.maxX > minX && bound.minX < maxX
                                && bound.maxZ > minZ && bound.minZ < maxZ) {
                            if (bound.minY <= headTop && bound.maxY >= headBottom) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    public boolean isTransparent(Material material) {
        if (material == null || material == Material.AIR) return true;
        if (!material.isBlock()) return false;
        String name = material.name();

        if (isMaterialEqual(name, AIR)) return true;
        if (isMaterialEqual(name, WATER_PLANT)) return true;
        if (isMaterialEqual(name, LIQUID)) return true;
        if (isMaterial(name, BUBBLE)) return true;
        if (isMaterialEqual(name, TRANSPARENT)) return true;

        switch (name) {
            case "TORCH":
            case "SOUL_TORCH":
            case "FIRE":
            case "SOUL_FIRE":
            case "REDSTONE":
            case "WHEAT":
            case "RAIL":
            case "LEVER":
            case "REDSTONE_TORCH":
            case "STONE_BUTTON":
            case "OAK_BUTTON":
            case "ACTIVATOR_RAIL":
            case "TALL_GRASS":
            case "LARGE_FERN":
            case "LEAF_LITTER":
            case "LIGHT":
            case "LONG_GRASS":
                return true;
            default:
                return false;
        }
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



            float newFrictionFactor = CollisionUtils.getBlockSlipperiness(
                    CollisionUtils.getMaterial(this.location.clone().subtract(0D, .825D, 0D))
            );

            this.lastFrictionFactor = this.frictionFactor;
            this.frictionFactor = newFrictionFactor;
            this.lastFrictionFactorUpdateTicks = this.frictionFactor != this.lastFrictionFactor ? 0 : this.lastFrictionFactorUpdateTicks + 1;


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

            //this.equipment.handle(p);

            //Fall Distance

            this.lastFallDistance = this.fallDistance;

            this.fallDistance = nms.getFallDistance(p);

            //Base Speed

            this.baseGroundSpeed = MoveUtils.getBaseGroundSpeed(profile);

            this.baseAirSpeed = MoveUtils.getBaseAirSpeed(profile);

            this.pastLocations.add(getLocation());

            moving = (deltaXZ != 0.0D && deltaXZ != lastDeltaXZ) || (deltaY != 0.0D && deltaY != lastDeltaY);

        }  finally {
            Profiler.stop("MovementData (playerdata without ticks or blocks)", profiler);
            handleNearbyBlocks();
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


    int tickTime = 0;

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

            boolean glidingNow = isGlidingNow();

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

            if ((isOnGround() || isServerGround()) && !isCustomInAir()) {
                this.lastSetBackLocation = getLocation();
            }

            Vector velocity = profile.getVelocityData().getExplosionKnockback();
            sinceExplosionTicks = OtherUtility.isZero(velocity) ? sinceExplosionTicks + 1 : 0;

            sinceCollideTicks = isColliding ? 0 : sinceCollideTicks + 1;
            sinceGlidingTicks = glidingNow ? 0 : sinceGlidingTicks + 1;
            glidingTicks = glidingNow ? glidingTicks + 1 : 0;

            updateElytraMomentum();

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
            sincePredictUpwardsTicks = movingUp || (predictUp && isNearStepMaterial()) ? 0 : sincePredictUpwardsTicks + 1;
            sinceNearGhastTicks = nearGhast ? 0 : sinceNearGhastTicks + 1;
            sinceTeleportTicks = profile.getExempt().isTeleports() ? 0 : sinceTeleportTicks + 1;
            isRiptiding = sinceRiptidingTicks < 20;
            ladderTicks = isClimb() ? ladderTicks + 1 : 0;
            sinceBubbleTicks = nearBubble ? 0 : sinceBubbleTicks + 1;
            sinceInsideWaterTicks = isInsideWater() ? 0 : sinceInsideWaterTicks + 1;
            sinceNearWaterTicks = isNearWater() ? 0 : sinceNearWaterTicks + 1;
            sinceOnBoatTicks = isOnBoat() ? 0 : sinceOnBoatTicks + 1;
            sinceNearBoatTicks = isNearBoat() ? 0 : sinceNearBoatTicks + 1;
            sinceUnderBoatTicks = isUnderBoat() ? 0 : sinceUnderBoatTicks + 1;
            sinceNearBoatSideTicks = isNearBoatSide() ? 0 : sinceNearBoatSideTicks + 1;
            sinceUnderblockTicks = isUnderblock() ? 0 : sinceUnderblockTicks + 1;
            sinceLevitationEffectTicks = potion.getLevitationTicks() > 0 ? 0 : sinceLevitationEffectTicks + 1;
            sinceJumpBoostEffectTicks = potion.getJumpTicks() > 0 ? 0 : sinceJumpBoostEffectTicks + 1;
            sinceSlowFallingEffectTicks = potion.getSlowFallingTicks() > 0 ? 0 : sinceSlowFallingEffectTicks + 1;
            sinceSpeedPotionEffectTicks = potion.getSpeedTicks() > 0 ? 0 : sinceSpeedPotionEffectTicks + 1;
            sinceOnGhostBlock = profile.isOnGhostBlock() ? 0 : sinceOnGhostBlock + 1;
            sinceSlimeTicks = isOnSlime() ? 0 : sinceSlimeTicks + 1;
            sinceSoulTicks = isOnSoulSand() ? 0 : sinceSoulTicks + 1;
            sinceHoneyTicks = isOnHoney() ? 0 : sinceHoneyTicks + 1;
            sinceIceTicks = isOnIce() ? 0 : sinceIceTicks + 1;

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

        if (action.getAction() != WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA) {
            return;
        }

        if (profile.getPlayer().isInsideVehicle()) {
            return;
        }

        /*
         * This packet is the earliest reliable indication of a real client
         * glide. A jump-glide can land and clear Bukkit pose before the next
         * movement packet, especially with transaction delay.
         * Works with vanilla Elytra and custom plugin glide abilities (e.g. Origins).
         */
        glideStartTransitionTicks = Math.max(glideStartTransitionTicks, getGlideTransitionTicks());
        glidingTicks = Math.max(glidingTicks, 1);
        sinceGlidingTicks = 0;
    }

    public int getGlideTransitionTicks() {
        int transactionTicks = 0;
        int pingTicks = 0;

        try {
            transactionTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());
            pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        } catch (Throwable ignored) {
        }

        int maxLag = Math.max(transactionTicks, pingTicks);
        // Allow up to 250 ticks (~12.5 seconds / 10,000+ ms ping) of transition compensation
        return Math.max(10, Math.min(250, 10 + (maxLag * 2)));
    }

    public boolean isGlidingNow() {
        return metadataGliding
                || glideStartTransitionTicks > 0
                || ReflectionUtils.isGliding(profile.getPlayer());
    }

    public boolean isGlidingOrRecentlyGlided() {
        return isGlidingOrRecentlyGlided(30);
    }

    public boolean isGlidingOrRecentlyGlided(int baseGraceTicks) {
        if (isGlidingNow() || glidingTicks > 0) {
            return true;
        }

        int transTicks = 0;
        int pingTicks = 0;
        try {
            transTicks = Math.max(0, profile.getConnectionData().getClientTickTrans());
            pingTicks = Math.max(0, profile.getConnectionData().getTransPing() / 50);
        } catch (Throwable ignored) {
        }

        int maxLag = Math.max(transTicks, pingTicks);
        // Generous lag compensation for laggy players up to 10,000ms ping (200 ticks * 4 = 800 ticks)
        int lagComp = Math.min(800, maxLag * 4);
        int maxExemptTicks = baseGraceTicks + lagComp;

        return sinceGlidingTicks < maxExemptTicks;
    }

    private void updateElytraMomentum() {
        // Do not use glideStartTransitionTicks here. It is only a packet-delay
        // grace window; this bonus must snapshot precisely when the fall-flying
        // pose actually ends.
        boolean elytraPose = metadataGliding || ReflectionUtils.isGliding(profile.getPlayer());
        if (elytraPose) {
//            elytraMomentumBonus = 0.0F;
            lastElytraPose = true;
            return;
        }

        if (lastElytraPose) {
            boolean air = isCustomInAir() && !isOnGround() && !isServerGround();
            double ordinaryLimit = air ? 0.35301212D : 0.28063D;
            elytraMomentumBonus = (float) Math.max(0.0D, Math.min(deltaXZ, 3.4) + 0.3D);
            lastElytraPose = false;
            return;
        }

        if (elytraMomentumBonus <= 0.0F) return;

        // Carry the captured excess exactly as ordinary horizontal movement
        // would: air is 0.91, ground is block slipperiness × 0.91.
        float friction = isCustomInAir()
                ? MoveUtils.FRICTION
                : getFrictionFactor() * MoveUtils.FRICTION;
        elytraMomentumBonus *= friction;

        if (elytraMomentumBonus < 0.00025F) {
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
        switch (depthStriderLevel) {
            case 1: return 0.602f;
            case 2: return 1.049f;
            case 3: return 1.494f;
            default: return 0.146f;
        }
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

    public void updateWaterPrediction(Profile profile) {
        /*
         * Only isInsideWater represents actual water movement.
         *
         * Do NOT use isOnTopOfWater here. A player can stand on a block
         * above water and still have isOnTopOfWater() = true.
         */
        boolean currentlyWater = isInsideWater();

        /*
         * ---------------------------------------------------------
         * CURRENTLY IN WATER
         * ---------------------------------------------------------
         */
        if (currentlyWater) {

            boolean modernMovement =
                    profile.getVersion().isNewerThan(ClientVersion.V_1_12_2);

            boolean sprinting =
                    profile.getActionData().isSprinting();

            float baseWaterFriction = getWaterFriction(
                    profile,
                    sprinting,
                    modernMovement
            );

            float[] possibleFrictions;
            if (!wasInWater) {
                if (isLastOnGround()) {
                    possibleFrictions = new float[]{lastFrictionFactor * 0.91F, 0.91F, baseWaterFriction};
                } else {
                    possibleFrictions = new float[]{0.91F, baseWaterFriction};
                }
            } else if (isNearWaterSurface() || !wasWasInWater) {
                if (isLastOnGround()) {
                    possibleFrictions = new float[]{baseWaterFriction, 0.91F, lastFrictionFactor * 0.91F};
                } else {
                    possibleFrictions = new float[]{baseWaterFriction, 0.91F};
                }
            } else {
                if (isLastOnGround()) {
                    possibleFrictions = new float[]{baseWaterFriction, lastFrictionFactor * 0.91F};
                } else {
                    possibleFrictions = new float[]{baseWaterFriction};
                }
            }

            double movementSpeed =
                    ReflectionUtils.getPlayerMovementSpeedWithoutSprint(
                            profile.getPlayer()
                    );

            if (!Double.isFinite(movementSpeed) || movementSpeed <= 0.0D) {
                movementSpeed = 0.1D;
            }

            if (sprinting) {
                movementSpeed *= 1.3D;
            }

            float acceleration = 0.02F;

            float depthStrider = Math.min(
                    3.0F,
                    (float) SpeedUtilities.getDepthStriderLevel(profile)
            );

            if (!isLastOnGround()) {
                depthStrider *= 0.5F;
            }

            if (depthStrider > 0.0F) {
                acceleration +=
                        ((float) movementSpeed - acceleration)
                                * depthStrider / 3.0F;
            }

            double bestDiff = Double.MAX_VALUE;
            double bestExpectedXZ = 0.0D;
            double maxCandidateXZ = 0.0D;

            float yaw = profile.getRotationData().getYaw();

            for (float carryFriction : possibleFrictions) {
                double carryX = lastDeltaX * carryFriction;
                double carryZ = lastDeltaZ * carryFriction;

                for (float[] keys : MovementMath.WATER_KEY_COMBOS) {
                    for (boolean sneaking : MovementMath.WATER_BOOLS) {
                        for (boolean blocking : MovementMath.WATER_BOOLS) {

                            float strafe = keys[0];
                            float forward = keys[1];

                            if (sneaking) {
                                strafe *= 0.3F;
                                forward *= 0.3F;
                            }

                            if (blocking) {
                                strafe *= 0.2F;
                                forward *= 0.2F;
                            }

                            strafe *= 0.98F;
                            forward *= 0.98F;

                            float force = strafe * strafe + forward * forward;

                            double inputX = 0.0D;
                            double inputZ = 0.0D;

                            if (force >= 1.0E-4F) {
                                force = MathHelper.sqrt_float(force);

                                if (force < 1.0F) {
                                    force = 1.0F;
                                }

                                force = acceleration / force;

                                strafe *= force;
                                forward *= force;

                                float yawRad =
                                        yaw * (float) Math.PI / 180.0F;

                                float sin = MathHelper.sin(yawRad);
                                float cos = MathHelper.cos(yawRad);

                                inputX = strafe * cos - forward * sin;
                                inputZ = forward * cos + strafe * sin;
                            }

                            double predictedX = carryX + inputX;
                            double predictedZ = carryZ + inputZ;
                            double candidateXZ = Math.hypot(predictedX, predictedZ);

                            if (candidateXZ > maxCandidateXZ) {
                                maxCandidateXZ = candidateXZ;
                            }

                            double diffX = deltaX - predictedX;
                            double diffZ = deltaZ - predictedZ;

                            double diff = Math.hypot(diffX, diffZ);

                            if (diff < bestDiff) {
                                bestDiff = diff;
                                bestExpectedXZ = candidateXZ;
                            }
                        }
                    }
                }
            }

            if (deltaXZ <= maxCandidateXZ + 0.005D) {
                expectedWaterDeltaXZ = Math.max(bestExpectedXZ, Math.min(deltaXZ, maxCandidateXZ));
            } else {
                expectedWaterDeltaXZ = bestExpectedXZ;
            }

            /*
             * Save the last REAL water prediction.
             *
             * This is what we will carry out of the water.
             */
            lastWaterPrediction = bestExpectedXZ;

            /*
             * We are currently water-predicted.
             */
            waterPredictionActive = true;
            wasActuallyInWater = true;

            /*
             * No separate post-water momentum while inside water.
             */
            waterMomentumBonus = 0.0D;

            return;
        }

        /*
         * ---------------------------------------------------------
         * LEFT WATER
         * ---------------------------------------------------------
         *
         * First tick after leaving:
         * take the last actual water prediction and put it on top
         * of the normal Speed A limit.
         */
        if (wasActuallyInWater) {
            waterMomentumBonus = lastWaterPrediction;
            wasActuallyInWater = false;
        }

        /*
         * No water momentum left.
         */
        if (waterMomentumBonus <= 0.00025D) {
            resetWaterPrediction();
            return;
        }

        /*
         * expectedWaterDeltaXZ now represents ONLY the retained
         * water momentum.
         */
        expectedWaterDeltaXZ = waterMomentumBonus;
        waterPredictionActive = true;

        /*
         * Decay the retained momentum for the NEXT tick.
         *
         * Ground = block friction * normal entity friction
         * Air    = 0.91
         */
        float decayFriction;

        if (isLastOnGround()) {
            decayFriction = lastFrictionFactor * 0.91F;
        } else {
            decayFriction = 0.91F;
        }

        waterMomentumBonus *= decayFriction;

        if (waterMomentumBonus <= 0.00025D) {
            waterMomentumBonus = 0.0D;
        }
    }

    private float getWaterFriction(
            Profile profile,
            boolean sprinting,
            boolean modernMovement
    ) {
        float friction = modernMovement
                && ReflectionUtils.isSwimming(profile.getPlayer())
                && sprinting
                ? 0.9F
                : 0.8F;

        float depthStrider = Math.min(
                3.0F,
                (float) SpeedUtilities.getDepthStriderLevel(profile)
        );

        if (!isLastOnGround()) {
            depthStrider *= 0.5F;
        }

        if (depthStrider > 0.0F) {
            friction +=
                    (0.54600006F - friction)
                            * depthStrider / 3.0F;
        }

        /*
         * Dolphin's Grace changes horizontal water drag.
         * The amplifier itself doesn't need to be multiplied.
         */
        if (modernMovement && dolphinGraceTicks > 0) {
            friction = 0.96F;
        }

        return friction;
    }

    public boolean isNearWaterSurface() {
        if (this.location == null || this.location.getWorld() == null) {
            return false;
        }
        World world = this.location.getWorld();
        String worldName = world.getName();

        int bx = MathHelper.floor_double(this.location.getX());
        int by = MathHelper.floor_double(this.location.getY());
        int bz = MathHelper.floor_double(this.location.getZ());

        ChunkCache cache = ChunkCache.get();

        // 1. If feet block water height is less than 0.95D, we are directly in a surface block or flowing water
        if (CollisionUtils.getWaterHeight(world, bx, by, bz) < 0.95D) {
            return true;
        }

        // 2. Check 1 block above feet
        Material mat1 = cache.getBlock(worldName, bx, by + 1, bz);
        if (mat1 == null || mat1 == Material.AIR || (!ChunkCache.isWaterMaterial(mat1) && !cache.isWaterLogged(worldName, bx, by + 1, bz))) {
            return true;
        }

        // 3. Check 2 blocks above feet (head level at surface)
        Material mat2 = cache.getBlock(worldName, bx, by + 2, bz);
        return mat2 == null || mat2 == Material.AIR || (!ChunkCache.isWaterMaterial(mat2) && !cache.isWaterLogged(worldName, bx, by + 2, bz));
    }

    public void resetWaterPrediction() {
        expectedWaterDeltaXZ = 0.0D;
        waterMomentumBonus = 0.0D;
        lastWaterPrediction = 0.0D;
        waterPredictionActive = false;
        wasActuallyInWater = false;
    }
}
