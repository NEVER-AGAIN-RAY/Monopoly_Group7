package com.monopoly.model;

/**
 * 万能房产牌：可替代任意颜色以凑齐完整地产集（分配逻辑见 {@link PropertySetCalculator}）。
 */
public class PropertyWildCard extends PropertyCard {

    public PropertyWildCard(String id, String name) {
        super(id, name, null);
    }

    @Override
    public boolean isWildProperty() {
        return true;
    }
}
