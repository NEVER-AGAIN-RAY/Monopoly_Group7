package com.monopoly.dto;

/**
 * 财产区按颜色统计的公开信息（单条：某颜色键下张数）。
 */
public class PropertyColorCount {

    private final String colorKey;
    private final int count;

    public PropertyColorCount(String colorKey, int count) {
        this.colorKey = colorKey;
        this.count = count;
    }

    public String getColorKey() {
        return colorKey;
    }

    public int getCount() {
        return count;
    }
}
