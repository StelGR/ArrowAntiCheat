package me.arrow.checks.enums;

import lombok.Getter;

/**
 * A checktype enumerations class that we'll use on our checks
 */
@Getter
public enum CheckType {
    AIM("Aim", CheckCategory.COMBAT),
    AUTOCLICKER("AutoClicker", CheckCategory.COMBAT),
    BADPACKETS("BadPackets", CheckCategory.WORLD),
    FLY("Fly", CheckCategory.MOVEMENT),
    KILLAURA("Killaura", CheckCategory.COMBAT),
    SCAFFOLD("Scaffold", CheckCategory.WORLD),
    SPEED("Speed", CheckCategory.MOVEMENT),
    MOTION("Motion", CheckCategory.MOVEMENT),
    ANALYSIS("Analysis", CheckCategory.MOVEMENT),
    ELYTRA("Elytra", CheckCategory.MOVEMENT),
    TIMER("Timer", CheckCategory.WORLD),
    REACH("Reach", CheckCategory.COMBAT),
    VELOCITY("Velocity", CheckCategory.COMBAT),
    INVENTORY("Inventory", CheckCategory.WORLD),
    INTERACT("Interact", CheckCategory.WORLD),
    GROUND("Ground", CheckCategory.WORLD),
    HITBOX("Hitbox", CheckCategory.COMBAT),
    PHASE("Phase", CheckCategory.WORLD),
    OMNISPRINT("OmniSprint", CheckCategory.MOVEMENT),
    NOSLOWDOWN("NoSlowdown", CheckCategory.MOVEMENT),
    VEHICLE("Vehicle", CheckCategory.MOVEMENT),
    BACKTRACK("BackTrack", CheckCategory.CONNECTION),
    XRAY("XRay", CheckCategory.WORLD),
    MACRO("Macro", CheckCategory.COMBAT);

    private final String checkName;
    private final CheckCategory checkCategory;

    CheckType(String checkName, CheckCategory checkCategory) {
        this.checkName = checkName;
        this.checkCategory = checkCategory;
    }

}