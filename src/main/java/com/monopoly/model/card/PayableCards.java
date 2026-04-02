package com.monopoly.model.card;

/**
 * 从卡牌读取支付面值（M）的工具。
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
