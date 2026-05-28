package com.monopoly.dto;

/**
 * One row: color key and card count.
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
