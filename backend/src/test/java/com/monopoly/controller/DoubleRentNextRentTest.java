package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleRentNextRentTest {

    @Test
    void doubleRentDoublesNextRentCardAndIsConsumed() {
        GameController controller = newPvpControllerInPlayPhase();
        Player actor = controller.getSessionPlayersView().get(0);
        Player target = controller.getSessionPlayersView().get(1);
        actor.addToPropertyZone(new PropertyCard("brown-1", "Brown", "BROWN"));
        ActionCard doubleRent = new ActionCard("double-rent", "Double The Rent", "DOUBLE_RENT");
        ActionCard rent = new ActionCard("rent", "Rent", "RENT");
        actor.receiveCardToHand(doubleRent);
        actor.receiveCardToHand(rent);
        target.addToBank(new MoneyCard("target-2m", "2M", 2));

        playAction(controller, doubleRent.getId(), null, null);

        assertTrue(controller.getGameContext().hasPendingDoubleRentFor(actor.getPlayerId()));
        assertEquals(0, controller.getGameContext().getEffectStackView().size());

        playAction(controller, rent.getId(), target.getPlayerId(), "BROWN");

        assertFalse(controller.getGameContext().hasPendingDoubleRentFor(actor.getPlayerId()));
        EffectStackEntry top = controller.getGameContext().peekTopEffect();
        assertEquals(2, top.getAmountDue());
    }

    private static GameController newPvpControllerInPlayPhase() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("double-rent-next-rent-test");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        controller.handleDrawCommand(2);
        return controller;
    }

    private static void playAction(
            GameController controller,
            String cardId,
            String targetPlayerId,
            String colorKey) {
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setCardId(cardId);
        req.setTargetPlayerId(targetPlayerId);
        req.setTargetColorKey(colorKey);
        controller.handlePlayActionRequest(req);
    }
}
