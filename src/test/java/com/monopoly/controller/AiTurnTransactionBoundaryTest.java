package com.monopoly.controller;

import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTurnTransactionBoundaryTest {

    private final String previousDelay = System.getProperty("monopoly.ai.decisionDelayMs");

    @AfterEach
    void restoreDelay() {
        if (previousDelay == null) {
            System.clearProperty("monopoly.ai.decisionDelayMs");
        } else {
            System.setProperty("monopoly.ai.decisionDelayMs", previousDelay);
        }
    }

    @Test
    void aiResumesAfterStealSettlementWithFreshPaymentSnapshot() throws Exception {
        System.setProperty("monopoly.ai.decisionDelayMs", "10");
        RecordingSubject subject = new RecordingSubject();
        GameController controller = new GameController(subject);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("ai-transaction-boundary");
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("EASY");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);

        Player human = controller.getSessionPlayersView().get(0);
        AIPlayer ai = (AIPlayer) controller.getSessionPlayersView().get(1);
        controller.handleDrawCommand(2);
        controller.endTurn(human);

        GameStateSnapshot aiDraw = subject.await(s -> "DRAW".equals(s.getPhase())
                && ai.getPlayerId().equals(s.getCurrentPlayerId()));
        assertNotNull(aiDraw);

        prepareScenario(human, ai);
        ai.setPlayStrategy(new ScriptedAiStrategy());

        GameStateSnapshot awaitingSteal = subject.await(s -> "ACTION_AWAITING_RESPONSE".equals(s.getPhase()));
        assertNotNull(awaitingSteal);

        passResponse(controller, human);

        GameStateSnapshot actionResolved = subject.await(s -> "ACTION_SUCCESS".equals(s.getPhase()));
        assertNotNull(actionResolved);
        assertEquals(3, human.getPropertyCardCount());
        assertEquals(3, propertyCount(actionResolved, human.getPlayerId()));

        GameStateSnapshot rentAwaiting = subject.await(s -> "RENT_AWAITING_RESPONSE".equals(s.getPhase()));
        assertNotNull(rentAwaiting);
        assertEquals(human.getPlayerId(), rentAwaiting.getPendingResponsePlayerId());
        assertEquals(3, propertyCount(rentAwaiting, human.getPlayerId()));
        assertTrue(rentAwaiting.getStateSequence() > actionResolved.getStateSequence());
    }

    private static void prepareScenario(Player human, AIPlayer ai) {
        human.addToPropertyZone(new PropertyCard("human-p1", "Human property 1", "GREEN"));
        human.addToPropertyZone(new PropertyCard("human-p2", "Human property 2", "YELLOW"));
        human.addToPropertyZone(new PropertyCard("human-p3", "Human property 3", "RAILROAD"));
        human.addToPropertyZone(new PropertyCard("human-p4", "Human property 4", "UTILITY"));
        human.addToBank(new MoneyCard("human-money", "1M", 1));
        ai.addToPropertyZone(new PropertyCard("ai-brown", "AI Brown", "BROWN"));
        ai.receiveCardToHand(new ActionCard("ai-steal", "Sly Deal", "STEAL_PROPERTY"));
        ai.receiveCardToHand(new ActionCard("ai-rent", "Rent", "RENT"));
    }

    private static void passResponse(GameController controller, Player actor) {
        PlayActionRequest pass = new PlayActionRequest();
        pass.setActionType("RESPONSE_PASS");
        pass.setActingPlayerId(actor.getPlayerId());
        controller.handlePlayActionRequest(pass);
    }

    private static int propertyCount(GameStateSnapshot snapshot, String playerId) {
        for (GameStateSnapshot.PlayerPublicSummary p : snapshot.getPlayers()) {
            if (playerId.equals(p.getPlayerId())) {
                return p.getPropertyZoneCards().size();
            }
        }
        return -1;
    }

    private static final class ScriptedAiStrategy implements AiPlayStrategy {
        private int step;

        @Override
        public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            if (step++ == 0) {
                req.setCardId("ai-steal");
                req.setTargetPlayerId("human-1");
                req.setTargetCardId("human-p1");
                req.setTargetZone("PROPERTY");
            } else {
                req.setCardId("ai-rent");
                req.setTargetPlayerId("human-1");
                req.setTargetColorKey("BROWN");
            }
            bridge.submitPlayAction(req);
            return true;
        }
    }

    private static final class RecordingSubject implements GameUpdateSubject {
        private final List<GameStateSnapshot> snapshots = new ArrayList<>();

        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public synchronized void notifyStateChanged(GameStateSnapshot snapshot) {
            snapshots.add(snapshot);
            notifyAll();
        }

        synchronized GameStateSnapshot await(Predicate<GameStateSnapshot> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                for (GameStateSnapshot snapshot : snapshots) {
                    if (predicate.test(snapshot)) {
                        return snapshot;
                    }
                }
                TimeUnit.MILLISECONDS.timedWait(this, 20);
            }
            for (GameStateSnapshot snapshot : snapshots) {
                if (predicate.test(snapshot)) {
                    return snapshot;
                }
            }
            return null;
        }
    }
}
