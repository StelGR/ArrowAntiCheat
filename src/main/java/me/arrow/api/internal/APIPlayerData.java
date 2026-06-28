package me.arrow.api.internal;


import me.arrow.Arrow;
import me.stel.data.PlayerData;
import me.arrow.managers.logs.PlayerLog;
import me.arrow.managers.profile.Profile;

import java.util.List;

public class APIPlayerData implements PlayerData {
    private final Profile profile;
    private final APICombatData combatData;

    public APIPlayerData(Profile profile) {
        this.profile = profile;
        this.combatData = new APICombatData(profile);
    }

    @Override
    public APICombatData getCombatData() {
        return combatData;
    }

    @Override
    public double getSensitivity() {
        return profile.getRotationData().getSensitivityProcessor().getSensitivityPercent();
    }

    @Override
    public String getLastFlaggedCheck() {
        return profile.getLastFlaggedCheck();
    }

    @Override
    public int getTotalLogs() {
        List<PlayerLog> logs = Arrow.getInstance().getLogManager()
                .getLogExporter()
                .getLogsForPlayer(profile.getPlayer().getName());
        return logs.size();
    }
}