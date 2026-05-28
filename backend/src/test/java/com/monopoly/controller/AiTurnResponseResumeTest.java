package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTurnResponseResumeTest {

    private String previousDelay;

    @BeforeEach
    void speedUpAiDecisionDelay() {
        previousDelay = System.getProperty("monopoly.ai.decisionDelayMs");
        System.setProperty("monopoly.ai.decisionDelayMs", "0");
    }

    @AfterEach
    void tearDown() {
        if (previousDelay == null) {
            System.clearProperty("monopoly.ai.decisionDelayMs");
        } else {
            System.setProperty("monopoly.ai.decisionDelayMs", previousDelay);
        }
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void aiTurnContinuesAfterHumanPassesRentResponse() {
        RecordingSubject subject = new RecordingSubject();
        GameController controller = new GameController(subject);
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("ai-response-resume");
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("EASY");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);

        Player human = controller.getSessionPlayersView().get(0);
        AIPlayer ai = (AIPlayer) controller.getSessionPlayersView().get(1);
        ActionCard rent = new ActionCard("ai-rent", "Rent", "RENT");
        ai.addToPropertyZone(new PropertyCard("ai-brown", "AI Brown", "BROWN"));
        ai.receiveCardToHand(rent);
        ai.setPlayStrategy(new RentThenStopStrategy(rent.getId(), human.getPlayerId()));
        human.addToBank(new MoneyCard("human-1m", "1M", 1));

        controller.handleDrawCommand(2);
        controller.handleEndTurnCommand();

        assertTrue(await(() -> controller.getGameContext().isAwaitingResponseFrom(human.getPlayerId())));
        assertEquals(ai.getPlayerId(), controller.getCurrentPlayer().getPlayerId());

        PlayActionRequest pass = new PlayActionRequest();
        pass.setActionType("RESPONSE_PASS");
        pass.setActingPlayerId(human.getPlayerId());
        controller.handlePlayActionRequest(pass);

        assertTrue(await(() -> human.getPlayerId().equals(controller.getCurrentPlayer().getPlayerId())));
    }

    @Test
    void aiTurnContinuesAfterHumanPassesActionResponse() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("ai-action-response-resume");
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("EASY");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);

        Player human = controller.getSessionPlayersView().get(0);
        AIPlayer ai = (AIPlayer) controller.getSessionPlayersView().get(1);
        ActionCard debtCollector = new ActionCard("ai-debt", "Debt Collector", "DEBT_COLLECTOR");
        ai.receiveCardToHand(debtCollector);
        ai.setPlayStrategy(new SingleActionThenStopStrategy(debtCollector.getId(), human.getPlayerId()));
        human.addToBank(new MoneyCard("human-5m", "5M", 5));

        controller.handleDrawCommand(2);
        controller.handleEndTurnCommand();

        assertTrue(await(() -> controller.getGameContext().isAwaitingResponseFrom(human.getPlayerId())));
        assertEquals(ai.getPlayerId(), controller.getCurrentPlayer().getPlayerId());

        PlayActionRequest pass = new PlayActionRequest();
        pass.setActionType("RESPONSE_PASS");
        pass.setActingPlayerId(human.getPlayerId());
        controller.handlePlayActionRequest(pass);

        assertTrue(await(() -> human.getPlayerId().equals(controller.getCurrentPlayer().getPlayerId())));
    }

    @Test
    void customAiTurnWaitsForHumanRentResponseWithDeadline() {
        RecordingSubject subject = new RecordingSubject();
        GameController controller = new GameController(subject);
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("custom-ai-response-lock");
        req.setPlayerCount(2);
        req.setGameMode("CUSTOM");
        req.setPlayerRoles(List.of("human", "hard"));
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);

        Player human = controller.getSessionPlayersView().get(0);
        AIPlayer ai = (AIPlayer) controller.getSessionPlayersView().get(1);
        ActionCard rent = new ActionCard("custom-ai-rent", "Rent", "RENT");
        ai.addToPropertyZone(new PropertyCard("custom-ai-brown", "AI Brown", "BROWN"));
        ai.receiveCardToHand(rent);
        ai.setPlayStrategy(new RentThenStopStrategy(rent.getId(), human.getPlayerId()));
        human.addToBank(new MoneyCard("custom-human-1m", "1M", 1));

        controller.handleDrawCommand(2);
        controller.handleEndTurnCommand();

        assertTrue(await(() -> controller.getGameContext().isAwaitingResponseFrom(human.getPlayerId())));
        assertEquals(ai.getPlayerId(), controller.getCurrentPlayer().getPlayerId());
        GameStateSnapshot awaiting = subject.latest("RENT_AWAITING_RESPONSE");
        assertEquals("WAITING_FOR_RESPONSE", awaiting.getTurnPhase());
        assertEquals(human.getPlayerId(), awaiting.getPendingResponsePlayerId());
        assertTrue(awaiting.getResponseDeadlineEpochMs() > System.currentTimeMillis());
        assertNotEquals(human.getPlayerId(), controller.getCurrentPlayer().getPlayerId());
    }

    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    private static final class RecordingSubject extends DefaultGameUpdateSubject {
        private final java.util.List<GameStateSnapshot> snapshots = new java.util.ArrayList<>();

        @Override
        public synchronized void notifyStateChanged(GameStateSnapshot snapshot) {
            snapshots.add(snapshot);
            super.notifyStateChanged(snapshot);
        }

        synchronized GameStateSnapshot latest(String phase) {
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                GameStateSnapshot snapshot = snapshots.get(i);
                if (phase.equals(snapshot.getPhase())) {
                    return snapshot;
                }
            }
            throw new AssertionError("snapshot not found: " + phase);
        }
    }

    private static final class RentThenStopStrategy implements AiPlayStrategy {
        private final String rentCardId;
        private final String targetPlayerId;
        private boolean playedRent;

        private RentThenStopStrategy(String rentCardId, String targetPlayerId) {
            this.rentCardId = rentCardId;
            this.targetPlayerId = targetPlayerId;
        }

        @Override
        public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
            if (playedRent) {
                return false;
            }
            playedRent = true;
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(rentCardId);
            req.setTargetPlayerId(targetPlayerId);
            req.setTargetColorKey("BROWN");
            bridge.submitPlayAction(req);
            return true;
        }
    }

    private static final class SingleActionThenStopStrategy implements AiPlayStrategy {
        private final String actionCardId;
        private final String targetPlayerId;
        private boolean playedAction;

        private SingleActionThenStopStrategy(String actionCardId, String targetPlayerId) {
            this.actionCardId = actionCardId;
            this.targetPlayerId = targetPlayerId;
        }

        @Override
        public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
            if (playedAction) {
                return false;
            }
            playedAction = true;
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(actionCardId);
            req.setTargetPlayerId(targetPlayerId);
            bridge.submitPlayAction(req);
            return true;
        }
    }
}
