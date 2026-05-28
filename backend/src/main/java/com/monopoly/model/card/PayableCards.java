package com.monopoly.model.card;

/**
 * Reads Payable face value from any card.
 */
public final class PayableCards {

    private PayableCards() {
    }

    public static int valueOf(Card card) {
        if (card instanceof Payable p) {
            return Math.max(0, p.getPaymentValue());
        }
        return 0;
    }
}
