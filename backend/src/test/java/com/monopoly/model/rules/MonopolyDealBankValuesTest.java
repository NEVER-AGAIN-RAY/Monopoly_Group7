package com.monopoly.model.rules;

import com.monopoly.model.card.ActionCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MonopolyDealBankValuesTest {

    @Test
    void bankValueForActionEffect_matchesPublishedCornerValues() {
        assertEquals(1, MonopolyDealBankValues.bankValueForActionEffect("RENT"));
        assertEquals(1, MonopolyDealBankValues.bankValueForActionEffect("RENT_DUAL"));
        assertEquals(1, MonopolyDealBankValues.bankValueForActionEffect("DOUBLE_RENT"));
        assertEquals(1, MonopolyDealBankValues.bankValueForActionEffect("PASS_GO"));
        assertEquals(2, MonopolyDealBankValues.bankValueForActionEffect("BIRTHDAY"));
        assertEquals(3, MonopolyDealBankValues.bankValueForActionEffect("STEAL_PROPERTY"));
        assertEquals(3, MonopolyDealBankValues.bankValueForActionEffect("FORCED_DEAL"));
        assertEquals(3, MonopolyDealBankValues.bankValueForActionEffect("DEBT_COLLECTOR"));
        assertEquals(3, MonopolyDealBankValues.bankValueForActionEffect("HOUSE"));
        assertEquals(4, MonopolyDealBankValues.bankValueForActionEffect("HOTEL"));
        assertEquals(4, MonopolyDealBankValues.bankValueForActionEffect("RENT_WAIVER"));
        assertEquals(5, MonopolyDealBankValues.bankValueForActionEffect("DEAL_BREAKER"));
    }

    @Test
    void actionCard_paymentValue_usesBankTable() {
        ActionCard dealBreaker = new ActionCard("a", "n", "DEAL_BREAKER");
        assertEquals(5, dealBreaker.getBankValueM());
        assertEquals(5, dealBreaker.getPaymentValue());

        ActionCard rent = new ActionCard("r", "n", "rent");
        assertEquals(1, rent.getPaymentValue());
    }

    @Test
    void unknownEffect_defaultsToThree() {
        assertEquals(3, MonopolyDealBankValues.bankValueForActionEffect("MYSTERY"));
        ActionCard x = new ActionCard("x", "n", "MYSTERY");
        assertEquals(3, x.getPaymentValue());
    }
}
