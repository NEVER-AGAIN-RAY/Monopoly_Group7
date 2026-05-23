package com.monopoly.pattern.strategy;

import com.monopoly.model.card.Card;
import com.monopoly.model.player.AIPlayer;
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

    default List<Card> chooseOverflowDiscards(AIPlayer bot, int limit, List<Card> fallbackCards) {
        return fallbackCards;
    }
}
