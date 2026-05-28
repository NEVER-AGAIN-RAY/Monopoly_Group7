package com.monopoly.model.settlement;

import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(creditor.getHandCardsView().isEmpty());
    }

    @Test
    void settleWithExplicit_rejectsInsufficientSum() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        MoneyCard m1 = new MoneyCard("m1", "1", 1);
        debtor.addToBank(m1);
        debtor.addToBank(new MoneyCard("m2", "2", 2));
        GameEngineSingleton engine = GameEngineSingleton.getInstance();

        PaymentSettlement.Result r = PaymentSettlement.settleWithExplicitCards(
                debtor, creditor, 5, List.of("m1"), engine);

        assertTrue(r.getStatus() == PaymentSettlement.Status.FAILED);
        assertEquals(2, debtor.getBankCardCount());
    }

    @Test
    void validateExplicitChoice_throwsWhenTooSmall() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        debtor.addToBank(new MoneyCard("m1", "1", 1));
        debtor.addToBank(new MoneyCard("m2", "2", 2));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentSettlement.validateExplicitChoice(debtor, 5, List.of("m1")));
    }

    @Test
    void settleWithExplicit_canUsePropertyCard() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        MoneyCard m1 = new MoneyCard("m1", "1", 1);
        debtor.addToBank(m1);
        PropertyCard p = new PropertyCard("p1", "p", "GREEN");
        debtor.addToPropertyZone(p);
        GameEngineSingleton engine = GameEngineSingleton.getInstance();

        PaymentSettlement.Result r = PaymentSettlement.settleWithExplicitCards(
                debtor, creditor, 3, List.of("m1", "p1"), engine);

        assertTrue(r.isSuccess());
        assertEquals(0, debtor.getBankCardCount());
        assertEquals(0, debtor.getPropertyCardCount());
        assertTrue(creditor.getBankCardsView().contains(m1));
        assertTrue(creditor.getPropertyCardsView().contains(p));
        assertEquals(0, creditor.getHandCardCount());
        assertEquals(1, creditor.getBankCardCount());
        assertEquals(1, creditor.getPropertyCardCount());
    }

    @Test
    void automaticPayment_prefersExactBankCardBeforeProperty() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        MoneyCard m1 = new MoneyCard("m1", "1", 1);
        PropertyCard p = new PropertyCard("p1", "p", "BROWN");
        debtor.addToBank(m1);
        debtor.addToPropertyZone(p);

        PaymentSettlement.Result r = PaymentSettlement.settle(
                debtor, creditor, 1, GameEngineSingleton.getInstance());

        assertTrue(r.isSuccess());
        assertEquals(0, debtor.getBankCardCount());
        assertEquals(1, debtor.getPropertyCardCount());
        assertTrue(creditor.getBankCardsView().contains(m1));
    }

    @Test
    void automaticPayment_usesBestBankCombinationBeforeTouchingProperties() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        debtor.addToBank(new MoneyCard("m1", "1", 1));
        debtor.addToBank(new MoneyCard("m2", "2", 2));
        debtor.addToBank(new MoneyCard("m5", "5", 5));
        debtor.addToPropertyZone(new PropertyCard("p1", "p", "GREEN"));

        PaymentSettlement.PaymentChoice choice = PaymentSettlement.chooseAutomaticPayment(debtor, 3);

        assertEquals(3, choice.amountPaid());
        assertEquals(List.of("m1", "m2"),
                choice.cards().stream().map(card -> card.getId()).toList());
    }

    @Test
    void estimateAutomaticAmountPaidReflectsNoChangeOverpayment() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        debtor.addToBank(new MoneyCard("m10", "10", 10));

        assertEquals(10, PaymentSettlement.estimateAutomaticAmountPaid(debtor, 5));
    }

    @Test
    void automaticPayment_transfersAllAssetsWhenUnableToCoverDue() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        MoneyCard m1 = new MoneyCard("m1", "1", 1);
        PropertyCard p = new PropertyCard("p1", "p", "UTILITY");
        debtor.addToBank(m1);
        debtor.addToPropertyZone(p);

        PaymentSettlement.Result r = PaymentSettlement.settle(
                debtor, creditor, 5, GameEngineSingleton.getInstance());

        assertTrue(r.isSuccess());
        assertEquals(3, r.getAmountPaid());
        assertEquals(0, debtor.getBankCardCount());
        assertEquals(0, debtor.getPropertyCardCount());
        assertTrue(creditor.getBankCardsView().contains(m1));
        assertTrue(creditor.getPropertyCardsView().contains(p));
        assertTrue(r.getMessage().contains("已付尽可支付资产"));
    }

    @Test
    void settleWithExplicit_allowsAllAssetsWhenUnableToCoverDue() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        MoneyCard m1 = new MoneyCard("m1", "1", 1);
        PropertyCard p = new PropertyCard("p1", "p", "UTILITY");
        debtor.addToBank(m1);
        debtor.addToPropertyZone(p);

        PaymentSettlement.Result r = PaymentSettlement.settleWithExplicitCards(
                debtor, creditor, 5, List.of("m1", "p1"), GameEngineSingleton.getInstance());

        assertTrue(r.isSuccess());
        assertEquals(3, r.getAmountPaid());
        assertEquals(0, debtor.getBankCardCount());
        assertEquals(0, debtor.getPropertyCardCount());
        assertTrue(creditor.getBankCardsView().contains(m1));
        assertTrue(creditor.getPropertyCardsView().contains(p));
    }

    @Test
    void propertyPaymentMovesAssignedWildToCreditorPropertyZone() {
        HumanPlayer debtor = new HumanPlayer("d", "D");
        HumanPlayer creditor = new HumanPlayer("c", "C");
        PropertyWildCard wild = new PropertyWildCard(
                "wild-r-y",
                "Red Yellow Wild",
                PropertyWildCard.WildPropertyKind.DUAL_COLOR,
                List.of("RED", "YELLOW"));
        wild.setAssignedColorKey("RED");
        debtor.addToPropertyZone(wild);

        PaymentSettlement.Result r = PaymentSettlement.settleWithExplicitCards(
                debtor, creditor, 3, List.of("wild-r-y"), GameEngineSingleton.getInstance());

        assertTrue(r.isSuccess());
        assertFalse(debtor.getPropertyCardsView().contains(wild));
        assertTrue(creditor.getPropertyCardsView().contains(wild));
        assertEquals("RED", wild.getAssignedColorKey());
        assertTrue(creditor.getHandCardsView().isEmpty());
    }
}
