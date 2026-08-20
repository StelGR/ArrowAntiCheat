package me.arrow.checks.impl.movement.speed;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckType;
import me.arrow.checks.types.Check;
import me.arrow.enums.MsgType;
import me.arrow.managers.profile.Profile;
import me.arrow.playerdata.data.impl.ActionData;
import me.arrow.playerdata.data.impl.MovementData;
import me.arrow.playerdata.data.impl.PredictionData;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.custom.desync.DesyncType;
import me.arrow.utils.customutils.OtherUtility;


// this is a basic NoSlow check, with some of the functions disabled as they are not complete, you can re-enable them and try to fix them but it has falses
// and the useItem stuff isn't spot on either

@Experimental
public class NoSlowdown extends Check {

    int usageTicks;
    int sneakingTicks;

    public NoSlowdown(Profile profile) {
        super(profile, CheckType.NOSLOWDOWN, "A", "Detects for correct slowdown when using items or blocking");
    }

    @Override
    public void handle(PacketSendEvent event) {

    }

    @Override
    public void handle(PacketReceiveEvent event) {
        if (OtherUtility.isFlying(event.getPacketType())) {

            if (profile.shouldCancel()) return;

            MovementData movementData = profile.getMovementData();
            ActionData actionData = profile.getActionData();
            PredictionData predictionData = profile.getPredictionData();

            sneakingTicks = profile.isSneaking() ? sneakingTicks + 1 : 0;

            usageTicks = (predictionData.isUseItem() || predictionData.isUseShield() || predictionData.isUseSword()) ? usageTicks + 1 : 0;


            verbose(this.getClass().getSimpleName(), movementData.getDeltaXZ(), movementData.getDeltaY(), " * Verbose\n * deltaXZ " + movementData.getDeltaXZ()
                    + "\n * spriting " + actionData.isSprinting()
                    + "\n * lastSpriting " + actionData.isLastSprinting()
                    + "\n * lastLastSpriting " + actionData.isLastLastSprinting()
                    + "\n * isGround " + movementData.isOnGround()
                    + "\n * isUseItem " + predictionData.isUseItem()
                    + "\n * isUseSword " + predictionData.isUseSword()
                    + "\n * isUseShield " + predictionData.isUseShield()
                    + "\n * honeyTicks " + movementData.getMovingOnHoneyTicks()
                    + "\n * soulTicks " + movementData.getMovingOnSoulTicks()
                    + "\n * slimeTicks " + movementData.getMovingOnSlimeTicks());

            NoSlowA(movementData, actionData, predictionData);
            //NoSlowB(movementData, actionData, predictionData);
            //NoSlowC(movementData, actionData, predictionData);
            //NoSlowD(movementData, actionData, predictionData);
        }
    }

    public void NoSlowA(MovementData movementData, ActionData actionData, PredictionData predictionData) {
        boolean invalid1 = profile.getPlayer().getFoodLevel() < 4 && actionData.isSprinting();

        if (invalid1) fail("Sprinting while hungry", "(No Debug Information)");
    }

    public void NoSlowB(MovementData movementData, ActionData actionData, PredictionData predictionData) {
        if (profile.getMovementData().getSinceGlidingTicks() < 10) return;

        if (movementData.getMovingOnHoneyTicks() > 10 || movementData.getMovingOnSoulTicks() > 10 || movementData.getMovingOnSlimeTicks() > 10) {

            if (movementData.getClientGroundTicks() > 7) {
                if (movementData.getDeltaXZ() > 0.1665) fail("Not Slowing down (Honey/Soulsand)", "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ() + "\nclientGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientGroundTicks());
            }
        }
    }

    public void NoSlowC(MovementData movementData, ActionData actionData, PredictionData predictionData) {
        int swiftSneak = movementData.getEquipment().getSwiftSneakLevel();

        if (profile.getVelocityData().isTakingVelocity()) return;

        double baseNoSprint = 0.092;
        double baseSprint = 0.12;

        double capNoSprint = baseNoSprint * (1.0 + (0.15 * swiftSneak));
        double capSprint = baseSprint * (1.0 + (0.15 * swiftSneak));

        boolean invalid3 = profile.isSneaking()
                && (actionData.isSprinting() && actionData.isLastSprinting() && actionData.isLastLastSprinting()
                ? movementData.getDeltaXZ() > capSprint
                : movementData.getDeltaXZ() > capNoSprint)
                && sneakingTicks > 10;

        if (invalid3) {
            actionData.getDesync().fix(DesyncType.SNEAKING);
            fail("Invalid motion when sneaking", "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ());
        }
    }

    public void NoSlowD(MovementData movementData, ActionData actionData, PredictionData predictionData) {
        if (profile.getMovementData().getSinceGlidingTicks() < 10) return;

        boolean invalid2 = (predictionData.isUseItem() || predictionData.isUseShield())
                && ((actionData.isSprinting()
                && actionData.isLastSprinting()
                && actionData.isLastLastSprinting()) ? movementData.getDeltaXZ() > 0.08 : movementData.getDeltaXZ() > 0.062)
                && movementData.getClientGroundTicks() > 7
                && usageTicks > 15
                && ((MaterialType.isMaterial(profile.getPlayer().getInventory().getItemInMainHand().getType().name(), MaterialType.SHIELD)
                || MaterialType.isMaterial(profile.getPlayer().getInventory().getItemInMainHand().getType().name(), MaterialType.BOW))
                || (MaterialType.isMaterial(profile.getPlayer().getInventory().getItemInOffHand().getType().name(), MaterialType.SHIELD)
                || MaterialType.isMaterial(profile.getPlayer().getInventory().getItemInOffHand().getType().name(), MaterialType.BOW)));

        if (invalid2) {
            actionData.getDesync().fix(DesyncType.BLOCKING);
            fail("Illegal movement when using an item", "deltaXZ " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getDeltaXZ()
                    + "\nusageTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + usageTicks
                    + "\nclientGroundTicks " + MsgType.MAIN_THEME_COLOR.getMessage() + movementData.getClientGroundTicks());
            predictionData.setUseItem(false);
            predictionData.setUseSword(false);
            predictionData.setUseShield(false);
        }
    }
}
