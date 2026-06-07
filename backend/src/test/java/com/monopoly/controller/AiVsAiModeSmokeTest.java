package com.monopoly.controller;

import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVsAiModeSmokeTest {

    private final String prevDeepSeekEnabled = System.getProperty("monopoly.deepseek.enabled");
    private final String prevAiBattleLogEnabled = System.getProperty("monopoly.aiBattle.log.enabled");

    @AfterEach
    void tearDown() {
        if (prevDeepSeekEnabled == null) {
            System.clearProperty("monopoly.deepseek.enabled");
        } else {
            System.setProperty("monopoly.deepseek.enabled", prevDeepSeekEnabled);
        }
        if (prevAiBattleLogEnabled == null) {
            System.clearProperty("monopoly.aiBattle.log.enabled");
        } else {
            System.setProperty("monopoly.aiBattle.log.enabled", prevAiBattleLogEnabled);
        }
    }

    @Test
    void aiVsAiStartsAndAdvancesWithoutHumanInputWhenDeepSeekFallsBack() {
        System.setProperty("monopoly.deepseek.enabled", "false");
        System.setProperty("monopoly.aiBattle.log.enabled", "false");
        AtomicInteger updates = new AtomicInteger();
        AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        AtomicReference<GameController> ref = new AtomicReference<>();
        GameUpdateSubject subject = new GameUpdateSubject() {
            @Override
            public void registerObserver(GameUpdateObserver observer) {
            }

            @Override
            public void unregisterObserver(GameUpdateObserver observer) {
            }

            @Override
            public void notifyStateChanged(GameStateSnapshot snapshot) {
                last.set(snapshot);
                int n = updates.incrementAndGet();
                GameController c = ref.get();
                if (n >= 8 && c != null && snapshot != null && !snapshot.isGameOver()) {
                    c.forceEndSession("AI_BATTLE_TURN_LIMIT");
                }
            }
        };
        GameController controller = new GameController(subject);
        ref.set(controller);
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("ai-vs-ai-smoke");
        req.setPlayerCount(2);
        req.setGameMode("AI_VS_AI");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (updates.get() <= 3 && System.nanoTime() < deadline) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(updates.get() > 3, "AI_VS_AI should generate snapshots beyond INIT without human input.");
        assertNotNull(last.get());
        assertTrue(controller.isAiBattleMode());
    }
}
