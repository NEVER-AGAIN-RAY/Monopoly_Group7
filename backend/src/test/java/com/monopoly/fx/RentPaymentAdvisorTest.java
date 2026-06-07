package com.monopoly.fx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RentPaymentAdvisorTest {

    @Test
    void prefersBankCardsWhenBankCanCoverTheRent() {
        JsonObject player = player("""
                "bankCards":[{"id":"bank-2","valueM":2},{"id":"bank-3","valueM":3}],
                "propertyZoneCards":[{"id":"prop-4","valueM":4}]
                """);

        assertEquals(List.of("bank-2", "bank-3"), RentPaymentAdvisor.recommendedPaymentIds(player, 5));
    }

    @Test
    void usesPropertyOnlyWhenBankCannotCoverTheRent() {
        JsonObject player = player("""
                "bankCards":[{"id":"bank-2","valueM":2}],
                "propertyZoneCards":[{"id":"prop-3","valueM":3},{"id":"prop-5","valueM":5}]
                """);

        assertEquals(List.of("bank-2", "prop-3"), RentPaymentAdvisor.recommendedPaymentIds(player, 5));
    }

    @Test
    void choosesSmallestOverpaymentAndThenFewerProperties() {
        List<RentPaymentAdvisor.PaymentOption> options = List.of(
                new RentPaymentAdvisor.PaymentOption("bank-4", 4, "BANK"),
                new RentPaymentAdvisor.PaymentOption("prop-1", 1, "PROPERTY"),
                new RentPaymentAdvisor.PaymentOption("bank-6", 6, "BANK"));

        assertEquals(List.of("bank-6"), RentPaymentAdvisor.bestPaymentCardIds(options, 6));
    }

    private static JsonObject player(String body) {
        return JsonParser.parseString("{" + body + "}").getAsJsonObject();
    }
}
