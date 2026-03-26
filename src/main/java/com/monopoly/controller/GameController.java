package com.monopoly.controller;

import com.monopoly.model.GameContext;
import com.monopoly.model.Player;
import com.monopoly.model.dto.GameStateSnapshot;
import com.monopoly.pattern.factory.CardFactory;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.singleton.GameEngineSingleton;

/**
 * 【Facade 外观模式】
 * 对 Flutter 客户端/网络层暴露统一的高层操作入口，隐藏摸牌、租金结算、回合推进等协作细节。
 * 骨架阶段仅做依赖编排与空方法签名。
 */
public class GameController {

    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();
    private final TurnManager turnManager = new TurnManager();
    private final CardFactory cardFactory = new MonopolyDealCardFactory();
    private final GameUpdateSubject gameUpdateSubject;

    public GameController(GameUpdateSubject gameUpdateSubject) {
        this.gameUpdateSubject = gameUpdateSubject;
    }

    /** 创建对局、绑定玩家与牌堆初始化入口 */
    public void startNewSession(String sessionId) {
        // 骨架：会话创建、工厂生成 108 张牌、装入引擎等
        engine.attachDrawPile(cardFactory.createStandardDeck108());
        pushSnapshot(sessionId, "INIT");
    }

    /** 摸牌：封装抽牌堆、补堆、广播等内部步骤 */
    public void drawCards(Player player, int count, GameContext context) {
        // 骨架
        pushSnapshot("unknown", "DRAW");
    }

    /** 出牌并触发效果链 */
    public void playCard(Player player, String cardId, GameContext context) {
        // 骨架
        pushSnapshot("unknown", "PLAY");
    }

    /** 租金结算入口（内部将协调 Payable、银行、财产退回等） */
    public void requestRentPayment(Player from, Player to, int amount, GameContext context) {
        // 骨架
        pushSnapshot("unknown", "RENT");
    }

    /** 结束回合：弃牌限制、轮转 */
    public void endTurn(Player player) {
        // 骨架
        turnManager.advanceTurn();
        pushSnapshot("unknown", "TURN_END");
    }

    public TurnManager getTurnManager() {
        return turnManager;
    }

    private void pushSnapshot(String sessionId, String phase) {
        GameStateSnapshot snap = new GameStateSnapshot();
        snap.setSessionId(sessionId);
        snap.setPhase(phase);
        gameUpdateSubject.notifyStateChanged(snap);
    }
}
