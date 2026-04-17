package com.monopoly.presentation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.RentCalculator;

import java.util.Locale;
import java.util.Map;

/**
 * 将领域 {@link Card} 转为客户端可视化的 JSON 片段（MY_HAND 中每张牌）。
 */
public final class HandCardJson {

    private static final Map<String, String> COLOR_ZH = Map.ofEntries(
            Map.entry("BROWN", "棕色地段"),
            Map.entry("LIGHT_BLUE", "浅蓝地段"),
            Map.entry("PINK", "粉色地段"),
            Map.entry("ORANGE", "橙色地段"),
            Map.entry("RED", "红色地段"),
            Map.entry("YELLOW", "黄色地段"),
            Map.entry("GREEN", "绿色地段"),
            Map.entry("DARK_BLUE", "深蓝地段"),
            Map.entry("RAILROAD", "铁路"),
            Map.entry("UTILITY", "公共事业")
    );

    private static final Map<String, String> EFFECT_TITLE = Map.ofEntries(
            Map.entry("RENT", "收租"),
            Map.entry("RENT_DUAL", "双色收租"),
            Map.entry("DOUBLE_RENT", "租金加倍"),
            Map.entry("STEAL_PROPERTY", "暗中夺产"),
            Map.entry("FORCED_DEAL", "强制交易"),
            Map.entry("DEBT_COLLECTOR", "讨债"),
            Map.entry("RENT_WAIVER", "休想"),
            Map.entry("PASS_GO", "经过起点"),
            Map.entry("HOUSE", "房屋"),
            Map.entry("HOTEL", "旅馆"),
            Map.entry("BIRTHDAY", "生日礼金"),
            Map.entry("DEAL_BREAKER", "交易破坏者")
    );

    private static final Map<String, String> EFFECT_HINT = Map.ofEntries(
            Map.entry("RENT", "须该色完整套；按牌面租金表收租。选颜色与对手。"),
            Map.entry("RENT_DUAL", "卡面色组二选一（须该色完整套）；其余每名玩家依次付租，每人可打免租。"),
            Map.entry("DOUBLE_RENT", "下一张租金卡效果翻倍。"),
            Map.entry("STEAL_PROPERTY", "从一名对手财产区偷一张房产。"),
            Map.entry("FORCED_DEAL", "用你的财产与对手交换。"),
            Map.entry("DEBT_COLLECTOR", "指定对手向你支付 5M。"),
            Map.entry("RENT_WAIVER", "抵消针对你的收费或偷牌。"),
            Map.entry("PASS_GO", "多摸 2 张牌。"),
            Map.entry("HOUSE", "加在已有完整套上（非铁路/公共）。"),
            Map.entry("HOTEL", "加在已有房屋上（非铁路/公共）。"),
            Map.entry("BIRTHDAY", "每位其他玩家向你付 2M。"),
            Map.entry("DEAL_BREAKER", "拆掉对手一套完整房产。")
    );

    private HandCardJson() {
    }

    public static JsonObject toHandCardObject(Card card) {
        JsonObject o = new JsonObject();
        o.addProperty("id", card.getId());
        o.addProperty("name", card.getName());
        if (card instanceof MoneyCard mc) {
            o.addProperty("kind", "MONEY");
            o.addProperty("valueM", mc.getValueM());
            o.addProperty("titleZh", mc.getValueM() + "M 现金");
            o.addProperty("hintZh", "可存入银行，或用于支付租金。");
        } else if (card instanceof PropertyWildCard w) {
            o.addProperty("kind", "WILD");
            o.addProperty("valueM", w.getPaymentValue());
            o.addProperty("buildingLevel", w.getBuildingLevel().name());
            o.addProperty("wildKind", w.getWildPropertyKind().name());
            if (w.getWildPropertyKind() == PropertyWildCard.WildPropertyKind.DUAL_COLOR) {
                JsonArray pc = new JsonArray();
                for (String c : w.getPrintedColorPairView()) {
                    pc.add(c);
                }
                o.add("printedColors", pc);
                o.addProperty("titleZh", "双色万能（" + String.join("·", w.getPrintedColorPairView()) + "）");
                o.addProperty("hintZh", "部署时仅可声明为上述两色之一；作支付价值 0M。");
            } else {
                o.addProperty("titleZh", "万能房产（任意标准色）");
                o.addProperty("hintZh", "部署时可声明为任意标准颜色组之一；作支付价值 0M。");
            }
            o.addProperty("rentDetailZh", RentScheduleText.forWildCard());
        } else if (card instanceof PropertyCard pc) {
            String ck = pc.getColorGroup();
            if (ck != null) {
                ck = ck.toUpperCase(Locale.ROOT);
            }
            o.addProperty("kind", "PROPERTY");
            o.addProperty("colorGroup", ck == null ? "" : ck);
            int need = ck == null || ck.isBlank()
                    ? 3
                    : PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(ck, 3);
            o.addProperty("setNeed", need);
            o.addProperty("valueM", pc.getPaymentValue());
            o.addProperty("buildingLevel", pc.getBuildingLevel().name());
            String cz = ck == null ? "房产" : COLOR_ZH.getOrDefault(ck, ck);
            o.addProperty("titleZh", cz + " · 房产");
            int bBonus = (ck == null || ck.isBlank()) ? 0 : RentCalculator.buildingBonusM(pc);
            o.addProperty("hintZh",
                    "抵押价值 " + pc.getPaymentValue() + "M。凑齐 " + need + " 张为完整套后方可对该色收租（胜利条件同套数）。"
                            + " 本张对收租的建筑加值 " + bBonus + "M（房屋 +3M；旅馆共 +7M）；整套基础租见下方阶梯。");
            if (ck != null && !ck.isBlank()) {
                o.addProperty("rentDetailZh", RentScheduleText.forColorKey(ck));
            }
        } else if (card instanceof ActionCard ac) {
            String code = ac.getEffectCode() == null ? "" : ac.getEffectCode().toUpperCase(Locale.ROOT);
            o.addProperty("kind", "ACTION");
            o.addProperty("effectCode", code);
            o.addProperty("valueM", ac.getBankValueM());
            if (!ac.getRentPaletteView().isEmpty()) {
                JsonArray pal = new JsonArray();
                for (String c : ac.getRentPaletteView()) {
                    pal.add(c);
                }
                o.add("rentPalette", pal);
            }
            String title = EFFECT_TITLE.getOrDefault(code, "行动牌");
            if ("RENT".equals(code) && ac.isWildcardRentCard()) {
                title = "租金（任意色）";
            } else if ("RENT_DUAL".equals(code) && !ac.getRentPaletteView().isEmpty()) {
                title = title + "（" + String.join("·", ac.getRentPaletteView()) + "）";
            }
            o.addProperty("titleZh", title);
            String baseHint = EFFECT_HINT.getOrDefault(code, "打出后按提示选择目标。");
            if ("RENT".equals(code) && ac.isWildcardRentCard()) {
                baseHint = "卡面多色；任选一种你已有完整套的颜色，向一名对手收租。";
            } else if ("RENT_DUAL".equals(code) && ac.isRentDualChargesEachOtherPlayer()) {
                baseHint = "卡面两色选一（须完整套）；其余每名玩家依次付租，每人可打免租。";
            } else if ("RENT_DUAL".equals(code)) {
                baseHint = "卡面两色选一（须完整套）；向一名对手按该色收租。";
            }
            o.addProperty("hintZh", baseHint + " 存入银行作 " + ac.getBankValueM() + "M。");
        } else {
            o.addProperty("kind", "UNKNOWN");
            o.addProperty("titleZh", card.getName());
            o.addProperty("hintZh", "");
        }
        return o;
    }
}
