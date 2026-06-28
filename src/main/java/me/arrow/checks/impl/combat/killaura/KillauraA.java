package me.arrow.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import me.arrow.Arrow;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.CombatData;
import me.arrow.playerdata.data.impl.MovementData;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

// never seen this flag, probably cus i can't code

public class KillauraA extends Check {

    private int attacks;

    public KillauraA(Profile profile) {
        super(profile, CheckType.KILLAURA, "A", "Checks for keep sprint");
    }

    @Override
    public void handle(PacketSendEvent event) {
    }

    @Override
    public void handle(PacketReceiveEvent event) {

        PacketTypeCommon packetType = event.getPacketType();

        if (packetType.equals(PacketType.Play.Client.PLAYER_FLYING)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION)
                || packetType.equals(PacketType.Play.Client.PLAYER_ROTATION)
                || packetType.equals(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {

            if (profile.shouldCancel()) {
                attacks = 0;
                resetBuffer();
                return;
            }

            MovementData movementData = profile.getMovementData();
            ActionData actionData = profile.getActionData();

            if (attacks > 0) {

                if (!profile.getPlayer().isInsideVehicle()
                        && movementData.getDeltaXZ() > 0.1
                        && movementData.getSinceIceTicks() > 2
                        && !profile.getVelocityData().isTakingVelocity()) {
                    double deltaX = movementData.getLastDeltaX() * (movementData.isLastLastOnGround() ? movementData.getLastFrictionFactor() : 0.91F);
                    double deltaZ = movementData.getLastDeltaZ() * (movementData.isLastLastOnGround() ? movementData.getLastFrictionFactor() : 0.91F);
                    deltaX *= 0.6;
                    deltaZ *= 0.6;
                    double deltaXZ = hypot(deltaX, deltaZ);
                    double attackMotion = Math.abs(movementData.getDeltaXZ() - deltaXZ);
                    double acceleration = Math.abs(movementData.getAccelXZ());
                    double extrabuffer = actionData.isSprinting() ? 1.0 : 0.0;
                    double moveSpeed = 0.1F;
                    moveSpeed += 0.1F * 0.3F;
                    if (!(attackMotion > moveSpeed) || !(acceleration < 0.005)) {
                        decreaseBufferBy(0.4);
                    } else if ((increaseBufferBy(0.5 + extrabuffer)) > 6.0) {
                        fail("Invalid motion when attacking",
                                "motion " + attackMotion + "/" + MsgType.MAIN_THEME_COLOR.getMessage() + moveSpeed
                                        + "\nacceleration " + MsgType.MAIN_THEME_COLOR.getMessage() + acceleration
                                        + "\nattacks " + MsgType.MAIN_THEME_COLOR.getMessage() + this.attacks);
                    }
                    else {
                        this.verbose(this.getClass().getSimpleName(), getBuffer(), 6.0,
                                "* Invalid motion when attacking\n §f* motion: §b" + attackMotion + "/" + moveSpeed + "\n §f* acceleration: §b" + acceleration + "\n §f* attacks: §b" + this.attacks
                        );
                    }
                } else {
                    decreaseBufferBy(0.2);
                }
            }

            attacks = 0;

        } else if (packetType.equals(PacketType.Play.Client.INTERACT_ENTITY)) {

            WrapperPlayClientInteractEntity useEntityPacket = new WrapperPlayClientInteractEntity(event);

            if (useEntityPacket.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {

                int entityId = useEntityPacket.getEntityId();
                Player player = profile.getPlayer();
                CombatData combatData = profile.getCombatData();

                Profile attackedProfile;
                try {
                    attackedProfile = Arrow.getInstance().getProfileManager().getProfile(
                            Objects.requireNonNull(Bukkit.getPlayer(combatData.getTrackedEntities().get(entityId)))
                    );
                } catch (Exception e) {
                    attackedProfile = null;
                }

                if (attackedProfile == null) {
                    return;
                }

                combatData.setTarget(attackedProfile.getPlayer().getEntityId());

                ItemStack inHand = player.getItemInHand();
                int kbLevel = inHand.getEnchantmentLevel(Enchantment.KNOCKBACK);

                if (kbLevel < 0) {
                    return;
                }

                if (profile.getActionData().isLastSprinting() || kbLevel > 0) {
                    attacks++;
                }
            }
        }
    }

    public static double hypot(double... value) {
        double total = 0.0;

        for (double val : value) {
            total += val * val;
        }

        return FastMath.sqrt(total);
    }
}
