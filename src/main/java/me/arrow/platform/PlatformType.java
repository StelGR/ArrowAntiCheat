package me.arrow.platform;

import lombok.Getter;

/**
 * Supported server software platforms detected adaptively at runtime.
 */
@Getter
public enum PlatformType {
    BUKKIT("Bukkit"),
    SPIGOT("Spigot"),
    PAPER("Paper"),
    FOLIA("Folia"),
    FABRIC("Fabric");

    private final String friendlyName;

    PlatformType(String friendlyName) {
        this.friendlyName = friendlyName;
    }

}
