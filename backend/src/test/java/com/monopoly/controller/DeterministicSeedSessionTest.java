package com.monopoly.controller;

import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.Card;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeterministicSeedSessionTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("monopoly.deck.seed");
        System.clearProperty("monopoly.firstPlayer.seed");
    }

    @Test
    void deckSeedReplaysInitialHandsAndRemainingDrawPile() {
        SessionSignature first = startSession(123456789L, null);
        SessionSignature replay = startSession(123456789L, null);
        SessionSignature different = startSession(123456790L, null);

        assertEquals(first, replay);
        assertNotEquals(first, different);
    }

    @Test
    void firstPlayerSeedReplaysRandomizedStarter() {
        SessionSignature first = startSession(987654321L, 42L);
        SessionSignature replay = startSession(987654321L, 42L);

        assertEquals(first, replay);
    }

    private static SessionSignature startSession(long deckSeed, Long firstPlayerSeed) {
        System.setProperty("monopoly.deck.seed", Long.toString(deckSeed));
        if (firstPlayerSeed == null) {
            System.clearProperty("monopoly.firstPlayer.seed");
        } else {
            System.setProperty("monopoly.firstPlayer.seed", Long.toString(firstPlayerSeed));
        }
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("seed-test");
        req.setPlayerCount(4);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(firstPlayerSeed != null);
        controller.startNewSession(req);
        List<List<String>> hands = controller.getSessionPlayersView().stream()
                .map(player -> player.getHandCardsView().stream().map(Card::getId).toList())
                .toList();
        List<String> drawPile = controller.getEngine().getDrawPileView().stream()
                .map(Card::getId)
                .toList();
        return new SessionSignature(controller.getCurrentPlayer().getPlayerId(), hands, drawPile);
    }

    private record SessionSignature(
            String currentPlayerId,
            List<List<String>> handsBySeat,
            List<String> drawPileIds) {
    }
}
