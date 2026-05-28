package com.monopoly.model.settlement;

/**
 * Steal target zone: property or bank.
 */
public enum StealTargetZone {
    PROPERTY,
    BANK;

    /** 与 ActionParamContext.getTargetZone 字符串互转，默认财产区。 */
    public static StealTargetZone fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROPERTY;
        }
        return "BANK".equalsIgnoreCase(raw.trim()) ? BANK : PROPERTY;
    }
}
