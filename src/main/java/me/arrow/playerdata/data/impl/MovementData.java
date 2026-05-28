package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.checks.impl.movement.prediction.MovementPredictionUtil;
import me.arrow.checks.impl.movement.speed.SpeedMath.SpeedUtilities;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.nms.NmsInstance;
import me.arrow.playerdata.data.Data;
import me.arrow.playerdata.processors.impl.CollisionProcessor;
import me.arrow.playerdata.processors.impl.SetbackProcessor;
import me.arrow.playerdata.processors.impl.SlimeProcessor;
import me.arrow.utils.CollisionUtils;
import me.arrow.utils.EntityUtil;
import me.arrow.utils.MoveUtils;
import me.arrow.utils.custom.*;
import me.arrow.utils.customutils.*;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.BoundingBox;
import me.arrow.utils.customutils.raytrace.BlockRayCastResult;
import me.arrow.utils.customutils.raytrace.RayCastUtility;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;

// this is the entire main data of the anticheat, there's alot of crap thrown in here, and some of them should be in other data classes
// there will be a big recode to organize stuff in the future.

@Getter
@Setter
public class MovementData implements Data {

    @Getter
    @Setter
    float BEDROCK_JUMP_MOTION;

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
    SampleList<CustomLocation> pastLocations = new SampleList<>(40);

    @Getter
    @Setter
    Location lastGroundLocation;


    @Getter
    boolean onGround, lastOnGround, lastLastOnGround, serverGround, lastServerGround, serverYGround, positionYGround, lastPositionYGround, lastServerYGround, isDigging,
        nearWater, nearBubble, nearLava, nearContact, nearWebs, nearWall, nearClimbable, nearBuggyBlock, nearBed, nearHoney, nearShulkerBox, nearDripLeaf, customInAir, underblock, insideLiquid, climb, moving, isInsideWater, isOnTopOfWater, isBottomOfWater, isColliding, nearBoat, nearGhast, nearShulker, nearFence, onBoat, onIce, onSlime, onExtendedHitboxSlime, onHoney, onSoulSand, movingUp, movingDown, isRiptiding, nearPiston;


    @Getter
    @Setter
    int clientAirTicks, serverAirTicks, serverGroundTicks, serverGroundTicksPlus, lastServerGroundTicks, nearGroundTicks, lastNearGroundTicks,
            clientGroundTicks, lastNearWallTicks,
            lastFrictionFactorUpdateTicks, lastNearEdgeTicks,
            customAirTicks, nearWallTicks, sinceExplosionTicks, sinceCollideTicks, sinceGlidingTicks, sincePowderSnowTicks, sinceElytraEquipTicks,
            sinceOnGhostBlock, sinceGlitchedInsideBlockTicks, sinceOnGround, sinceRiptidingTicks, sinceBubbleTicks, sincePredictUpwardsTicks, sincePredictDownwardsTicks, sinceSpeedPotionEffectTicks, sinceNearGhastTicks, movingOnSoulTicks, movingOnSoulBlocksTicks, movingTicks, sinceMovingOnSlimeTicks, sinceMovingOnIceTicks, movingOnHoneyTicks, sinceMovingOnHoneyTicks, slimeTicks, soulTicks, honeyTicks, sinceSlimeTicks, sinceSoulTicks, sinceHoneyTicks, iceTicks, sinceIceTicks, sinceMovingUpTicks, sinceMovingDownTicks, sinceDolphinGraceTicks, dolphinGraceTicks, ladderTicks, sinceInsideWaterTicks, sinceNearWaterTicks, sinceLevitationEffectTicks, tick, sinceTeleportTicks;

    @Getter
    @Setter
    float movingUnderblockTicks, movingOnIceTicks, movingOnSlimeTicks;

    @Getter
    @Setter
    boolean intersecting;

    @Getter
    @Setter
    boolean packetNearWall;

    boolean packetMoving;

    @Getter
    CollisionUtils.NearbyBlocksResult nearbyBlocksResult;

    @Getter
    SlimeProcessor slimeProcessor;

    @Getter
    public MovementPredictionUtil.RelativeMove relative;

    @Getter
    public MovementPredictionUtil.VerticalMove verticalMove;




    public MovementData(Profile profile) {
        this.profile = profile;

        this.equipment = new Equipment();
        this.setbackProcessor = new SetbackProcessor(profile);
        this.slimeProcessor = new SlimeProcessor(profile);
      //  this.ghostBlockProcessor = new GhostBlockProcessor(profile, false);

        /*
        Initialize the current location.
         */
        this.location = this.lastLocation = this.lastLastLocation = new CustomLocation(profile.getPlayer().getLocation());
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {

        final World world = profile.getPlayer().getWorld();

        final long currentTime = event.getTimestamp();
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
        if (event.getPacketType().equals(PLAYER_POSITION)) {
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
                    world,
                    move.getLocation().getX(), move.getLocation().getY(), move.getLocation().getZ(),
                    move.getLocation().getYaw(), move.getLocation().getPitch(),
                    currentTime
            );



            processLocationData();
        }
        if (event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)) {

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
                    world,
                    posLook.getLocation().getX(), posLook.getLocation().getY(), posLook.getLocation().getZ(),
                    posLook.getYaw(), posLook.getPitch(),
                    currentTime
            );

            processLocationData();
        }
        if (event.getPacketType().equals(PLAYER_ROTATION)) {
            final WrapperPlayClientPlayerRotation look = new WrapperPlayClientPlayerRotation(event);

            this.lastOnGround = this.onGround;
            this.onGround = look.isOnGround();

            this.packetNearWall = look.isHorizontalCollision();
            this.packetMoving = look.hasPositionChanged();

            this.clientAirTicks = this.onGround ? 0 : this.clientAirTicks + 1;
            this.clientGroundTicks = this.onGround ? this.clientGroundTicks + 1 : 0;

            this.lastLastLocation = this.lastLocation;
            this.lastLocation = this.location;
        }
        if (event.getPacketType() == PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging dig = new WrapperPlayClientPlayerDigging(event);
            isDigging = switch (dig.getAction()) {
                case START_DIGGING -> true;
                case CANCELLED_DIGGING, FINISHED_DIGGING, DROP_ITEM_STACK, DROP_ITEM, RELEASE_USE_ITEM,
                     SWAP_ITEM_WITH_OFFHAND, STAB -> false;
            };
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {

    }

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
            setLastGroundLocation(profile.getPlayer().getLocation());
        }

        //Process data
        processPlayerData();
        processBlocks();

        if (profile.getPlayer().getAllowFlight()) {
            profile.getLastFlightToggleTimer().reset();
        }

        nearBoat = EntityUtil.isNearBoat(profile);
        nearShulker = EntityUtil.isNearShulker(profile);
        nearGhast = EntityUtil.isNearGhast(profile);
        onBoat = EntityUtil.isOnBoat(profile);

        if (onGround) sinceOnGround = 0;
        else sinceOnGround++;

        //this.getGhostBlockProcessor().process();

        predictPlayerMovement();

        // very poor attempt at syncing the randomized jump height to prevent falses on the checks.
        // there should be a better way right... anyway, bedrock is cancer but i must support bedrock
        // no matter what, as you can spoof your client to be on bedrock, or yk cheats exist on bedrock
        if (profile.isBedrockPlayer()) {
            boolean groundTransition = !isOnGround() && isLastOnGround();

            boolean cleanContext =
                    !profile.shouldCancel()
                            && !profile.getExempt().vehicle()
                            && !profile.getVelocityData().isTakingVelocity()
                            && profile.getMovementData().getSinceCollideTicks() > 5
                            && !profile.getMovementData().isNearClimbable()
                            && profile.getMovementData().getSinceNearWaterTicks() > 5
                            && !profile.getMovementData().isNearWebs()
                            && profile.getMovementData().getSinceSlimeTicks() > 5
                            && !profile.isBouncingOnSlime()
                            && profile.getMovementData().getSinceTeleportTicks() > 5;

            boolean possibleJump =
                    deltaY > 0.4198
                            && deltaY < 0.422;

            if (groundTransition
                    //&& cleanContext
                    && possibleJump
            ) {
                BEDROCK_JUMP_MOTION = (float) deltaY;
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
    }

    private void predictPlayerMovement() {
//        MovementPredictionUtil.BlockSample[] samples =
//                MovementPredictionUtil.scanAroundFeet(
//                        profile.getPlayer().getWorld(),
//                        location.getX(),
//                        location.getY(),
//                        location.getZ()
//                );

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
        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();
        BoundingBox playerBox = profile.getBoundingBox();

        /*
        Handle collisions
        NOTE: You should ALWAYS use NMS if you plan on supporting 1.9+
        For a production server, DO NOT use spigot's api. It's slow. (Especially for Blocks, Chunks, Materials)
         */
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(getLocation().clone(), true);
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult2 = CollisionUtils.getNearbyBlocks(getLocation().clone().add(0, 1, 0), true);
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult3 = CollisionUtils.getNearbyBlocks(getLocation().clone().subtract(0, 1, 0), true);
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult4 = CollisionUtils.getNearbyBlocks(getLocation().clone().subtract(0, 2, 0), true);

        this.nearbyBlocksResult = nearbyBlocksResult;

        customInAir = !nearbyBlocksResult.isNearGround()
                && !nearbyBlocksResult2.isNearGround()
                //&& (!nearbyBlocksResult3.isNearGround() && CollisionUtils.isNearEdge(getLocation()))
//                && !nearbyBlocksResult4.isNearGround()
                && !profile.isExempt().isFlight()
                && !profile.shouldCancel()
                && !profile.getPlayer().isInsideVehicle()
                && !isNearBoat()
                && !isOnBoat();

        intersecting = false
                //((isPlayerIntersectingBlocks(profile) || profile.getBlockData().collideSlime || isPhasing(profile)) && (deltaXZ > 20 || deltaY > 60 || deltaY < -60) || isPhasing(profile))
        ;

        profile.setBouncingOnSlime(getSlimeProcessor().isBouncing(profile.getMovementData(), profile.getPotionData()));
        if (Config.Setting.DEBUG.getBoolean()) profile.getPlayer().sendMessage(OtherUtility.translate( "Bouncing on Slime: &c" + profile.isBouncingOnSlime()));

        movingUp = moving && (getDeltaY() > 0 || getLastDeltaY() > 0)
                && (nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.FENCE))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.WALL))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.HALF_BLOCK))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.STAIRS))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.SLAB))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.SNOW)));

        movingDown = moving && (getDeltaY() < 0 || getLastDeltaY() < 0)
                && (nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.FENCE))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.WALL))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.HALF_BLOCK))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.STAIRS))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.SLAB))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.SNOW)));
       // OtherUtility.log("Player: " + profile.getPlayer().getName() + " | isPhasing: " + isPhasing(profile));


//        isCollidingNearbyEntitiesAsync(profile.getPlayer(), playerBox).thenAccept(colliding -> isColliding = colliding);
       if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8) && profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) isColliding = false;
       else isColliding = CollisionProcessor.isColliding(profile.getPlayer(), profile.getBoundingBox());
    }

    public boolean isPhasing() {
        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();
        Player player = profile.getPlayer();
        World world = player.getWorld();
        Location legLocation = player.getLocation();
        Vector direction = legLocation.getDirection();
        double maxDistance = 1;

        Object result;

        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_13)) {
            result = RayCastUtility.rayCastBlocks(player, maxDistance, true, RayCastUtility.Precision.PRECISE_ENTITY);
        } else {
            result = world.rayTraceBlocks(
                    legLocation,
                    direction,
                    maxDistance,
                    FluidCollisionMode.NEVER,
                    true
            );
        }

        if (result != null) {
            Block hitBlock = null;

            if (result instanceof BlockRayCastResult br) {
                hitBlock = br.getBlock();
            } else if (result instanceof RayTraceResult rr) {
                hitBlock = rr.getHitBlock();
            }

            if (hitBlock != null) {

                if (MaterialType.isMaterial(hitBlock.getType().name(), MaterialType.LIQUID) || MaterialType.isMaterial(hitBlock.getType().name(), MaterialType.AIR))
                    return false;

                // Reconstruct player bounding box
                me.arrow.utils.custom.BoundingBox playerBox =
                        me.arrow.utils.custom.BoundingBox.fromPlayerVector(player.getLocation().toVector());

                // Convert block to custom bounding box
                me.arrow.utils.custom.BoundingBox blockBox =
                        me.arrow.utils.custom.BoundingBox.of(hitBlock);

                // Check if player bounding box intersects with block bounding box
                return playerBox.intersects(blockBox);
            }
        }

        return false;
    }


    void processBlocks() {
        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(this.location, true);

        boolean badVector = Math.abs(getLocation().toVector().length()
                - getLastLocation().toVector().length()) >= 1;

        profile.setBoundingBox(new BoundingBox((badVector ? getLocation().toVector()
                : getLastLocation().toVector()), getLocation().toVector())
                .grow(0.3f, 0, 0.3f).add(0, 0, 0, 0, 1.84f, 0));

        boolean flag_water = false, flag_lava = false, flag_web = false, flag_climbable = false,
                flag_nearBuggyBlock = false, flag_bubble = false, flag_bed = false,
                flag_honey = false, flag_shulker = false, flag_contact = false,
                flag_dripleaf = false, flag_fence = false;

        World world = location.getWorld();

        if (world != null) {
            int baseX = location.getBlockX();
            int baseY = location.getBlockY();
            int baseZ = location.getBlockZ();

            int[] yLevels = {
                    3,
                    2,
                    1,
                    0,
                    -1
            };

            for (int yOffset : yLevels) {
                for (int xOffset = -1; xOffset <= 1; xOffset++) {
                    for (int zOffset = -1; zOffset <= 1; zOffset++) {
                        Block block = world.getBlockAt(baseX + xOffset, baseY + yOffset, baseZ + zOffset);
                        Material mat = nms.getType(block);
                        String mName = mat.name();

                        flag_water = flag_water || isWaterOrWaterlogged(block);
                        flag_bubble = flag_bubble || MaterialType.isMaterial(mName, MaterialType.BUBBLE);
                        flag_lava = flag_lava || MaterialType.isMaterial(mName, MaterialType.LAVA);
                        flag_web = flag_web || MaterialType.isMaterial(mName, MaterialType.WEB);

                        flag_climbable = flag_climbable
                                || MaterialType.isMaterial(mName, MaterialType.CLIMBABLE)
                                || MaterialType.isMaterial(mName, MaterialType.SCAFFOLDING);

                        flag_nearBuggyBlock = flag_nearBuggyBlock || MaterialType.isMaterial(mName, MaterialType.BUGGY_BLOCK);
                        flag_bed = flag_bed || MaterialType.isMaterial(mName, MaterialType.BED);
                        flag_honey = flag_honey || MaterialType.isMaterial(mName, MaterialType.HONEY);
                        flag_shulker = flag_shulker || MaterialType.isMaterial(mName, MaterialType.SHULKER);
                        flag_dripleaf = flag_dripleaf || MaterialType.isMaterial(mName, MaterialType.DRIP_LEAF);

                        flag_contact = flag_contact
                                || MaterialType.isMaterial(mName, MaterialType.CACTUS)
                                || MaterialType.isMaterial(mName, MaterialType.BERRIES);

                        flag_fence = flag_fence
                                || MaterialType.isMaterial(mName, MaterialType.FENCE)
                                || MaterialType.isMaterial(mName, MaterialType.WALL);
                    }
                }
            }
        }

        nearContact = flag_contact;
        nearWater = flag_water;
        nearBubble = flag_bubble;
        nearLava = flag_lava;
        nearWebs = flag_web;
        nearClimbable = flag_climbable;
        nearBuggyBlock = flag_nearBuggyBlock;
        nearBed = flag_bed;
        nearHoney = flag_honey;
        nearShulkerBox = flag_shulker;
        nearDripLeaf = flag_dripleaf;
        nearFence = flag_fence;


        isOnTopOfWater = CollisionUtils.isStandingOnWater(this.location, nearbyBlocksResult, true, MaterialType.WATER);
        isInsideWater = true;
        CustomLocation playerLoc = new CustomLocation(
                profile.getPlayer().getWorld(),
                profile.getPlayer().getLocation().getX(),
                profile.getPlayer().getLocation().getY(),
                profile.getPlayer().getLocation().getZ()
        );



        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                CustomLocation checkLoc = playerLoc.clone();
                checkLoc.setX(checkLoc.getX() + x);
                checkLoc.setZ(checkLoc.getZ() + z);
                checkLoc.setY(playerLoc.getY() + 0.5);
                Block block = CollisionUtils.getBlock(checkLoc, true);
                String mName = nms.getType(checkLoc.getBlock()).name();
                if (!MaterialType.isMaterial(mName, MaterialType.WATER)) {
                    isInsideWater = false;
                    break;
                }
            }
            if (!isInsideWater) break;
        }

        isBottomOfWater = isInsideWater && isServerGround();


        final int[][] locs = {{-1, 0, 1, -1, 0, 1, -1, 0, 1}, {-1, -1, -1, 0, 0, 0, 1, 1, 1}};
        boolean flag = false;

        for (int i = 0; i < 9; i++) {

            Material t1 = nms.getType(location.clone().add(locs[0][i], 0, locs[1][i]).getBlock());
            Material t2 = nms.getType(location.clone().add(locs[0][i], 1, locs[1][i]).getBlock());

            boolean tmp1 = !isTransparent(t1);
            boolean tmp2 = !isTransparent(t2);

            flag = flag || (tmp1 && tmp2);
            if (flag) break;
        }

        for (int i = 0; i < 9; i++) {
            Material t2 = nms.getType(location.clone().add(locs[0][i], 1, locs[1][i]).getBlock());
            boolean tmp2 = !isTransparent(t2);

            flag = flag || tmp2;
            if (flag) break;
        }

        for (int i = 0; i < 9; i++) {
            Material t1 = nms.getType(location.clone().add(locs[0][i], 0, locs[1][i]).getBlock());

            boolean tmp1 = !isTransparent(t1);

            flag = flag || tmp1;
            if (flag) break;
        }

        nearWall = CollisionUtils.isNearWall(getLocation());

        boolean flag_underblock = false;

        for (int x2 = -1; x2 <= 1; x2++) {
            for (int z2 = -1; z2 <= 1; z2++) {
                flag_underblock = flag_underblock || !isTransparent(nms.getType(getLocation().clone().add(x2, 2, z2).getBlock()));
            }
        }

        for (int x2 = -1; x2 <= 1; x2++) {
            for (int z2 = -1; z2 <= 1; z2++) {
                flag_underblock = flag_underblock || !isTransparent(nms.getType(getLocation().clone().add(x2, 1, z2).getBlock()));
            }
        }

        if (profile.isCrawling()) {
            for (int x2 = -1; x2 <= 1; x2++) {
                for (int z2 = -1; z2 <= 1; z2++) {
                    flag_underblock = flag_underblock || !isTransparent(nms.getType(getLocation().clone().add(x2, 3, z2).getBlock()));
                }
            }

            for (int x2 = -1; x2 <= 1; x2++) {
                for (int z2 = -1; z2 <= 1; z2++) {
                    flag_underblock = flag_underblock || !isTransparent(nms.getType(getLocation().clone().add(x2, 0, z2).getBlock()));
                }
            }
        }

        //profile.getPlayer().sendMessage("isSwimming " + Arrow.getInstance().getNmsManager().getNmsInstance().isSneaking(profile.getPlayer()));

        underblock = flag_underblock;

        insideLiquid = MaterialType.isMaterial(nms.getType(location.clone().subtract(0D, 1D, 0D).getBlock()).name(), MaterialType.LIQUID)
                || MaterialType.isMaterial(nms.getType(location.clone().getBlock()).name(), MaterialType.LIQUID);

        climb = MaterialType.isMaterial(nms.getType(location.clone().subtract(0D, -1D, 0D).getBlock()).name(), MaterialType.CLIMBABLE)
                || MaterialType.isMaterial(nms.getType(location.clone().getBlock()).name(), MaterialType.CLIMBABLE);

    }

    private boolean isCustomHeavyInAir(CustomLocation location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();

        for (int y = -1; y <= 0; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    Material mat = nms.getType(location.getWorld().getBlockAt(baseX + x, baseY + y, baseZ + z));
                    String name = mat.name();

                    if (!MaterialType.isMaterial(name, MaterialType.TRANSPARENT)
                            || !MaterialType.isMaterial(name, MaterialType.LIQUID)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static @NotNull BoundingBox getBoundingBox(Entity entity) {
        BoundingBox entityBox;
        if (entity instanceof LivingEntity le) {
            Location loc = le.getLocation();
            entityBox = new BoundingBox(
                    new Vector(loc.getX() - 0.3, loc.getY(), loc.getZ() - 0.3),
                    new Vector(loc.getX() + 0.3, loc.getY() + 1.8, loc.getZ() + 0.3)
            );
        } else {
            Location loc = entity.getLocation();
            entityBox = new BoundingBox(
                    new Vector(loc.getX() - 0.25, loc.getY(), loc.getZ() - 0.25),
                    new Vector(loc.getX() + 0.25, loc.getY() + 0.25, loc.getZ() + 0.25)
            );
        }
        return entityBox;
    }

    public boolean isTransparent(Material material) {
        if (!material.isBlock()) return false;
        String name = material.name();

        if (MaterialType.isMaterial(name, MaterialType.AIR)) return true;
        if (MaterialType.isMaterial(name, MaterialType.WATER_PLANT)) return true;
        if (MaterialType.isMaterial(name, MaterialType.LIQUID)) return true;
        if (MaterialType.isMaterial(name, MaterialType.BUBBLE)) return true;
        if (MaterialType.isMaterial(name, MaterialType.TRANSPARENT)) return true;

        return switch (name) {
            case "TORCH", "SOUL_TORCH", "FIRE", "SOUL_FIRE", "REDSTONE", "WHEAT", "RAIL", "LEVER", "REDSTONE_TORCH",
                 "STONE_BUTTON", "OAK_BUTTON", "ACTIVATOR_RAIL", "TALL_GRASS", "LARGE_FERN", "LEAF_LITTER", "LIGHT", "LONG_GRASS" -> true;
            default -> false;
        };
    }


    private void processPlayerData() {

        final Player p = profile.getPlayer();

        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();

        //Chunk

//        if ((this.lastUnloadedChunkTicks = nms.isChunkLoaded(
//                this.location.getWorld(), this.location.getBlockX(), this.location.getBlockZ())
//                ? this.lastUnloadedChunkTicks + 1 : 0) > 10) {
//
//            //Nearby Entities
//
//            //this.nearbyEntityProcessor.process();
//
//            //Nearby Blocks
//
//
//        }
        handleNearbyBlocks();

        //Friction Factor

        this.frictionFactor = CollisionUtils.getBlockSlipperiness(
                nms.getType(this.location.clone().subtract(0D, .825D, 0D).getBlock())
        );

        this.lastFrictionFactorUpdateTicks = this.frictionFactor != this.lastFrictionFactor ? 0 : this.lastFrictionFactorUpdateTicks + 1;

        this.lastFrictionFactor = this.frictionFactor;


        //Near Wall

        this.lastNearWallTicks = CollisionUtils.isNearWall(this.location) ? 0 : this.lastNearWallTicks + 1;

        //Near Edge

        this.lastNearEdgeTicks = this.lastNearGroundTicks == 0 && CollisionUtils.isNearEdge(this.location) ? 0 : this.lastNearEdgeTicks + 1;

        //Server Ground

        final boolean lastServerGround = this.serverGround;

        final boolean serverGround = CollisionUtils.isServerGround(this.location.getY());

        this.lastServerGround = lastServerGround;

        this.serverGround = serverGround;

        this.serverGroundTicks = serverGround && serverGroundTicks < 20 ? this.serverGroundTicks + 1 : 0;

        this.lastServerGroundTicks = serverGround && lastServerGroundTicks < 20 ? 0 : this.lastServerGroundTicks + 1;

        //Equipment

        this.equipment.handle(p);

        //Fall Distance

        this.lastFallDistance = this.fallDistance;

        this.fallDistance = nms.getFallDistance(p);

        //Base Speed

        this.baseGroundSpeed = MoveUtils.getBaseGroundSpeed(profile);

        this.baseAirSpeed = MoveUtils.getBaseAirSpeed(profile);

        this.pastLocations.add(getLocation());

        moving = (deltaXZ != 0.0D && deltaXZ != lastDeltaXZ) || (deltaY != 0.0D && deltaY != lastDeltaY);

        //Ghost Blocks

        //this.ghostBlockProcessor.process();

        updateTicks();


    }


    int tickTime;

    void updateTicks() {
        this.tick++;

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(this.location, true);
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lower = CollisionUtils.getNearbyBlocks(this.lastLocation, true);
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lowest = CollisionUtils.getNearbyBlocks(this.lastLastLocation, true);

        boolean powdersnow = nearbyBlocksResult.getBlockTypes().stream()
                .anyMatch(material -> material.name().equals("POWDER_SNOW"));

        if (powdersnow) {
            sincePowderSnowTicks = 0;
        } else {
            sincePowderSnowTicks++;
        }

        boolean onIce0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, true, MaterialType.ICE);
        boolean onIce1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, true, MaterialType.ICE);
        boolean onIce2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, true, MaterialType.ICE);
        onIce = onIce0 || onIce1 || onIce2;

        if (moving && (onIce0 || onIce1 || onIce2)) {
            movingOnIceTicks += (movingOnIceTicks < 30 ? 1f : 0);
        } else {
            movingOnIceTicks = Math.max(0, movingOnIceTicks - 0.25f);
        }

        if (onIce0 || onIce1 || onIce2) {
            iceTicks += (iceTicks < 25 ? 1 : 0);
        } else {
            iceTicks = Math.max(0, iceTicks - 1);
        }

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelow =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 1, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelow_lower =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 1, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelow_lowest =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 1, 0), true);


        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 2, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lower =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 2, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lowest =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 2, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow1 =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 3, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lower1 =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 3, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lowest1 =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 3, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove =
                CollisionUtils.getNearbyBlocks(this.location.clone().add(0, 1, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove_lower =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().add(0, 1, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove_lowest =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().add(0, 1, 0), true);


        final CollisionUtils.NearbyBlocksResult nearbyBlocks =
                CollisionUtils.getNearbyBlocks(this.location, true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksBelow =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 1, 0), true);

        final CollisionUtils.NearbyBlocksResult nearbyBlocksBelow2 =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 2, 0), true);


        final CollisionUtils.NearbyBlocksResult nearbyBlocksAbove =
                CollisionUtils.getNearbyBlocks(this.location.clone().add(0, 1, 0), true);


        boolean slimeBelow0 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelow, true, MaterialType.SLIME);
        boolean slimeBelow1 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelow_lower, true, MaterialType.SLIME);
        boolean slimeBelow2 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelow_lowest, true, MaterialType.SLIME);

        boolean slimeBelowBelow0 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow, true, MaterialType.SLIME);
        boolean slimeBelowBelow1 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lower, true, MaterialType.SLIME);
        boolean slimeBelowBelow2 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lowest, true, MaterialType.SLIME);

        boolean slimeBelowBelow3 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow1, true, MaterialType.SLIME);
        boolean slimeBelowBelow4 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lower1, true, MaterialType.SLIME);
        boolean slimeBelowBelow5 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lowest1, true, MaterialType.SLIME);

        boolean slimeAbove0 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultAbove, true, MaterialType.SLIME);
        boolean slimeAbove1 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultAbove_lower, true, MaterialType.SLIME);
        boolean slimeAbove2 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultAbove_lowest, true, MaterialType.SLIME);

        boolean onSlime0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, true, MaterialType.SLIME);
        boolean onSlime1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, true, MaterialType.SLIME);
        boolean onSlime2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, true, MaterialType.SLIME);
        onSlime = onSlime0 || onSlime1 || onSlime2;


        boolean nearPiston0 = nearbyBlocks.getBlockTypes().stream().anyMatch(material -> MaterialType.isMaterial(material.name(), MaterialType.PISTON));
        boolean nearPiston1 = nearbyBlocksBelow.getBlockTypes().stream().anyMatch(material -> MaterialType.isMaterial(material.name(), MaterialType.PISTON));
        boolean nearPiston2 = nearbyBlocksBelow2.getBlockTypes().stream().anyMatch(material -> MaterialType.isMaterial(material.name(), MaterialType.PISTON));
        boolean nearPiston3 = nearbyBlocksAbove.getBlockTypes().stream().anyMatch(material -> MaterialType.isMaterial(material.name(), MaterialType.PISTON));

        nearPiston = nearPiston0 || nearPiston1 || nearPiston2 || nearPiston3;





        // this is a temporary, test fix, for piston movable slime blocks, it may not work properly in all scenarios, but it will do for now
        // assuming that it even works...
        onExtendedHitboxSlime = onSlime || slimeBelow0 || slimeBelow1 || slimeBelow2 || slimeAbove0 || slimeAbove1 || slimeAbove2 || slimeBelowBelow0 || slimeBelowBelow1 || slimeBelowBelow2 || slimeBelowBelow3 || slimeBelowBelow4 || slimeBelowBelow5
                || (getMovingOnSlimeTicks() < 11 && getMovingOnSlimeTicks() > 0) || getSinceMovingOnSlimeTicks() < 10;

       // profile.getPlayer().sendMessage("nearPiston: " + nearPiston + ", onSlime " + onExtendedHitboxSlime + ", deltaY " + deltaY + ", slimeTicks " + getMovingOnSlimeTicks() + ", sinceSlimeTicks " + getSinceMovingOnSlimeTicks());


        if (moving && (onSlime0 || onSlime1 || onSlime2)) {
            movingOnSlimeTicks += (movingOnSlimeTicks < 30 ? 1F : 0F);
        } else {
            movingOnSlimeTicks = Math.max(0, movingOnSlimeTicks - 0.5F);
        }

        if (onSlime0 || onSlime1 || onSlime2) {
            slimeTicks += (slimeTicks < 25 ? 1 : 0);
        } else {
            slimeTicks = Math.max(0, slimeTicks - 1);
        }

        boolean onSoul0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, true, MaterialType.SOUL_SAND);
        boolean onSoul1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, true, MaterialType.SOUL_SAND);
        boolean onSoul2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, true, MaterialType.SOUL_SAND);
        onSoulSand = onSoul0 || onSoul1 || onSoul2;

        if (moving && (onSoul0 || onSoul1 || onSoul2)) {
            movingOnSoulTicks += (movingOnSoulTicks < 25 ? 1 : 0);
        } else {
            movingOnSoulTicks = Math.max(0, movingOnSoulTicks - 1);
        }

        if (onSoul0 || onSoul1 || onSoul2) {
            soulTicks += (soulTicks < 25 ? 1 : 0);
        } else {
            soulTicks = Math.max(0, soulTicks - 1);
        }

        boolean onSoulBlock0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, true, MaterialType.SOUL_BLOCK);
        boolean onSoulBlock1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, true, MaterialType.SOUL_BLOCK);
        boolean onSoulBlock2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, true, MaterialType.SOUL_BLOCK);

        if (moving && (onSoulBlock0 || onSoulBlock1 || onSoulBlock2)) {
            movingOnSoulBlocksTicks += (movingOnSoulBlocksTicks < 25 ? 1 : 0);
        } else {
            movingOnSoulBlocksTicks = Math.max(0, movingOnSoulBlocksTicks - 1);
        }

        boolean onHoney0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, true, MaterialType.HONEY);
        boolean onHoney1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, true, MaterialType.HONEY);
        boolean onHoney2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, true, MaterialType.HONEY);
        onHoney = onHoney0 || onHoney1 || onHoney2;


        if (moving && (onHoney0 || onHoney1 || onHoney2)) {
            movingOnHoneyTicks += (movingOnHoneyTicks < 25 ? 1 : 0);
        } else {
            movingOnHoneyTicks = Math.max(0, movingOnHoneyTicks - 1);
        }

        if (onHoney0 || onHoney1 || onHoney2) {
            honeyTicks += (honeyTicks < 25 ? 1 : 0);
        } else {
            honeyTicks = Math.max(0, honeyTicks - 1);
        }

        if (movingOnIceTicks > 0) {
            sinceMovingOnIceTicks = 0;
        } else sinceMovingOnIceTicks++;

        if (movingOnSlimeTicks > 0) {
            sinceMovingOnSlimeTicks = 0;
        } else sinceMovingOnSlimeTicks++;

        if (moving && isUnderblock()) {
            movingUnderblockTicks += (movingUnderblockTicks < 25 ? 1f : 0);
        } else {
            movingUnderblockTicks = Math.max(0, movingUnderblockTicks - 0.5f);
        }

        if (moving) {
            movingTicks += 1;
        } else {
            movingTicks = 0;
        }

        if (isCustomInAir()
                && !profile.getPlayer().isInsideVehicle()
                && !isClimb()
                && !isNearWebs()
                && !EntityUtil.isOnBoat(profile)
                && !profile.isBouncingOnSlime()
                && profile.getVelocityData().getTotalVerticalVelocity() < 1
                && profile.getMovementData().getSinceOnGhostBlock() > (4 + profile.getConnectionData().getClientTickTrans())
                //&& !CollisionUtils.isNearEdge(getLocation())
        ) {
            customAirTicks++;
        } else {
            customAirTicks = 0;
        }

        if (isNearWall()
                && !(isNearLava() || isInsideWater() || isNearWebs())
                && !profile.getPlayer().isInsideVehicle()){
            nearWallTicks++;
        } else {
            nearWallTicks = 0;
        }

        if (profile.getPredictionData().isRiptiding() || Arrow.getInstance()
                .getNmsManager()
                .getNmsInstance()
                .isRiptiding(profile.getPlayer())) {
            sinceRiptidingTicks = 0;
        } else sinceRiptidingTicks++;

        profile.getConnectionData().setFlyingTick(profile.getConnectionData().getFlyingTick() + 1);

        profile.getConnectionData().setTransDropTick(profile.getConnectionData().getTransDropTick() + 1);


        tickTime++;
        if (tickTime >= 20 && isOnGround()) {
            this.lastSetBackLocation = getLocation();
            tickTime = 0;  // Reset only when condition is met
        }

        Vector velocity = profile.getVelocityData().getExplosionKnockback();
        if (velocity.getX() == 0 && velocity.getY() == 0 && velocity.getZ() == 0) {
            sinceExplosionTicks++;
        }
        else sinceExplosionTicks = 0;

        if (isColliding) {
            sinceCollideTicks = 0;
        } else sinceCollideTicks++;

        if (Arrow.getInstance().getNmsManager().getNmsInstance().isGliding(profile.getPlayer())) {
            sinceGlidingTicks = 0;
        }
        else sinceGlidingTicks++;

        if (profile.isWearingFunctionalElytra()) {
            sinceElytraEquipTicks = 0;
        }
        else sinceElytraEquipTicks++;

        if (isIntersecting()) {
            sinceGlitchedInsideBlockTicks = 0;
        }
        else sinceGlitchedInsideBlockTicks++;

        if (isServerGround()) {
            if (serverGroundTicksPlus < 20) serverGroundTicksPlus++;
            serverAirTicks = 0;
        } else {
            serverGroundTicksPlus = 0;
            if (serverAirTicks < 20) serverAirTicks++;
        }

        if (profile.getPlayer().isInsideVehicle()) {
            if (profile.getVehicleData().getVehicleTicks() < 20) {
                profile.getVehicleData().setVehicleTicks(profile.getVehicleData().getVehicleTicks() + 1);
            }
        } else {
            if (profile.getVehicleData().getVehicleTicks() > 0) {
                profile.getVehicleData().setVehicleTicks(profile.getVehicleData().getVehicleTicks() - 1);
            }
        }

        if (verticalMove == MovementPredictionUtil.VerticalMove.DOWN) {
            sincePredictDownwardsTicks = 0;
        }
        else sincePredictDownwardsTicks++;

        if (verticalMove == MovementPredictionUtil.VerticalMove.UP) {
            sincePredictUpwardsTicks = 0;
        }
        else sincePredictUpwardsTicks++;

        if (isMovingUp()) {
            sincePredictDownwardsTicks = 0;
        }
        else sincePredictDownwardsTicks++;

        if (isMovingUp()) {
            sincePredictUpwardsTicks = 0;
        }
        else sincePredictUpwardsTicks++;

        if (profile.getPotionData().isHasSpeed()) sinceSpeedPotionEffectTicks = 0;
        else sinceSpeedPotionEffectTicks += 1;

        if (nearGhast) sinceNearGhastTicks = 0;
        else sinceNearGhastTicks++;

        if (profile.getExempt().isTeleports()) {
            sinceTeleportTicks = 0;
        } else sinceTeleportTicks++;

        isRiptiding =
                //profile.getPredictionData().isRiptideHeuristicActive() && profile.getPredictionData().isRiptideMotionAllowed(getDeltaXZ(),  getDeltaY())
//        profile.getPlayer().isRiptiding()
                sinceRiptidingTicks < 20
        ;

        //profile.getPlayer().sendMessage("isRiptiding: " + isRiptiding + " riptideUse: " + profile.getPredictionData().isRiptideCharging());

        if (movingUp) sinceMovingUpTicks = 0;
        else sinceMovingUpTicks++;

        if (movingDown) sinceMovingDownTicks = 0;
        else sinceMovingDownTicks++;

        if (nearBubble) sinceBubbleTicks = 0;
        else sinceBubbleTicks++;

        if (isClimb()) ladderTicks++;
        else ladderTicks = 0;

        if (isInsideWater()) sinceInsideWaterTicks = 0;
        else sinceInsideWaterTicks++;

        if (isNearWater()) sinceNearWaterTicks = 0;
        else sinceNearWaterTicks++;

        if (profile.getPotionData().isHasLevitation()) sinceLevitationEffectTicks = 0;
        else sinceLevitationEffectTicks++;

        try {
            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
                if (profile.getPotionData().getPotionEffectLevel(PotionType.DOLPHINS_GRACE) > 0) {
                    dolphinGraceTicks++;
                    sinceDolphinGraceTicks = 0;
                }
                else {
                    dolphinGraceTicks = 0;
                    sinceDolphinGraceTicks++;
                }
            }
        } catch (NoSuchMethodError exception) {
            return;
        }

        if (profile.isOnGhostBlock()) {
            sinceOnGhostBlock = 0;
        }
        else sinceOnGhostBlock++;

        dolphinGraceBoost = dolphinGraceMomentum();


//        if (profile.getTick() > 120) {
//            if (profile.getConnectionData().getTransDropTick() > (profile.getConnectionData().getLastTransPing() + (profile.getConnectionData().getClientTickTrans() * 3))) {
//                profile.kick("Timed out (T. O.)");
//                for (Player player : Bukkit.getOnlinePlayers()) {
//                    Profile playerProfile = Arrow.getInstance().getProfileManager().getProfile(player);
//
//                    if (playerProfile.isAlerts()) {
//                        player.sendMessage(OtherUtility.translate(MsgType.PREFIX.getMessage() + MsgType.MAIN_THEME_COLOR.getMessage() +profile.getPlayer().getName()+ " has cancelled transaction order"));
//                    }
//                }
//                profile.getConnectionData().setTransDropTick(0);
//            }
//
//            if (profile.getConnectionData().getTransDropTick() > 0) {
//                samples.add((long) profile.getConnectionData().getTransDropTick());
//
//                if (!this.samples.isCollected()) return;
//
//                final double deviation = getDevation(this.samples);
//
//                final double average = getAverageLong(this.samples);
//
//                //Bukkit.broadcastMessage(profile.getPlayer().getName()+", deviation: "+ deviation+", average: " + average);
//
//                if (deviation > 1.3 && deviation < 3 && average > 2) {
//                    profile.kick("Timed Out (T. O. A.)");
//                    for (Player player : Bukkit.getOnlinePlayers()) {
//                        Profile playerProfile = Arrow.getInstance().getProfileManager().getProfile(player);
//
//                        if (playerProfile.isAlerts()) {
//                            player.sendMessage(OtherUtility.translate(MsgType.PREFIX.getMessage() + MsgType.MAIN_THEME_COLOR.getMessage() +profile.getPlayer().getName()+ " was kicked for transaction order abuse"));
//                        }
//                    }
//                    profile.getConnectionData().setTransDropTick(0);
//                    samples.clear();
//                }
//            }
//        }
    }

    public boolean isWaterOrWaterlogged(Block block) {
        if (block == null || block.getType() == null) {
            return false;
        }

        Material material = block.getType();
        String name = material.name();

        if (MaterialType.isMaterial(name, MaterialType.WATER)) {
            return true;
        }

        if (isWaterPlantOrFluid(name)) {
            return true;
        }

        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
            try {
                Object blockData = block.getClass().getMethod("getBlockData").invoke(block);

                if (blockData == null) {
                    return false;
                }

                try {
                    Object value = blockData.getClass().getMethod("isWaterlogged").invoke(blockData);

                    if (value instanceof Boolean) {
                        return (Boolean) value;
                    }
                } catch (NoSuchMethodException ignored) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }

        return false;
    }

    private boolean isWaterPlantOrFluid(String name) {
        if (name == null) {
            return false;
        }

        return name.equals("KELP")
                || name.equals("KELP_PLANT")
                || name.equals("SEAGRASS")
                || name.equals("TALL_SEAGRASS")
                || name.equals("BUBBLE_COLUMN")
                || name.equals("WATER_CAULDRON")
                || name.equals("LEGACY_STATIONARY_WATER")
                || name.equals("LEGACY_WATER");
    }

    public float elytraMomentum() {
        int ping = Math.max(0, profile.getConnectionData().getTransPing());
        final float START = 0.8f + (Math.max(0f, ping - 50f) / 50f) * 0.3f;
        final float STEP  = 0.025f;
        final int   TICKS_PER_STEP = 4;
        final int   MAX_STEPS = 20;    // 6 * 2 = 12 ticks
        final int   ZERO_AT_TICK = 80;

        if (sinceGlidingTicks == 0 && getSinceElytraEquipTicks() == 0) return 0.2F;
        if (sinceGlidingTicks == 1) return START;
        if (sinceGlidingTicks >= ZERO_AT_TICK) return 0f;

        int steps = Math.min(sinceGlidingTicks / TICKS_PER_STEP, MAX_STEPS);
        float value = START - steps * STEP;
        return Math.max(value, 0f);
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
            boolean swimming = Arrow.getInstance().getNmsManager().getNmsInstance().isSwimming(profile.getPlayer());
            int depthStrider = SpeedUtilities.getDepthStriderLevel(profile);

            float cap = getDolphinGraceBonusCap(depthStrider);

            if (hasGrace && (isInsideWater || isOnTopOfWater)) {
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
            dolphinGraceMomentum = Math.max(0f, dolphinGraceMomentum - ((isInsideWater || isOnTopOfWater) ? stepAfterWater : stepAfterAir));
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
}