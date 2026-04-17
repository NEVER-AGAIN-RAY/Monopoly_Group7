package com.monopoly.model.settlement;

import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentSettlementExplicitTest {

    @Test
    void settleWithExplicit_transfersChosenCards_only() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        MoneyCard m1 = new MoneyCard("m1", "1", 1);
        MoneyCard m5 = new MoneyCard("m5", "5", 5);
        debtor.addToBank(m1);
        debtor.addToBank(m5);
        GameEngineSingleton engine = GameEngineSingleton.getInstance();

        PaymentSettlement.Result r = PaymentSettlement.settleWithExplicitCards(
                debtor, creditor, 3, List.of("m5"), engine);

        assertTrue(r.isSuccess());
        assertEquals(1, debtor.getBankCardCount());
        assertTrue(debtor.getBankCardsView().contains(m1));
        assertEquals(1, creditor.getBankCardCount());
        assertTrue(creditor.getBankCardsView().contains(m5));
    }

    @Test
    void settleWithExplicit_rejectsInsufficientSum() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        debtor.addToBank(new MoneyCard("m1", "1", 1));
        GameEngineSingleton engine = GameEngineSingleton.getInstance();

        PaymentSettlement.Result r = PaymentSettlement.settleWithExplicitCards(
                debtor, creditor, 5, List.of("m1"), engine);

        assertTrue(r.getStatus() == PaymentSettlement.Status.FAILED);
        assertEquals(1, debtor.getBankCardCount());
    }

    @Test
    void validateExplicitChoice_throwsWhenTooSmall() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        debtor.addToBank(new MoneyCard("m1", "1", 1));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentSettlement.validateExplicitChoice(debtor, 5, List.of("m1")));
    }

    @Test
    void settleWithExplicit_canUsePropertyCard() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        debtor.addToBank(new MoneyCard("m1", "1", 1));
        PropertyCard p = new PropertyCard("p1", "p", "GREEN");
        debtor.addToPropertyZone(p);
        GameEngineSingleton engine = GameEngineSingleton.getInstance();

        PaymentSettlement.Result r = PaymentSettlement.settleWithExplicitCards(
                debtor, creditor, 3, List.of("m1", "p1"), engine);

        assertTrue(r.isSuccess());
        assertEquals(0, debtor.getBankCardCount());
        assertEquals(0, debtor.getPropertyCardCount());
    }
}
