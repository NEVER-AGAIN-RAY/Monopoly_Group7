package com.monopoly.model;

/**
 * 房产卡：可部署到财产区，参与收租计算（具体计算不在此实现）。
 */
public class PropertyCard extends Card implements Payable {

    private final String colorGroup;

    public PropertyCard(String id, String name, String colorGroup) {
        super(id, name);
        this.colorGroup = colorGroup;
    }

    public String getColorGroup() {
        return colorGroup;
    }

    @Override
    public boolean canPlay(Player actor, GameContext context) {
        // 骨架：后续补充“部署房产”合法性
        return true;
    }

    @Override
    public int getPaymentValue() {
        // 骨架：后续按规则返回抵押/支付价值
        return 0;
    }
}
