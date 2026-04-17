package com.monopoly.dto;

/**
 * 某颜色在财产区的凑套进度（公开信息，用于客户端展示）。
 */
public final class PropertyColorProgress {

    private final String colorKey;
    private final int effectiveCount;
    private final int need;
    private final int completeSets;

    public PropertyColorProgress(String colorKey, int effectiveCount, int need, int completeSets) {
        this.colorKey = colorKey;
        this.effectiveCount = effectiveCount;
        this.need = need;
        this.completeSets = completeSets;
    }

    public String getColorKey() {
        return colorKey;
    }

    public int getEffectiveCount() {
        return effectiveCount;
    }

    public int getNeed() {
        return need;
    }

    public int getCompleteSets() {
        return completeSets;
    }
}
