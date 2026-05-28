package com.monopoly.pattern.strategy;

import com.monopoly.model.card.Card;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;

import java.nio.file.Path;
import java.util.List;

/**
 * Search lookahead for normal card play, with an optional local ranker only for
 * payment decisions. This keeps the current buildingA play policy intact while
 * allowing small counterfactual PAYMENT models to be screened safely.
 */
public final class HybridLookaheadPaymentRankerAiPlayStrategy implements AiPlayStrategy, AiChoiceAdvisor {

    private final SearchLookaheadAiPlayStrategy lookahead;
    private final LocalRankerAiPlayStrategy paymentRanker;

    public HybridLookaheadPaymentRankerAiPlayStrategy(Path paymentModelPath) {
        this.lookahead = new SearchLookaheadAiPlayStrategy();
        this.paymentRanker = new LocalRankerAiPlayStrategy(paymentModelPath);
    }

    @Override
    public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
        return lookahead.tryPlayOneCard(bot, context, bridge);
    }

    @Override
    public AiHeuristics.AiResponseDecision chooseResponse(
            AIPlayer bot,
            GameContext context,
            boolean counterRole) {
        return lookahead.chooseResponse(bot, context, counterRole);
    }

    @Override
    public PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        return paymentRanker.choosePayment(bot, context, creditor, amountDue, fallbackChoice);
    }

    @Override
    public List<Card> chooseOverflowDiscards(
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards) {
        return lookahead.chooseOverflowDiscards(bot, context, limit, fallbackCards);
    }
}
