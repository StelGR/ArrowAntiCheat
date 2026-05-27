package me.arrow.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.files.Checks;
import me.arrow.managers.profile.Profile;

public class AutoClickerA extends Check {

    private double cps;
    private final int maxCPS ;

    public AutoClickerA(Profile profile) {
        super(profile, CheckType.AUTOCLICKER, "A", "Checks if the player is clicking above the cps limit");
        maxCPS = Checks.Setting.AUTOCLICKER_A_MAX_CPS.getInt();
    }

    @Override
    public void handle(PacketSendEvent event) {}

    @Override
    public void handle(PacketReceiveEvent event) {

        if (event.getPacketType().equals(PacketType.Play.Client.PLAYER_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_FLYING)
                || event.getPacketType().equals(PacketType.Play.Client.PLAYER_POSITION)) {

            if (!profile.getMovementData().isMoving()) {
                cps = 0;
                return;
            }
            cps = profile.getCombatData().getCurrentCps();


        }

        if (event.getPacketType().equals(PacketType.Play.Client.ANIMATION)) {
            if (cps >= maxCPS) {
                fail("CPS Limit",
                        "CPS " + MsgType.MAIN_THEME_COLOR.getMessage() + cps +
                                "\nMax CPS " + MsgType.MAIN_THEME_COLOR.getMessage() + maxCPS);
            }
        }
    }
}