package com.monopoly.controller;

import com.monopoly.model.ActionCard;
import com.monopoly.model.AIPlayer;
import com.monopoly.model.Card;
import com.monopoly.model.GameContext;
import com.monopoly.model.HumanPlayer;
import com.monopoly.model.Player;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.dto.GameStateSnapshot;
import com.monopoly.pattern.factory.CardFactory;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.EasyAiPlayStrategy;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【Facade 外观模式】
 * 对 Flutter 客户端/网络层暴露统一的高层操作入口，隐藏摸牌、租金结算、回合推进等协作细节。
 * 骨架阶段仅做依赖编排与空方法签名。
 */
public class GameController {

    private enum TurnPhase {
        DRAW,
        PLAY,
        END
    }

    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();
    private final TurnManager turnManager = new TurnManager();
    private final CardFactory cardFactory = new MonopolyDealCardFactory();
    private final GameUpdateSubject gameUpdateSubject;
    private final List<Player> sessionPlayers = new ArrayList<>();
    private String currentSessionId = "unknown";

    // 仅骨架阶段使用：记录“当前轮次玩家”的出牌/行动次数，避免一个回合打出超过 3 张牌
    private static final int MAX_ACTIONS_PER_TURN = 3;
    private static final int MAX_HAND_SIZE = 7;
    private static final int INITIAL_HAND_SIZE = 5;
    private String currentTurnPlayerId;
    private int currentTurnActionCount;
    private TurnPhase currentTurnPhase;

    public GameController(GameUpdateSubject gameUpdateSubject) {
        this.gameUpdateSubject = gameUpdateSubject;
        this.currentTurnPlayerId = null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;
    }

    /** 创建对局、绑定玩家与牌堆初始化入口 */
    public void startNewSession(String sessionId) {
        currentSessionId = (sessionId == null || sessionId.isBlank()) ? "session-default" : sessionId;
        engine.attachDrawPile(cardFactory.createStandardDeck108());

        sessionPlayers.clear();
        HumanPlayer human = new HumanPlayer("human-1", "Human");
        AIPlayer ai = new AIPlayer("ai-1", "AI-Easy", new EasyAiPlayStrategy());
        sessionPlayers.add(human);
        sessionPlayers.add(ai);
        turnManager.bindTurnOrder(sessionPlayers);

        // 初始化发牌：每人 5 张
        for (Player p : sessionPlayers) {
            for (int i = 0; i < INITIAL_HAND_SIZE; i++) {
                Card card = engine.drawOne();
                if (card == null) {
                    break;
                }
                p.receiveCardToHand(card);
            }
        }

        Player current = turnManager.getCurrentPlayer();
        this.currentTurnPlayerId = current != null ? current.getPlayerId() : null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;
        pushSnapshot(currentSessionId, "INIT");
    }

    /** 摸牌：封装抽牌堆、补堆、广播等内部步骤 */
    public void drawCards(Player player, int count) {
        if (player == null) {
            return;
        }
        ensureTurnContext(player);
        if (currentTurnPhase != TurnPhase.DRAW) {
            throw new IllegalStateException("当前不是摸牌阶段，不能重复摸牌。");
        }
        if (count <= 0) {
            return;
        }
        // 真实抽牌骨架：从引擎抽牌堆取牌，抽到空堆就提前结束
        for (int i = 0; i < count; i++) {
            Card card = engine.drawOne();
            if (card == null) {
                break;
            }
            player.receiveCardToHand(card);
        }
        // 摸牌阶段完成后进入出牌阶段
        currentTurnPhase = TurnPhase.PLAY;
        // 提示网络层刷新状态（骨架 phase）
        pushSnapshot(currentSessionId, "DRAW");
    }

    /** 出牌并触发效果链 */
    public void playCard(Player player, Card card, String actionType) {
        if (player == null || card == null) {
            return;
        }
        ensureTurnContext(player);
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("当前不是出牌阶段，请先完成摸牌。");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("该卡牌不在当前玩家手牌中，不能打出。");
        }
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType 不能为空。");
        }
        String normalizedActionType = actionType.trim().toUpperCase();

        // 每回合最多可打出三张牌（所有出牌行为累计）
        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            throw new IllegalStateException("每回合最多可出 3 张牌，已达到上限。");
        }
        currentTurnActionCount++;

        // 根据 actionType 把牌转移到对应分区（不实现复杂合法性/效果计算）
        if ("DEPOSIT".equals(normalizedActionType)) {
            player.depositToBank(card);
        } else if ("DEPLOY".equals(normalizedActionType)) {
            if (!(card instanceof PropertyCard)) {
                throw new IllegalArgumentException("DEPLOY 需要 PropertyCard（房产卡）。");
            }
            player.deployProperty((PropertyCard) card);
        } else if ("ACTION".equals(normalizedActionType)) {
            if (!(card instanceof ActionCard)) {
                throw new IllegalArgumentException("ACTION 需要 ActionCard（行动卡）。");
            }
            player.placeActionToCenter((ActionCard) card);
        } else {
            throw new IllegalArgumentException("未知 actionType: " + actionType);
        }

        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            // 达到上限后允许直接结束回合
            currentTurnPhase = TurnPhase.END;
        }

        pushSnapshot(currentSessionId, normalizedActionType);
    }

    /** 租金结算入口（内部将协调 Payable、银行、财产退回等） */
    public void requestRentPayment(Player from, Player to, int amount, GameContext context) {
        // 骨架
        pushSnapshot(currentSessionId, "RENT");
    }

    /** 结束回合：弃牌限制、轮转 */
    public void endTurn(Player player) {
        if (player == null) {
            return;
        }
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.DRAW) {
            throw new IllegalStateException("当前回合尚未摸牌，不能结束回合。");
        }

        // 执行真实弃牌到上限（骨架版自动弃牌）
        if (player.getHandCardCount() > MAX_HAND_SIZE) {
            List<Card> discarded = player.discardOverflowTo(MAX_HAND_SIZE);
            engine.discardMany(discarded);
            System.out.println("[FORCE_DISCARD] 玩家 " + player.getPlayerId() + " 弃牌 " + discarded.size() + " 张至上限。");
        }

        if (checkWinCondition(player)) {
            pushSnapshot(currentSessionId, "GAME_OVER");
            return;
        }

        turnManager.advanceTurn();

        // 回合推进后重置行动计数对象
        Player next = turnManager.getCurrentPlayer();
        this.currentTurnPlayerId = next != null ? next.getPlayerId() : null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;

        pushSnapshot(currentSessionId, "TURN_END");

        // 人机模式：若下一位是 AI，自动执行一个最小 AI 回合
        if (next instanceof AIPlayer ai) {
            executeAiTurn(ai);
        }
    }

    public TurnManager getTurnManager() {
        return turnManager;
    }

    public List<Player> getSessionPlayersView() {
        return Collections.unmodifiableList(sessionPlayers);
    }

    public Player getCurrentPlayer() {
        return turnManager.getCurrentPlayer();
    }

    public void handleDrawCommand(int count) {
        Player current = requireCurrentPlayer();
        drawCards(current, count);
    }

    public void handlePlayCommand(int handIndex, String actionType) {
        Player current = requireCurrentPlayer();
        if (handIndex < 0 || handIndex >= current.getHandCardsView().size()) {
            throw new IllegalArgumentException("handIndex 越界。");
        }
        Card card = current.getHandCardsView().get(handIndex);
        playCard(current, card, actionType);
    }

    public void handleEndTurnCommand() {
        Player current = requireCurrentPlayer();
        endTurn(current);
    }

    private void ensureTurnContext(Player player) {
        String pid = player.getPlayerId();
        if (currentTurnPlayerId == null) {
            currentTurnPlayerId = pid;
            currentTurnActionCount = 0;
            currentTurnPhase = TurnPhase.DRAW;
            return;
        }
        if (!currentTurnPlayerId.equals(pid)) {
            throw new IllegalStateException("当前不是玩家 " + pid + " 的回合。");
        }
    }

    private Player requireCurrentPlayer() {
        Player current = turnManager.getCurrentPlayer();
        if (current == null) {
            throw new IllegalStateException("当前没有可行动玩家，请先 startNewSession。");
        }
        return current;
    }

    private void executeAiTurn(AIPlayer ai) {
        // 骨架：调用策略入口，随后执行最小自动流程
        ai.requestPlayDecision(new GameContext());
        drawCards(ai, 2);

        int played = 0;
        while (played < MAX_ACTIONS_PER_TURN && !ai.getHandCardsView().isEmpty()) {
            Card card = ai.getHandCardsView().get(0);
            try {
                if (card instanceof PropertyCard) {
                    playCard(ai, card, "DEPLOY");
                } else if (card instanceof ActionCard) {
                    playCard(ai, card, "ACTION");
                } else {
                    playCard(ai, card, "DEPOSIT");
                }
                played++;
            } catch (RuntimeException ex) {
                break;
            }
        }
        endTurn(ai);
    }

    private boolean checkWinCondition(Player player) {
        return player.countCompletePropertySets() >= 3;
    }

    private void pushSnapshot(String sessionId, String phase) {
        GameStateSnapshot snap = new GameStateSnapshot();
        snap.setSessionId(sessionId);
        snap.setPhase(phase);
        snap.setCurrentPlayerId(currentTurnPlayerId);
        snap.setTurnPhase(currentTurnPhase == null ? "UNKNOWN" : currentTurnPhase.name());
        snap.setDrawPileCount(engine.remainingCount());
        snap.setDiscardPileCount(engine.discardCount());
        for (Player p : sessionPlayers) {
            snap.addPlayerSummary(
                    p.getPlayerId(),
                    p.getDisplayName(),
                    p.getHandCardCount(),
                    p.getBankCardCount(),
                    p.getPropertyCardCount(),
                    p.getActionZoneCardCount(),
                    p.countCompletePropertySets()
            );
        }
        gameUpdateSubject.notifyStateChanged(snap);
    }
}
