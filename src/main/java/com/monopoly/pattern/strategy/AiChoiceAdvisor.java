package com.monopoly.pattern.strategy;

import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;

import java.util.List;

/**
 * Optional AI hooks for non-play decisions such as payment and discard.
 */
public interface AiChoiceAdvisor {

    default PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        return fallbackChoice;
    }

    default PaymentSettlement.PaymentChoice choosePayment(
            AIPlayer bot,
            GameContext context,
            Player creditor,
            int amountDue,
            PaymentSettlement.PaymentChoice fallbackChoice) {
        return choosePayment(bot, amountDue, fallbackChoice);
    }

    default AiHeuristics.AiResponseDecision chooseResponse(
            AIPlayer bot,
            GameContext context,
            boolean counterRole) {
        return AiHeuristics.chooseResponse(bot, context, counterRole);
    }

    default List<Card> chooseOverflowDiscards(AIPlayer bot, int limit, List<Card> fallbackCards) {
        return fallbackCards;
    }

    default List<Card> chooseOverflowDiscards(
            AIPlayer bot,
            GameContext context,
            int limit,
            List<Card> fallbackCards) {
        return chooseOverflowDiscards(bot, limit, fallbackCards);
    }
}
