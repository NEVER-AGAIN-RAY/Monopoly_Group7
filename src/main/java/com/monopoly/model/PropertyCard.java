package com.monopoly.model;

/**
 * 房产卡：可部署到财产区，参与收租计算（具体计算不在此实现）。
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

    public void setBuildingLevel(BuildingLevel buildingLevel) {
        if (buildingLevel != null) {
            this.buildingLevel = buildingLevel;
        }
    }

    /** 是否为万能房产牌（可计入任意颜色套数，由 {@link PropertySetCalculator} 分配）。 */
    public boolean isWildProperty() {
        return false;
    }

    @Override
    public boolean canPlay(Player actor, GameContext context) {
        // 骨架：后续补充“部署房产”合法性
        return true;
    }

    /**
     * 抵押/支付价值：用于支付租金时从财产区退回弃牌堆的折算（M）。
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
