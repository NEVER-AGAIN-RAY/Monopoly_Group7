package com.monopoly.pattern.factory;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameConstants;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;

import java.util.ArrayList;
import java.util.List;

/**
 * 具体工厂：生成 {@link GameConstants#STANDARD_DECK_SIZE} 张标准牌。
 * <p>
 * 房产牌使用与 {@link com.monopoly.model.PropertySetCalculator} 一致的颜色键（大写），按轨道轮换分配；
 * 另含少量万能房产牌。行动牌按 Monopoly Deal 官方比例分配真实效果码。
 * <p>
 * 官方行动卡分布（共 58 张）：
 * <ul>
 *   <li>RENT         × 10（5 种双色收租，每色 2 张）</li>
 *   <li>DOUBLE_RENT  × 3</li>
 *   <li>STEAL_PROPERTY (Sly Deal)     × 3</li>
 *   <li>FORCED_DEAL  × 4</li>
 *   <li>DEBT_COLLECTOR × 3</li>
 *   <li>RENT_WAIVER (Just Say No) × 3</li>
 *   <li>其余 32 张：Pass Go×2、House×3、Hotel×3、Birthday×3、Deal Breaker×2，以及补充 Birthday/Deal Breaker 以凑满 32（同 effectCode 可多张）</li>
 * </ul>
 */
public class MonopolyDealCardFactory extends CardFactory {

    /** 与 PropertySetCalculator 中标准轨道一致，按顺序轮换发牌颜色 */
    private static final String[] COLOR_CYCLE = {
            "BROWN",
            "LIGHT_BLUE",
            "PINK",
            "ORANGE",
            "RED",
            "YELLOW",
            "GREEN",
            "DARK_BLUE",
            "RAILROAD",
            "UTILITY"
    };

    private static final int PROPERTY_ROTATING_COUNT = 26;
    private static final int PROPERTY_WILD_COUNT = 4;
    /** 钱币卡（1M–5M 轮换），用于银行与支付测试 */
    private static final int MONEY_COUNT = 20;
    private static final int ACTION_COUNT = GameConstants.STANDARD_DECK_SIZE
            - PROPERTY_ROTATING_COUNT - PROPERTY_WILD_COUNT - MONEY_COUNT;

    /**
     * 行动卡效果码分布（顺序决定构造时的效果码，总数必须与 ACTION_COUNT 相等）。
     * 每种 effectCode 重复若干次以匹配官方比例。
     */
    private static final String[] ACTION_EFFECT_CYCLE;

    static {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) codes.add("RENT");
        for (int i = 0; i < 3;  i++) codes.add("DOUBLE_RENT");
        for (int i = 0; i < 3;  i++) codes.add("STEAL_PROPERTY");
        for (int i = 0; i < 4;  i++) codes.add("FORCED_DEAL");
        for (int i = 0; i < 3;  i++) codes.add("DEBT_COLLECTOR");
        for (int i = 0; i < 3;  i++) codes.add("RENT_WAIVER");
        addN(codes, "PASS_GO", 2);
        addN(codes, "HOUSE", 3);
        addN(codes, "HOTEL", 3);
        addN(codes, "BIRTHDAY", 3);
        addN(codes, "DEAL_BREAKER", 2);
        addN(codes, "BIRTHDAY", 10);
        addN(codes, "DEAL_BREAKER", 9);
        if (codes.size() != ACTION_COUNT) {
            throw new IllegalStateException(
                    "行动卡效果码总数必须为 " + ACTION_COUNT + "，当前=" + codes.size());
        }
        ACTION_EFFECT_CYCLE = codes.toArray(new String[0]);
    }

    private static void addN(List<String> list, String effectCode, int n) {
        for (int i = 0; i < n; i++) {
            list.add(effectCode);
        }
    }

    @Override
    protected Card createCard(String specKey) {
        if (specKey != null && specKey.startsWith("PROP")) {
            int idx = parseSuffix(specKey);
            String color = COLOR_CYCLE[Math.floorMod(idx, COLOR_CYCLE.length)];
            return new PropertyCard(specKey, "property-" + color + "-" + idx, color);
        }
        if (specKey != null && specKey.startsWith("WILD")) {
            return new PropertyWildCard(specKey, "wild-property-" + specKey);
        }
        if (specKey != null && specKey.startsWith("ACT_")) {
            int idx = parseSuffix(specKey);
            String effectCode = ACTION_EFFECT_CYCLE[Math.floorMod(idx, ACTION_EFFECT_CYCLE.length)];
            return new ActionCard(specKey, effectCode.toLowerCase() + "-" + idx, effectCode);
        }
        return new ActionCard(specKey, "action-" + specKey, "BIRTHDAY");
    }

    private static int parseSuffix(String specKey) {
        int u = specKey.lastIndexOf('_');
        if (u < 0 || u >= specKey.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(specKey.substring(u + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public List<Card> createStandardDeck108() {
        List<Card> deck = new ArrayList<>(GameConstants.STANDARD_DECK_SIZE);

        for (int i = 0; i < PROPERTY_ROTATING_COUNT; i++) {
            deck.add(createCard("PROP_" + i));
        }
        for (int i = 0; i < PROPERTY_WILD_COUNT; i++) {
            deck.add(createCard("WILD_" + i));
        }
        int[] moneyValues = {1, 2, 3, 4, 5};
        for (int i = 0; i < MONEY_COUNT; i++) {
            int m = moneyValues[i % moneyValues.length];
            deck.add(new MoneyCard("MONEY_" + i, m + "M", m));
        }
        for (int i = 0; i < ACTION_COUNT; i++) {
            deck.add(createCard("ACT_" + i));
        }

        if (deck.size() != GameConstants.STANDARD_DECK_SIZE) {
            throw new IllegalStateException(
                    "标准牌堆必须为 " + GameConstants.STANDARD_DECK_SIZE + " 张，当前=" + deck.size());
        }
        return deck;
    }
}
