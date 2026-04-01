package com.monopoly.model;

import com.monopoly.model.dto.ActionParamContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家抽象父类：维护手牌引用与身份；人类/AI 由子类区分行为。
 */
public abstract class Player {

    protected final String playerId;
    protected final String displayName;
    // 牌区：后续由控制器/网络层驱动状态推进，本类只负责维护分区数据结构
    protected final List<Card> handCards = new ArrayList<>();
    protected final List<PropertyCard> propertyCards = new ArrayList<>();
    protected final List<Card> bankCards = new ArrayList<>();
    protected final List<ActionCard> actionZoneCards = new ArrayList<>();

    protected Player(String playerId, String displayName) {
        this.playerId = playerId;
        this.displayName = displayName;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Card> getHandCardsView() {
        return Collections.unmodifiableList(handCards);
    }

    public List<PropertyCard> getPropertyCardsView() {
        return Collections.unmodifiableList(propertyCards);
    }

    public List<Card> getBankCardsView() {
        return Collections.unmodifiableList(bankCards);
    }

    public List<ActionCard> getActionZoneCardsView() {
        return Collections.unmodifiableList(actionZoneCards);
    }

    public int getHandCardCount() {
        return handCards.size();
    }

    public int getBankCardCount() {
        return bankCards.size();
    }

    public int getPropertyCardCount() {
        return propertyCards.size();
    }

    /**
     * 财产区已完成的完整地产集<strong>总套数</strong>（各色分别计套后相加；胜利条件为 {@code >= 3}，见 {@code docs/REQ_TRACE.md}）。
     */
    public int countCompletePropertySets() {
        return PropertySetCalculator.countCompletePropertySets(propertyCards);
    }

    public int getActionZoneCardCount() {
        return actionZoneCards.size();
    }

    /** 该玩家当前持有的全部牌张数：手牌 + 银行 + 财产区 + 行动区（用于全场牌数守恒校验）。 */
    public int countOwnedCardsTotal() {
        return handCards.size() + bankCards.size() + propertyCards.size() + actionZoneCards.size();
    }

    /** 摸牌/发牌时由控制器调用（把牌放入手牌区） */
    public void receiveCardToHand(Card card) {
        if (card != null) {
            handCards.add(card);
        }
    }

    /** 弃牌/处理后由控制器调用（从手牌区移除） */
    public void removeCardFromHand(Card card) {
        handCards.remove(card);
    }

    /**
     * 从手牌区弃置一张卡，并返回是否成功。
     */
    public boolean discardFromHand(Card card) {
        if (card == null) {
            return false;
        }
        return handCards.remove(card);
    }

    /**
     * 超过手牌上限时自动弃牌到指定上限（骨架：从手牌尾部依次弃置）。
     *
     * @param limit 手牌上限（例如 7）
     * @return 本次被弃置的卡牌列表，供控制器放入弃牌堆
     */
    public List<Card> discardOverflowTo(int limit) {
        List<Card> discarded = new ArrayList<>();
        if (limit < 0) {
            limit = 0;
        }
        while (handCards.size() > limit) {
            Card removed = handCards.remove(handCards.size() - 1);
            discarded.add(removed);
        }
        return discarded;
    }

    /**
     * 存入银行：把手牌卡牌转移到银行堆分区（骨架阶段：不做复杂合法性校验）。
     * 规则细节（例如 ActionCard 存入后失去功能）由控制器/引擎在后续实现。
     */
    public void depositToBank(Card card) {
        if (card == null) {
            return;
        }
        removeCardFromHand(card);
        bankCards.add(card);
    }

    public void addToBank(Card card) {
        if (card != null) {
            bankCards.add(card);
        }
    }

    public boolean removeFromBank(Card card) {
        return card != null && bankCards.remove(card);
    }

    public boolean removePropertyCard(PropertyCard card) {
        return card != null && propertyCards.remove(card);
    }

    /** 直接往财产区放一张房产（用于偷牌/交换到达己方时，不经过手牌区）。 */
    public void addToPropertyZone(PropertyCard card) {
        if (card != null) {
            propertyCards.add(card);
        }
    }

    /** 银行堆中所有可支付牌的总面值（M）。 */
    public int totalBankValueM() {
        int s = 0;
        for (Card c : bankCards) {
            s += PayableCards.valueOf(c);
        }
        return s;
    }

    /** 财产区房产用于支付时的可抵押总值（M，不含手牌）。 */
    public int totalPropertyPaymentValueM() {
        int s = 0;
        for (PropertyCard p : propertyCards) {
            s += PayableCards.valueOf(p);
        }
        return s;
    }

    /**
     * 部署房产：把手牌中的房产卡转移到财产区分区（骨架阶段：只做类型转移）。
     */
    public void deployProperty(PropertyCard card) {
        if (card == null) {
            return;
        }
        removeCardFromHand(card);
        propertyCards.add(card);
    }

    /**
     * 执行动作卡：把手牌中的行动卡转移到行动区分区。
     * 注意：行动结算逻辑不在本类实现，仅维护分区数据。
     */
    public void placeActionToCenter(ActionCard card) {
        if (card == null) {
            return;
        }
        removeCardFromHand(card);
        actionZoneCards.add(card);
    }

    /**
     * 把“是否可出牌”的判断交给 Card 的 canPlay 实现；{@code params} 可为 null（非参数化出牌）。
     */
    public boolean canPlay(Card card, ActionParamContext params, GameContext context) {
        if (card == null) {
            return false;
        }
        if (context == null) {
            context = new GameContext();
        }
        return card.canPlay(this, params, context);
    }

    /**
     * 轮到自己时请求“下一步可执行动作决策”。
     * - HumanPlayer：等待客户端发来动作请求，后端骨架可为空实现
     * - AIPlayer：由策略模式计算下一步动作（在子类实现）
     */
    public abstract void requestPlayDecision(GameContext context);
}
