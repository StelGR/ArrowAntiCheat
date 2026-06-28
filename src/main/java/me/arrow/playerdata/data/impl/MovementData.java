package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
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
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.*;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.custom.materials.PEMaterials;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;

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
    SampleList<CustomLocation> pastLocations = new SampleList<>(40);

    @Getter
    @Setter
    Location lastGroundLocation;


    @Getter
    boolean onGround, lastOnGround, lastLastOnGround, serverGround, lastServerGround, serverYGround, positionYGround, lastPositionYGround, lastServerYGround,
        nearWater, nearBubble, nearLava, nearContact, nearSlime, nearWebs, nearWall, nearClimbable, nearBuggyBlock, nearBed, nearHoney, nearShulkerBox, nearDripLeaf, customInAir, underblock, insideLiquid, climb, moving, isInsideWater, isOnTopOfWater, isBottomOfWater, isColliding, nearBoat, nearGhast, nearShulker, nearFence, onBoat, onIce, onSlime, onExtendedHitboxSlime, onHoney, onSoulSand, movingUp, nearStepMaterial, movingDown, isRiptiding, nearPiston;


    @Getter
    @Setter
    int clientAirTicks, serverAirTicks, serverGroundTicks, serverGroundTicksPlus, lastServerGroundTicks, nearGroundTicks, lastNearGroundTicks,
            clientGroundTicks, lastNearWallTicks,
            lastFrictionFactorUpdateTicks, lastNearEdgeTicks,
            customAirTicks, nearWallTicks, sinceExplosionTicks, sinceCollideTicks, sinceGlidingTicks, sincePowderSnowTicks, sinceElytraEquipTicks,
            sinceOnGhostBlock, sinceGlitchedInsideBlockTicks, sinceOnGround, sinceRiptidingTicks, sinceBubbleTicks, sincePredictUpwardsTicks, sincePredictDownwardsTicks, sinceSpeedPotionEffectTicks, sinceNearGhastTicks, movingOnSoulTicks, movingOnSoulBlocksTicks, movingTicks, sinceMovingOnSlimeTicks, sinceMovingOnIceTicks, movingOnHoneyTicks, sinceMovingOnHoneyTicks, slimeTicks, soulTicks, honeyTicks, sinceSlimeTicks, sinceSoulTicks, sinceHoneyTicks, iceTicks, sinceIceTicks, sinceMovingUpTicks, sinceMovingDownTicks, sinceDolphinGraceTicks, dolphinGraceTicks, ladderTicks, sinceInsideWaterTicks, sinceNearWaterTicks, sinceLevitationEffectTicks, tick, sinceTeleportTicks, sinceNearSlimeTicks;

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
                    profile.getPlayer().getWorld(),
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
                    profile.getPlayer().getWorld(),
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

            processLocationData();
        }
    }

    @Override
    public void processSend(PacketSendEvent event) {

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
            setLastGroundLocation(profile.getPlayer().getLocation());
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
                    //&& cleanContext
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
        /*
        Handle collisions
        NOTE: You should ALWAYS use NMS if you plan on supporting 1.9+
        For a production server, DO NOT use spigot's api. It's slow. (Especially for Blocks, Chunks, Materials)
         */
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(getLocation().clone(), !TaskUtils.isFoliaServer());
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult2 = CollisionUtils.getNearbyBlocks(getLocation().clone().add(0, 1, 0), !TaskUtils.isFoliaServer());

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

        nearStepMaterial = nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.HALF_BLOCK))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(MaterialType::isSlab)
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(MaterialType::isFence)
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(MaterialType::isFenceGate)
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(m -> MaterialType.isMaterial(m.name(), MaterialType.SNOW))
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(PEMaterials::isNonFullShape)
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(MaterialType::isStair)
                || nearbyBlocksResult.getBlockTypes().stream().anyMatch(MaterialType::isWall);

        movingUp = moving && (getDeltaY() > 0 || getLastDeltaY() > 0) && nearStepMaterial;

        movingDown = moving && (getDeltaY() < 0 || getLastDeltaY() < 0) && nearStepMaterial;
       // OtherUtility.log("Player: " + profile.getPlayer().getName() + " | isPhasing: " + isPhasing(profile));


//        isCollidingNearbyEntitiesAsync(profile.getPlayer(), playerBox).thenAccept(colliding -> isColliding = colliding);
        if (!supportsEntityCollisionCheck()) {
            isColliding = false;
        } else {
            isColliding = CollisionProcessor.isColliding(profile.getPlayer(), profile.getBoundingBox());
        }
    }

    void processBlocks() {
        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(this.location, !TaskUtils.isFoliaServer());

        boolean flag_water = false, flag_lava = false, flag_web = false, flag_climbable = false,
                flag_nearBuggyBlock = false, flag_bubble = false, flag_bed = false,
                flag_honey = false, flag_shulker = false, flag_contact = false,
                flag_dripleaf = false, flag_fence = false, flag_slime = false;

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
                        flag_slime = flag_slime || MaterialType.isMaterial(mName, MaterialType.SLIME);
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
        nearSlime = flag_slime;



        isOnTopOfWater = CollisionUtils.isStandingOnWater(this.location, nearbyBlocksResult, !TaskUtils.isFoliaServer(), MaterialType.WATER);

        CustomLocation playerLoc = new CustomLocation(
                profile.getPlayer().getWorld(),
                profile.getPlayer().getLocation().getX(),
                profile.getPlayer().getLocation().getY(),
                profile.getPlayer().getLocation().getZ()
        );


        isInsideWater = false;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                CustomLocation checkLoc = playerLoc.clone();
                checkLoc.setX(checkLoc.getX() + x);
                checkLoc.setZ(checkLoc.getZ() + z);
                checkLoc.setY(playerLoc.getY() + 0.5);
                //Block block = CollisionUtils.getBlock(checkLoc, !TaskUtils.isFoliaServer());
                String mName = nms.getType(checkLoc.getBlock()).name();
                if (MaterialType.isMaterial(mName, MaterialType.WATER)) {
                    isInsideWater = true;
                    break;
                }
            }
            if (isInsideWater) break;
        }

        isBottomOfWater = isInsideWater && isServerGround();
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

    private boolean supportsEntityCollisionCheck() {
        try {
            return !PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8)
                    && !profile.getVersion().isOlderThanOrEquals(ClientVersion.V_1_8);
        } catch (Throwable ignored) {
            return false;
        }
    }


    private void processPlayerData() {

        final Player p = profile.getPlayer();

        NmsInstance nms = Arrow.getInstance().getNmsManager().getNmsInstance();

        profile.setBoundingBox(createPlayerBox());

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

        String pose = getPoseName(player);

        // Sleeping hitbox
        if (pose.equals("SLEEPING")) {
            return PlayerBoxSize.SLEEPING;
        }

        // Swimming, crawling, elytra gliding, trident spin attack
        if (pose.equals("SWIMMING")
                || pose.equals("CRAWLING")
                || pose.equals("FALL_FLYING")
                || pose.equals("SPIN_ATTACK")
                || isSwimming(player)
                || isGliding(player)) {
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


    private String getPoseName(Player player) {
        try {
            Method getPose = player.getClass().getMethod("getPose");
            Object pose = getPose.invoke(player);

            if (pose != null) {
                return pose.toString().toUpperCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }

        return "STANDING";
    }

    private boolean isSwimming(Player player) {
        try {
            Method isSwimming = player.getClass().getMethod("isSwimming");
            Object result = isSwimming.invoke(player);

            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isGliding(Player player) {
        try {
            return Arrow.getInstance().getNmsManager().getNmsInstance().isGliding(player);
        } catch (Throwable ignored) {
        }

        try {
            Method isGliding = player.getClass().getMethod("isGliding");
            Object result = isGliding.invoke(player);

            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
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
        this.tick++;

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult = CollisionUtils.getNearbyBlocks(this.location, !TaskUtils.isFoliaServer());
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lower = CollisionUtils.getNearbyBlocks(this.lastLocation, !TaskUtils.isFoliaServer());
        final CollisionUtils.NearbyBlocksResult nearbyBlocksResult_lowest = CollisionUtils.getNearbyBlocks(this.lastLastLocation, !TaskUtils.isFoliaServer());

        boolean powdersnow = nearbyBlocksResult.getBlockTypes().stream()
                .anyMatch(material -> material.name().equals("POWDER_SNOW"));

        if (powdersnow) {
            sincePowderSnowTicks = 0;
        } else {
            sincePowderSnowTicks++;
        }

        boolean onIce0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, !TaskUtils.isFoliaServer(), MaterialType.ICE);
        boolean onIce1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, !TaskUtils.isFoliaServer(), MaterialType.ICE);
        boolean onIce2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, !TaskUtils.isFoliaServer(), MaterialType.ICE);
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
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 1, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelow_lower =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 1, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelow_lowest =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 1, 0), !TaskUtils.isFoliaServer());


        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 2, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lower =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 2, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lowest =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 2, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow1 =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 3, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lower1 =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().subtract(0, 3, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultBelowBelow_lowest1 =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().subtract(0, 3, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove =
                CollisionUtils.getNearbyBlocks(this.location.clone().add(0, 1, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove_lower =
                CollisionUtils.getNearbyBlocks(this.lastLocation.clone().add(0, 1, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksResultAbove_lowest =
                CollisionUtils.getNearbyBlocks(this.lastLastLocation.clone().add(0, 1, 0), !TaskUtils.isFoliaServer());


        final CollisionUtils.NearbyBlocksResult nearbyBlocks =
                CollisionUtils.getNearbyBlocks(this.location, !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksBelow =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 1, 0), !TaskUtils.isFoliaServer());

        final CollisionUtils.NearbyBlocksResult nearbyBlocksBelow2 =
                CollisionUtils.getNearbyBlocks(this.location.clone().subtract(0, 2, 0), !TaskUtils.isFoliaServer());


        final CollisionUtils.NearbyBlocksResult nearbyBlocksAbove =
                CollisionUtils.getNearbyBlocks(this.location.clone().add(0, 1, 0), !TaskUtils.isFoliaServer());


        boolean slimeBelow0 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelow, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeBelow1 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelow_lower, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeBelow2 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelow_lowest, !TaskUtils.isFoliaServer(), MaterialType.SLIME);

        boolean slimeBelowBelow0 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeBelowBelow1 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lower, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeBelowBelow2 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lowest, !TaskUtils.isFoliaServer(), MaterialType.SLIME);

        boolean slimeBelowBelow3 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow1, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeBelowBelow4 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lower1, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeBelowBelow5 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultBelowBelow_lowest1, !TaskUtils.isFoliaServer(), MaterialType.SLIME);

        boolean slimeAbove0 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultAbove, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeAbove1 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultAbove_lower, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean slimeAbove2 = CollisionUtils.isStandingOnSlime(this.location, nearbyBlocksResultAbove_lowest, !TaskUtils.isFoliaServer(), MaterialType.SLIME);

        boolean onSlime0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean onSlime1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
        boolean onSlime2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, !TaskUtils.isFoliaServer(), MaterialType.SLIME);
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

        if (isNearSlime()) sinceNearSlimeTicks = 0;
        else sinceNearSlimeTicks++;

        boolean onSoul0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, !TaskUtils.isFoliaServer(), MaterialType.SOUL_SAND);
        boolean onSoul1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, !TaskUtils.isFoliaServer(), MaterialType.SOUL_SAND);
        boolean onSoul2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, !TaskUtils.isFoliaServer(), MaterialType.SOUL_SAND);
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

        boolean onSoulBlock0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, !TaskUtils.isFoliaServer(), MaterialType.SOUL_BLOCK);
        boolean onSoulBlock1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, !TaskUtils.isFoliaServer(), MaterialType.SOUL_BLOCK);
        boolean onSoulBlock2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, !TaskUtils.isFoliaServer(), MaterialType.SOUL_BLOCK);

        if (moving && (onSoulBlock0 || onSoulBlock1 || onSoulBlock2)) {
            movingOnSoulBlocksTicks += (movingOnSoulBlocksTicks < 25 ? 1 : 0);
        } else {
            movingOnSoulBlocksTicks = Math.max(0, movingOnSoulBlocksTicks - 1);
        }

        boolean onHoney0 = CollisionUtils.isStandingOnMaterial(this.location, nearbyBlocksResult, !TaskUtils.isFoliaServer(), MaterialType.HONEY);
        boolean onHoney1 = CollisionUtils.isStandingOnMaterial(this.lastLocation, nearbyBlocksResult_lower, !TaskUtils.isFoliaServer(), MaterialType.HONEY);
        boolean onHoney2 = CollisionUtils.isStandingOnMaterial(this.lastLastLocation, nearbyBlocksResult_lowest, !TaskUtils.isFoliaServer(), MaterialType.HONEY);
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

        if (verticalMove == MovementPredictionUtil.VerticalMove.DOWN && nearStepMaterial) {
            sincePredictDownwardsTicks = 0;
        }
        else sincePredictDownwardsTicks++;

        if (verticalMove == MovementPredictionUtil.VerticalMove.UP && nearStepMaterial) {
            sincePredictUpwardsTicks = 0;
        }
        else sincePredictUpwardsTicks++;

        if (isMovingDown()) {
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
    }

    public boolean isWaterOrWaterlogged(Block block) {
        if (block == null) {
            return false;
        } else {
            block.getType();
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
}