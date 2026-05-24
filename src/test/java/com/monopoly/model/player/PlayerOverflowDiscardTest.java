package com.monopoly.model.player;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOverflowDiscardTest {

    @Test
    void overflowDiscardPreservesStrategicActionCardsOverLowMoney() {
        HumanPlayer player = new HumanPlayer("p1", "P1");
        player.receiveCardToHand(new PropertyCard("prop", "Red", "RED"));
        player.receiveCardToHand(new MoneyCard("m1", "1M", 1));
        player.receiveCardToHand(new MoneyCard("m2", "2M", 2));
        player.receiveCardToHand(new ActionCard("pass-go", "Pass Go", "PASS_GO"));
        player.receiveCardToHand(new ActionCard("steal", "Steal", "STEAL_PROPERTY"));
        player.receiveCardToHand(new ActionCard("no", "Just Say No", "RENT_WAIVER"));
        player.receiveCardToHand(new MoneyCard("m3", "3M", 3));
        player.receiveCardToHand(new ActionCard("deal-breaker", "Deal Breaker", "DEAL_BREAKER"));

        List<com.monopoly.model.card.Card> discarded = player.discardOverflowTo(7);

        assertEquals(1, discarded.size());
        assertEquals("m1", discarded.get(0).getId());
        assertTrue(player.getHandCardsView().stream().anyMatch(c -> "deal-breaker".equals(c.getId())));
    }

    @Test
    void chooseOverflowDiscardsDoesNotMutateHand() {
        HumanPlayer player = new HumanPlayer("p1", "P1");
        player.receiveCardToHand(new MoneyCard("m1", "1M", 1));
        player.receiveCardToHand(new MoneyCard("m2", "2M", 2));
        player.receiveCardToHand(new MoneyCard("m3", "3M", 3));

        List<com.monopoly.model.card.Card> chosen = player.chooseOverflowDiscardsTo(2);

        assertEquals(1, chosen.size());
        assertEquals("m1", chosen.get(0).getId());
        assertEquals(3, player.getHandCardCount());
    }

    @Test
    void discardSpecificFromHandOnlyRemovesChosenCards() {
        HumanPlayer player = new HumanPlayer("p1", "P1");
        MoneyCard m1 = new MoneyCard("m1", "1M", 1);
        MoneyCard m2 = new MoneyCard("m2", "2M", 2);
        player.receiveCardToHand(m1);
        player.receiveCardToHand(m2);

        List<com.monopoly.model.card.Card> discarded = player.discardSpecificFromHand(List.of(m2));

        assertEquals(List.of(m2), discarded);
        assertEquals(1, player.getHandCardCount());
        assertEquals("m1", player.getHandCardsView().get(0).getId());
    }
}
