package me.arrow.files;

import lombok.Getter;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckMode;
import me.arrow.files.commentedfiles.CommentedFileConfiguration;
import me.arrow.managers.Initializer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

@Getter
public class Checks implements Initializer {

    private static final String[] HEADER = new String[] {
            " $$$$$$\\   $$$$$$\\   $$$$$$\\   $$$$$$\\  $$\\  $$\\  $$\\ ",
            " \\____$$\\ $$  __$$\\ $$  __$$\\ $$  __$$\\ $$ | $$ | $$ |",
            " $$$$$$$ |$$ |  \\__|$$ |  \\__|$$ /  $$ |$$ | $$ | $$ |",
            "$$  __$$ |$$ |      $$ |      $$ |  $$ |$$ | $$ | $$ | ",
            "\\$$$$$$$ |$$ |      $$ |      \\$$$$$$  |\\$$$$$\\$$$$  |",
            " \\_______|\\__|      \\__|       \\______/  \\_____\\____/ "
    };

    private final JavaPlugin plugin;
    private CommentedFileConfiguration configuration;
    private static boolean exists;

    public Checks(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return the config.yml as a CommentedFileConfiguration
     */
    public CommentedFileConfiguration getConfig() {
        return this.configuration;
    }

    @Override
    public void initialize() {

        File configFile = new File(this.plugin.getDataFolder(), "checks.yml");

        exists = configFile.exists();

        boolean setHeaderFooter = !exists;

        boolean changed = setHeaderFooter;

        this.configuration = CommentedFileConfiguration.loadConfiguration(this.plugin, configFile);

        if (setHeaderFooter) this.configuration.addComments(HEADER);

        for (Setting setting : Setting.values()) {

            setting.reset();

            changed |= setting.setIfNotExists(this.configuration);
        }

        if (changed) this.configuration.save();
    }

    @Override
    public void shutdown() {
        for (Setting setting : Setting.values()) setting.reset();
    }

    public enum Setting {

        AIM_A("AimA.enabled", true, "Should we enable this module?"),
        AIM_A_PUNISH("AimA.punish", "", "Punishment settings"),
        AIM_A_PUNiSH_ENABLED("AimA.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_A_PUNiSH_MODE("AimA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_A_MAX_VL("AimA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_B("AimB.enabled", true, "Should we enable this module?"),
        AIM_B_PUNISH("AimB.punish", "", "Punishment settings"),
        AIM_B_PUNiSH_ENABLED("AimB.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_B_PUNiSH_MODE("AimB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_B_MAX_VL("AimB.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_C("AimC.enabled", true, "Should we enable this module?"),
        AIM_C_PUNISH("AimC.punish", "", "Punishment settings"),
        AIM_C_PUNiSH_ENABLED("AimC.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_C_PUNiSH_MODE("AimC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_C_MAX_VL("AimC.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_D("AimD.enabled", true, "Should we enable this module?"),
        AIM_D_PUNISH("AimD.punish", "", "Punishment settings"),
        AIM_D_PUNiSH_ENABLED("AimD.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_D_PUNiSH_MODE("AimD.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_D_MAX_VL("AimD.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_E("AimE.enabled", true, "Should we enable this module?"),
        AIM_E_PUNISH("AimE.punish", "", "Punishment settings"),
        AIM_E_PUNiSH_ENABLED("AimE.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_E_PUNiSH_MODE("AimE.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_E_MAX_VL("AimE.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_F("AimF.enabled", true, "Should we enable this module?"),
        AIM_F_PUNISH("AimF.punish", "", "Punishment settings"),
        AIM_F_PUNiSH_ENABLED("AimF.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_F_PUNiSH_MODE("AimF.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_F_MAX_VL("AimF.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        REACH_A("ReachA.enabled", true, "Should we enable this module?"),
        REACH_A_MAX_SAMPLES("ReachA.maxSamples", 40, "Do not touch this if you don't know what you are doing."),
        REACH_A_FLAG_SAMPLES("ReachA.flagSamples", 16, "Do not touch this if you don't know what you are doing."),
        REACH_A_MINIMUM_REACH("ReachA.minimumReach", 3.0005, "Do not touch this if you don't know what you are doing."),
        REACH_A_BOX_EXPAND_HORIZONTAL("ReachA.boxExpandHorizontal", 0.035, "Do not touch this if you don't know what you are doing."),
        REACH_A_BOX_EXPAND_VERTICAL("ReachA.boxExpandVertical", 0.035, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_LAG_BOX_EXPAND("ReachA.maxLagBoxExpand", 0.13, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_FORGIVING_HORIZONTAL_BOX_EXPAND("ReachA.maxForgivingHorizontalBoxExpand", 0.22, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_FORGIVING_VERTICAL_BOX_EXPAND("ReachA.maxForgivingVerticalBoxExpand", 0.18, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_REACH_TOLERANCE("ReachA.maxForgivingVerticalBoxExpand", 0.16, "Do not touch this if you don't know what you are doing."),
        REACH_A_RAY("ReachA.requireRayForReach", false, "Do not touch this if you don't know what you are doing."),
        REACH_A_PUNISH("ReachA.punish", "", "Punishment settings"),
        REACH_A_PUNiSH_ENABLED("ReachA.punish.enabled", true, "Should punishments be enabled for this check?"),
        REACH_A_PUNiSH_MODE("ReachA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        REACH_A_MAX_VL("ReachA.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        REACH_B("ReachB.enabled", true, "Should we enable this module?"),
        REACH_B_PUNISH("ReachB.punish", "", "Punishment settings"),
        REACH_B_PUNiSH_ENABLED("ReachB.punish.enabled", false, "Should punishments be enabled for this check?"),
        REACH_B_PUNiSH_MODE("ReachB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        REACH_B_MAX_VL("ReachB.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),


        BACKTRACK_A("BackTrackA.enabled", true, "Should we enable this module?"),
        BACKTRACK_A_PUNISH("BackTrackA.punish", "", "Punishment settings"),
        BACKTRACK_A_PUNiSH_ENABLED("BackTrackA.punish.enabled", true, "Should punishments be enabled for this check?"),
        BACKTRACK_A_PUNiSH_MODE("BackTrackA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BACKTRACK_A_MAX_VL("BackTrackA.punish.vl", 200, "The maximum violation amount a player needs to reach in order to get punished"),

        BACKTRACK_B("BackTrackB.enabled", true, "Should we enable this module?"),
        BACKTRACK_B_PUNISH("BackTrackB.punish", "", "Punishment settings"),
        BACKTRACK_B_PUNiSH_ENABLED("BackTrackB.punish.enabled", true, "Should punishments be enabled for this check?"),
        BACKTRACK_B_PUNiSH_MODE("BackTrackB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BACKTRACK_B_MAX_VL("BackTrackB.punish.vl", 100, "The maximum violation amount a player needs to reach in order to get punished"),


        AUTOCLICKER_A("AutoClickerA.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_A_MAX_CPS("AutoClickerA.maxcps", 25, "Maximum cps that autoclicker a will start flagging for"),
        AUTOCLICKER_A_PUNISH("AutoClickerA.punish", "", "Punishment settings"),
        AUTOCLICKER_A_PUNiSH_ENABLED("AutoClickerA.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_A_PUNiSH_MODE("AutoClickerA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_A_MAX_VL("AutoClickerA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_B("AutoClickerB.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_B_PUNISH("AutoClickerB.punish", "", "Punishment settings"),
        AUTOCLICKER_B_PUNiSH_ENABLED("AutoClickerB.punish.enabled", false, "Should punishments be enabled for this check?"),
        AUTOCLICKER_B_PUNiSH_MODE("AutoClickerB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_B_MAX_VL("AutoClickerB.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_C("AutoClickerC.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_C_PUNISH("AutoClickerC.punish", "", "Punishment settings"),
        AUTOCLICKER_C_PUNiSH_ENABLED("AutoClickerC.punish.enabled", false, "Should punishments be enabled for this check?"),
        AUTOCLICKER_C_PUNiSH_MODE("AutoClickerC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_C_MAX_VL("AutoClickerC.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_D("AutoClickerD.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_D_PUNISH("AutoClickerD.punish", "", "Punishment settings"),
        AUTOCLICKER_D_PUNiSH_ENABLED("AutoClickerD.punish.enabled", false, "Should punishments be enabled for this check?"),
        AUTOCLICKER_D_PUNiSH_MODE("AutoClickerD.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_D_MAX_VL("AutoClickerD.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_E("AutoClickerE.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_E_PUNISH("AutoClickerE.punish", "", "Punishment settings"),
        AUTOCLICKER_E_PUNiSH_ENABLED("AutoClickerE.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_E_PUNiSH_MODE("AutoClickerE.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_E_MAX_VL("AutoClickerE.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_F("AutoClickerF.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_F_PUNISH("AutoClickerF.punish", "", "Punishment settings"),
        AUTOCLICKER_F_PUNiSH_ENABLED("AutoClickerF.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_F_PUNiSH_MODE("AutoClickerF.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_F_MAX_VL("AutoClickerF.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_G("AutoClickerG.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_G_PUNISH("AutoClickerG.punish", "", "Punishment settings"),
        AUTOCLICKER_G_PUNiSH_ENABLED("AutoClickerG.punish.enabled", false, "Should punishments be enabled for this check?"),
        AUTOCLICKER_G_PUNiSH_MODE("AutoClickerG.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_G_MAX_VL("AutoClickerG.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        MACRO_A("MacroA.enabled", true, "Should we enable this module?"),
        MACRO_A_PUNISH("MacroA.punish", "", "Punishment settings"),
        MACRO_A_PUNiSH_ENABLED("MacroA.punish.enabled", false, "Should punishments be enabled for this check?"),
        MACRO_A_PUNiSH_MODE("MacroA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MACRO_A_MAX_VL("MacroA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        KILLAURA_A("KillauraA.enabled", true, "Should we enable this module?"),
        KILLAURA_A_PUNISH("KillauraA.punish", "", "Punishment settings"),
        KILLAURA_A_PUNiSH_ENABLED("KillauraA.punish.enabled", false, "Should punishments be enabled for this check?"),
        KILLAURA_A_PUNiSH_MODE("KillauraA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        KILLAURA_A_MAX_VL("KillauraA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        VELOCITY_A("VelocityA.enabled", false, "Should we enable this module?"),
        VELOCITY_A_PUNISH("VelocityA.punish", "", "Punishment settings"),
        VELOCITY_A_PUNiSH_ENABLED("VelocityA.punish.enabled", false, "Should punishments be enabled for this check?"),
        VELOCITY_A_PUNiSH_MODE("VelocityA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        VELOCITY_A_MAX_VL("VelocityA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        VELOCITY_B("VelocityB.enabled", false, "Should we enable this module?"),
        VELOCITY_B_PUNISH("VelocityB.punish", "", "Punishment settings"),
        VELOCITY_B_PUNiSH_ENABLED("VelocityB.punish.enabled", false, "Should punishments be enabled for this check?"),
        VELOCITY_B_PUNiSH_MODE("VelocityB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        VELOCITY_B_MAX_VL("VelocityB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        INTERACT_A("InteractA.enabled", true, "Should we enable this module?"),
        INTERACT_A_PUNISH("InteractA.punish", "", "Punishment settings"),
        INTERACT_A_PUNiSH_ENABLED("InteractA.punish.enabled", true, "Should punishments be enabled for this check?"),
        INTERACT_A_PUNiSH_MODE("InteractA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INTERACT_A_MAX_VL("InteractA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        INTERACT_B("InteractB.enabled", true, "Should we enable this module?"),
        INTERACT_B_PUNISH("InteractB.punish", "", "Punishment settings"),
        INTERACT_B_PUNiSH_ENABLED("InteractB.punish.enabled", false, "Should punishments be enabled for this check?"),
        INTERACT_B_PUNiSH_MODE("InteractB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INTERACT_B_MAX_VL("InteractB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        INTERACT_C("InteractC.enabled", true, "Should we enable this module?"),
        INTERACT_C_PUNISH("InteractC.punish", "", "Punishment settings"),
        INTERACT_C_PUNiSH_ENABLED("InteractC.punish.enabled", true, "Should punishments be enabled for this check?"),
        INTERACT_C_PUNiSH_MODE("InteractC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        INTERACT_C_MAX_VL("InteractC.punish.vl", 5, "The maximum violation amount a player needs to reach in order to get punished"),

        INVENTORY_A("InventoryA.enabled", true, "Should we enable this module?"),
        INVENTORY_A_PUNISH("InventoryA.punish", "", "Punishment settings"),
        INVENTORY_A_PUNiSH_ENABLED("InventoryA.punish.enabled", true, "Should punishments be enabled for this check?"),
        INVENTORY_A_PUNiSH_MODE("InventoryA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INVENTORY_A_MAX_VL("InventoryA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        INVENTORY_B("InventoryB.enabled", true, "Should we enable this module?"),
        INVENTORY_B_PUNISH("InventoryB.punish", "", "Punishment settings"),
        INVENTORY_B_PUNiSH_ENABLED("InventoryB.punish.enabled", true, "Should punishments be enabled for this check?"),
        INVENTORY_B_PUNiSH_MODE("InventoryB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INVENTORY_B_MAX_VL("InventoryB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        INVENTORY_C("InventoryC.enabled", true, "Should we enable this module?"),
        INVENTORY_C_PUNISH("InventoryC.punish", "", "Punishment settings"),
        INVENTORY_C_PUNiSH_ENABLED("InventoryC.punish.enabled", true, "Should punishments be enabled for this check?"),
        INVENTORY_C_PUNiSH_MODE("InventoryC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INVENTORY_C_MAX_VL("InventoryC.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        SCAFFOLD_A("ScaffoldA.enabled", true, "Should we enable this module?"),
        SCAFFOLD_A_PUNISH("ScaffoldA.punish", "", "Punishment settings"),
        SCAFFOLD_A_PUNiSH_ENABLED("ScaffoldA.punish.enabled", true, "Should punishments be enabled for this check?"),
        SCAFFOLD_A_PUNiSH_MODE("ScaffoldA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        SCAFFOLD_A_MAX_VL("ScaffoldA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        SCAFFOLD_B("ScaffoldB.enabled", true, "Should we enable this module?"),
        SCAFFOLD_B_PUNISH("ScaffoldB.punish", "", "Punishment settings"),
        SCAFFOLD_B_PUNiSH_ENABLED("ScaffoldB.punish.enabled", true, "Should punishments be enabled for this check?"),
        SCAFFOLD_B_PUNiSH_MODE("ScaffoldB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        SCAFFOLD_B_MAX_VL("ScaffoldB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        SCAFFOLD_C("ScaffoldC.enabled", true, "Should we enable this module?"),
        SCAFFOLD_C_PUNISH("ScaffoldC.punish", "", "Punishment settings"),
        SCAFFOLD_C_PUNiSH_ENABLED("ScaffoldC.punish.enabled", true, "Should punishments be enabled for this check?"),
        SCAFFOLD_C_PUNiSH_MODE("ScaffoldC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        SCAFFOLD_C_MAX_VL("ScaffoldC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        SPEED_A("SpeedA.enabled", true, "Should we enable this module?"),
        SPEED_A_MODE("SpeedA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        SPEED_A_PUNISH("SpeedA.punish", "", "Punishment settings"),
        SPEED_A_PUNiSH_ENABLED("SpeedA.punish.enabled", true, "Should punishments be enabled for this check?"),
        SPEED_A_PUNiSH_MODE("SpeedA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        SPEED_A_MAX_VL("SpeedA.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        SPEED_B("SpeedB.enabled", true, "Should we enable this module?"),
        SPEED_B_MODE("SpeedB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        SPEED_B_PUNISH("SpeedB.punish", "", "Punishment settings"),
        SPEED_B_PUNiSH_ENABLED("SpeedB.punish.enabled", false, "Should punishments be enabled for this check?"),
        SPEED_B_PUNiSH_MODE("SpeedB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        SPEED_B_MAX_VL("SpeedB.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        SPEED_C("SpeedC.enabled", true, "Should we enable this module?"),
        SPEED_C_MODE("SpeedC.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        SPEED_C_PUNISH("SpeedC.punish", "", "Punishment settings"),
        SPEED_C_PUNiSH_ENABLED("SpeedC.punish.enabled", true, "Should punishments be enabled for this check?"),
        SPEED_C_PUNiSH_MODE("SpeedC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        SPEED_C_MAX_VL("SpeedC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        OMNISPRINT_A("OmniSprintA.enabled", true, "Should we enable this module?"),
        OMNISPRINT_A_MODE("OmniSprintA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        OMNISPRINT_A_PUNISH("OmniSprintA.punish", "", "Punishment settings"),
        OMNISPRINT_A_PUNiSH_ENABLED("OmniSprintA.punish.enabled", true, "Should punishments be enabled for this check?"),
        OMNISPRINT_A_PUNiSH_MODE("OmniSprintA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        OMNISPRINT_A_MAX_VL("OmniSprintA.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        GROUND_A("GroundA.enabled", true, "Should we enable this module?"),
        GROUND_A_MODE("GroundA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GROUND_A_PUNISH("GroundA.punish", "", "Punishment settings"),
        GROUND_A_PUNiSH_ENABLED("GroundA.punish.enabled", true, "Should punishments be enabled for this check?"),
        GROUND_A_PUNiSH_MODE("GroundA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GROUND_A_MAX_VL("GroundA.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        GROUND_B("GroundB.enabled", true, "Should we enable this module?"),
        GROUND_B_MODE("GroundB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GROUND_B_PUNISH("GroundB.punish", "", "Punishment settings"),
        GROUND_B_PUNiSH_ENABLED("GroundB.punish.enabled", true, "Should punishments be enabled for this check?"),
        GROUND_B_PUNiSH_MODE("GroundB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GROUND_B_MAX_VL("GroundB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        GROUND_C("GroundC.enabled", true, "Should we enable this module? (THIS IS THE GHOSTBLOCK HANDLER)"),
        GROUND_C_MODE("GroundC.mode", CheckMode.MITIGATE.getCheckMode(), "This should never be changed unless you are debugging."),
        GROUND_C_PUNISH("GroundC.punish", "", "Punishment settings"),
        GROUND_C_PUNiSH_ENABLED("GroundC.punish.enabled", false, "Should punishments be enabled for this check?"),
        GROUND_C_PUNiSH_MODE("GroundC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        GROUND_C_MAX_VL("GroundC.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        ELYTRA_A("ElytraA.enabled", true, "Should we enable this module?"),
        ELYTRA_A_MODE("ElytraA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        ELYTRA_A_PUNISH("ElytraA.punish", "", "Punishment settings"),
        ELYTRA_A_PUNiSH_ENABLED("ElytraA.punish.enabled", false, "Should punishments be enabled for this check?"),
        ELYTRA_A_PUNiSH_MODE("ElytraA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        ELYTRA_A_MAX_VL("ElytraA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        FLY_A("FlyA.enabled", true, "Should we enable this module?"),
        FLY_A_MODE("FlyA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        FLY_A_PUNISH("FlyA.punish", "", "Punishment settings"),
        FLY_A_PUNiSH_ENABLED("FlyA.punish.enabled", true, "Should punishments be enabled for this check?"),
        FLY_A_PUNiSH_MODE("FlyA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        FLY_A_MAX_VL("FlyA.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        FLY_B("FlyB.enabled", true, "Should we enable this module?"),
        FLY_B_MODE("FlyB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        FLY_B_PUNISH("FlyB.punish", "", "Punishment settings"),
        FLY_B_PUNiSH_ENABLED("FlyB.punish.enabled", true, "Should punishments be enabled for this check?"),
        FLY_B_PUNiSH_MODE("FlyB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        FLY_B_MAX_VL("FlyB.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        FLY_C("FlyC.enabled", true, "Should we enable this module?"),
        FLY_C_MODE("FlyC.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        FLY_C_PUNISH("FlyC.punish", "", "Punishment settings"),
        FLY_C_PUNiSH_ENABLED("FlyC.punish.enabled", true, "Should punishments be enabled for this check?"),
        FLY_C_PUNiSH_MODE("FlyC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        FLY_C_MAX_VL("FlyC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_A("MotionA.enabled", true, "Should we enable this module?"),
        MOTION_A_MODE("MotionA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_A_PUNISH("MotionA.punish", "", "Punishment settings"),
        MOTION_A_PUNiSH_ENABLED("MotionA.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_A_PUNiSH_MODE("MotionA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_A_MAX_VL("MotionA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_B("MotionB.enabled", true, "Should we enable this module?"),
        MOTION_B_MODE("MotionB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_B_PUNISH("MotionB.punish", "", "Punishment settings"),
        MOTION_B_PUNiSH_ENABLED("MotionB.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_B_PUNiSH_MODE("MotionB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_B_MAX_VL("MotionB.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_C("MotionC.enabled", true, "Should we enable this module?"),
        MOTION_C_MODE("MotionC.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_C_PUNISH("MotionC.punish", "", "Punishment settings"),
        MOTION_C_PUNiSH_ENABLED("MotionC.punish.enabled", false , "Should punishments be enabled for this check?"),
        MOTION_C_PUNiSH_MODE("MotionC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_C_MAX_VL("MotionC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_D("MotionD.enabled", true, "Should we enable this module?"),
        MOTION_D_MODE("MotionD.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_D_PUNISH("MotionD.punish", "", "Punishment settings"),
        MOTION_D_PUNiSH_ENABLED("MotionD.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_D_PUNiSH_MODE("MotionD.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_D_MAX_VL("MotionD.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_E("MotionE.enabled", true, "Should we enable this module?"),
        MOTION_E_MODE("MotionE.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_E_PUNISH("MotionE.punish", "", "Punishment settings"),
        MOTION_E_PUNiSH_ENABLED("MotionE.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_E_PUNiSH_MODE("MotionE.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_E_MAX_VL("MotionE.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_F("MotionF.enabled", true, "Should we enable this module?"),
        MOTION_F_MODE("MotionF.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_F_PUNISH("MotionF.punish", "", "Punishment settings"),
        MOTION_F_PUNiSH_ENABLED("MotionF.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_F_PUNiSH_MODE("MotionF.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_F_MAX_VL("MotionF.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        ILLEGALMOVE_A("IllegalMoveA.enabled", true, "Should we enable this module?"),
        ILLEGALMOVE_A_MODE("IllegalMoveA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        ILLEGALMOVE_A_PUNISH("IllegalMoveA.punish", "", "Punishment settings"),
        ILLEGALMOVE_A_PUNiSH_ENABLED("IllegalMoveA.punish.enabled", true, "Should punishments be enabled for this check?"),
        ILLEGALMOVE_A_PUNiSH_MODE("IllegalMoveA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        ILLEGALMOVE_A_MAX_VL("IllegalMoveA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        ILLEGALMOVE_B("IllegalMoveB.enabled", true, "Should we enable this module?"),
        ILLEGALMOVE_B_MODE("IllegalMoveB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        ILLEGALMOVE_B_PUNISH("IllegalMoveB.punish", "", "Punishment settings"),
        ILLEGALMOVE_B_PUNiSH_ENABLED("IllegalMoveB.punish.enabled", true, "Should punishments be enabled for this check?"),
        ILLEGALMOVE_B_PUNiSH_MODE("IllegalMoveB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        ILLEGALMOVE_B_MAX_VL("IllegalMoveB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        TIMER_A("TimerA.enabled", true, "Should we enable this module?"),
        TIMER_A_MODE("TimerA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        TIMER_A_PUNISH("TimerA.punish", "", "Punishment settings"),
        TIMER_A_PUNiSH_ENABLED("TimerA.punish.enabled", true, "Should punishments be enabled for this check?"),
        TIMER_A_PUNiSH_MODE("TimerA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        TIMER_A_MAX_VL("TimerA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        TIMER_B("TimerB.enabled", true, "Should we enable this module?"),
        TIMER_B_MODE("TimerB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        TIMER_B_PUNISH("TimerB.punish", "", "Punishment settings"),
        TIMER_B_PUNiSH_ENABLED("TimerB.punish.enabled", true, "Should punishments be enabled for this check?"),
        TIMER_B_PUNiSH_MODE("TimerB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        TIMER_B_MAX_VL("TimerB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_A("BadPacketsA.enabled", true, "Should we enable this module?"),
        BADPACKETS_A_MODE("BadPacketsA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        BADPACKETS_A_PUNISH("BadPacketsA.punish", "", "Punishment settings"),
        BADPACKETS_A_PUNiSH_ENABLED("BadPacketsA.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_A_PUNiSH_MODE("BadPacketsA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_A_MAX_VL("BadPacketsA.punish.vl", 1, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_B("BadPacketsB.enabled", true, "Should we enable this module?"),
        BADPACKETS_B_MODE("BadPacketsB.mode", CheckMode.BOTH.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        BADPACKETS_B_PUNISH("BadPacketsB.punish", "", "Punishment settings"),
        BADPACKETS_B_PUNiSH_ENABLED("BadPacketsB.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_B_PUNiSH_MODE("BadPacketsB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_B_MAX_VL("BadPacketsB.punish.vl", 5, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_C("BadPacketsC.enabled", true, "Should we enable this module?"),
        BADPACKETS_C_MODE("BadPacketsC.mode", CheckMode.BOTH.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        BADPACKETS_C_PUNISH("BadPacketsC.punish", "", "Punishment settings"),
        BADPACKETS_C_PUNiSH_ENABLED("BadPacketsC.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_C_PUNiSH_MODE("BadPacketsC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_C_MAX_VL("BadPacketsC.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        NOSLOWDOWN_A("NoSlowdownA.enabled", true, "Should we enable this module?"),
        NOSLOWDOWN_A_PUNISH("NoSlowdownA.punish", "", "Punishment settings"),
        NOSLOWDOWN_A_PUNiSH_ENABLED("NoSlowdownA.punish.enabled", false, "Should punishments be enabled for this check?"),
        NOSLOWDOWN_A_PUNiSH_MODE("NoSlowdownA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        NOSLOWDOWN_A_MAX_VL("NoSlowdownA.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        PHASE_A("PhaseA.enabled", true, "Should we enable this module?"),
        PHASE_A_PUNISH("PhaseA.punish", "", "Punishment settings"),
        PHASE_A_PUNiSH_ENABLED("PhaseA.punish.enabled", false, "Should punishments be enabled for this check?"),
        PHASE_A_PUNiSH_MODE("PhaseA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        PHASE_A_MAX_VL("PhaseA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        VEHICLE_A("VehicleA.enabled", false, "Should we enable this module?"),
        VEHICLE_A_PUNISH("VehicleA.punish", "", "Punishment settings"),
        VEHICLE_A_PUNiSH_ENABLED("VehicleA.punish.enabled", false, "Should punishments be enabled for this check?"),
        VEHICLE_A_PUNiSH_MODE("VehicleA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        VEHICLE_A_MAX_VL("VehicleA.punish.vl", 200, "The maximum violation amount a player needs to reach in order to get punished");

        @Getter
        private final String key;
        private final Object defaultValue;
        private boolean excluded;
        private final String[] comments;
        private Object value = null;

        Setting(String key, Object defaultValue, String... comments) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.comments = comments != null ? comments : new String[0];
        }

        Setting(String key, Object defaultValue, boolean excluded, String... comments) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.comments = comments != null ? comments : new String[0];
            this.excluded = excluded;
        }

        /**
         * Gets the setting as a boolean
         *
         * @return The setting as a boolean
         */
        public boolean getBoolean() {
            this.loadValue();

            if (this.value instanceof Boolean bool) {
                return bool;
            }

            if (this.value instanceof String string) {
                return Boolean.parseBoolean(string);
            }

            if (this.defaultValue instanceof Boolean bool) {
                return bool;
            }

            return false;
        }

        public int getInt() {
            this.loadValue();
            return (int) this.getNumber();
        }

        public long getLong() {
            this.loadValue();
            return (long) this.getNumber();
        }

        public double getDouble() {
            this.loadValue();
            return this.getNumber();
        }

        public float getFloat() {
            this.loadValue();
            return (float) this.getNumber();
        }

        public String getString() {
            this.loadValue();

            if (this.value == null) {
                return this.defaultValue == null ? "" : String.valueOf(this.defaultValue);
            }

            return String.valueOf(this.value);
        }

        private double getNumber() {
            if (this.value instanceof Number number) {
                return number.doubleValue();
            }

            if (this.value instanceof String string) {
                try {
                    return Double.parseDouble(string);
                } catch (NumberFormatException ignored) {
                }
            }

            if (this.defaultValue instanceof Number number) {
                return number.doubleValue();
            }

            if (this.defaultValue instanceof String string) {
                try {
                    return Double.parseDouble(string);
                } catch (NumberFormatException ignored) {
                }
            }

            return 0.0D;
        }

        @SuppressWarnings("unchecked")
        public List<String> getStringList() {
            this.loadValue();

            if (this.value instanceof List<?> list) {
                return (List<String>) list;
            }

            if (this.defaultValue instanceof List<?> list) {
                return (List<String>) list;
            }

            return List.of();
        }

        private boolean setIfNotExists(CommentedFileConfiguration fileConfiguration) {
            if (exists && this.excluded) {
                this.value = fileConfiguration.get(this.key);

                if (this.value == null) {
                    this.value = this.defaultValue;
                }

                return false;
            }

            Object currentValue = fileConfiguration.get(this.key);

            if (currentValue == null) {
                List<String> comments = Stream.of(this.comments).toList();

                if (this.defaultValue != null) {
                    fileConfiguration.set(this.key, this.defaultValue, comments.toArray(new String[0]));
                    this.value = this.defaultValue;
                } else {
                    fileConfiguration.addComments(comments.toArray(new String[0]));
                    this.value = null;
                }

                return true;
            }

            this.value = currentValue;
            return false;
        }

        public void reset() {
            this.value = null;
        }

        public void setValue(String value) {
            setObjectValue(value);
        }

        public void setValue(double value) {
            setObjectValue(value);
        }

        public void setValue(int value) {
            setObjectValue(value);
        }

        public void setValue(float value) {
            setObjectValue(value);
        }

        public void setValue(long value) {
            setObjectValue(value);
        }

        public void setValue(boolean value) {
            setObjectValue(value);
        }

        private void setObjectValue(Object value) {
            this.value = value;

            try {
                Arrow.getInstance().getChecks().set(this.key, value);
                Arrow.getInstance().getChecks().save();
            } catch (Throwable ignored) {
            }
        }

        public boolean isSection() {
            return this.defaultValue == null;
        }

        private void loadValue() {
            if (this.value != null) {
                return;
            }

            try {
                this.value = Arrow.getInstance().getChecks().get(this.key);
            } catch (Throwable ignored) {
                this.value = null;
            }

            if (this.value == null) {
                this.value = this.defaultValue;
            }
        }
    }
}