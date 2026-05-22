package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.persistence.GameSessionMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OverflowDiscardRuleTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void overflowDiscardAfterFailedEndTurnDoesNotConsumeActionLimit() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("overflow-discard");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        controller.handleDrawCommand(2);

        Player current = controller.getCurrentPlayer();
        current.receiveCardToHand(new MoneyCard("deposit-1", "1M", 1));
        current.receiveCardToHand(new MoneyCard("deposit-2", "1M", 1));
        current.receiveCardToHand(new MoneyCard("deposit-3", "1M", 1));
        current.receiveCardToHand(new MoneyCard("overflow-1", "1M", 1));
        current.receiveCardToHand(new MoneyCard("overflow-2", "1M", 1));

        play(controller, "DEPOSIT", "deposit-1");
        play(controller, "DEPOSIT", "deposit-2");
        play(controller, "DEPOSIT", "deposit-3");

        assertThrows(IllegalStateException.class, controller::handleEndTurnCommand);
        assertEquals(9, current.getHandCardCount());

        play(controller, "DISCARD", "overflow-1");
        play(controller, "DISCARD", "overflow-2");

        assertEquals(7, current.getHandCardCount());
        controller.handleEndTurnCommand();
    }

    private static void play(GameController controller, String actionType, String cardId) {
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType(actionType);
        req.setCardId(cardId);
        controller.handlePlayActionRequest(req);
    }
}
