package com.monopoly.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家抽象父类：维护手牌引用与身份；人类/AI 由子类区分行为。
 */
public abstract class Player {

    protected final String playerId;
    protected final String displayName;
    protected final List<Card> hand = new ArrayList<>();

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

    public List<Card> getHandView() {
        return Collections.unmodifiableList(hand);
    }

    /** 摸牌/发牌时由控制器调用，骨架阶段仅占位 */
    protected void receiveCard(Card card) {
        hand.add(card);
    }

    /** 出牌时由控制器调用，骨架阶段仅占位 */
    protected void removeCardFromHand(Card card) {
        hand.remove(card);
    }
}
