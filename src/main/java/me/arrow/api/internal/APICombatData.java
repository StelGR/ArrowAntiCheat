package me.arrow.api.internal;

import me.stel.data.CombatData;
import me.arrow.managers.profile.Profile;

public class APICombatData implements CombatData {
    Profile profile;

    public APICombatData(Profile profile) {
        this.profile = profile;
    }

    @Override
    public double getCurrentCps() {
        return profile.getCombatData().getCurrentCps();
    }

    @Override
    public double getAverageCps() {
        return profile.getCombatData().getAverageCps();
    }
}
