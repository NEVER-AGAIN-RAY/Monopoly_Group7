package com.monopoly.model;

/**
 * 偷牌类行动卡作用的目标分区：财产区或银行堆。
 */
public enum StealTargetZone {
    PROPERTY,
    BANK;

    /** 与 {@link com.monopoly.model.dto.ActionParamContext#getTargetZone()} 字符串互转，默认财产区。 */
    public static StealTargetZone fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROPERTY;
        }
        return "BANK".equalsIgnoreCase(raw.trim()) ? BANK : PROPERTY;
    }
}
