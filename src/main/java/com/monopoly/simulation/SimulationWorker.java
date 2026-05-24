package com.monopoly.simulation;

import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.BrokeredAiPlayStrategy;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one real backend game while all AI decisions go through a shared broker.
 */
public final class SimulationWorker implements Callable<SimulationResult> {

    private final String sessionId;
    private final int playerCount;
    private final DecisionBroker broker;
    private final int maxSnapshots;
    private final Duration maxRuntime;

    public SimulationWorker(
            String sessionId,
            int playerCount,
            DecisionBroker broker,
            int maxSnapshots,
            Duration maxRuntime) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId.trim();
        this.playerCount = Math.max(2, Math.min(5, playerCount));
        this.broker = Objects.requireNonNull(broker, "broker");
        this.maxSnapshots = Math.max(1, maxSnapshots);
        this.maxRuntime = maxRuntime == null ? Duration.ofSeconds(30) : maxRuntime;
    }

    @Override
    public SimulationResult call() throws Exception {
        AtomicInteger snapshots = new AtomicInteger();
        AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        AtomicReference<GameController> controllerRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        GameUpdateSubject subject = new GameUpdateSubject() {
            @Override
            public void registerObserver(GameUpdateObserver observer) {
            }

            @Override
            public void unregisterObserver(GameUpdateObserver observer) {
            }

            @Override
            public void notifyStateChanged(GameStateSnapshot snapshot) {
                if (snapshot == null) {
                    return;
                }
                last.set(snapshot);
                int count = snapshots.incrementAndGet();
                if (snapshot.isGameOver()) {
                    done.countDown();
                    return;
                }
                GameController controller = controllerRef.get();
                if (controller != null && count >= maxSnapshots) {
                    controller.forceEndSession("SIMULATION_SNAPSHOT_LIMIT");
                }
            }
        };

        GameController controller = new GameController(subject);
        controller.setLlmAiStrategyFactory(
                playerNumber -> new BrokeredAiPlayStrategy(broker, sessionId));
        controllerRef.set(controller);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId(sessionId);
        req.setPlayerCount(playerCount);
        req.setGameMode("AI_VS_AI");
        req.setRandomizeFirstPlayer(true);

        controller.startNewSession(req);
        boolean completed = done.await(Math.max(1L, maxRuntime.toMillis()), TimeUnit.MILLISECONDS);
        if (!completed && !controller.isSessionForceEndedPublic()) {
            controller.forceEndSession("SIMULATION_RUNTIME_LIMIT");
            done.await(200, TimeUnit.MILLISECONDS);
        }
        GameStateSnapshot snapshot = last.get();
        return new SimulationResult(
                sessionId,
                snapshots.get(),
                snapshot != null && snapshot.isGameOver(),
                snapshot == null ? null : snapshot.getPhase(),
                snapshot == null ? null : snapshot.getForceEndReason());
    }
}
