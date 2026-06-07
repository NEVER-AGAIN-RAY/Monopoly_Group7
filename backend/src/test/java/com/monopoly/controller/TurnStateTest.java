package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TurnStateTest {

    private TurnState freshState() {
        return new TurnState();
    }

    @Test
    void defaultPhaseIsDraw() {
        TurnState ts = freshState();
        assertEquals(TurnFlowService.TurnPhase.DRAW, ts.phase());
    }

    @Test
    void initForSessionSetsAllFields() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        assertEquals("P1", ts.currentPlayerId());
        assertEquals(0, ts.actionCount());
        assertEquals(TurnFlowService.TurnPhase.DRAW, ts.phase());
        assertFalse(ts.mustDiscardOverflow());
    }

    @Test
    void initForSessionWithNullPreservesNull() {
        TurnState ts = freshState();
        ts.initForSession(null);
        assertNull(ts.currentPlayerId());
    }

    @Test
    void enterPlayTransitionsFromDrawToPlay() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        assertEquals(TurnFlowService.TurnPhase.PLAY, ts.phase());
    }

    @Test
    void enterWaitingForResponseTransitionsPhase() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        ts.enterWaitingForResponse();
        assertEquals(TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE, ts.phase());
    }

    @Test
    void resumeToPlayOrEndResumesPlayWhenBelowLimit() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        ts.enterWaitingForResponse();
        ts.resumeToPlayOrEnd(1);
        assertEquals(TurnFlowService.TurnPhase.PLAY, ts.phase());
    }

    @Test
    void resumeToPlayOrEndEndsTurnAtLimit() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        ts.enterWaitingForResponse();
        ts.resumeToPlayOrEnd(TurnFlowService.MAX_ACTIONS_PER_TURN);
        assertEquals(TurnFlowService.TurnPhase.END_TURN, ts.phase());
    }

    @Test
    void resumeToPlayOrEndEndsTurnAboveLimit() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        ts.enterWaitingForResponse();
        ts.resumeToPlayOrEnd(TurnFlowService.MAX_ACTIONS_PER_TURN + 1);
        assertEquals(TurnFlowService.TurnPhase.END_TURN, ts.phase());
    }

    @Test
    void markEndTurnSetsEndTurnPhase() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        ts.markEndTurn();
        assertEquals(TurnFlowService.TurnPhase.END_TURN, ts.phase());
    }

    @Test
    void incrementAndDecrementAction() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        assertEquals(0, ts.actionCount());
        ts.incrementAction();
        ts.incrementAction();
        assertEquals(2, ts.actionCount());
        ts.decrementAction();
        assertEquals(1, ts.actionCount());
    }

    @Test
    void setMustDiscardOverflow() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        assertFalse(ts.mustDiscardOverflow());
        ts.setMustDiscardOverflow(true);
        assertTrue(ts.mustDiscardOverflow());
        ts.setMustDiscardOverflow(false);
        assertFalse(ts.mustDiscardOverflow());
    }

    @Test
    void ensureTurnContextInitializesWhenCurrentPlayerIsNull() {
        TurnState ts = freshState();
        assertNull(ts.currentPlayerId());
        Player p = new HumanPlayer("P1", "Alice");
        ts.ensureTurnContext(p);
        assertEquals("P1", ts.currentPlayerId());
        assertEquals(0, ts.actionCount());
        assertEquals(TurnFlowService.TurnPhase.DRAW, ts.phase());
        assertFalse(ts.mustDiscardOverflow());
    }

    @Test
    void ensureTurnContextAcceptsSamePlayer() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        Player p1 = new HumanPlayer("P1", "Alice");
        ts.ensureTurnContext(p1);
        assertEquals("P1", ts.currentPlayerId());
    }

    @Test
    void ensureTurnContextRejectsDifferentPlayer() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        Player p2 = new HumanPlayer("P2", "Bob");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ts.ensureTurnContext(p2));
        assertTrue(ex.getMessage().contains("Not player P2's turn"));
    }

    @Test
    void ensureTurnActionAvailablePassesBelowLimit() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        ts.incrementAction();
        ts.ensureTurnActionAvailable();
    }

    @Test
    void ensureTurnActionAvailableThrowsAtLimit() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.enterPlay();
        for (int i = 0; i < TurnFlowService.MAX_ACTIONS_PER_TURN; i++) {
            ts.incrementAction();
        }
        assertThrows(IllegalStateException.class, ts::ensureTurnActionAvailable);
    }

    @Test
    void ensureNoPendingOverflowDiscardPassesWhenFalse() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        Player p = new HumanPlayer("P1", "Alice");
        ts.ensureNoPendingOverflowDiscard(p, "playing a card");
    }

    @Test
    void ensureNoPendingOverflowDiscardClearsFlagWhenUnderLimit() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.setMustDiscardOverflow(true);
        Player p = new HumanPlayer("P1", "Alice");
        ts.ensureNoPendingOverflowDiscard(p, "playing a card");
        assertFalse(ts.mustDiscardOverflow());
    }

    @Test
    void ensureNoPendingOverflowDiscardThrowsWhenOverLimit() {
        TurnState ts = freshState();
        ts.initForSession("P1");
        ts.setMustDiscardOverflow(true);
        Player p = new HumanPlayer("P1", "Alice");
        for (int i = 0; i < 8; i++) {
            p.receiveCardToHand(new MoneyCard("m" + i, "1M", 1));
        }
        assertThrows(IllegalStateException.class,
                () -> ts.ensureNoPendingOverflowDiscard(p, "playing a card"));
    }

    @Test
    void failedNonActionPlayDoesNotConsumeActionCount() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("failed-play-action-count");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        controller.handleDrawCommand(2);

        Player current = controller.getCurrentPlayer();
        MoneyCard money = new MoneyCard("fixture-money", "1M", 1);
        ControllerTestCards.receiveToHand(controller, current, money);
        int before = controller.turnFlowService().actionCount();

        PlayActionRequest play = new PlayActionRequest();
        play.setActionType("DEPLOY");
        play.setCardId(money.getId());

        assertThrows(IllegalArgumentException.class,
                () -> controller.handlePlayActionRequest(play));
        assertEquals(before, controller.turnFlowService().actionCount());
    }
}
