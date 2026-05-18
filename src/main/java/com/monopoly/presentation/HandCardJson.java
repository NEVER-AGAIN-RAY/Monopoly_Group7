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

    private static final Map<String, String> COLOR_EN = Map.ofEntries(
            Map.entry("BROWN", "Brown"),
            Map.entry("LIGHT_BLUE", "Light Blue"),
            Map.entry("PINK", "Pink"),
            Map.entry("ORANGE", "Orange"),
            Map.entry("RED", "Red"),
            Map.entry("YELLOW", "Yellow"),
            Map.entry("GREEN", "Green"),
            Map.entry("DARK_BLUE", "Dark Blue"),
            Map.entry("RAILROAD", "Railroad"),
            Map.entry("UTILITY", "Utility")
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

    private static final Map<String, String> EFFECT_TITLE_EN = Map.ofEntries(
            Map.entry("RENT", "Rent"),
            Map.entry("RENT_DUAL", "Dual-Color Rent"),
            Map.entry("DOUBLE_RENT", "Double Rent"),
            Map.entry("STEAL_PROPERTY", "Sly Deal"),
            Map.entry("FORCED_DEAL", "Forced Deal"),
            Map.entry("DEBT_COLLECTOR", "Debt Collector"),
            Map.entry("RENT_WAIVER", "Just Say No"),
            Map.entry("PASS_GO", "Pass Go"),
            Map.entry("HOUSE", "House"),
            Map.entry("HOTEL", "Hotel"),
            Map.entry("BIRTHDAY", "Birthday"),
            Map.entry("DEAL_BREAKER", "Deal Breaker")
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

    private static final Map<String, String> EFFECT_HINT_EN = Map.ofEntries(
            Map.entry("RENT", "Requires a complete set of that color; charge rent per the rent table. Pick color and opponent."),
            Map.entry("RENT_DUAL", "Pick one of the two colors (requires complete set); all other players pay rent in turn, each may play Just Say No."),
            Map.entry("DOUBLE_RENT", "Doubles the next rent card played."),
            Map.entry("STEAL_PROPERTY", "Steal one property from an opponent."),
            Map.entry("FORCED_DEAL", "Swap one of your properties with an opponent's."),
            Map.entry("DEBT_COLLECTOR", "Force an opponent to pay you 5M."),
            Map.entry("RENT_WAIVER", "Cancel a charge or steal targeting you."),
            Map.entry("PASS_GO", "Draw 2 extra cards."),
            Map.entry("HOUSE", "Place on a complete set (not Railroad/Utility)."),
            Map.entry("HOTEL", "Place on a set that already has a House (not Railroad/Utility)."),
            Map.entry("BIRTHDAY", "Every other player pays you 2M."),
            Map.entry("DEAL_BREAKER", "Steal an opponent's complete property set.")
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
            o.addProperty("titleEn", mc.getValueM() + "M Cash");
            o.addProperty("hintEn", "Deposit to bank or use to pay rent.");
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
                o.addProperty("titleEn", "Dual Wild (" + String.join("/", w.getPrintedColorPairView()) + ")");
                o.addProperty("hintEn", "Deploy as one of the two colors above; payment value 0M.");
            } else {
                o.addProperty("titleZh", "万能房产（任意标准色）");
                o.addProperty("hintZh", "部署时可声明为任意标准颜色组之一；作支付价值 0M。");
                o.addProperty("titleEn", "Wild Property (Any Color)");
                o.addProperty("hintEn", "Deploy as any standard color group; payment value 0M.");
            }
            o.addProperty("rentDetailZh", RentScheduleText.forWildCard());
            o.addProperty("rentDetailEn", RentScheduleText.forWildCardEn());
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
            String ce = ck == null ? "Property" : COLOR_EN.getOrDefault(ck, ck);
            o.addProperty("titleEn", ce + " Property");
            int bBonus = (ck == null || ck.isBlank()) ? 0 : RentCalculator.buildingBonusM(pc);
            o.addProperty("hintZh",
                    "抵押价值 " + pc.getPaymentValue() + "M。凑齐 " + need + " 张为完整套后方可对该色收租（胜利条件同套数）。"
                            + " 本张对收租的建筑加值 " + bBonus + "M（房屋 +3M；旅馆共 +7M）；整套基础租见下方阶梯。");
            o.addProperty("hintEn",
                    "Mortgage value " + pc.getPaymentValue() + "M. Collect " + need + " cards for a complete set to charge rent."
                            + " Building bonus " + bBonus + "M (House +3M; Hotel +7M total). See rent tiers below.");
            if (ck != null && !ck.isBlank()) {
                o.addProperty("rentDetailZh", RentScheduleText.forColorKey(ck));
                o.addProperty("rentDetailEn", RentScheduleText.forColorKeyEn(ck));
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
            String titleEn = EFFECT_TITLE_EN.getOrDefault(code, "Action");
            if ("RENT".equals(code) && ac.isWildcardRentCard()) {
                title = "租金（任意色）";
                titleEn = "Rent (Any Color)";
            } else if ("RENT_DUAL".equals(code) && !ac.getRentPaletteView().isEmpty()) {
                title = title + "（" + String.join("·", ac.getRentPaletteView()) + "）";
                titleEn = titleEn + " (" + String.join("/", ac.getRentPaletteView()) + ")";
            }
            o.addProperty("titleZh", title);
            o.addProperty("titleEn", titleEn);
            String baseHint = EFFECT_HINT.getOrDefault(code, "打出后按提示选择目标。");
            String baseHintEn = EFFECT_HINT_EN.getOrDefault(code, "Play and follow prompts to pick a target.");
            if ("RENT".equals(code) && ac.isWildcardRentCard()) {
                baseHint = "卡面多色；任选一种你已有完整套的颜色，向一名对手收租。";
                baseHintEn = "Multi-color card; pick any color you have a complete set of to charge one opponent.";
            } else if ("RENT_DUAL".equals(code) && ac.isRentDualChargesEachOtherPlayer()) {
                baseHint = "卡面两色选一（须完整套）；其余每名玩家依次付租，每人可打免租。";
                baseHintEn = "Pick one of two colors (requires complete set); all other players pay rent, each may play Just Say No.";
            } else if ("RENT_DUAL".equals(code)) {
                baseHint = "卡面两色选一（须完整套）；向一名对手按该色收租。";
                baseHintEn = "Pick one of two colors (requires complete set); charge one opponent rent.";
            }
            o.addProperty("hintZh", baseHint + " 存入银行作 " + ac.getBankValueM() + "M。");
            o.addProperty("hintEn", baseHintEn + " Bank value " + ac.getBankValueM() + "M.");
        } else {
            o.addProperty("kind", "UNKNOWN");
            o.addProperty("titleZh", card.getName());
            o.addProperty("hintZh", "");
            o.addProperty("titleEn", card.getName());
            o.addProperty("hintEn", "");
        }
        return o;
    }
}
