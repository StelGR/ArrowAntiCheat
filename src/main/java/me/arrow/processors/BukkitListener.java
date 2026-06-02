package me.arrow.processors;

import me.arrow.Arrow;
import me.arrow.checks.impl.misc.interact.InteractB;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.custom.MaterialType;
import me.arrow.utils.customutils.*;
import me.arrow.utils.customutils.raytrace.*;
import me.arrow.utils.versionutils.VersionUtils;
import me.arrow.utils.versionutils.impl.VelocityClientVersionBridge;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.UUID;

import static me.arrow.utils.customutils.OtherUtility.*;
import static org.bukkit.Bukkit.getServer;


//only really using it for the test server mode, but it needs alot of clean up, also using it for Interact B cus it's much harder to make that on a packet specific check, but yet again, i may just be retarded

/**
 * A bukkit listener class that we'll use for our bukkit checks and data
 * <p>
 * NOTE: You shouldn't be using bukkit events in the first place, I just added this for the sake of having it.
 */
public class BukkitListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClickEvent(InventoryClickEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpenEvent(InventoryOpenEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReel(PlayerFishEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEvent(PlayerInteractEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPreJoin(AsyncPlayerPreLoginEvent event) {
        this.processEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPreJoin(PlayerPreLoginEvent event) {
        this.processEvent(event);
    }

    void processEvent(Event event) {
        if (event instanceof InventoryClickEvent || event instanceof InventoryCloseEvent || event instanceof InventoryOpenEvent) {
            process(event);
        } else {
            Arrow.getInstance().getExecutorService().execute(() -> this.process(event));
        }
    }

    void process(Event event) {
        if (event instanceof AsyncPlayerPreLoginEvent) {
            if (!Arrow.getInstance().isHasLoaded()) ((AsyncPlayerPreLoginEvent) event).disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,"Server is still loading, please try again later");
        }

        if (event instanceof PlayerPreLoginEvent) {
            if (!Arrow.getInstance().isHasLoaded()) ((PlayerPreLoginEvent) event).disallow(PlayerPreLoginEvent.Result.KICK_OTHER,"Server is still loading, please try again later");
        }

        if (event instanceof PlayerJoinEvent joinEvent) {
            final Player joiningPlayer = joinEvent.getPlayer();
            final UUID joiningUuid = joiningPlayer.getUniqueId();



            new BukkitRunnable() {
                private int tries = 0;

                @Override
                public void run() {
                    Player p = Bukkit.getPlayer(joiningUuid);

                    if (p == null || !p.isOnline()) {
                        cancel();
                        return;
                    }

                    Profile joiningProfile = Arrow.getInstance().getProfileManager().getProfile(p);

                    if (joiningProfile == null) {
                        return;
                    }

                    tries++;

                    if (!VelocityClientVersionBridge.hasVersion(p) && tries < 40) {
                        return;
                    }

                    joiningProfile.setVersion(VersionUtils.getClientVersion(p));

                    try {
                        for (Player receiver : Bukkit.getOnlinePlayers()) {
                            if (receiver == null) continue;

                            UUID receiverUuid = receiver.getUniqueId();

                            TaskUtils.taskLaterAsync(() -> {
                                Player recv = Bukkit.getPlayer(receiverUuid);
                                if (recv == null || !recv.isOnline()) return;

                                if (recv.hasPermission(Permissions.ALERTS.getPermission())) {
                                    recv.sendMessage(translate(Arrow.getInstance().getThemeManager().getTheme().getPrefix()
                                            + MsgType.MAIN_THEME_COLOR.getMessage() + p.getDisplayName()
                                            + MsgType.SECOND_THEME_COLOR.getMessage() + " is joining with "
                                            + MsgType.MAIN_THEME_COLOR.getMessage() + joiningProfile.getClient()
                                            + MsgType.SECOND_THEME_COLOR.getMessage() + " on "
                                            + MsgType.MAIN_THEME_COLOR.getMessage() + joiningProfile.getVersion().getReleaseName()));
                                }
                            }, 40L);

                            if (receiver.equals(p)) continue;

                            Profile otherProfile = Arrow.getInstance().getProfileManager().getProfile(receiver);
                            if (otherProfile == null) continue;

                            joiningProfile.getCombatData().getTrackedEntities()
                                    .put(receiver.getEntityId(), receiver.getUniqueId());

                            otherProfile.getCombatData().getTrackedEntities()
                                    .put(p.getEntityId(), p.getUniqueId());
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    cancel();
                }
            }.runTaskTimer(Arrow.getInstance().getHost(), 0L, 2L); // try immediately, then every 2 ticks until success
        }

        if (event instanceof PlayerQuitEvent) {
            Player quitting = ((PlayerQuitEvent) event).getPlayer();
            Profile quittingProfile = Arrow.getInstance().getProfileManager().getProfile(quitting);

            if (quittingProfile != null) {
                quittingProfile.getCombatData().getTrackedEntities().clear();
                VelocityClientVersionBridge.remove(quitting);
            }

            // Remove quitting player from all other players' trackedEntities
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.equals(quitting)) continue; // skip self (already offline anyway)

                Profile otherProfile = Arrow.getInstance().getProfileManager().getProfile(online);
                if (otherProfile == null) continue;

                otherProfile.getCombatData().getTrackedEntities().remove(quitting.getEntityId());
            }

           // OtherUtility.log("Removed " + quitting.getName() + " from all trackedEntities.");
        }

        if (event instanceof PlayerFishEvent) {
            if (((PlayerFishEvent) event).getState() == PlayerFishEvent.State.REEL_IN && ((PlayerFishEvent) event).getHook().getHookedEntity() instanceof Player target) {
                Profile user = Arrow.getInstance().getProfileManager().getProfile(target);
                if (user != null) {
                    user.getReelingTicks().reset();
                }
            }
        }


        if (event instanceof EntityDamageEvent) {
            Profile user = Arrow.getInstance().getProfileManager().getProfile((Player) ((EntityDamageEvent) event).getEntity());

            if (user != null) {
                if (((EntityDamageEvent) event).getCause() == EntityDamageEvent.DamageCause.FALL) {
                    user.getLastFallDamageTimer().reset();
                }

                if (((EntityDamageEvent) event).getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                    user.getLastAttackByEntityTimer().reset();
                }

                if (((EntityDamageEvent) event).getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
                    user.getLastShotByArrowTimer().reset();
                }

                if (((EntityDamageEvent) event).getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                    int ticks = user.getCombatData().getCancelTicks();
                    if (((EntityDamageEvent) event).isCancelled()) {
                        ticks += (ticks < 20 ? 1 : 0);
                    } else {
                        ticks -= (ticks > 0 ? 5 : 0);
                    }
                    user.getCombatData().setCancelTicks(ticks);
                }

            }
        }

        if (event instanceof PlayerTeleportEvent) {
            Profile user = Arrow.getInstance().getProfileManager().getProfile(((PlayerTeleportEvent) event).getPlayer());

            if (user != null) {
                if (((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN || ((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.COMMAND || ((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.END_GATEWAY || ((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT || ((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL || ((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
                    user.getLastTeleportTimer().reset();
                }

                if (((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
                    user.getLastUnknownTeleportTimer().reset();
                }

                if (((PlayerTeleportEvent) event).getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
                    if (user.getEnderPearlThrowLocation() != null
                            && user.getEnderPearlThrowLocation().getWorld().equals(user.getPlayer().getWorld())) {
                        user.setEnderPearlDistance(user.getEnderPearlThrowLocation()
                                .distance(user.getPlayer().getLocation()));
                    }
                    user.getLastEnderpearlTimer().reset();
                }
            }
        }

        if (event instanceof PlayerRespawnEvent) {
            Profile user = Arrow.getInstance().getProfileManager().getProfile(((PlayerRespawnEvent) event).getPlayer());

            if (user != null) {
                user.getSinceDeathTimer().reset();
            }
        }

        if (event instanceof BlockPlaceEvent) {
            Profile user = Arrow.getInstance().getProfileManager().getProfile(((BlockPlaceEvent) event).getPlayer());

            if (user != null) {
                user.setBlockPlaced(((BlockPlaceEvent) event).getBlockPlaced());

                if (((BlockPlaceEvent) event).getItemInHand().getType().isBlock()) {
                    if (((BlockPlaceEvent) event).isCancelled()) {
                        user.getLastBlockPlaceCancelTimer().reset();
                        return;
                    }

                    user.getLastBlockPlaceTimer().reset();
                }
            }

        }

        if (event instanceof BlockBreakEvent blockBreakEvent) {
            Profile user = Arrow.getInstance().getProfileManager().getProfile(blockBreakEvent.getPlayer());

            if (user != null) {
                user.getLastBlockBreakTimer().reset();

                for (Check check : user.getCheckHolder().getChecks()) {
                    String classname = check.getCheckName() + check.getCheckType();

                    if (classname.equals(InteractB.class.getSimpleName())) {
                        if (check.isEnabled()) {
                            Player player = user.getPlayer();
                            Block targetBlock = blockBreakEvent.getBlock();

                            if (isBedBlock(targetBlock)) {
                                BedBreakRayResult rayResult = getBedBreakRayResult(player, targetBlock, 8.0D);

                                if (!rayResult.allowed()) {
                                    blockBreakEvent.setCancelled(true);

                                    check.fail("Breaking bed through block",
                                            "eyeLocation " + MsgType.MAIN_THEME_COLOR.getMessage() + player.getEyeLocation().toVector()
                                                    + "\ntargetLocation " + MsgType.MAIN_THEME_COLOR.getMessage() + new Vector(targetBlock.getX() + 0.5D, targetBlock.getY() + 0.5D, targetBlock.getZ() + 0.5D)
                                                    + "\nhitBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + (rayResult.hitBlock() == null ? "none" : rayResult.hitBlock().getType().name())
                                                    + "\nhitLocation " + MsgType.MAIN_THEME_COLOR.getMessage() + rayResult.hitLocation()
                                                    + "\ntargetBlock " + MsgType.MAIN_THEME_COLOR.getMessage() + targetBlock.getType().name()
                                                    + "\ndistance " + MsgType.MAIN_THEME_COLOR.getMessage() + player.getEyeLocation().distance(targetBlock.getLocation().add(0.5D, 0.5D, 0.5D)));
                                }
                            }
                        }

                        break;
                    }
                }
            }
        }

        if (event instanceof InventoryClickEvent) {
            try {
                // getWhoClicked() returns HumanEntity — use reflection to avoid direct InventoryView type usage
                Object whoClicked = ((InventoryClickEvent) event).getWhoClicked();
                if (whoClicked == null) return;

                // call getOpenInventory()
                Method getOpenInv = whoClicked.getClass().getMethod("getOpenInventory");
                Object view = getOpenInv.invoke(whoClicked);
                if (view == null) return;

                // call getTitle() on the view
                Method getTitle = view.getClass().getMethod("getTitle");
                String title = (String) getTitle.invoke(view);

                if (title != null && (title.contains("Arrow") || title.contains("Player: "))) {
                    ((InventoryClickEvent) event).setCancelled(true);
                }
            } catch (ReflectiveOperationException ex) {
                // reflection failed — fallback: try event.getView().getTitle() if available
                try {
                    String title = ((InventoryClickEvent) event).getView().getTitle(); // may throw LinkageError on mismatch
                    if (title != null && (title.contains("Arrow") || title.contains("Player: "))) {
                        ((InventoryClickEvent) event).setCancelled(true);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        if (event instanceof InventoryOpenEvent) {
            Player player = (Player) ((InventoryOpenEvent) event).getPlayer();
            Profile user = Arrow.getInstance().getProfileManager().getProfile(player.getUniqueId());

            if (user != null) {
                user.getActionData().setInInventory(true);
            }
        }
        if (event instanceof InventoryCloseEvent) {
            Player player = (Player) ((InventoryCloseEvent) event).getPlayer();
            Profile user = Arrow.getInstance().getProfileManager().getProfile(player.getUniqueId());

            if (user != null) {
                user.getActionData().setInInventory(false);
            }
        }

        if (event instanceof PlayerInteractEvent) {
            Profile user = Arrow.getInstance().getProfileManager().getProfile(((PlayerInteractEvent) event).getPlayer());

            if (user != null) {

                if (((PlayerInteractEvent) event).getAction() == Action.RIGHT_CLICK_AIR
                        || ((PlayerInteractEvent) event).getAction() == Action.RIGHT_CLICK_BLOCK) {

                    if (((PlayerInteractEvent) event)
                            .getPlayer().getItemInHand().getType().equals(Material.ENDER_PEARL)) {
                        user.setEnderPearlThrowLocation(user.getPlayer().getLocation());
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        try {
            Profile profile = Arrow.getInstance().getProfileManager().getProfile(event.getPlayer());

//            if (Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
//                if (hasGroupManager()) {
//                    event.getPlayer().setDisplayName(OtherUtility.translate(getPrefix(event.getPlayer()) + event.getPlayer().getName()));
//                    event.getPlayer().setPlayerListName(OtherUtility.translate(getPrefix(event.getPlayer()) + event.getPlayer().getName()));
//                }
//            }

            TaskUtils.taskLater(() -> {
                if (Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
                    event.getPlayer().teleport(parseLocation(Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_SPAWN.getString(), Config.Setting.TEST_SERVER_MODE_WORLD.getString()), PlayerTeleportEvent.TeleportCause.PLUGIN);
                    event.getPlayer().sendMessage(translate("&7Test server mode is &cENABLED&7. You have been warned."));
                   if (Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean() && Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ITEMS.getBoolean()) {
                       giveTestItems(event.getPlayer());
                       event.getPlayer().setGameMode(GameMode.SURVIVAL);
                   }
                }
            }, (40));

        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }


    @EventHandler
    public void onBlockPlace2(BlockPlaceEvent event) {
        if (Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
            String[] corners = Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_REGION.getString().split(" \\| ");
            if (event.getPlayer().getWorld().getName().equals(Config.Setting.TEST_SERVER_MODE_WORLD.getString())
                    && event.getPlayer().getGameMode() == GameMode.SURVIVAL
                    && !isLocationInRegion(event.getBlockPlaced().getLocation(),
                    parseLocation(corners[0], Config.Setting.TEST_SERVER_MODE_WORLD.getString()),
                    parseLocation(corners[1], Config.Setting.TEST_SERVER_MODE_WORLD.getString()))) {
                event.setCancelled(true);
            }
            else if (event.getPlayer().getWorld().getName().equals(Config.Setting.TEST_SERVER_MODE_WORLD.getString())
                    && event.getPlayer().getGameMode() == GameMode.SURVIVAL
                    && Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ITEMS.getBoolean()
                    && isLocationInRegion(event.getBlockPlaced().getLocation(),
                    parseLocation(corners[0], Config.Setting.TEST_SERVER_MODE_WORLD.getString()),
                    parseLocation(corners[1], Config.Setting.TEST_SERVER_MODE_WORLD.getString()))
                    && !event.isCancelled()) {
                Player player = event.getPlayer();
                Block placedBlock = event.getBlockPlaced();

                if (placedBlock.getType() == Material.DIAMOND_BLOCK) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            placedBlock.setType(Material.AIR);
                            player.getInventory().addItem(new ItemStack(Material.DIAMOND_BLOCK, 1));
                        }
                    }.runTaskLater(Arrow.getInstance().getHost(), 100L);
                }
            }
        }
    }

    @EventHandler
    public void onItemUse(PlayerInteractEvent event) {
        if (!Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean()
                || !Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
            return;
        }

        Player player = event.getPlayer();
        String worldName = Config.Setting.TEST_SERVER_MODE_WORLD.getString();

        if (!player.getWorld().getName().equals(worldName) || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }

        String[] corners = Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_REGION.getString().split(" \\| ");
        Location corner1 = parseLocation(corners[0], worldName);
        Location corner2 = parseLocation(corners[1], worldName);

        boolean insideRegion = isLocationInRegion(player.getLocation(), corner1, corner2);
        if (insideRegion) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        Material type = item.getType();

        Material waterBucket = Material.matchMaterial("WATER_BUCKET");
        Material lavaBucket = Material.matchMaterial("LAVA_BUCKET");
        Material flintAndSteel = Material.matchMaterial("FLINT_AND_STEEL");
        Material fireCharge = Material.matchMaterial("FIRE_CHARGE");
        Material powderSnowBucket = Material.matchMaterial("POWDER_SNOW_BUCKET");

        if ((waterBucket != null && type == waterBucket)
                || (lavaBucket != null && type == lavaBucket)
                || (flintAndSteel != null && type == flintAndSteel)
                || (fireCharge != null && type == fireCharge)
                || (powderSnowBucket != null && type == powderSnowBucket)) {
            event.setCancelled(true);
        }
    }



    private void giveTestItems(Player player) {
        player.getInventory().clear();

        // Always available
        addUnbreakableItem(player, "DIAMOND_SWORD");
        addUnbreakableItem(player, "FISHING_ROD");
        addUnbreakableItemWithEnchantments(player, new String[]{"BOW"}, new String[]{"INFINITY"}, 1);

        player.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
        addItem(player, 64, "DIAMOND_BLOCK");
        addItem(player, 1, "WATER_BUCKET");
        addItem(player, 1, "LAVA_BUCKET");

        // 1.9+
        addUnbreakableItem(player, "SHIELD");
        addUnbreakableItem(player, "DIAMOND_AXE");
        addItem(player, 64, "FIREWORK_ROCKET", "FIREWORK"); // 1.8 fallback
        addUnbreakableItemWithEnchantments(player, new String[]{"ELYTRA"}, new String[]{"UNBREAKING", "DURABILITY"}, 3);

        // 1.13+
        addUnbreakableItemWithEnchantments(player, new String[]{"TRIDENT"}, new String[]{"RIPTIDE"}, 3);

        // 1.16+
        addUnbreakableItemWithEnchantments(player, new String[]{"NETHERITE_BOOTS"}, new String[]{"DEPTH_STRIDER"}, 3);

        // 1.17+
        addUnbreakableItem(player, "POWDER_SNOW_BUCKET");

        // 1.21+
        addItem(player, 64, "WIND_CHARGE");
        addUnbreakableItemWithEnchantments(player, new String[]{"MACE"}, new String[]{"WIND_BURST"}, 3);

        // 1.21.1+
        addUnbreakableItemWithEnchantments(player, new String[]{"WOODEN_SPEAR"}, new String[]{"LUNGE"}, 3);
        addUnbreakableItemWithEnchantments(player, new String[]{"NETHERITE_SPEAR"}, new String[]{"LUNGE"}, 3);
    }

    private void addItem(Player player, int amount, String... materialNames) {
        Material material = firstMaterial(materialNames);
        if (material == null) return;
        player.getInventory().addItem(new ItemStack(material, amount));
    }

    private void addUnbreakableItem(Player player, String... materialNames) {
        Material material = firstMaterial(materialNames);
        if (material == null) return;
        player.getInventory().addItem(createUnbreakableItem(material));
    }

    private void addUnbreakableItemWithEnchantments(Player player, String[] materialNames, String[] enchantNames, int level) {
        Material material = firstMaterial(materialNames);
        if (material == null) return;

        ItemStack itemStack = createUnbreakableItem(material);
        org.bukkit.inventory.meta.ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) return;

        Enchantment enchantment = firstEnchantment(enchantNames);
        if (enchantment != null) {
            itemMeta.addEnchant(enchantment, level, true);
            itemStack.setItemMeta(itemMeta);
        }

        player.getInventory().addItem(itemStack);
    }

    private Material firstMaterial(String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) return material;
        }
        return null;
    }

    private Enchantment firstEnchantment(String... names) {
        for (String name : names) {
            Enchantment enchantment = Enchantment.getByName(name);
            if (enchantment != null) return enchantment;
        }
        return null;
    }
    @EventHandler
    public void onCrossbowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        // Check if it’s your custom “Infinity Crossbow”
        if (Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean() && Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ITEMS.getBoolean()) {
            event.setConsumeItem(false); // prevents arrow consumption
        }
    }


    @EventHandler
    public void onBlockBreak2(BlockBreakEvent event) {
        if (Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
            String[] corners = Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_REGION.getString().split(" \\| ");
            if (event.getPlayer().getWorld().getName().equals(Config.Setting.TEST_SERVER_MODE_WORLD.getString())
                    && event.getPlayer().getGameMode() == GameMode.SURVIVAL
                    && !isLocationInRegion(event.getBlock().getLocation(),
                    parseLocation(corners[0], Config.Setting.TEST_SERVER_MODE_WORLD.getString()),
                    parseLocation(corners[1], Config.Setting.TEST_SERVER_MODE_WORLD.getString()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player){
            try {
//                User user = Sonix.getInstance().getUserManager().getUser((Player) event.getEntity());
//                if (user.getSetbackHandler().getReduce().get(((Player) event.getEntity()).getPlayer()) != null && user.getSetbackHandler().reduce.get(((Player) event.getEntity()).getPlayer()))
//                    event.setDamage(event.getDamage() * 0.25);

                if (event.getDamager() instanceof Player) {
                    if (Config.Setting.TEST_SERVER_MODE_PREVENT_DAMAGE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
                        if (event.getDamager().getWorld().getName().equals(Config.Setting.TEST_SERVER_MODE_WORLD.getString()))
                            event.setDamage(0.0);
                    }
                }
                if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                    if (Config.Setting.TEST_SERVER_MODE_PREVENT_DAMAGE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
                        if (event.getDamager().getWorld().getName().equals(Config.Setting.TEST_SERVER_MODE_WORLD.getString()))
                            event.setDamage(0.0);
                    }
                }
            } catch (Exception ignored) {

            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void Damage(EntityDamageEvent event) {
        if (event.getEntity().getType() == EntityType.PLAYER && event.getCause() != EntityDamageEvent.DamageCause.FALL){
            if (Config.Setting.TEST_SERVER_MODE_PREVENT_DAMAGE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()){
                if (event.getEntity().getWorld().equals(Bukkit.getWorld(Config.Setting.TEST_SERVER_MODE_WORLD.getString())))
                    event.setDamage(0.0);
            }
        }
        if (event.getEntity() instanceof Player && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (Config.Setting.TEST_SERVER_MODE_PREVENT_DAMAGE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
                if (event.getEntity().getWorld().equals(Bukkit.getWorld(Config.Setting.TEST_SERVER_MODE_WORLD.getString()))){
                    event.getEntity().sendMessage(translate("&7You would have taken &c" + event.getDamage() + " &7fall damage."));
                    event.setDamage(0.0);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerPing(ServerListPingEvent event) {

        if (Config.Setting.TEST_SERVER_MODE_ENABLED_MOTD.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
            event.setMotd(translate("&6Arrow AntiCheat &7test server mode\n&7This server is now set up to test &6Arrow"));
        }
    }

    @EventHandler
    public void onRocketUse(PlayerInteractEvent event) {
        if (!Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean()
                || !Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()
                || !Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ITEMS.getBoolean()) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Material rocketMaterial = getMaterial("FIREWORK_ROCKET", "FIREWORK");
        if (rocketMaterial == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != rocketMaterial) return;

        Player player = event.getPlayer();
        int slot = player.getInventory().getHeldItemSlot();

        TaskUtils.taskLater(() -> {
            if (!player.isOnline()) return;

            ItemStack inHand = player.getInventory().getItem(slot);
            if (inHand == null || inHand.getType() != rocketMaterial) return;

            inHand.setAmount(64);
            player.getInventory().setItem(slot, inHand);
            player.updateInventory();
        }, 1L);
    }

    @EventHandler
    public void onWindChargeUse(PlayerInteractEvent event) {
        if (!Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean()
                || !Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()
                || !Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ITEMS.getBoolean()) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Material windChargeMaterial = getMaterial("WIND_CHARGE");
        if (windChargeMaterial == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != windChargeMaterial) return;

        Player player = event.getPlayer();
        int slot = player.getInventory().getHeldItemSlot();

        TaskUtils.taskLater(() -> {
            if (!player.isOnline()) return;

            ItemStack inHand = player.getInventory().getItem(slot);
            if (inHand == null || inHand.getType() != windChargeMaterial) return;

            inHand.setAmount(64);
            player.getInventory().setItem(slot, inHand);
            player.updateInventory();
        }, 1L);
    }


    private Material getMaterial(String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) return material;
        }
        return null;
    }

    @EventHandler
    public void onArrowUse(PlayerItemConsumeEvent event) {
        if (Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean() && Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ITEMS.getBoolean()) {
            Player player = event.getPlayer();
            // Ensure slot 9 always has 1 arrow
            ItemStack slot = player.getInventory().getItem(9);
            if (slot == null || slot.getType() != Material.ARROW || slot.getAmount() != 1) {
                player.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
            }
        }
    }


    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (Config.Setting.TEST_SERVER_MODE_PREVENT_DAMAGE.getBoolean() && Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
            if (event.getEntity() instanceof org.bukkit.entity.Player) {
                event.setCancelled(true);
                try {
                    event.getEntity().setFoodLevel(20);
                    event.getEntity().setSaturation(20);
                } catch (NoSuchMethodError ignored) {

                }
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!isTestServerBuildZoneEnabled()) return;

        event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!isTestServerBuildZoneEnabled()) return;
        event.blockList().clear();
    }



    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!isTestServerBuildZoneEnabled()) return;

        if (event.getHitBlock() != null && event.getHitBlock().getType() == Material.DECORATED_POT) {
            event.setCancelled(true);
        }
    }

    private boolean isTestServerBuildZoneEnabled() {
        return Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean() &&
                Config.Setting.TEST_SERVER_MODE_BUILD_ZONE_ENABLE.getBoolean();
    }

    private BedBreakRayResult getBedBreakRayResult(Player player, Block targetBlock, double maxDistance) {
        if (player == null || targetBlock == null || targetBlock.getWorld() == null) {
            return new BedBreakRayResult(false, null, null);
        }

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        if (direction.lengthSquared() <= 0.0D) {
            return new BedBreakRayResult(false, null, null);
        }

        direction.normalize();

        BedBreakRayResult precise = getPreciseBukkitRayResult(player, targetBlock, eye, direction, maxDistance);

        if (precise != null) {
            return precise;
        }

        if (targetBlock.getType().isAir()) return new BedBreakRayResult(true, null, null);

        return getFallbackRayResult(player, targetBlock, eye, direction, maxDistance);
    }

    private BedBreakRayResult getPreciseBukkitRayResult(Player player, Block targetBlock, Location eye, Vector direction, double maxDistance) {
        try {
            World world = player.getWorld();

            Class<?> fluidCollisionModeClass = Class.forName("org.bukkit.FluidCollisionMode");
            Object never = Enum.valueOf((Class<Enum>) fluidCollisionModeClass.asSubclass(Enum.class), "NEVER");

            java.lang.reflect.Method rayTraceBlocks = world.getClass().getMethod(
                    "rayTraceBlocks",
                    Location.class,
                    Vector.class,
                    double.class,
                    fluidCollisionModeClass,
                    boolean.class
            );

            Object result = rayTraceBlocks.invoke(world, eye, direction, maxDistance, never, true);

            if (result == null) {
                return new BedBreakRayResult(false, null, null);
            }

            java.lang.reflect.Method getHitBlock = result.getClass().getMethod("getHitBlock");
            java.lang.reflect.Method getHitPosition = result.getClass().getMethod("getHitPosition");

            Object hitBlockObject = getHitBlock.invoke(result);
            Object hitPositionObject = getHitPosition.invoke(result);

            Block hitBlock = hitBlockObject instanceof Block ? (Block) hitBlockObject : null;
            Vector hitPosition = hitPositionObject instanceof Vector ? (Vector) hitPositionObject : null;

            boolean allowed = isAllowedBedHit(hitBlock, targetBlock);

            return new BedBreakRayResult(allowed, hitBlock, hitPosition);
        } catch (Throwable ignored) {
        }

        try {
            World world = player.getWorld();

            java.lang.reflect.Method rayTraceBlocks = world.getClass().getMethod(
                    "rayTraceBlocks",
                    Location.class,
                    Vector.class,
                    double.class
            );

            Object result = rayTraceBlocks.invoke(world, eye, direction, maxDistance);

            if (result == null) {
                return new BedBreakRayResult(false, null, null);
            }

            java.lang.reflect.Method getHitBlock = result.getClass().getMethod("getHitBlock");
            java.lang.reflect.Method getHitPosition = result.getClass().getMethod("getHitPosition");

            Object hitBlockObject = getHitBlock.invoke(result);
            Object hitPositionObject = getHitPosition.invoke(result);

            Block hitBlock = hitBlockObject instanceof Block ? (Block) hitBlockObject : null;
            Vector hitPosition = hitPositionObject instanceof Vector ? (Vector) hitPositionObject : null;

            boolean allowed = isAllowedBedHit(hitBlock, targetBlock);

            return new BedBreakRayResult(allowed, hitBlock, hitPosition);
        } catch (Throwable ignored) {
        }

        return null;
    }

    private BedBreakRayResult getFallbackRayResult(Player player, Block targetBlock, Location eye, Vector direction, double maxDistance) {
        World world = player.getWorld();

        double step = 0.025D;
        Block lastBlock = null;

        for (double travelled = 0.0D; travelled <= maxDistance; travelled += step) {
            double x = eye.getX() + direction.getX() * travelled;
            double y = eye.getY() + direction.getY() * travelled;
            double z = eye.getZ() + direction.getZ() * travelled;

            Block block = world.getBlockAt(floor(x), floor(y), floor(z));

            if (block.equals(lastBlock)) {
                continue;
            }

            lastBlock = block;

            if (isIgnoredRayBlock(block)) {
                continue;
            }

            boolean allowed = isAllowedBedHit(block, targetBlock);
            return new BedBreakRayResult(allowed, block, new Vector(x, y, z));
        }

        return new BedBreakRayResult(false, null, null);
    }

    private boolean isAllowedBedHit(Block hitBlock, Block targetBlock) {
        if (hitBlock == null || targetBlock == null) {
            return false;
        }

        if (targetBlock.getType().name().equals(MaterialType.AIR.name())
                || hitBlock.getType().name().equals(MaterialType.AIR.name())
        ) return true;

        if (hitBlock.equals(targetBlock)) {
            return true;
        }

        if (!isBedBlock(hitBlock) || !isBedBlock(targetBlock)) {
            return false;
        }

        if (!hitBlock.getWorld().equals(targetBlock.getWorld())) {
            return false;
        }



        return hitBlock.getLocation().distanceSquared(targetBlock.getLocation()) <= 2.01D;
    }

    private boolean isIgnoredRayBlock(Block block) {
        if (block == null) {
            return true;
        }

        Material type = block.getType();

        if (type == Material.AIR) {
            return true;
        }

        String name = type.name();

        if (name.equals("CAVE_AIR") || name.equals("VOID_AIR")) {
            return true;
        }

        if (name.equals("LIGHT") || name.equals("STRUCTURE_VOID")) {
            return true;
        }

        if (block.isLiquid()) {
            return true;
        }

        if (name.contains("TALL_GRASS")
                || name.equals("GRASS")
                || name.contains("FLOWER")
                || name.contains("SAPLING")
                || name.contains("MUSHROOM")
                || name.contains("CARPET")
                || name.contains("TORCH")
                || name.contains("BUTTON")
                || name.contains("PRESSURE_PLATE")
                || name.contains("SIGN")
                || name.contains("BANNER")) {
            return true;
        }

        return false;
    }

    private int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static final class BedBreakRayResult {
        private final boolean allowed;
        private final Block hitBlock;
        private final Vector hitLocation;

        private BedBreakRayResult(boolean allowed, Block hitBlock, Vector hitLocation) {
            this.allowed = allowed;
            this.hitBlock = hitBlock;
            this.hitLocation = hitLocation;
        }

        private boolean allowed() {
            return allowed;
        }

        private Block hitBlock() {
            return hitBlock;
        }

        private Vector hitLocation() {
            return hitLocation;
        }
    }

    private boolean isBedBlock(Block block) {
        if (block == null) {
            return false;
        }

        String name = block.getType().name();

        return name.equals("BED_BLOCK")
                || name.equals("BED")
                || name.endsWith("_BED");
    }
}