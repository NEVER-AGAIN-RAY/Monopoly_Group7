package com.monopoly.pattern.factory;

import com.monopoly.model.ActionCard;
import com.monopoly.model.Card;
import com.monopoly.model.PropertyCard;

import java.util.ArrayList;
import java.util.List;

/**
 * 具体工厂：按 Monopoly Deal 标准配置生成 108 张牌（此处仅占位循环与分支，无数值规则）。
 */
public class MonopolyDealCardFactory extends CardFactory {

    @Override
    protected Card createCard(String specKey) {
        // 骨架：根据 specKey 解析并返回 PropertyCard / ActionCard / MoneyCard 等
        if (specKey != null && specKey.startsWith("PROP")) {
            return new PropertyCard(specKey, "property-" + specKey, "UNKNOWN_COLOR");
        }
        return new ActionCard(specKey, "action-" + specKey, "EFFECT_PLACEHOLDER");
    }

    @Override
    public List<Card> createStandardDeck108() {
        List<Card> deck = new ArrayList<>(108);
        // 骨架：按规则表填充 108 张，此处仅占位
        for (int i = 0; i < 108; i++) {
            deck.add(createCard("CARD_" + i));
        }
        return deck;
    }
}
