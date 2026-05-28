package com.monopoly.model.settlement;

/**
 * Steal target zone: property or bank.
 */
public enum StealTargetZone {
    PROPERTY,
    BANK;

    /** Convert the request string into a zone; unknown or blank values fall back to PROPERTY. */
    public static StealTargetZone fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROPERTY;
        }
        return "BANK".equalsIgnoreCase(raw.trim()) ? BANK : PROPERTY;
    }
}
