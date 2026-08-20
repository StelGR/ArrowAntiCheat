package me.arrow.checks.impl.misc.interact;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.customutils.OtherUtility;

// uh idk seems obvious...

@Experimental
public class InteractC extends Check {
    public InteractC(Profile profile) {
        super(profile, CheckType.INTERACT, "C", "Checks for invalid block order");
    }

    boolean attacking;

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY)) {
            WrapperPlayClientInteractEntity attack = new WrapperPlayClientInteractEntity(event);

            if (attack.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                attacking = true;
            }

            boolean invalid1 = profile.getPredictionData().isUseShield() && attacking;
            boolean invalid2 = profile.getPlayer().isBlocking() && attacking;
//
//            if (invalid1)
//            {
//                fail("Attacking while interacting (1)","(No Debug Provided)");
//            }
            if (invalid2)
            {
                int requiredBuffer = profile.getTrustFactor().getRequiredBuffer();

                if (increaseBuffer() > requiredBuffer) {
                    if (profile.getTrustFactor().getTrust() >= 80) {
                        profile.getTrustFactor().decreaseTrustBy(5);
                    } else {
                        fail("Attacking while blocking with a shield", "(No Debug Provided)");
                        profile.getTrustFactor().decreaseTrustBy(20);
                    }
                }
            }
            else {
                decreaseBufferBy(0.5);
                if (profile.getTick() % 20 == 0 && profile.getTick() != 0) {
                    profile.getTrustFactor().increaseTrustBy(0.0125);
                }
            }

            //else decreaseBufferBy(0.5);
        }
        else if (OtherUtility.isFlying(event.getPacketType())) {
            attacking = false;
        }
    }
}
