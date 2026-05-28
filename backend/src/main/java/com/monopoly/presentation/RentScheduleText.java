package com.monopoly.presentation;

import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.RentTierTable;

import java.util.Locale;

public final class RentScheduleText {

    private RentScheduleText() {
    }

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
        return "拥有该色至少 1 张即可按阶梯收租；凑齐 " + need + " 张为该色完整套。"
                + " 平地基础租阶梯：" + tierLine
                + "。多套同色时按「整段套数 + 余张」累计表上金额。"
                + " 另：每间房屋 +3M，旅馆共 +7M（加在总租上）。"
                + " 铁路与公共事业不可加盖房屋/旅馆。";
    }

    public static String forWildCard() {
        return "部署时选一色计入该色轨道，租金阶梯与该单色一致。"
                + " 声明颜色后不可改色；任意色万能无货币价值，双色万能按牌面角标价值支付。";
    }

    public static String forColorKeyEn(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return "";
        }
        String ck = colorKey.trim().toUpperCase(Locale.ROOT);
        int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(ck, 3);
        int[] tiers = RentTierTable.tiersForColor(ck);
        StringBuilder tierLine = new StringBuilder();
        for (int i = 0; i < tiers.length; i++) {
            if (i > 0) {
                tierLine.append("; ");
            }
            boolean full = i == tiers.length - 1;
            tierLine.append(i + 1).append("->").append(tiers[i]).append("M");
            if (full) {
                tierLine.append(" (full set)");
            }
        }
        return "Need " + need + " to complete set; must have full set to charge rent."
                + " Base rent tiers: " + tierLine
                + ". Multiple sets accumulate."
                + " House +3M, Hotel +7M (added to total)."
                + " Railroad/Utility cannot have House/Hotel.";
    }

    public static String forWildCardEn() {
        return "Deploy as one color, rent follows that color's tier."
                + " Color is locked after declaration. Any-color wild has no monetary value; printed dual wilds use card face value.";
    }
}
