package com.monopoly.model;

import java.util.Locale;

/**
 * 万能房产牌：打出部署时需指定当作何种颜色使用，后端持久化 {@link #assignedColorKey} 后参与套数与收租计算。
 */
public class PropertyWildCard extends PropertyCard {

    /** 部署时由客户端/AI 传入并保存；未指定前不得计入任意颜色套数 */
    private String assignedColorKey;

    public PropertyWildCard(String id, String name) {
        super(id, name, null);
    }

    /**
     * 部署到财产区时调用，声明本万能牌计入的颜色键（与 {@link PropertySetCalculator} 一致，大写）。
     */
    public void setAssignedColorKey(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            this.assignedColorKey = null;
        } else {
            this.assignedColorKey = colorKey.trim().toUpperCase(Locale.ROOT);
        }
    }

    public String getAssignedColorKey() {
        return assignedColorKey;
    }

    @Override
    public boolean isWildProperty() {
        return true;
    }

    @Override
    public int getPaymentValue() {
        return 3;
    }
}
