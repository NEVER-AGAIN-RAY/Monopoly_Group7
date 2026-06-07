package com.monopoly.model.card;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.Player;

/**
 * Property card with color group and building level.
 */
public class PropertyCard extends Card implements Payable {

    private final String colorGroup;
    private BuildingLevel buildingLevel = BuildingLevel.BASE;

    public PropertyCard(String id, String name, String colorGroup) {
        super(id, name);
        this.colorGroup = colorGroup;
    }

    public String getColorGroup() {
        return colorGroup;
    }

    public BuildingLevel getBuildingLevel() {
        return buildingLevel;
    }

    /** Attach or upgrade the building on this property (called by House/Hotel effects). */
    public void setBuildingLevel(BuildingLevel buildingLevel) {
        if (buildingLevel != null) {
            this.buildingLevel = buildingLevel;
        }
    }

    /** Whether this is a wild property whose color is resolved by PropertySetCalculator. */
    public boolean isWildProperty() {
        return false;
    }

    @Override
    public boolean canPlay(Player actor, ActionParamContext params, GameContext context) {
        return true;
    }

    /**
     * Value when surrendered from property zone to pay rent.
     */
    @Override
    public int getPaymentValue() {
        if (colorGroup == null || colorGroup.isBlank()) {
            return 3;
        }
        String k = colorGroup.trim().toUpperCase();
        return switch (k) {
            case "BROWN", "DARK_BLUE" -> 2;
            case "LIGHT_BLUE", "PINK", "ORANGE", "RED", "YELLOW", "GREEN" -> 3;
            case "RAILROAD" -> 4;
            case "UTILITY" -> 2;
            default -> 3;
        };
    }
}
