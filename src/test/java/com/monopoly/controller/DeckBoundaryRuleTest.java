package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.core.GameConstants;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.effects.PassGoEffect;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import com.monopoly.persistence.GameSessionMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckBoundaryRuleTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void startSessionDealsFiveCardsToEachPlayerAndLeavesTheRestInDrawPile() {
        GameController controller = newPvpController(4);

        assertEquals(4, controller.getSessionPlayersView().size());
        for (Player player : controller.getSessionPlayersView()) {
            assertEquals(5, player.getHandCardCount());
        }
        assertEquals(GameConstants.STANDARD_DECK_SIZE - 20, controller.getEngine().remainingCount());
        assertEquals(0, controller.getEngine().discardCount());
    }

    @Test
    void drawCommandIgnoresClientRequestedCountAndUsesRuleCount() {
        GameController controller = newPvpController(2);
        Player current = controller.getCurrentPlayer();
        int before = current.getHandCardCount();
        int drawBefore = controller.getEngine().remainingCount();

        controller.handleDrawCommand(99);

        assertEquals(before + 2, current.getHandCardCount());
        assertEquals(drawBefore - 2, controller.getEngine().remainingCount());
    }

    @Test
    void drawCommandDrawsFiveWhenCurrentPlayerHandIsEmpty() {
        GameController controller = newPvpController(2);
        Player current = controller.getCurrentPlayer();
        current.discardSpecificFromHand(List.copyOf(current.getHandCardsView()));
        int drawBefore = controller.getEngine().remainingCount();

        controller.handleDrawCommand(2);

        assertEquals(5, current.getHandCardCount());
        assertEquals(drawBefore - 5, controller.getEngine().remainingCount());
    }

    @Test
    void drawReplenishesFromDiscardPileWhenDrawPileIsEmpty() {
        GameEngineSingleton engine = GameEngineSingleton.getInstance();
        Card first = new MoneyCard("draw-1", "1M", 1);
        Card discardA = new MoneyCard("discard-1", "1M", 1);
        Card discardB = new MoneyCard("discard-2", "2M", 2);
        engine.attachDrawPile(List.of(first));
        engine.discard(discardA);
        engine.discard(discardB);

        assertEquals(first, engine.drawOne());
        Card second = engine.drawOne();

        assertTrue(second == discardA || second == discardB);
        assertEquals(1, engine.remainingCount());
        assertEquals(0, engine.discardCount());
    }

    @Test
    void drawReturnsNullWhenDrawAndDiscardPilesAreBothEmpty() {
        GameEngineSingleton engine = GameEngineSingleton.getInstance();
        engine.attachDrawPile(List.of());

        assertNull(engine.drawOne());
        assertEquals(0, engine.remainingCount());
        assertEquals(0, engine.discardCount());
    }

    @Test
    void passGoDrawsOnlyAvailableCardsWhenPilesRunOut() {
        Player actor = new com.monopoly.model.player.HumanPlayer("p1", "P1");
        GameEngineSingleton engine = GameEngineSingleton.getInstance();
        engine.attachDrawPile(List.of(new MoneyCard("only", "1M", 1)));
        ActionEffectContext ctx = ActionEffectContext.builder(actor, engine, List.of(actor)).build();

        ActionEffectResult result = new PassGoEffect().execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals(1, actor.getHandCardCount());
        assertEquals(0, engine.remainingCount());
        assertEquals(0, engine.discardCount());
    }

    @Test
    void actionZoneCardsMoveToDiscardPileWhenTurnReallyEnds() {
        GameController controller = newPvpController(2);
        Player current = controller.getCurrentPlayer();
        controller.handleDrawCommand(2);
        current.addToPropertyZone(new PropertyCard("rentable", "Rentable", "BROWN"));
        ActionCard doubleRent = new ActionCard("test-double", "Double Rent", "DOUBLE_RENT");
        current.receiveCardToHand(doubleRent);

        PlayActionRequest play = new PlayActionRequest();
        play.setActionType("ACTION");
        play.setCardId(doubleRent.getId());
        controller.handlePlayActionRequest(play);

        assertTrue(current.getActionZoneCardsView().contains(doubleRent));
        int discardBeforeEnd = controller.getEngine().discardCount();
        controller.handleEndTurnCommand();

        assertEquals(0, current.getActionZoneCardCount());
        assertEquals(discardBeforeEnd + 1, controller.getEngine().discardCount());
        assertTrue(controller.getEngine().getDiscardPileView().contains(doubleRent));
    }

    private static GameController newPvpController(int playerCount) {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("deck-boundary");
        req.setPlayerCount(playerCount);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        return controller;
    }
}
