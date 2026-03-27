package com.monopoly.pattern.factory;

import com.monopoly.model.ActionCard;
import com.monopoly.model.Card;
import com.monopoly.model.MoneyCard;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.PropertyWildCard;

import java.util.ArrayList;
import java.util.List;

/**
 * 具体工厂：生成 108 张标准牌。
 * <p>
 * 房产牌使用与 {@link com.monopoly.model.PropertySetCalculator} 一致的颜色键（大写），按轨道轮换分配；
 * 另含少量万能房产牌。其余为行动牌占位（效果码后续细化）。
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
    private static final int ACTION_COUNT = 108 - PROPERTY_ROTATING_COUNT - PROPERTY_WILD_COUNT - MONEY_COUNT;

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
        return new ActionCard(specKey, "action-" + specKey, "EFFECT_PLACEHOLDER");
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
        List<Card> deck = new ArrayList<>(108);

        for (int i = 0; i < PROPERTY_ROTATING_COUNT; i++) {
            String spec = "PROP_" + i;
            deck.add(createCard(spec));
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
            deck.add(new ActionCard("ACT_" + i, "action-" + i, "EFFECT_PLACEHOLDER"));
        }

        if (deck.size() != 108) {
            throw new IllegalStateException("标准牌堆必须为 108 张，当前=" + deck.size());
        }
        return deck;
    }
}
