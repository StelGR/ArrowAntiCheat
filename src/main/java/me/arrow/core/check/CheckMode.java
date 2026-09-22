package me.arrow.core.check;

public enum CheckMode {

    FLAG("FLAG"),
    MITIGATE("MITIGATE"),
    BOTH("BOTH");

    private final String checkMode;

    CheckMode(String checkMode) {
        this.checkMode = checkMode;
    }

    public String getCheckMode() {
        return checkMode;
    }
}
