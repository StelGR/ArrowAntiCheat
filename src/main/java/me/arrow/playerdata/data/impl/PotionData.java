package me.arrow.playerdata.data.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import lombok.Getter;
import lombok.Setter;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.Data;
import me.arrow.utils.custom.PotionType;
import org.bukkit.potion.PotionEffect;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.*;

@Getter
@Setter
public class PotionData implements Data {

    Profile profile;
    private boolean hasSpeed, hasJump, hasLevitation, hasSlowFalling;
    private int speedAmplifier, jumpAmplifier, levitationAmplifier, slowFallingAmplifier;
    private int speedTicks, jumpTicks, levitationTicks, slowFallingTicks;

    public PotionData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public void processReceive(PacketReceiveEvent event) {
        if (event.getPacketType().equals(PLAYER_POSITION)
                || event.getPacketType().equals(PLAYER_ROTATION)
                || event.getPacketType().equals(PLAYER_FLYING)
                || event.getPacketType().equals(PLAYER_POSITION_AND_ROTATION)) {

            // Speed
            if ((this.hasSpeed = hasPotion(PotionType.SPEED))) {
                this.speedAmplifier = getPotionEffectLevel(PotionType.SPEED);
                this.speedTicks += (this.speedTicks < 20 ? 1 : 0);
            } else {
                this.speedTicks -= (this.speedTicks > 0 ? 1 : 0);
            }

            // Jump Boost
            if ((this.hasJump = hasPotion(PotionType.JUMP_BOOST))) {
                this.jumpAmplifier = getPotionEffectLevel(PotionType.JUMP_BOOST);
                this.jumpTicks += (this.jumpTicks < 20 ? 1 : 0);
            } else {
                this.jumpTicks -= (this.jumpTicks > 0 ? 1 : 0);
            }

            // Levitation
            if ((this.hasLevitation = hasPotion(PotionType.LEVITATION))) {
                this.levitationAmplifier = getPotionEffectLevel(PotionType.LEVITATION);
                this.levitationTicks += (this.levitationTicks < 20 ? 1 : 0);
            } else {
                this.levitationTicks -= (this.levitationTicks > 0 ? 1 : 0);
            }

            // Slow Falling
            if ((this.hasSlowFalling = hasPotion(PotionType.SLOW_FALLING))) {
                this.slowFallingAmplifier = getPotionEffectLevel(PotionType.SLOW_FALLING);
                this.slowFallingTicks += (this.slowFallingTicks < 20 ? 1 : 0);
            } else {
                this.slowFallingTicks -= (this.slowFallingTicks > 0 ? 1 : 0);
            }
        }
    }

    @Override
    public void processSend( PacketSendEvent event) {

    }

    private boolean hasPotion(PotionType potionType) {
        return getPotionEffectLevel(potionType) > 0;
    }

    public int getPotionEffectLevel(PotionType potionType) {
        if (potionType == null || profile == null || profile.getPlayer() == null) {
            return 0;
        }

        try {
            for (PotionEffect effect : profile.getPlayer().getActivePotionEffects()) {
                if (effect == null) {
                    continue;
                }

                if (PotionType.isPotionEffect(effect.getType(), potionType)) {
                    return effect.getAmplifier() + 1;
                }
            }
        } catch (Throwable ignored) {
            // Keeps this safe across weird forks / version edge cases.
        }

        return 0;
    }
}
