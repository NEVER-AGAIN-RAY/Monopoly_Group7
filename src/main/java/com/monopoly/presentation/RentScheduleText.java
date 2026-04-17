package com.monopoly.presentation;

import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.RentTierTable;

import java.util.Locale;

/**
 * 手牌/公开区房产卡上的中文租金说明（与 {@link RentTierTable}、{@link PropertySetCalculator} 一致）。
 */
public final class RentScheduleText {

    private RentScheduleText() {
    }

    /**
     * 某颜色：套数需求、牌面阶梯租（1 张…满套）、房/旅馆加值说明。
     */
    public static String forColorKey(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return "";
        }
        String ck = colorKey.trim().toUpperCase(Locale.ROOT);
        int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(ck, 3);
        int[] tiers = RentTierTable.tiersForColor(ck);
        StringBuilder tierLine = new StringBuilder();
        for (int i = 0; i < tiers.length; i++) {
            if (i > 0) {
                tierLine.append("；");
            }
            boolean full = i == tiers.length - 1;
            tierLine.append(i + 1).append(" 张→").append(tiers[i]).append("M");
            if (full) {
                tierLine.append("（满套）");
            }
        }
        return "凑齐 " + need + " 张为该色完整套；须完整套方可对该色收租。"
                + " 平地整套基础租阶梯：" + tierLine
                + "。多套同色时按「整段套数 + 余张」累计表上金额。"
                + " 另：每间房屋 +3M，旅馆共 +7M（加在总租上）。"
                + " 铁路与公共事业不可加盖房屋/旅馆。";
    }

    /** 万能牌：说明无实体双色地权卡。 */
    public static String forWildCard() {
        return "部署时选一色计入该色轨道，租金阶梯与该单色一致。"
                + " 本牌堆为简化万能模型，无实体双色地权卡；作支付价值 0M。";
    }
}
