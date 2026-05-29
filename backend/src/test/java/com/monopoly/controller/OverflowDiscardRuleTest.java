package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.persistence.GameSessionMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OverflowDiscardRuleTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void overflowDiscardAfterFailedEndTurnDoesNotConsumeActionLimit() {
        RecordingSubject subject = new RecordingSubject();
        GameController controller = new GameController(subject);
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

        controller.handleEndTurnCommand();
        assertEquals(9, current.getHandCardCount());
        assertTrue(subject.snapshots.stream()
                .anyMatch(s -> "FORCE_DISCARD_REQUIRED".equals(s.getPhase())
                        && s.getOverflowDiscardCount() == 2));

        play(controller, "DISCARD", "overflow-1");
        play(controller, "DISCARD", "overflow-2");

        assertEquals(7, current.getHandCardCount());
        controller.handleEndTurnCommand();
    }

    @Test
    void endingWithOverflowKeepsSamePlayerUntilManualOverflowDiscards() {
        RecordingSubject subject = new RecordingSubject();
        GameController controller = new GameController(subject);
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("overflow-stays");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        controller.handleDrawCommand(2);

        Player current = controller.getCurrentPlayer();
        String currentId = current.getPlayerId();
        current.receiveCardToHand(new MoneyCard("overflow-1", "1M", 1));
        current.receiveCardToHand(new MoneyCard("overflow-2", "1M", 1));

        controller.handleEndTurnCommand();

        assertEquals(currentId, controller.getCurrentPlayer().getPlayerId());
        assertEquals("END_TURN", subject.last().getTurnPhase());
        assertEquals(2, subject.last().getOverflowDiscardCount());

        play(controller, "DISCARD", "overflow-1");
        play(controller, "DISCARD", "overflow-2");
        controller.handleEndTurnCommand();

        assertTrue(!currentId.equals(controller.getCurrentPlayer().getPlayerId()));
    }

    @Test
    void fourthPlayReportsActionLimitInsteadOfDrawPhaseHint() {
        GameController controller = new GameController(new RecordingSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("three-action-limit-message");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        controller.handleDrawCommand(2);

        Player current = controller.getCurrentPlayer();
        current.receiveCardToHand(new MoneyCard("deposit-1", "1M", 1));
        current.receiveCardToHand(new MoneyCard("deposit-2", "1M", 1));
        current.receiveCardToHand(new MoneyCard("deposit-3", "1M", 1));
        current.receiveCardToHand(new MoneyCard("deposit-4", "1M", 1));

        play(controller, "DEPOSIT", "deposit-1");
        play(controller, "DEPOSIT", "deposit-2");
        play(controller, "DEPOSIT", "deposit-3");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> play(controller, "DEPOSIT", "deposit-4"));
        assertEquals("每回合最多可出 3 张牌，已达到上限。", ex.getMessage());
    }

    private static void play(GameController controller, String actionType, String cardId) {
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType(actionType);
        req.setCardId(cardId);
        controller.handlePlayActionRequest(req);
    }

    private static final class RecordingSubject implements GameUpdateSubject {
        private final List<com.monopoly.dto.GameStateSnapshot> snapshots = new ArrayList<>();

        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public void notifyStateChanged(com.monopoly.dto.GameStateSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        com.monopoly.dto.GameStateSnapshot last() {
            return snapshots.get(snapshots.size() - 1);
        }
    }
}
