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
    static boolean exists;

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

    private Object[] readCheckSettings(String check, String[] paths) {
        Object[] values = new Object[paths.length];
        for (int i = 0; i < paths.length; i++) {
            values[i] = this.configuration.get(check + "." + paths[i]);
        }
        return values;
    }

    private void writeCheckSettings(String check, String[] paths, Object[] values) {
        for (int i = 0; i < paths.length; i++) {
            if (values[i] != null) {
                this.configuration.set(check + "." + paths[i], values[i]);
            }
        }
    }

    @Override
    public void shutdown() {
        for (Setting setting : Setting.values()) setting.reset();
    }

    public enum Setting {

        AIM_A("AimA.enabled", true, "Should we enable this module?"),
        AIM_A_PUNISH("AimA.punish", "", "Punishment settings"),
        AIM_A_PUNISH_ENABLED("AimA.punish.enabled", true, "Should punishments be enabled for this check?"),
        AIM_A_PUNISH_MODE("AimA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_A_MAX_VL("AimA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_B("AimB.enabled", true, "Should we enable this module?"),
        AIM_B_PUNISH("AimB.punish", "", "Punishment settings"),
        AIM_B_PUNISH_ENABLED("AimB.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_B_PUNISH_MODE("AimB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_B_MAX_VL("AimB.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_C("AimC.enabled", true, "Should we enable this module?"),
        AIM_C_PUNISH("AimC.punish", "", "Punishment settings"),
        AIM_C_PUNISH_ENABLED("AimC.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_C_PUNISH_MODE("AimC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_C_MAX_VL("AimC.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_D("AimD.enabled", true, "Should we enable this module?"),
        AIM_D_PUNISH("AimD.punish", "", "Punishment settings"),
        AIM_D_PUNISH_ENABLED("AimD.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_D_PUNISH_MODE("AimD.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_D_MAX_VL("AimD.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_E("AimE.enabled", true, "Should we enable this module?"),
        AIM_E_PUNISH("AimE.punish", "", "Punishment settings"),
        AIM_E_PUNISH_ENABLED("AimE.punish.enabled", true, "Should punishments be enabled for this check?"),
        AIM_E_PUNISH_MODE("AimE.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_E_MAX_VL("AimE.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_F("AimF.enabled", true, "Should we enable this module?"),
        AIM_F_PUNISH("AimF.punish", "", "Punishment settings"),
        AIM_F_PUNISH_ENABLED("AimF.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_F_PUNISH_MODE("AimF.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_F_MAX_VL("AimF.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_G("AimG.enabled", true, "Should we enable this module?"),
        AIM_G_PUNISH("AimG.punish", "", "Punishment settings"),
        AIM_G_PUNISH_ENABLED("AimG.punish.enabled", true, "Should punishments be enabled for this check?"),
        AIM_G_PUNISH_MODE("AimG.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_G_MAX_VL("AimG.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        AIM_H("AimH.enabled", false, "Should we enable this module?"),
        AIM_H_PUNISH("AimH.punish", "", "Punishment settings"),
        AIM_H_PUNISH_ENABLED("AimH.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_H_PUNISH_MODE("AimH.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_H_MAX_VL("AimH.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),
        AIM_I("AimI.enabled", true, "Should we enable this module?"),
        AIM_I_PUNISH("AimI.punish", "", "Punishment settings"),
        AIM_I_PUNISH_ENABLED("AimI.punish.enabled", false, "Should punishments be enabled for this check?"),
        AIM_I_PUNISH_MODE("AimI.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AIM_I_MAX_VL("AimI.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        REACH_A("ReachA.enabled", true, "Should we enable this module?"),
        REACH_A_MAX_SAMPLES("ReachA.maxSamples", 40, "Do not touch this if you don't know what you are doing."),
        REACH_A_FLAG_SAMPLES("ReachA.flagSamples", 16, "Do not touch this if you don't know what you are doing."),
        REACH_A_MINIMUM_REACH("ReachA.minimumReach", 3.0005, "The measured reach distance at which Reach A starts flagging."),
        REACH_A_BOX_EXPAND_HORIZONTAL("ReachA.boxExpandHorizontal", 0.035, "Do not touch this if you don't know what you are doing."),
        REACH_A_BOX_EXPAND_VERTICAL("ReachA.boxExpandVertical", 0.035, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_LAG_BOX_EXPAND("ReachA.maxLagBoxExpand", 0.13, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_FORGIVING_HORIZONTAL_BOX_EXPAND("ReachA.maxForgivingHorizontalBoxExpand", 0.22, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_FORGIVING_VERTICAL_BOX_EXPAND("ReachA.maxForgivingVerticalBoxExpand", 0.18, "Do not touch this if you don't know what you are doing."),
        REACH_A_MAX_REACH_TOLERANCE("ReachA.maxReachTolerance", 0.03, "Maximum extra reach tolerance after position compensation."),
        REACH_A_RAY("ReachA.requireRayForReach", false, "Do not touch this if you don't know what you are doing."),
        REACH_A_PUNISH("ReachA.punish", "", "Punishment settings"),
        REACH_A_PUNISH_ENABLED("ReachA.punish.enabled", true, "Should punishments be enabled for this check?"),
        REACH_A_PUNISH_MODE("ReachA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        REACH_A_MAX_VL("ReachA.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        HITBOX_A("HitboxA.enabled", true, "Should we enable this module?"),
        HITBOX_A_PUNISH("HitboxA.punish", "", "Punishment settings"),
        HITBOX_A_PUNISH_ENABLED("HitboxA.punish.enabled", true, "Should punishments be enabled for this check?"),
        HITBOX_A_PUNISH_MODE("HitboxA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        HITBOX_A_MAX_VL("HitboxA.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        REACH_B("ReachB.enabled", false, "Should we enable this module?"),
        REACH_B_PUNISH("ReachB.punish", "", "Punishment settings"),
        REACH_B_PUNISH_ENABLED("ReachB.punish.enabled", false, "Should punishments be enabled for this check?"),
        REACH_B_PUNISH_MODE("ReachB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        REACH_B_MAX_VL("ReachB.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),


        BACKTRACK_A("BackTrackA.enabled", true, "Should we enable this module?"),
        BACKTRACK_A_PUNISH("BackTrackA.punish", "", "Punishment settings"),
        BACKTRACK_A_PUNISH_ENABLED("BackTrackA.punish.enabled", true, "Should punishments be enabled for this check?"),
        BACKTRACK_A_PUNISH_MODE("BackTrackA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BACKTRACK_A_MAX_VL("BackTrackA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        BACKTRACK_B("BackTrackB.enabled", true, "Should we enable this module?"),
        BACKTRACK_B_PUNISH("BackTrackB.punish", "", "Punishment settings"),
        BACKTRACK_B_PUNISH_ENABLED("BackTrackB.punish.enabled", true, "Should punishments be enabled for this check?"),
        BACKTRACK_B_PUNISH_MODE("BackTrackB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BACKTRACK_B_MAX_VL("BackTrackB.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),


        AUTOCLICKER_A("AutoClickerA.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_A_MAX_CPS("AutoClickerA.maxcps", 25, "Maximum cps that autoclicker a will start flagging for"),
        AUTOCLICKER_A_PUNISH("AutoClickerA.punish", "", "Punishment settings"),
        AUTOCLICKER_A_PUNISH_ENABLED("AutoClickerA.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_A_PUNISH_MODE("AutoClickerA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_A_MAX_VL("AutoClickerA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_B("AutoClickerB.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_B_PUNISH("AutoClickerB.punish", "", "Punishment settings"),
        AUTOCLICKER_B_PUNISH_ENABLED("AutoClickerB.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_B_PUNISH_MODE("AutoClickerB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_B_MAX_VL("AutoClickerB.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_B2("AutoClickerB2.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_B2_PUNISH("AutoClickerB2.punish", "", "Punishment settings"),
        AUTOCLICKER_B2_PUNISH_ENABLED("AutoClickerB2.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_B2_PUNISH_MODE("AutoClickerB2.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_B2_MAX_VL("AutoClickerB2.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_C("AutoClickerC.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_C_PUNISH("AutoClickerC.punish", "", "Punishment settings"),
        AUTOCLICKER_C_PUNISH_ENABLED("AutoClickerC.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_C_PUNISH_MODE("AutoClickerC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_C_MAX_VL("AutoClickerC.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_D("AutoClickerD.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_D_PUNISH("AutoClickerD.punish", "", "Punishment settings"),
        AUTOCLICKER_D_PUNISH_ENABLED("AutoClickerD.punish.enabled", false, "Should punishments be enabled for this check?"),
        AUTOCLICKER_D_PUNISH_MODE("AutoClickerD.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_D_MAX_VL("AutoClickerD.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_E("AutoClickerE.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_E_PUNISH("AutoClickerE.punish", "", "Punishment settings"),
        AUTOCLICKER_E_PUNISH_ENABLED("AutoClickerE.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_E_PUNISH_MODE("AutoClickerE.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_E_MAX_VL("AutoClickerE.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_F("AutoClickerF.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_F_PUNISH("AutoClickerF.punish", "", "Punishment settings"),
        AUTOCLICKER_F_PUNISH_ENABLED("AutoClickerF.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_F_PUNISH_MODE("AutoClickerF.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_F_MAX_VL("AutoClickerF.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_G("AutoClickerG.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_G_PUNISH("AutoClickerG.punish", "", "Punishment settings"),
        AUTOCLICKER_G_PUNISH_ENABLED("AutoClickerG.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_G_PUNISH_MODE("AutoClickerG.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_G_MAX_VL("AutoClickerG.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_H("AutoClickerH.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_H_PUNISH("AutoClickerH.punish", "", "Punishment settings"),
        AUTOCLICKER_H_PUNISH_ENABLED("AutoClickerH.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_H_PUNISH_MODE("AutoClickerH.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_H_MAX_VL("AutoClickerH.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_I("AutoClickerI.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_I_PUNISH("AutoClickerI.punish", "", "Punishment settings"),
        AUTOCLICKER_I_PUNISH_ENABLED("AutoClickerI.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_I_PUNISH_MODE("AutoClickerI.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_I_MAX_VL("AutoClickerI.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_J("AutoClickerJ.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_J_PUNISH("AutoClickerJ.punish", "", "Punishment settings"),
        AUTOCLICKER_J_PUNISH_ENABLED("AutoClickerJ.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_J_PUNISH_MODE("AutoClickerJ.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_J_MAX_VL("AutoClickerJ.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        AUTOCLICKER_K("AutoClickerK.enabled", true, "Should we enable this module?"),
        AUTOCLICKER_K_PUNISH("AutoClickerK.punish", "", "Punishment settings"),
        AUTOCLICKER_K_PUNISH_ENABLED("AutoClickerK.punish.enabled", true, "Should punishments be enabled for this check?"),
        AUTOCLICKER_K_PUNISH_MODE("AutoClickerK.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        AUTOCLICKER_K_MAX_VL("AutoClickerK.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MACRO_A("MacroA.enabled", true, "Should we enable this module?"),
        MACRO_A_PUNISH("MacroA.punish", "", "Punishment settings"),
        MACRO_A_PUNISH_ENABLED("MacroA.punish.enabled", false, "Should punishments be enabled for this check?"),
        MACRO_A_PUNISH_MODE("MacroA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MACRO_A_MAX_VL("MacroA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        MACRO_B("MacroB.enabled", true, "Should we enable this module?"),
        MACRO_B_PUNISH("MacroB.punish", "", "Punishment settings"),
        MACRO_B_PUNISH_ENABLED("MacroB.punish.enabled", false, "Should punishments be enabled for this check?"),
        MACRO_B_PUNISH_MODE("MacroB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MACRO_B_MAX_VL("MacroB.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        KILLAURA_A("KillauraA.enabled", true, "Should we enable this module?"),
        KILLAURA_A_PUNISH("KillauraA.punish", "", "Punishment settings"),
        KILLAURA_A_PUNISH_ENABLED("KillauraA.punish.enabled", false, "Should punishments be enabled for this check?"),
        KILLAURA_A_PUNISH_MODE("KillauraA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        KILLAURA_A_MAX_VL("KillauraA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        VELOCITY_A("VelocityA.enabled", true, "Should we enable this module?"),
        VELOCITY_A_PUNISH("VelocityA.punish", "", "Punishment settings"),
        VELOCITY_A_PUNISH_ENABLED("VelocityA.punish.enabled", true, "Should punishments be enabled for this check?"),
        VELOCITY_A_PUNISH_MODE("VelocityA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        VELOCITY_A_MAX_VL("VelocityA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        VELOCITY_B("VelocityB.enabled", true, "Should we enable this module?"),
        VELOCITY_B_PUNISH("VelocityB.punish", "", "Punishment settings"),
        VELOCITY_B_PUNISH_ENABLED("VelocityB.punish.enabled", true, "Should punishments be enabled for this check?"),
        VELOCITY_B_PUNISH_MODE("VelocityB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        VELOCITY_B_MAX_VL("VelocityB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        INTERACT_A("InteractA.enabled", true, "Should we enable this module?"),
        INTERACT_A_PUNISH("InteractA.punish", "", "Punishment settings"),
        INTERACT_A_PUNISH_ENABLED("InteractA.punish.enabled", true, "Should punishments be enabled for this check?"),
        INTERACT_A_PUNISH_MODE("InteractA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INTERACT_A_MAX_VL("InteractA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        INTERACT_B("InteractB.enabled", true, "Should we enable this module?"),
        INTERACT_B_PUNISH("InteractB.punish", "", "Punishment settings"),
        INTERACT_B_PUNISH_ENABLED("InteractB.punish.enabled", true, "Should punishments be enabled for this check?"),
        INTERACT_B_PUNISH_MODE("InteractB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INTERACT_B_MAX_VL("InteractB.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        INTERACT_C("InteractC.enabled", true, "Should we enable this module?"),
        INTERACT_C_PUNISH("InteractC.punish", "", "Punishment settings"),
        INTERACT_C_PUNISH_ENABLED("InteractC.punish.enabled", true, "Should punishments be enabled for this check?"),
        INTERACT_C_PUNISH_MODE("InteractC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        INTERACT_C_MAX_VL("InteractC.punish.vl", 5, "The maximum violation amount a player needs to reach in order to get punished"),

        INTERACT_D("InteractD.enabled", true, "Should we enable this module?"),
        INTERACT_D_PUNISH("InteractD.punish", "", "Punishment settings"),
        INTERACT_D_PUNISH_ENABLED("InteractD.punish.enabled", true, "Should punishments be enabled for this check?"),
        INTERACT_D_PUNISH_MODE("InteractD.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INTERACT_D_MAX_VL("InteractD.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        INVENTORY_A("InventoryA.enabled", true, "Should we enable this module?"),
        INVENTORY_A_PUNISH("InventoryA.punish", "", "Punishment settings"),
        INVENTORY_A_PUNISH_ENABLED("InventoryA.punish.enabled", true, "Should punishments be enabled for this check?"),
        INVENTORY_A_PUNISH_MODE("InventoryA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INVENTORY_A_MAX_VL("InventoryA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        INVENTORY_B("InventoryB.enabled", true, "Should we enable this module?"),
        INVENTORY_B_PUNISH("InventoryB.punish", "", "Punishment settings"),
        INVENTORY_B_PUNISH_ENABLED("InventoryB.punish.enabled", true, "Should punishments be enabled for this check?"),
        INVENTORY_B_PUNISH_MODE("InventoryB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        INVENTORY_B_MAX_VL("InventoryB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        SCAFFOLD_A("ScaffoldA.enabled", true, "Should we enable this module?"),
        SCAFFOLD_A_PUNISH("ScaffoldA.punish", "", "Punishment settings"),
        SCAFFOLD_A_PUNISH_ENABLED("ScaffoldA.punish.enabled", true, "Should punishments be enabled for this check?"),
        SCAFFOLD_A_PUNISH_MODE("ScaffoldA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        SCAFFOLD_A_MAX_VL("ScaffoldA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        SCAFFOLD_B("ScaffoldB.enabled", true, "Should we enable this module?"),
        SCAFFOLD_B_PUNISH("ScaffoldB.punish", "", "Punishment settings"),
        SCAFFOLD_B_PUNISH_ENABLED("ScaffoldB.punish.enabled", true, "Should punishments be enabled for this check?"),
        SCAFFOLD_B_PUNISH_MODE("ScaffoldB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        SCAFFOLD_B_MAX_VL("ScaffoldB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        SCAFFOLD_C("ScaffoldC.enabled", true, "Should we enable this module?"),
        SCAFFOLD_C_PUNISH("ScaffoldC.punish", "", "Punishment settings"),
        SCAFFOLD_C_PUNISH_ENABLED("ScaffoldC.punish.enabled", true, "Should punishments be enabled for this check?"),
        SCAFFOLD_C_PUNISH_MODE("ScaffoldC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        SCAFFOLD_C_MAX_VL("ScaffoldC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        SPEED_A("SpeedA.enabled", true, "Should we enable this module?"),
        SPEED_A_MODE("SpeedA.mode", CheckMode.BOTH.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        SPEED_A_PUNISH("SpeedA.punish", "", "Punishment settings"),
        SPEED_A_PUNISH_ENABLED("SpeedA.punish.enabled", true, "Should punishments be enabled for this check?"),
        SPEED_A_PUNISH_MODE("SpeedA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        SPEED_A_MAX_VL("SpeedA.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        SPEED_B("SpeedB.enabled", true, "Should we enable this module?"),
        SPEED_B_MODE("SpeedB.mode", CheckMode.BOTH.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        SPEED_B_PUNISH("SpeedB.punish", "", "Punishment settings"),
        SPEED_B_PUNISH_ENABLED("SpeedB.punish.enabled", true, "Should punishments be enabled for this check?"),
        SPEED_B_PUNISH_MODE("SpeedB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        SPEED_B_MAX_VL("SpeedB.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        OMNISPRINT_A("OmniSprintA.enabled", true, "Should we enable this module?"),
        OMNISPRINT_A_MODE("OmniSprintA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        OMNISPRINT_A_PUNISH("OmniSprintA.punish", "", "Punishment settings"),
        OMNISPRINT_A_PUNISH_ENABLED("OmniSprintA.punish.enabled", true, "Should punishments be enabled for this check?"),
        OMNISPRINT_A_PUNISH_MODE("OmniSprintA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        OMNISPRINT_A_MAX_VL("OmniSprintA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        GROUND_A("GroundA.enabled", true, "Should we enable this module?"),
        GROUND_A_MODE("GroundA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GROUND_A_PUNISH("GroundA.punish", "", "Punishment settings"),
        GROUND_A_PUNISH_ENABLED("GroundA.punish.enabled", true, "Should punishments be enabled for this check?"),
        GROUND_A_PUNISH_MODE("GroundA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GROUND_A_MAX_VL("GroundA.punish.vl", 5, "The maximum violation amount a player needs to reach in order to get punished"),

        GROUND_B("GroundB.enabled", true, "Should we enable this module?"),
        GROUND_B_MODE("GroundB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GROUND_B_PUNISH("GroundB.punish", "", "Punishment settings"),
        GROUND_B_PUNISH_ENABLED("GroundB.punish.enabled", true, "Should punishments be enabled for this check?"),
        GROUND_B_PUNISH_MODE("GroundB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GROUND_B_MAX_VL("GroundB.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        GROUND_C("GroundC.enabled", true, "Should we enable this module? (THIS IS THE GHOSTBLOCK HANDLER)"),
        GROUND_C_MODE("GroundC.mode", CheckMode.MITIGATE.getCheckMode(), "This should never be changed unless you are debugging."),
        GROUND_C_PUNISH("GroundC.punish", "", "Punishment settings"),
        GROUND_C_PUNISH_ENABLED("GroundC.punish.enabled", false, "Should punishments be enabled for this check?"),
        GROUND_C_PUNISH_MODE("GroundC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        GROUND_C_MAX_VL("GroundC.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        ELYTRA_A("ElytraA.enabled", true, "Should we enable this module?"),
        ELYTRA_A_MODE("ElytraA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        ELYTRA_A_PUNISH("ElytraA.punish", "", "Punishment settings"),
        ELYTRA_A_PUNISH_ENABLED("ElytraA.punish.enabled", false, "Should punishments be enabled for this check?"),
        ELYTRA_A_PUNISH_MODE("ElytraA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        ELYTRA_A_MAX_VL("ElytraA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        GRAVITY_A("GravityA.enabled", true, "Should we enable this module?"),
        GRAVITY_A_MODE("GravityA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GRAVITY_A_PUNISH("GravityA.punish", "", "Punishment settings"),
        GRAVITY_A_PUNISH_ENABLED("GravityA.punish.enabled", true, "Should punishments be enabled for this check?"),
        GRAVITY_A_PUNISH_MODE("GravityA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GRAVITY_A_MAX_VL("GravityA.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        GRAVITY_B("GravityB.enabled", true, "Should we enable this module?"),
        GRAVITY_B_MODE("GravityB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GRAVITY_B_PUNISH("GravityB.punish", "", "Punishment settings"),
        GRAVITY_B_PUNISH_ENABLED("GravityB.punish.enabled", true, "Should punishments be enabled for this check?"),
        GRAVITY_B_PUNISH_MODE("GravityB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GRAVITY_B_MAX_VL("GravityB.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        GRAVITY_C("GravityC.enabled", true, "Should we enable this module?"),
        GRAVITY_C_MODE("GravityC.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GRAVITY_C_PUNISH("GravityC.punish", "", "Punishment settings"),
        GRAVITY_C_PUNISH_ENABLED("GravityC.punish.enabled", true, "Should punishments be enabled for this check?"),
        GRAVITY_C_PUNISH_MODE("GravityC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GRAVITY_C_MAX_VL("GravityC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        GRAVITY_D("GravityD.enabled", true, "Should we enable this module?"),
        GRAVITY_D_MODE("GravityD.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GRAVITY_D_PUNISH("GravityD.punish", "", "Punishment settings"),
        GRAVITY_D_PUNISH_ENABLED("GravityD.punish.enabled", true, "Should punishments be enabled for this check?"),
        GRAVITY_D_PUNISH_MODE("GravityD.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GRAVITY_D_MAX_VL("GravityD.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        GRAVITY_E("GravityD.enabled", false, "Should we enable this module?"),
        GRAVITY_E_MODE("GravityD.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        GRAVITY_E_PUNISH("GravityD.punish", "", "Punishment settings"),
        GRAVITY_E_PUNISH_ENABLED("GravityD.punish.enabled", false, "Should punishments be enabled for this check?"),
        GRAVITY_E_PUNISH_MODE("GravityD.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        GRAVITY_E_MAX_VL("GravityD.punish.vl", 30, "The maximum violation amount a player needs to reach in order to get punished"),

        FLY_A("FlyA.enabled", true, "Should we enable this module?"),
        FLY_A_MODE("FlyA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        FLY_A_PUNISH("FlyA.punish", "", "Punishment settings"),
        FLY_A_PUNISH_ENABLED("FlyA.punish.enabled", true, "Should punishments be enabled for this check?"),
        FLY_A_PUNISH_MODE("FlyA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        FLY_A_MAX_VL("FlyA.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        FLY_B("FlyB.enabled", true, "Should we enable this module?"),
        FLY_B_MODE("FlyB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        FLY_B_PUNISH("FlyB.punish", "", "Punishment settings"),
        FLY_B_PUNISH_ENABLED("FlyB.punish.enabled", true, "Should punishments be enabled for this check?"),
        FLY_B_PUNISH_MODE("FlyB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        FLY_B_MAX_VL("FlyB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_A("MotionA.enabled", true, "Should we enable this module?"),
        MOTION_A_MODE("MotionA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_A_PUNISH("MotionA.punish", "", "Punishment settings"),
        MOTION_A_PUNISH_ENABLED("MotionA.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_A_PUNISH_MODE("MotionA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_A_MAX_VL("MotionA.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_B("MotionB.enabled", true, "Should we enable this module?"),
        MOTION_B_MODE("MotionB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_B_PUNISH("MotionB.punish", "", "Punishment settings"),
        MOTION_B_PUNISH_ENABLED("MotionB.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_B_PUNISH_MODE("MotionB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_B_MAX_VL("MotionB.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_C("MotionC.enabled", true, "Should we enable this module?"),
        MOTION_C_MODE("MotionC.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_C_PUNISH("MotionC.punish", "", "Punishment settings"),
        MOTION_C_PUNISH_ENABLED("MotionC.punish.enabled", false , "Should punishments be enabled for this check?"),
        MOTION_C_PUNISH_MODE("MotionC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_C_MAX_VL("MotionC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_D("MotionD.enabled", true, "Should we enable this module?"),
        MOTION_D_MODE("MotionD.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_D_PUNISH("MotionD.punish", "", "Punishment settings"),
        MOTION_D_PUNISH_ENABLED("MotionD.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_D_PUNISH_MODE("MotionD.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_D_MAX_VL("MotionD.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_E("MotionE.enabled", true, "Should we enable this module?"),
        MOTION_E_MODE("MotionE.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_E_PUNISH("MotionE.punish", "", "Punishment settings"),
        MOTION_E_PUNISH_ENABLED("MotionE.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_E_PUNISH_MODE("MotionE.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_E_MAX_VL("MotionE.punish.vl", 25, "The maximum violation amount a player needs to reach in order to get punished"),

        MOTION_F("MotionF.enabled", true, "Should we enable this module?"),
        MOTION_F_MODE("MotionF.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        MOTION_F_PUNISH("MotionF.punish", "", "Punishment settings"),
        MOTION_F_PUNISH_ENABLED("MotionF.punish.enabled", true, "Should punishments be enabled for this check?"),
        MOTION_F_PUNISH_MODE("MotionF.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        MOTION_F_MAX_VL("MotionF.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        ILLEGALMOVE_A("IllegalMoveA.enabled", true, "Should we enable this module?"),
        ILLEGALMOVE_A_MODE("IllegalMoveA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        ILLEGALMOVE_A_PUNISH("IllegalMoveA.punish", "", "Punishment settings"),
        ILLEGALMOVE_A_PUNISH_ENABLED("IllegalMoveA.punish.enabled", true, "Should punishments be enabled for this check?"),
        ILLEGALMOVE_A_PUNISH_MODE("IllegalMoveA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        ILLEGALMOVE_A_MAX_VL("IllegalMoveA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        ILLEGALMOVE_B("IllegalMoveB.enabled", true, "Should we enable this module?"),
        ILLEGALMOVE_B_MODE("IllegalMoveB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        ILLEGALMOVE_B_PUNISH("IllegalMoveB.punish", "", "Punishment settings"),
        ILLEGALMOVE_B_PUNISH_ENABLED("IllegalMoveB.punish.enabled", true, "Should punishments be enabled for this check?"),
        ILLEGALMOVE_B_PUNISH_MODE("IllegalMoveB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        ILLEGALMOVE_B_MAX_VL("IllegalMoveB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        ILLEGALMOVE_C("IllegalMoveC.enabled", true, "Should we enable this module?"),
        ILLEGALMOVE_C_MODE("IllegalMoveC.mode", CheckMode.BOTH.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        ILLEGALMOVE_C_PUNISH("IllegalMoveC.punish", "", "Punishment settings"),
        ILLEGALMOVE_C_PUNISH_ENABLED("IllegalMoveC.punish.enabled", true, "Should punishments be enabled for this check?"),
        ILLEGALMOVE_C_PUNISH_MODE("IllegalMoveC.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        ILLEGALMOVE_C_MAX_VL("IllegalMoveC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        TIMER_A("TimerA.enabled", true, "Should we enable this module?"),
        TIMER_A_MODE("TimerA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        TIMER_A_PUNISH("TimerA.punish", "", "Punishment settings"),
        TIMER_A_PUNISH_ENABLED("TimerA.punish.enabled", true, "Should punishments be enabled for this check?"),
        TIMER_A_PUNISH_MODE("TimerA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        TIMER_A_MAX_VL("TimerA.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        TIMER_B("TimerB.enabled", true, "Should we enable this module?"),
        TIMER_B_MODE("TimerB.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        TIMER_B_PUNISH("TimerB.punish", "", "Punishment settings"),
        TIMER_B_PUNISH_ENABLED("TimerB.punish.enabled", true, "Should punishments be enabled for this check?"),
        TIMER_B_PUNISH_MODE("TimerB.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        TIMER_B_MAX_VL("TimerB.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        TIMER_C("TimerC.enabled", true, "Should we enable this module?"),
        TIMER_C_MODE("TimerC.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        TIMER_C_PUNISH("TimerC.punish", "", "Punishment settings"),
        TIMER_C_PUNISH_ENABLED("TimerC.punish.enabled", true, "Should punishments be enabled for this check?"),
        TIMER_C_PUNISH_MODE("TimerC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        TIMER_C_MAX_VL("TimerC.punish.vl", 20, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_A("BadPacketsA.enabled", true, "Should we enable this module?"),
        BADPACKETS_A_MODE("BadPacketsA.mode", CheckMode.FLAG.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        BADPACKETS_A_PUNISH("BadPacketsA.punish", "", "Punishment settings"),
        BADPACKETS_A_PUNISH_ENABLED("BadPacketsA.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_A_PUNISH_MODE("BadPacketsA.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_A_MAX_VL("BadPacketsA.punish.vl", 1, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_B("BadPacketsB.enabled", true, "Should we enable this module?"),
        BADPACKETS_B_MODE("BadPacketsB.mode", CheckMode.BOTH.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        BADPACKETS_B_PUNISH("BadPacketsB.punish", "", "Punishment settings"),
        BADPACKETS_B_PUNISH_ENABLED("BadPacketsB.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_B_PUNISH_MODE("BadPacketsB.punish.mode", "BAN", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_B_MAX_VL("BadPacketsB.punish.vl", 5, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_C("BadPacketsC.enabled", true, "Should we enable this module?"),
        BADPACKETS_C_MODE("BadPacketsC.mode", CheckMode.BOTH.getCheckMode(), "Choose whether to flag, mitigate or do both (Must type all caps either FLAG, MITIGATE or BOTH)"),
        BADPACKETS_C_PUNISH("BadPacketsC.punish", "", "Punishment settings"),
        BADPACKETS_C_PUNISH_ENABLED("BadPacketsC.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_C_PUNISH_MODE("BadPacketsC.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_C_MAX_VL("BadPacketsC.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_D("BadPacketsD.enabled", true, "Should we enable this module?"),
        BADPACKETS_D_PUNISH("BadPacketsD.punish", "", "Punishment settings"),
        BADPACKETS_D_PUNISH_ENABLED("BadPacketsD.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_D_PUNISH_MODE("BadPacketsD.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_D_MAX_VL("BadPacketsD.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_E("BadPacketsE.enabled", true, "Should we enable this module?"),
        BADPACKETS_E_PUNISH("BadPacketsE.punish", "", "Punishment settings"),
        BADPACKETS_E_PUNISH_ENABLED("BadPacketsE.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_E_PUNISH_MODE("BadPacketsE.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_E_MAX_VL("BadPacketsE.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        BADPACKETS_F("BadPacketsF.enabled", true, "Should we enable this module?"),
        BADPACKETS_F_PUNISH("BadPacketsF.punish", "", "Punishment settings"),
        BADPACKETS_F_PUNISH_ENABLED("BadPacketsF.punish.enabled", true, "Should punishments be enabled for this check?"),
        BADPACKETS_F_PUNISH_MODE("BadPacketsF.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        BADPACKETS_F_MAX_VL("BadPacketsF.punish.vl", 10, "The maximum violation amount a player needs to reach in order to get punished"),

        NOSLOWDOWN_A("NoSlowdownA.enabled", true, "Should we enable this module?"),
        NOSLOWDOWN_A_PUNISH("NoSlowdownA.punish", "", "Punishment settings"),
        NOSLOWDOWN_A_PUNISH_ENABLED("NoSlowdownA.punish.enabled", false, "Should punishments be enabled for this check?"),
        NOSLOWDOWN_A_PUNISH_MODE("NoSlowdownA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        NOSLOWDOWN_A_MAX_VL("NoSlowdownA.punish.vl", 15, "The maximum violation amount a player needs to reach in order to get punished"),

        PHASE_A("PhaseA.enabled", false, "Should we enable this module?"),
        PHASE_A_PUNISH("PhaseA.punish", "", "Punishment settings"),
        PHASE_A_PUNISH_ENABLED("PhaseA.punish.enabled", false, "Should punishments be enabled for this check?"),
        PHASE_A_PUNISH_MODE("PhaseA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
        PHASE_A_MAX_VL("PhaseA.punish.vl", 50, "The maximum violation amount a player needs to reach in order to get punished"),

        VEHICLE_A("VehicleA.enabled", false, "Should we enable this module?"),
        VEHICLE_A_PUNISH("VehicleA.punish", "", "Punishment settings"),
        VEHICLE_A_PUNISH_ENABLED("VehicleA.punish.enabled", false, "Should punishments be enabled for this check?"),
        VEHICLE_A_PUNISH_MODE("VehicleA.punish.mode", "KICK", "What punish mode should we use for this check (KICK or BAN)"),
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
