package me.arrow.api.events;

import lombok.Getter;
import me.arrow.checks.enums.CheckCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class AnticheatViolationEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    @Getter
    private final Player player;
    private final String checkName;

    @Getter
    private final String description;

    @Getter
    private final CheckCategory checkCategory;


    @Getter
    private final String type;

    @Getter
    private final String information;

    @Getter
    private final String informationTitle;

    @Getter
    private final int vl;

    @Getter
    private final int maxVl;

    @Getter
    private final boolean experimental;
    private boolean cancel = false;

    /**
     * This event will always be called async, Beware.
     */
    public AnticheatViolationEvent(Player player, String checkName, String description, CheckCategory checkCategory, String type, String informationTitle, String information, int vl, int maxVl, boolean experimental) {
        super(true);
        this.player = player;
        this.checkName = checkName;
        this.description = description;
        this.checkCategory = checkCategory;
        this.type = type;
        this.informationTitle = informationTitle;
        this.information = information;
        this.vl = vl;
        this.maxVl = maxVl;
        this.experimental = experimental;
    }

    public boolean isCancelled() {
        return this.cancel;
    }

    public void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    /**
     * @return The check included in this event
     */
    public String getCheck() {
        return checkName;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    public @NotNull HandlerList getHandlers() {
        return handlers;
    }
}