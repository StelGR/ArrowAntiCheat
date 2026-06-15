package me.arrow.api.internal;

import me.arrow.data.CombatData;
import me.arrow.managers.profile.Profile;

public final class APICombatData implements CombatData {
    private final Profile profile;

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
