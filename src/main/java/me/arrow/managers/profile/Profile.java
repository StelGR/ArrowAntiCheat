package me.arrow.managers.profile;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.checks.types.TrustFactor;
import me.arrow.files.Config;
import me.arrow.listeners.ClientBrandListener;
import me.arrow.managers.threads.ProfileThread;
import me.arrow.playerdata.data.impl.*;
import me.arrow.playerdata.data.impl.TeleportData;
import me.arrow.playerdata.data.impl.worldcomp.BlockProcessor;
import me.arrow.playerdata.data.impl.worldcomp.ClientWorldTracker;
import me.arrow.playerdata.processors.impl.NMSProcessor;
import me.arrow.playerdata.processors.impl.TransactionProcessor;
import me.arrow.utils.ChatUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.playerdata.data.CheckHolder;
import me.arrow.utils.custom.Exempt;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.BoundingBox;
import me.arrow.utils.customutils.EventTimer;
import me.arrow.utils.customutils.EvictingMap;
import me.arrow.utils.versionutils.VersionUtils;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A profile class containing every single information we need
 */
@Getter
@Setter
public class Profile {

    //-------------------------------------------
    private final ActionData actionData;
    private final CombatData combatData;
    private final ConnectionData connectionData;
    private final MovementData movementData;
    private final RotationData rotationData;
    private final TeleportData teleportData;
    private final VelocityData velocityData;
    private final RodData rodData;
    private final VehicleData vehicleData;
    private final PredictionData predictionData;
    private final PotionData potionData;
    //private final LocationData locationData;
    private final ClientBrandListener clientBrandListener;
    private final BlockProcessor blockProcessor;
    private final ClientWorldTracker clientWorldTracker;
    private final NMSProcessor nmsProcessor;
    //-------------------------------------------

    //--------------------------------------
    private final CheckHolder checkHolder;
    TransactionProcessor transactionProcessor;
    @Getter
    private final TrustFactor trustFactor;

    //--------------------------------------

    //--------------------------------------
    @Setter
    @Getter
    private ClientVersion version;
    @Setter
    private String client = "Vanilla";

    @Getter
    @Setter
    private String lastFlaggedCheck = "";
    //--------------------------------------

    @Setter
    private BoundingBox boundingBox = new BoundingBox(0f, 0f, 0f, 0f, 0f, 0f);

    @Getter
    @Setter
    boolean isSwimming, isSneaking, isCrawling, isSleeping, isBouncingOnSlime, hasTeleportedOnce;

    @Getter
    @Setter
    float attackCooldown;
    double reachDistance;

    @Getter @Setter
    private double trustScore = 70;

    //------------------------------------------
    private final ProfileThread profileThread;
    private final Player player;
    private final UUID uuid;
    //------------------------------------------


    //---------------------------

    Map<Short, Long> transactionMap = new HashMap<>(100);
    Map<Long, Long> keepAliveMap = new HashMap<>(100);

    boolean hasReceivedTransaction = false;

//    private final ConnectionProcessor connectionProcessor = new ConnectionProcessor();

    private final Exempt exempt;
    private double enderPearlDistance;
    private Location enderPearlThrowLocation;
    private Block blockPlaced;
    EventTimer lastFlightToggleTimer = new EventTimer(20, this),
            lastSuffocationTimer = new EventTimer(20, this),
            lastBlockBreakTimer = new EventTimer(20, this),
            lastExplosionTimer = new EventTimer(40, this),
            lastShotByArrowTimer = new EventTimer(20, this),
            lastAttackByEntityTimer = new EventTimer(20, this),
            lastFireTickTimer = new EventTimer(40, this),
            lastBlockPlaceCancelTimer = new EventTimer(20, this),
            lastBlockPlaceTimer = new EventTimer(40, this),
            lastFallDamageTimer = new EventTimer(40, this),
            lastTeleportTimer = new EventTimer(20, this),
            sinceDeathTimer = new EventTimer(40, this),
            lastEnderpearlTimer = new EventTimer(20, this),
            lastUnknownTeleportTimer = new EventTimer(20, this),
            teleportTicks = new EventTimer(20, this),
            vehicleTicks = new EventTimer(20, this),
            reelingTicks = new EventTimer(40, this);

    private int tick, flyingTicks;

    private final Map<Long, Long> sentKeepAlives = new EvictingMap<>(100);
    private final Map<Integer, Long> iSentTransactions = new EvictingMap<>(100);
    private final Map<Short, Long> sSentTransactions = new EvictingMap<>(100);

    boolean verbose = false, alerts = true, banned = false, setbackDebug = false;
    String verbosingClass = "None";
    //---------------------------

    public Profile(Player player) {

        //Player Object
        this.player = player;

        //UUID
        this.uuid = player.getUniqueId();

        //Version
        version  = VersionUtils.getClientVersion(player);

        //this.keepAliveProcessor = new KeepAliveProcessor();
        this.transactionProcessor = new TransactionProcessor(this);

        //Data
        this.connectionData = new ConnectionData(this);
        this.actionData = new ActionData(this);
        this.velocityData = new VelocityData(this);
        this.rotationData = new RotationData(this);
        this.movementData = new MovementData(this);
        this.teleportData = new TeleportData(this);
        this.combatData = new CombatData(this);
        this.rodData = new RodData(this);
        this.predictionData = new PredictionData(this);
        this.potionData = new PotionData(this);
        this.vehicleData = new VehicleData(this);
        //this.locationData = new LocationData(this);
        this.nmsProcessor = new NMSProcessor(this);
        this.clientWorldTracker = new ClientWorldTracker(this);
        this.blockProcessor = new BlockProcessor(this);
        this.clientBrandListener = new ClientBrandListener(Arrow.getInstance());


        //Check Holder
        this.checkHolder = new CheckHolder(this);

        //Exempt
        this.exempt = new Exempt(this);

        //Thread
        this.profileThread = Arrow.getInstance().getThreadManager().getAvailableProfileThread();


        //Initialize Checks
        reloadChecks();

        this.trustFactor = new TrustFactor(this);

    }

    public void handleReceive(PacketReceiveEvent event) {

        if (this.player == null) return;

        this.connectionData.processReceive(event);
        this.velocityData.processReceive(event);
        this.actionData.processReceive(event);
        this.movementData.processReceive(event);
        this.rotationData.processReceive(event);
        this.combatData.processReceive(event);
        this.rodData.processReceive(event);
        this.teleportData.processReceive(event);
        this.predictionData.processReceive(event);
        this.potionData.processReceive(event);
        this.vehicleData.processReceive(event);
        this.clientWorldTracker.processReceive(event);
        this.blockProcessor.processReceive(event);
        this.clientBrandListener.processReceive(event);
        this.nmsProcessor.processReceive(event);

        this.exempt.handleExempts(event.getTimestamp());

        this.checkHolder.runChecks(event);
    }

    public void handleSend(PacketSendEvent event) {

        if (this.player == null) return;

        this.connectionData.processSend(event);
        this.velocityData.processSend(event);
        this.actionData.processSend(event);
        this.movementData.processSend(event);
        this.combatData.processSend(event);
        this.rotationData.processSend(event);
        this.rodData.processSend(event);
        this.teleportData.processSend(event);
        this.predictionData.processSend(event);
        this.potionData.processSend(event);
        this.vehicleData.processSend(event);
        this.clientWorldTracker.processSend(event);
        this.blockProcessor.processSend(event);
        this.clientBrandListener.processSend(event);
        this.nmsProcessor.processSend(event);

        this.exempt.handleExempts(event.getTimestamp());

        this.checkHolder.runChecks(event);
    }

    public void kick(String reason) {

        if (this.player == null) return;

        TaskUtils.task(() -> this.player.kickPlayer(ChatUtils.format(reason)));
    }

    public UUID getUUID() {
        return this.uuid;
    }

    public void reloadChecks() {
        this.checkHolder.registerAll();
    }

    public void handleTick(long currentTime) {
        setTick(getTick() + 1);
        if (Config.Setting.DEBUG.getBoolean()) player.sendMessage("CurrentTime: " + currentTime);
    }

    public Exempt isExempt() {
        return exempt;
    }

    public boolean shouldCancel() {
        return player.isFlying()
                || getLastFlightToggleTimer().hasNotPassed(20)
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || getTick() < 120
                //|| !Arrow.getInstance().getNmsManager().getNmsInstance().isChunkLoaded(movementData.getLocation().getWorld(), (int) movementData.getLocation().getX(), (int) movementData.getLocation().getZ())
                //|| isExempt().isSetback()
                ;
    }

    public boolean isSword(ItemStack itemStack) {
        return itemStack.getType() == Material.WOODEN_SWORD
                || itemStack.getType() == Material.STONE_SWORD
                || itemStack.getType() == Material.GOLDEN_SWORD
                || itemStack.getType() == Material.IRON_SWORD
                || itemStack.getType() == Material.DIAMOND_SWORD
                || itemStack.getType() == Material.NETHERITE_SWORD;
    }

    public ItemStack getPlayerHead() {
        ItemStack item = new ItemStack(Material.SKELETON_SKULL, 1, (short) 3);
        SkullMeta skull = (SkullMeta) item.getItemMeta();
        skull.setOwner(player.getName());
        item.setItemMeta(skull);
        return item;
    }

    public boolean isAirBridging(Location location) {
        Block block = location.subtract(0.0D, 3.0D, 0.0D).getBlock();

        return (block != null && block.getType() == Material.AIR);
    }

    public void sendPacket(PacketTypeCommon packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(getPlayer(), packet);
    }

    public void sendPacket(WrapperPlayServerChatMessage wrapperPlayServerChatMessage) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(getPlayer(), wrapperPlayServerChatMessage);
    }

    public void sendPacket(WrapperPlayServerKeepAlive wrapperPlayServerKeepAlive) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(getPlayer(), wrapperPlayServerKeepAlive);
        //Bukkit.broadcastMessage(getPlayer().getName()+", received keepalive. id: "+wrapperPlayServerKeepAlive.getId());
    }

    public void sendPacket(WrapperPlayServerWindowConfirmation wrapperPlayServerPing) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(getPlayer(), wrapperPlayServerPing);
        //Bukkit.broadcastMessage(getPlayer().getName()+", sent transaction. ActionID: "+wrapperPlayServerPing.getActionId() + " Window ID: " + wrapperPlayServerPing.getWindowId() + " clientVer " + wrapperPlayServerPing.getClientVersion());
    }
    public void sendPacket(WrapperPlayServerPing wrapperPlayServerPing) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(getPlayer(), wrapperPlayServerPing);
        //Bukkit.broadcastMessage(getPlayer().getName()+", sent ping. id: "+wrapperPlayServerPing.getId()  + " clientVer " + wrapperPlayServerPing.getClientVersion());
    }

    public void sendPacket(WrapperPlayServerParticle wrapperPlayServerParticle) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(getPlayer(), wrapperPlayServerParticle);
    }

    public boolean isOnGhostBlock() {

        if ( (movementData.getFallDistance() > 1.3 && (movementData.getCustomAirTicks() > 5 && movementData.getClientAirTicks() == 0))
                || (movementData.getLastFallDistance() > 1.3 && ( movementData.getCustomAirTicks() > 5 && movementData.getClientAirTicks() == 0))) return false;

        boolean isGhostBlock = movementData.isCustomInAir()
                && movementData.isOnGround()
                && getTick() > 20
                && movementData.getCustomAirTicks() >= 2
                && !isExempt().isTeleports()
                && isExempt().isRespawned()
                && !isExempt().isDead()
                && !isBouncingOnSlime()
                &&
                (movementData.getLocation().getY() - Math.floor(movementData.getLocation().getY()) != 0.60000002384186
                        ||
                        (
                                (movementData.getLocation().getY() - Math.floor(movementData.getLocation().getY()) == 0)
                                && movementData.getCustomAirTicks() > 10
                        )
                );

        //if (CollisionUtils.isNearEdge(movementData.getLocation()) && movementData.getCustomAirTicks() < 3) isGhostBlock = false;

        return isGhostBlock;
//        return false;
    }


    //1.8

    public int getPing() {
        Player player = getPlayer();

        if (player == null) {
            return 0;
        }

        try {
            Object value = player.getClass().getMethod("getPing").invoke(player);

            if (value instanceof Number) {
                return Math.max(0, ((Number) value).intValue());
            }
        } catch (Throwable ignored) {
        }

        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);

            try {
                Object value = handle.getClass().getField("ping").get(handle);

                if (value instanceof Number) {
                    return Math.max(0, ((Number) value).intValue());
                }
            } catch (Throwable ignored) {
            }

            try {
                Object value = handle.getClass().getField("latency").get(handle);

                if (value instanceof Number) {
                    return Math.max(0, ((Number) value).intValue());
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    public boolean isWearingFunctionalElytra() {
        ItemStack chestplate = getPlayer().getInventory().getChestplate();
        return chestplate != null &&
                chestplate.getType() == Material.ELYTRA;
    }

    public boolean isBedrockPlayer() {
        if (Arrow.getInstance().isFloodgatePresent()) {
            FloodgateApi api = FloodgateApi.getInstance();
            if (api != null && api.isFloodgatePlayer(getUUID())) return true;
        }
        if (Arrow.getInstance().isGeyserPresent()) {
            try {
                return GeyserApi.api().isBedrockPlayer(getUUID());
            } catch (Throwable ignored) {}
        }

        return player.getName().contains(".");
    }

}