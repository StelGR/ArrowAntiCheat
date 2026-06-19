package me.arrow.utils.custom;

import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.MovementData;
import org.bukkit.GameMode;

//idk if this is true nik but i trust you

/**
 * A simple class that we'll be using for exempting some checks, We'll cache the booleans every tick to
 * Save up some perfomance except for the ones that get updated by the server.
 * <p>
 * This is similar to Elevated's Exempt method however instead of using Predicates
 * We're caching the booleans as soon as we receive a packet for maximum perfomance.
 * <p>
 * This is a LOT faster especially when having a lot of checks, Using cached booleans instead of
 * Checking for example (player.getAllowFlight()) every single tick on every check.
 */
public class Exempt {

    private final Profile profile;

    public Exempt(Profile profile) {
        this.profile = profile;
    }

    @Getter @Setter
    private boolean movement, velocity, jesus, elytra, vehicle, autoclicker, aim, dead, flight, teleports, respawned, setback, reelingIn;

    public void handleExempts(long timeStamp) {

        MovementData movementData = profile.getMovementData();

        //Example
        this.movement = movementData.getDeltaXZ() == 0D && movementData.getDeltaY() == 0D;
        this.vehicle = profile.getPlayer().isInsideVehicle() || profile.getPlayer().getVehicle() != null;
        this.dead = profile.getPlayer().isDead();
        this.flight = profile.getPlayer().isFlying() || profile.getPlayer().getAllowFlight() || profile.getPlayer().getGameMode().equals(GameMode.SPECTATOR) || profile.getPlayer().getGameMode().equals(GameMode.CREATIVE);

        this.respawned = profile.getSinceDeathTimer().passed();

        this.reelingIn = profile.getReelingTicks().hasNotPassed();

        this.teleports = profile.getTeleportData().isTeleporting();
    }

    public boolean movement() {
        return this.movement;
    }

    public boolean velocity() {
        return this.velocity;
    }

    public boolean jesus() {
        return this.jesus;
    }

    public boolean autoclicker() {
        return this.autoclicker;
    }

    public boolean aim() {
        return this.aim;
    }

    public boolean elytra() {
        return this.elytra;
    }

    public boolean vehicle() {
        return this.vehicle;
    }
}