package com.monopoly.model.card;

/**
 * Marker for cards usable in rent payment.
 */
public interface Payable {

    /**
     * @return payment value in M
     */
    int getPaymentValue();
}
