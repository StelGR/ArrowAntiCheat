package me.arrow.backend;

/**
 * Supported server software platforms detected adaptively at runtime.
 */
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

    public String getFriendlyName() {
        return friendlyName;
    }

}
