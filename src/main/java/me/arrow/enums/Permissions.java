package me.arrow.enums;

import lombok.Getter;

/**
 * A permissions enumerations class in order to cache our permissions and easily grab them
 */
@Getter
public enum Permissions {
    ADMIN("arrow.admin"), //used for GUI settings
    ALERTS("arrow.alerts"), // general access to alerts
    VERBOSE("arrow.verbose"), // ability to verbose
    SETBACKS("arrow.setbacks"), // ability to see what check caused setback
    DEBUG("arrow.debug"), // extra information on alerts in chat
    HOVER("arrow.hover"), // ability to see debug information on hover over the alert
    BASIC_ALERTS("arrow.alerts.basic"), // made for servers that want basic information, such as only the type of cheat the player used, nothing else on anticheat alerts (must have arrow.alerts)
    COMMAND_ALERTS("arrow.commands.alerts"),
    COMMAND_PROFILER("arrow.commands.profiler"), // command to toggle alerts on or off
    COMMAND_GUI("arrow.commands.gui"), // anticheat checks or settings
    COMMAND_RELOAD("arrow.commands.reload"), // not used
    COMMAND_VERBOSE("arrow.commands.verbose"), // toggle verbose feedback for your self
    COMMAND_SETBACKS("arrow.commands.setbacks"), // toggle setback feedback for your self
    COMMAND_PING("arrow.commands.ping"),// see your ping
    COMMAND_INFO("arrow.commands.info"), // see player info
    COMMAND_LOGS("arrow.commands.logs"); // player logs

    private final String permission;

    Permissions(String permission) {
        this.permission = permission;
    }

}