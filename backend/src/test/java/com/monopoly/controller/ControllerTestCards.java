package com.monopoly.controller;

import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.Player;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ControllerTestCards {

    private ControllerTestCards() {
    }

    static void receiveToHand(GameController controller, Player player, Card... cards) {
        consumeDrawCards(controller, cards.length);
        for (Card card : cards) {
            player.receiveCardToHand(card);
        }
    }

    static void addToBank(GameController controller, Player player, Card... cards) {
        consumeDrawCards(controller, cards.length);
        for (Card card : cards) {
            player.addToBank(card);
        }
    }

    static void addToPropertyZone(GameController controller, Player player, PropertyCard... cards) {
        consumeDrawCards(controller, cards.length);
        for (PropertyCard card : cards) {
            player.addToPropertyZone(card);
        }
    }

    static void discardEntireHandToEngine(GameController controller, Player player) {
        controller.getEngine().discardMany(player.discardSpecificFromHand(List.copyOf(player.getHandCardsView())));
    }

    private static void consumeDrawCards(GameController controller, int count) {
        for (int i = 0; i < count; i++) {
            assertNotNull(controller.getEngine().drawOne(), "not enough draw cards to replace with a test fixture card");
        }
    }
}
