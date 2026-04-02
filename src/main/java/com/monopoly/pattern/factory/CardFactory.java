package com.monopoly.pattern.factory;

import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameConstants;

import java.util.List;

/**
 * 【Factory Method 工厂方法模式】
 * 抽象创建者：声明用于生成单张/整套牌的工厂方法，由具体工厂子类决定
 * {@link GameConstants#STANDARD_DECK_SIZE} 张标准牌的实例化细节。
 */
public abstract class CardFactory {

    /**
     * 工厂方法：由子类实现具体 Card 的创建方式（类型、参数、扩展点）。
     */
    protected abstract Card createCard(String specKey);

    /**
     * 模板流程：组合工厂方法产出完整牌堆（洗牌等不在此实现）。
     */
    public abstract List<Card> createStandardDeck108();
}
