package com.monopoly.controller;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.pattern.strategy.AiPlayStrategy;

/**
 * AI 回合执行，从 {@link GameController} 抽出。
 * <p>
 * 人机模式下，回合推进到 AI 玩家时由 {@link GameController#endTurn} 自动触发。
 * AI 出牌通过 {@link AiPlayStrategy#tryPlayOneCard} 调用 {@link GameController}
 * （{@link com.monopoly.model.AiGameBridge} 接口），保证人机走同一套校验与效果链路。
 */
final class AiTurnService {

    private final GameController controller;
    private final TurnFlowService turnFlow;

    AiTurnService(GameController controller, TurnFlowService turnFlow) {
        this.controller = controller;
        this.turnFlow = turnFlow;
    }

    void executeAiTurn(AIPlayer ai) {
        controller.ensureNotPaused();
        controller.ensureSessionActive();
        controller.getGameContext().bindPlayers(controller.getSessionPlayersView());
        turnFlow.drawCards(ai, 2);

        int played = 0;
        AiPlayStrategy strategy = ai.getPlayStrategy();
        while (played < TurnFlowService.MAX_ACTIONS_PER_TURN
                && !ai.getHandCardsView().isEmpty()) {
            boolean progressed = strategy != null
                    && strategy.tryPlayOneCard(ai, controller.getGameContext(), controller);
            if (!progressed) {
                break;
            }
            played++;
        }
        controller.endTurn(ai);
    }
}
