package me.arrow.checks.impl.misc.badpackets;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

// self explanetory

public class BadPacketsB extends Check {
    public BadPacketsB(Profile profile) {
        super(profile, CheckType.BADPACKETS, "B", "Checks for vehicle/abilities disabler");
    }

    private double ticks;
    private boolean sent;

    double buffer;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.STEER_VEHICLE)) {
            WrapperPlayClientSteerVehicle steerVehiclePacket = new WrapperPlayClientSteerVehicle(event);

            sent = profile.getTick() > 20 && !steerVehiclePacket.isUnmount();
        } else if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION) || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            if (sent) {
                if (ticks++ > 3.0D) {
                    if (profile.getPlayer().getVehicle() == null && !profile.getPlayer().isInsideVehicle()) {
                        if (buffer++ > 5.0) {
                            fail("Sent vehicle packet without being in a vehicle", "(No Debug Provided)");
                        }
                    } else {
                        buffer -= Math.min(buffer, 0.75);
                    }
                }
                sent = false;
            } else {
                ticks = 0;
            }
        }
        else if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ABILITIES)) {
            WrapperPlayClientPlayerAbilities abilitiesPacket = new WrapperPlayClientPlayerAbilities(event);
            if (abilitiesPacket.isFlying() && !profile.getPlayer().isFlying() && !profile.getPlayer().getAllowFlight() && profile.getTick() > 60) {
                if (++buffer > 5.0D) {
                    //profile.getMovementData().getSetbackProcessor().causeSetBack(getFullCheckName());
                    fail("Sent ability packet without flying", "(No Debug Provided)");
                }
            } else {
                buffer = 0.0D;
            }
        }
    }
}