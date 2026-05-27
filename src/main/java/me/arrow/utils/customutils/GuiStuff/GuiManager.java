package me.arrow.utils.customutils.GuiStuff;


import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.arrow.Arrow;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.managers.logs.PlayerLog;
import me.arrow.managers.profile.Profile;
import me.arrow.tasks.TickTask;
import me.arrow.utils.custom.MaterialType;
import me.arrow.utils.customutils.OtherUtility;
import me.arrow.utils.customutils.animationSystem.Animation;
import me.arrow.utils.customutils.animationSystem.BanAnimationGuiLayout;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.UUID;
import static me.arrow.utils.customutils.GuiStuff.GuiUtility.*;
import static me.arrow.utils.customutils.OtherUtility.guiLine;
import static me.arrow.utils.customutils.OtherUtility.translate;

// these are our GUIs we need to create a player owner on them to prevent pulling a vulcan, as vanilla chests have null owners

public class GuiManager {
    private final Map<UUID, BukkitTask> infoGuiTasks = new ConcurrentHashMap<>();


    public void openArrowGUI(Player player) {
        Inventory gui = Bukkit.createInventory(player, 27, translate(MsgType.MAIN_THEME_COLOR.getMessage()+"Arrow &7- &7Main Menu"));

        gui.setItem(11, generateItem(new ItemStack(Material.BOOK, 1), translate(MsgType.MAIN_THEME_COLOR.getMessage()+"Checks"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7View the checks"),
                        translate(guiLine())
                )));

        gui.setItem(13, generateItem(new ItemStack(Material.REDSTONE, 1),
                translate(MsgType.MAIN_THEME_COLOR.getMessage()+ "Info"), Arrays.asList(
                        translate(guiLine()),
                        translate("&7Version: "+MsgType.MAIN_THEME_COLOR.getMessage()+ "b" + Arrow.getInstance().getVersion()),
                        translate(ChatColor.GRAY + "TPS: " + MsgType.MAIN_THEME_COLOR.getMessage() + TickTask.getTPS()),
                        "",
                        translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7has "+MsgType.MAIN_THEME_COLOR.getMessage()+ Arrow.getInstance().getProfileManager().getProfile(player).getCheckHolder().getChecksSize() + " &7different checks"),
                        "",
                        translate("&7Server Version: "+MsgType.MAIN_THEME_COLOR.getMessage() + PacketEvents.getAPI().getServerManager().getVersion().getReleaseName()),
                        translate(guiLine())
                )));

        if (player.hasPermission(Permissions.ADMIN.getPermission())) {
            gui.setItem(15, generateItem(new ItemStack(Material.LEVER, 1),
                    translate(MsgType.MAIN_THEME_COLOR.getMessage()+ "Settings"), Arrays.asList(
                            translate(guiLine()),
                            translate("&7Change anticheat settings"),
                            translate(guiLine())
                    )));
        } else {
            gui.setItem(15, generateItem(new ItemStack(Material.LEVER, 1),
                    translate(MsgType.MAIN_THEME_COLOR.getMessage()+ "Settings"), Arrays.asList(
                            translate(guiLine()),
                            translate("&7Change anticheat settings"),
                            "",
                            translate("&c&oRequires arrow.admin permissions"),
                            translate(guiLine())
                    )));
        }



        ItemStack spacer = createSpacer();

        for (int slots = 0; slots < 27; slots++) {
            if (gui.getItem(slots) == null) gui.setItem(slots, spacer);
        }

        player.openInventory(gui);
    }

    public void openPlayerInfoGUI(Player player, Player playerForInfo) {
        if (player == null || playerForInfo == null) {
            return;
        }

        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        String title = translate("&7Info for " + MsgType.MAIN_THEME_COLOR.getMessage() + playerForInfo.getName());

        Inventory gui = Bukkit.createInventory(player, 27, title);
        Profile playerInfo = Arrow.getInstance().getProfileManager().getProfile(playerForInfo);

        if (playerInfo == null) {
            player.sendMessage(translate("&cCould not find profile for that player."));
            return;
        }

        gui.setItem(11, generateItem(
                new ItemStack(Material.DIAMOND_SWORD, 1),
                translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Combat"),
                new ArrayList<>(),
                true
        ));

        ItemStack skullItem = GuiUtility.createPlayerHeadItem(serverVersion);

        gui.setItem(13, generateSkull(
                skullItem,
                translate(playerForInfo.getDisplayName() + MsgType.MAIN_THEME_COLOR.getMessage() + "'s info."),
                new ArrayList<>(),
                playerForInfo.getUniqueId()
        ));

        gui.setItem(15, generateItem(
                new ItemStack(Material.REDSTONE, 1),
                translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Extra Debug"),
                new ArrayList<>()
        ));

        ItemStack spacer = createSpacer();

        for (int slot = 0; slot < 27; slot++) {
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, spacer);
            }
        }

        java.util.function.BiConsumer<Integer, List<String>> updateLore = (slot, lore) -> {
            ItemStack item = gui.getItem(slot);

            if (item == null || item.getType() == Material.AIR) {
                return;
            }

            ItemMeta meta = item.getItemMeta();

            if (meta == null) {
                return;
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
            gui.setItem(slot, item);
        };

        Runnable refresh = () -> {
            if (playerForInfo == null || !playerForInfo.isOnline()) {
                return;
            }

            Profile info = Arrow.getInstance().getProfileManager().getProfile(playerForInfo);

            if (info == null) {
                return;
            }

            List<PlayerLog> logs = Arrow.getInstance().getLogManager()
                    .getLogExporter()
                    .getLogsForPlayer(playerForInfo.getName());

            updateLore.accept(11, Arrays.asList(
                    translate(guiLine()),
                    translate("&7AVG CPS: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getCombatData().getAverageCps()),
                    translate("&7CPS: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getCombatData().getCurrentCps()),
                    "",
                    translate("&7Sensitivity: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getRotationData().getSensitivityProcessor().getSensitivityPercent()),
                    translate("&7Attack Cooldown: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getAttackCooldown()),
                    "",
                    translate("&7Attack Distance: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getReachDistance()),
                    "",
                    translate("&7Velocity H: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getVelocityData().getTotalHorizontalVelocity()),
                    translate("&7Velocity V: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getVelocityData().getTotalVerticalVelocity()),
                    "",
                    translate("&7Velocity H Sustain: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getVelocityData().getTotalHorizontalVelocitySustain()),
                    translate("&7Velocity V Sustain: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getVelocityData().getTotalVerticalVelocitySustain()),
                    "",
                    translate("&7Velocity H Stacked: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getVelocityData().getStackedHorizontalVelocity()),
                    translate("&7Velocity V Stacked: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getVelocityData().getStackedVerticalVelocity()),
                    translate(guiLine())
            ));

            updateLore.accept(13, Arrays.asList(
                    translate(guiLine()),
                    translate("&7Version: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getVersion().getReleaseName()),
                    translate("&7Client: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getClient()),
                    translate("&7Total Logs: " + MsgType.MAIN_THEME_COLOR.getMessage() + logs.size()),
                    "",
                    translate("&7Trust Factor: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getTrustFactor().getRank()),
                    "",
                    translate("&7Ping: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getConnectionData().getTransPing()),
                    translate("&7DropTick: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getConnectionData().getTransDropTick()),
                    translate("&7Avg Ping: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getConnectionData().getAverageTransactionPing()),
                    "",
                    translate("&7is Bedrock: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isBedrockPlayer()),
                    "",
                    translate("&7is Sneaking (NMS): " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isSneaking()),
                    translate("&7is Swimming (NMS): " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isSwimming()),
                    translate("&7is Sleeping (NMS): " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isSleeping()),
                    translate("&7is Crawling (NMS): " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isCrawling()),
                    "",
                    translate("&7UUID: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getUUID()),
                    translate(guiLine())
            ));

            List<String> extraDebugLore = new ArrayList<>();

            extraDebugLore.add(translate(guiLine()));
            extraDebugLore.add(translate("&7BoundingBox: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getBoundingBox()));

            if (info.getMovementData() != null && info.getMovementData().getLocation() != null) {
                extraDebugLore.add(translate("&7Location: "));
                extraDebugLore.add(translate(" &7X: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getLocation().getX()));
                extraDebugLore.add(translate(" &7Y: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getLocation().getY()));
                extraDebugLore.add(translate(" &7Z: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getLocation().getZ()));
                if (info.getMovementData().getLocation().getWorld() != null) {
                    extraDebugLore.add(translate(" &7World: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getLocation().getWorld().getName()));
                }
                extraDebugLore.add(translate(" &7Pitch: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getLocation().getPitch()));
                extraDebugLore.add(translate(" &7Yaw: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getLocation().getYaw()));
            }
            extraDebugLore.add(translate("&7Elytra: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isWearingFunctionalElytra()));
            extraDebugLore.add(translate("&7Air Bridging: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isAirBridging(info.getPlayer().getLocation())));
            extraDebugLore.add(translate("&7Ghost Block: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isOnGhostBlock()));
            extraDebugLore.add(translate("&7Teleporting: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.isExempt().isTeleports()));
            extraDebugLore.add("");
            extraDebugLore.add(translate("&7Moving: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().isMoving()));
            extraDebugLore.add(translate("&7DeltaXZ: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getDeltaXZ()));
            extraDebugLore.add(translate("&7DeltaY: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getDeltaY()));
            extraDebugLore.add(translate("&7Predict Up Ticks: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getSincePredictUpwardsTicks()));
            extraDebugLore.add(translate("&7Predict Down Ticks: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().getSincePredictDownwardsTicks()));
            extraDebugLore.add(translate("&7In Air: " + MsgType.MAIN_THEME_COLOR.getMessage() + info.getMovementData().isCustomInAir()));
            extraDebugLore.add("");

            try {
                AttributeInstance attribute = playerForInfo.getAttribute(Attribute.MOVEMENT_SPEED);

                if (attribute != null) {
                    extraDebugLore.add(translate("&7Movement Attribute:"));
                    extraDebugLore.add(translate(" &7Base: " + MsgType.MAIN_THEME_COLOR.getMessage() + attribute.getBaseValue()));
                    extraDebugLore.add(translate(" &7Value: " + MsgType.MAIN_THEME_COLOR.getMessage() + attribute.getValue()));
                    extraDebugLore.add("");
                }
            } catch (Throwable ignored) {
            }

            extraDebugLore.add(translate("&7Potion Effects:"));

            if (playerForInfo.getActivePotionEffects().isEmpty()) {
                extraDebugLore.add(translate(" &cNone"));
            } else {
                for (PotionEffect effect : playerForInfo.getActivePotionEffects()) {
                    String name = effect.getType().getName();
                    int level = effect.getAmplifier() + 1;
                    int durationSeconds = effect.getDuration() / 20;

                    extraDebugLore.add(translate(" &7- " + MsgType.MAIN_THEME_COLOR.getMessage()
                            + name
                            + " &7Level: " + MsgType.MAIN_THEME_COLOR.getMessage() + level
                            + " &7Time: " + MsgType.MAIN_THEME_COLOR.getMessage() + durationSeconds + "s"));
                }
            }

            extraDebugLore.add(translate(guiLine()));

            updateLore.accept(15, extraDebugLore);
        };

        refresh.run();

        player.openInventory(gui);

        UUID viewerUuid = player.getUniqueId();

        BukkitTask oldTask = infoGuiTasks.remove(viewerUuid);

        if (oldTask != null) {
            oldTask.cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(Arrow.getInstance().getHost(), () -> {
            Player onlineViewer = Bukkit.getPlayer(viewerUuid);

            if (onlineViewer == null || !onlineViewer.isOnline()) {
                BukkitTask currentTask = infoGuiTasks.remove(viewerUuid);

                if (currentTask != null) {
                    currentTask.cancel();
                }

                return;
            }

            if (!playerForInfo.isOnline()) {
                BukkitTask currentTask = infoGuiTasks.remove(viewerUuid);

                if (currentTask != null) {
                    currentTask.cancel();
                }

                return;
            }

            if (onlineViewer.getOpenInventory() == null
                    || onlineViewer.getOpenInventory().getTopInventory() == null
                    || !onlineViewer.getOpenInventory().getTopInventory().equals(gui)) {
                BukkitTask currentTask = infoGuiTasks.remove(viewerUuid);

                if (currentTask != null) {
                    currentTask.cancel();
                }

                return;
            }

            refresh.run();
        }, 1L, 1L);

        infoGuiTasks.put(viewerUuid, task);
    }


    public void openSettingsGUI(Player player) {
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        Inventory gui = Bukkit.createInventory(player, 45, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7Settings"));

        ItemStack alertsOnJoinItem = createToggleWool(Config.Setting.TOGGLE_ALERTS_ON_JOIN.getBoolean(), serverVersion);
        gui.setItem(10, generateItem(alertsOnJoinItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Alerts on Join"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould we enable alerts for admins when they join?"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.TOGGLE_ALERTS_ON_JOIN.getBoolean()),
                        translate(guiLine())
                )));

        ItemStack alertConsoleItem = createToggleWool(Config.Setting.CHECK_SETTINGS_ALERT_CONSOLE.getBoolean(), serverVersion);
        gui.setItem(12, generateItem(alertConsoleItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Alert Console"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould we also send alerts in console?"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.CHECK_SETTINGS_ALERT_CONSOLE.getBoolean()),
                        translate(guiLine())
                )));

        ItemStack logsItem = createToggleWool(Config.Setting.LOGS_ENABLED.getBoolean(), serverVersion);
        gui.setItem(14, generateItem(logsItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Logs"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould we enable logging?"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.LOGS_ENABLED.getBoolean()),
                        translate(guiLine())
                )));

        ItemStack punishItem = createToggleWool(Config.Setting.PUNISH_ENABLED.getBoolean(), serverVersion);
        gui.setItem(16, generateItem(punishItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Punishments"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould we punish players for cheating?"),
                        translate("&7&oCommand: " + Config.Setting.PUNISH_COMMAND.getString()),
                        translate("&7&o(Must be Changed from the config)"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.PUNISH_ENABLED.getBoolean()),
                        translate(guiLine())
                )));

        ItemStack testModeItem = createToggleWool(Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean(), serverVersion);
        gui.setItem(28, generateItem(testModeItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Test Server Mode"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould we enable the test server mode?"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()),
                        translate(guiLine())
                )));

        ItemStack debugItem = createToggleWool(Config.Setting.DEBUG.getBoolean(), serverVersion);
        gui.setItem(30, generateItem(debugItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Debug Mode"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould we enable the debug mode?"),
                        translate("&7DO NOT ENABLE THIS UNLESS TOLD BY AN ANTICHEAT ADMIN"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.DEBUG.getBoolean()),
                        translate(guiLine())
                )));

        ItemStack bypassItem = createToggleWool(Config.Setting.IGNORE_BEDROCK.getBoolean(), serverVersion);
        gui.setItem(32, generateItem(bypassItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Ignore Bedrock"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould we ignore bedrock players?"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.IGNORE_BEDROCK.getBoolean()),
                        translate(guiLine())
                )));

        ItemStack animationItem = createToggleWool(Config.Setting.BAN_ANIMATION_ENABLED.getBoolean(), serverVersion);
        gui.setItem(34, generateItem(animationItem, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Ban Animations"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7&oShould punishments use ban animations?"),
                        "",
                        translate("&7Current Setting: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.BAN_ANIMATION_ENABLED.getBoolean()),
                        translate("&7Current Animation: " + MsgType.MAIN_THEME_COLOR.getMessage() + Config.Setting.BAN_ANIMATION_CURRENT.getString()),
                        "",
                        translate("&aLeft Click &7to toggle animations."),
                        translate("&eRight Click &7to select animation style."),
                        translate(guiLine())
                )));

        gui.setItem(40, generateItem(new ItemStack(Material.BARRIER, 1), translate("&cBack"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7Click to go back."),
                        translate(guiLine())
                )));

        ItemStack spacer = createSpacer();

        for (int slots = 0; slots < 45; slots++) {
            if (gui.getItem(slots) == null) gui.setItem(slots, spacer);
        }

        player.openInventory(gui);
    }

    public void openBanAnimationGUI(Player player) {
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        Inventory gui = Bukkit.createInventory(player, 54, translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7Ban Animations"));

        ItemStack spacer = createSpacer();

        for (int slot = 0; slot < 54; slot++) {
            int row = slot / 9;
            int column = slot % 9;

            if (row == 0 || row == 5 || column == 0 || column == 8) {
                gui.setItem(slot, spacer);
            }
        }

        Animation.Type currentType = getCurrentBanAnimationType();

        for (Map.Entry<Integer, Animation.Type> entry : BanAnimationGuiLayout.getAnimationSlots().entrySet()) {
            setAnimationItem(gui, serverVersion, entry.getKey(), entry.getValue(), currentType);
        }

        gui.setItem(49, generateItem(new ItemStack(Material.BARRIER, 1), translate("&cBack"),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7Click to go back to settings."),
                        translate(guiLine())
                )));

        for (int slot = 0; slot < 54; slot++) {
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, spacer);
            }
        }

        player.openInventory(gui);
    }

    private void setAnimationItem(Inventory gui, ServerVersion serverVersion, int slot, Animation.Type type, Animation.Type currentType) {
        boolean active = type == currentType;

        ItemStack item = createToggleWool(active, serverVersion);

        gui.setItem(slot, generateItem(item, translate((active ? "&a" : "&c") + formatAnimationName(type)),
                Arrays.asList(
                        translate(guiLine()),
                        translate("&7Status: " + (active ? "&aActive" : "&cInactive")),
                        "",
                        translate("&7Animation ID: " + MsgType.MAIN_THEME_COLOR.getMessage() + type.name()),
                        "",
                        translate("&eClick &7to select this animation."),
                        translate(guiLine())
                )));
    }

    private Animation.Type getCurrentBanAnimationType() {
        try {
            return Animation.Type.valueOf(Config.Setting.BAN_ANIMATION_CURRENT.getString().toUpperCase());
        } catch (Exception ignored) {
            return Animation.Type.DESTROYED;
        }
    }

    private String formatAnimationName(Animation.Type type) {
        String name = type.name().toLowerCase().replace("_", " ");
        StringBuilder builder = new StringBuilder();

        for (String part : name.split(" ")) {
            if (part.isEmpty()) continue;

            builder.append(Character.toUpperCase(part.charAt(0)));

            if (part.length() > 1) {
                builder.append(part.substring(1));
            }

            builder.append(" ");
        }

        return builder.toString().trim();
    }


    public void openChecksGUI(Player player) {
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        Inventory gui = Bukkit.createInventory(player, 27, OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7Select Category"));

        gui.setItem(10, GuiUtility.generateItem(new ItemStack(Material.IRON_SWORD, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Combat"),
            Arrays.asList(
                OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the combat checks"),
                OtherUtility.translate(OtherUtility.guiLine())
            ), true));

        gui.setItem(13, GuiUtility.generateItem(new ItemStack(Material.SUGAR, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Movement"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the combat checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        gui.setItem(16, GuiUtility.generateItem(new ItemStack(serverVersion.isNewerThanOrEquals(ServerVersion.V_1_13) ? Material.REDSTONE_TORCH : Material.getMaterial(MaterialType.REDSTONE_TORCH_ON.name()), 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Misc"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the misc checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        gui.setItem(22, GuiUtility.generateItem(new ItemStack(Material.BARRIER, 1), OtherUtility.translate("&cBack"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Click to go back."),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        ItemStack spacer = GuiUtility.createSpacer();

        for (int slots = 0; slots < 27; slots++) {
            if (gui.getItem(slots) == null)
                gui.setItem(slots, spacer);
        }
        player.openInventory(gui);
    }

    public void openCombatChecksGUI(Player player) {
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        Inventory gui = Bukkit.createInventory(player, 27, OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7Combat Checks"));

        gui.setItem(10, GuiUtility.generateItem(new ItemStack(Material.NETHER_STAR, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Aim Assist"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the aim assist checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        gui.setItem(11, GuiUtility.generateItem(new ItemStack(Material.BLAZE_ROD, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "BackTrack"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the backtrack checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));

        gui.setItem(12, GuiUtility.generateItem(new ItemStack(Material.LEVER, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "AutoClicker"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the autoclicker checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        gui.setItem(13, GuiUtility.generateItem(new ItemStack(Material.WOODEN_SWORD, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Hitbox"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the hitbox checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));


        gui.setItem(14, GuiUtility.generateItem(new ItemStack(Material.STICK, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Reach"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the reach checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        gui.setItem(15, GuiUtility.generateItem(new ItemStack(Material.COBWEB, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Velocity"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the velocity checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));

        gui.setItem(16, GuiUtility.generateItem(new ItemStack(Material.DIAMOND_SWORD, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "KillAura"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the killaura checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                ), true));

        gui.setItem(22, GuiUtility.generateItem(new ItemStack(Material.BARRIER, 1), OtherUtility.translate("&cBack"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Click to go back."),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        ItemStack spacer = GuiUtility.createSpacer();

        for (int slots = 0; slots < 27; slots++) {
            if (gui.getItem(slots) == null)
                gui.setItem(slots, spacer);
        }
        player.openInventory(gui);
    }


    public void openMovementChecksGUI(Player player) {
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        Inventory gui = Bukkit.createInventory(player, 27, OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7Movement Checks"));

        gui.setItem(10, GuiUtility.generateItem(new ItemStack(Material.FEATHER, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Fly"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the fly checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));
        gui.setItem(11, GuiUtility.generateItem(new ItemStack(Material.SUGAR, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Speed"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the speed checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));
        gui.setItem(12, GuiUtility.generateItem(new ItemStack(Material.RABBIT_FOOT, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Motion"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the motion checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));
        gui.setItem(13, GuiUtility.generateItem(new ItemStack(Material.BEACON, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Analysis"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the analysis checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));
        gui.setItem(14, GuiUtility.generateItem(new ItemStack(Material.ANVIL, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Ground"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the ground checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));
        gui.setItem(15, GuiUtility.generateItem(new ItemStack(serverVersion.isNewerThanOrEquals(ServerVersion.V_1_13) ? Material.ELYTRA : Material.BARRIER, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Elytra"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the elytra checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));

        gui.setItem(22, GuiUtility.generateItem(new ItemStack(Material.BARRIER, 1), OtherUtility.translate("&cBack"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Click to go back."),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));

        ItemStack spacer = GuiUtility.createSpacer();

        for (int slots = 0; slots < 27; slots++) {
            if (gui.getItem(slots) == null) gui.setItem(slots, spacer);

        }
        player.openInventory(gui);
    }

    public void openMiscChecksGUI(Player player) {
        Inventory gui = Bukkit.createInventory(player, 27, OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7Misc Checks"));

        gui.setItem(10, GuiUtility.generateItem(new ItemStack(Material.TNT, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Bad Packets"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the bad packets checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));
        gui.setItem(11, GuiUtility.generateItem(new ItemStack(Material.GRASS_BLOCK, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Interact"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the interact checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));
        gui.setItem(12, GuiUtility.generateItem(new ItemStack(Material.CHEST, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Inventory"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the inventory checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));
        gui.setItem(13, GuiUtility.generateItem(new ItemStack(Material.SUGAR, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "No Slowdown"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the no slowdown checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));
        gui.setItem(14, GuiUtility.generateItem(new ItemStack(Material.SANDSTONE_STAIRS, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Scaffold"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the scaffold checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));
        gui.setItem(15, GuiUtility.generateItem(new ItemStack(Material.REDSTONE, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Timer"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Manage the timer checks"),
                OtherUtility.translate(OtherUtility.guiLine())
        )));
        gui.setItem(16, GuiUtility.generateItem(new ItemStack(Material.DIAMOND_AXE, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Macro"),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&7Manage the Macro checks"),
                        OtherUtility.translate(OtherUtility.guiLine())
                )));

        gui.setItem(22, GuiUtility.generateItem(new ItemStack(Material.BARRIER, 1), OtherUtility.translate("&cBack"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Click to go back."),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        ItemStack spacer = GuiUtility.createSpacer();

        for (int slots = 0; slots < 27; slots++) {
            if (gui.getItem(slots) == null)
                gui.setItem(slots, spacer);
        }
        player.openInventory(gui);
    }


    public void openArrowCheckGUI(Player player, String checkType, String uiName) {
        Inventory gui = Bukkit.createInventory(player, 27, OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7" + uiName));

        int slot = 0;

        for (Check check : Arrow.getInstance().getProfileManager().getProfile(player).getCheckHolder().getChecks()) {
            if (check.getClass().getSimpleName().startsWith(checkType)) {
                String enabledStatus = check.isEnabled() ? "§a✓" : "§c✗";
                String punishableStatus = check.isCanPunish() ? "§a✓" : "§c✗";

                Material bookMaterial = check.isEnabled() ? Material.ENCHANTED_BOOK : Material.BOOK;

                gui.setItem(slot, GuiUtility.generateItem(new ItemStack(bookMaterial, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + check.getFullCheckName()),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&fEnabled: " + enabledStatus),
                        OtherUtility.translate("&fPunishable: " + punishableStatus),
                        OtherUtility.translate("&fPunish VL: &b" + check.getMaxVl()),
                        OtherUtility.translate("&fPunish Mode: &b" + check.getPunishMode()),
                        OtherUtility.translate("&fMode: &b" + check.getCheckMode()),
                        OtherUtility.translate(""),
                        OtherUtility.translate("&fDescription:"),
                        OtherUtility.translate("&7" + check.getDescription()),
                        OtherUtility.translate(OtherUtility.guiLine()))));

                slot++;

                if (slot >= 27) {
                    break;
                }
            }
        }


        gui.setItem(26, GuiUtility.generateItem(new ItemStack(Material.BARRIER, 1), OtherUtility.translate("&cBack"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Click to go back."),
                OtherUtility.translate(OtherUtility.guiLine())
        )));

        player.openInventory(gui);
    }

    public void openArrowCheckSpeedGUI(Player player, String checkType1, String checkType2, String uiName) {
        Inventory gui = Bukkit.createInventory(player, 27, OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + "Arrow &7- &7" + uiName));

        int slot = 0;

        for (Check check : Arrow.getInstance().getProfileManager().getProfile(player).getCheckHolder().getChecks()) {
            if (check.getClass().getSimpleName().startsWith(checkType1) || check.getClass().getSimpleName().startsWith(checkType2)) {
                String enabledStatus = check.isEnabled() ? "§a✓" : "§c✗";
                String punishableStatus = check.isCanPunish() ? "§a✓" : "§c✗";

                Material bookMaterial = check.isEnabled() ? Material.ENCHANTED_BOOK : Material.BOOK;

                gui.setItem(slot, GuiUtility.generateItem(new ItemStack(bookMaterial, 1), OtherUtility.translate(MsgType.MAIN_THEME_COLOR.getMessage() + check.getFullCheckName()),
                Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                        OtherUtility.translate("&fEnabled: " + enabledStatus),
                        OtherUtility.translate("&fPunishable: " + punishableStatus),
                        OtherUtility.translate("&fPunish VL: &b" + check.getMaxVl()),
                        OtherUtility.translate("&fPunish Mode: &b" + check.getPunishMode()),
                        OtherUtility.translate("&fMode: &b" + check.getCheckMode()),
                        OtherUtility.translate(""),
                        OtherUtility.translate("&fDescription:"),
                        OtherUtility.translate("&7" + check.getDescription()),
                        OtherUtility.translate(OtherUtility.guiLine()))));

                slot++;

                if (slot >= 27) {
                    break;
                }
            }
        }


        gui.setItem(26, GuiUtility.generateItem(new ItemStack(Material.BARRIER, 1), OtherUtility.translate("&cBack"),
        Arrays.asList(OtherUtility.translate(OtherUtility.guiLine()),
                OtherUtility.translate("&7Click to go back."),
                OtherUtility.translate(OtherUtility.guiLine()))));

        player.openInventory(gui);
    }
}
