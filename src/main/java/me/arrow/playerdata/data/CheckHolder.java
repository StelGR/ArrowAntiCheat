package me.arrow.playerdata.data;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import lombok.Getter;
import me.arrow.checks.annotations.Testing;
import me.arrow.checks.impl.combat.aimassist.*;
import me.arrow.checks.impl.combat.autoclicker.*;
import me.arrow.checks.impl.combat.backtrack.BackTrackA;
import me.arrow.checks.impl.combat.backtrack.BackTrackB;
import me.arrow.checks.impl.combat.killaura.KillauraA;
import me.arrow.checks.impl.combat.reach.ReachA;
import me.arrow.checks.impl.combat.reach.ReachB;
import me.arrow.checks.impl.combat.velocity.VelocityA;
import me.arrow.checks.impl.combat.velocity.VelocityB;
import me.arrow.checks.impl.misc.badpackets.*;
import me.arrow.checks.impl.misc.interact.InteractA;
import me.arrow.checks.impl.misc.interact.InteractB;
import me.arrow.checks.impl.misc.interact.InteractC;
import me.arrow.checks.impl.misc.inventory.InventoryA;
import me.arrow.checks.impl.misc.inventory.InventoryB;
import me.arrow.checks.impl.misc.inventory.InventoryC;
import me.arrow.checks.impl.misc.phase.PhaseA;
import me.arrow.checks.impl.misc.scaffold.*;
import me.arrow.checks.impl.misc.timer.TimerA;
import me.arrow.checks.impl.misc.timer.TimerB;
import me.arrow.checks.impl.misc.vehicle.VehicleA;
import me.arrow.checks.impl.movement.fly.*;
import me.arrow.checks.impl.movement.ground.GroundA;
import me.arrow.checks.impl.movement.ground.GroundB;
import me.arrow.checks.impl.movement.ground.GroundC;
import me.arrow.checks.impl.movement.motion.*;
import me.arrow.checks.impl.movement.speed.*;
import me.arrow.checks.types.Check;
import me.arrow.managers.profile.Profile;

import java.util.Arrays;

public class CheckHolder {

    private final Profile profile;
    @Getter
    private Check[] checks;
    @Getter
    private int checksSize;
    private boolean testing; //Used for testing new checks

    public CheckHolder(Profile profile) {
        this.profile = profile;
    }



    public void runChecks(PacketReceiveEvent event) {
        /*
        Fastest way to loop through many objects, If you think this is stupid
        Then benchmark the long term perfomance yourself with many profilers and java articles.
         */
        for (int i = 0; i < this.checksSize; i++) this.checks[i].handle(event);


    }

    public void runChecks(PacketSendEvent event) {
        /*
        Fastest way to loop through many objects, If you think this is stupid
        Then benchmark the long term perfomance yourself with many profilers and java articles.
         */
        for (int i = 0; i < this.checksSize; i++) this.checks[i].handle(event);
    }

    public void registerAll() {

        /*
         * Check initialization
         */
        addChecks(
                new AimA(this.profile),
                new AimB(this.profile),
                new AimC(this.profile),
                new AimD(this.profile),
                new AimE(this.profile),
                new AimF(this.profile),

                new AutoClickerA(this.profile),
                new AutoClickerB(this.profile),
                new AutoClickerC(this.profile),
                new AutoClickerD(this.profile),
                new AutoClickerE(this.profile),
                new AutoClickerF(this.profile),
                new AutoClickerG(this.profile),
                new MacroA(this.profile),

                new KillauraA(this.profile),

                new BackTrackA(this.profile),
                new BackTrackB(this.profile),

                new ReachA(this.profile),
                new ReachB(this.profile),


                // both vel checks are terrible.
                new VelocityA(this.profile),
                new VelocityB(this.profile),

                new InteractA(this.profile),
                new InteractB(this.profile),
                new InteractC(this.profile),

                new InventoryA(this.profile),
                new InventoryB(this.profile),
                new InventoryC(this.profile),

                new ScaffoldA(this.profile),
                new ScaffoldB(this.profile),
                new ScaffoldC(this.profile),


                new SpeedA(this.profile),
                new SpeedB(this.profile),
                new SpeedC(this.profile),

                new NoSlowdown(this.profile),
                new OmniSprintA(this.profile),

                new GroundA(this.profile),
                new GroundB(this.profile),
                new GroundC(this.profile),

                new ElytraA(this.profile),
                new FlyA(this.profile),
                new FlyB(this.profile),
                new FlyC(this.profile),

                new MotionA(this.profile),
                new MotionB(this.profile),
                new MotionC(this.profile),
                new MotionD(this.profile),
                new MotionE(this.profile),
                new MotionF(this.profile),
                new MotionG(this.profile),

                new BadPacketsA(this.profile),
                new BadPacketsB(this.profile),
                new BadPacketsC(this.profile),

                //new PhaseA(this.profile),

                new TimerA(this.profile),
                new TimerB(this.profile),
                new VehicleA(this.profile)

        );

        /*
        Remove checks if a testing check is present.
         */
        testing:
        {

            /*
            Testing check not present, break.
             */
            if (!this.testing) break testing;

            /*
            Remove the rest of the checks since a testing check is present.
             */
            this.checks = Arrays.stream(this.checks)
                    .filter(check -> check.getClass().isAnnotationPresent(Testing.class))
                    .toArray(Check[]::new);

            /*
            Update the size since we're only going to be running one check.
             */
            this.checksSize = 1;
        }
    }

    private void addChecks(Check... checks) {

        /*
        Create a new check array to account for reloads.
         */
        this.checks = new Check[0];

        /*
        Reset the check size to account for reloads
         */
        this.checksSize = 0;

        /*
        Loop through the input checks
         */
        for (Check check : checks) {

            /*
            Check if this is being used by a GUI, where we put null as the profile
            Or a check with the @Testing annotation is present or disabled.
             */
            if (this.profile != null && (isTesting(check))) continue;

            /*
            Copy the original array and increment the size just like an ArrayList.
             */
            this.checks = Arrays.copyOf(this.checks, this.checksSize + 1);

            /*
            Update the check.
             */
            this.checks[this.checksSize] = check;

            /*
            Update the check size variable for improved looping perfomance
             */
            this.checksSize++;
        }
    }

    /**
     * If a check with the testing annotation is present, It'll set the testing boolean to true, load it and then
     * Prevent any other checks from registering.
     */
    private boolean isTesting(Check check) {

        if (this.testing) return true;

        /*
        Update the variable and return false in order to register this check
        But not the next ones.
         */
        if (check.getClass().isAnnotationPresent(Testing.class)) this.testing = true;

        return false;
    }

}