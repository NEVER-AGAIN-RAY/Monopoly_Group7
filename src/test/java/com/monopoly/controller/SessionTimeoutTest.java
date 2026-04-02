package com.monopoly.controller;

import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：{@code -Dmonopoly.sessionLimitMs=60000} 等缩短单局上限；超时后 phase 为 GAME_FORCE_END。
 */
class SessionTimeoutTest {

    private String prevLimit;

    @AfterEach
    void tearDown() {
        if (prevLimit == null) {
            System.clearProperty("monopoly.sessionLimitMs");
        } else {
            System.setProperty("monopoly.sessionLimitMs", prevLimit);
        }
    }

    @Test
    void afterSessionLimitElapsedNextCommandFailsAndSnapshotShowsForceEnd() throws Exception {
        prevLimit = System.getProperty("monopoly.sessionLimitMs");
        System.setProperty("monopoly.sessionLimitMs", "60000");

        AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
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
            }
        };
        var controller = new GameController(subject);

        var req = new StartSessionRequest();
        req.setSessionId("test-session");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);

        Field f = GameController.class.getDeclaredField("sessionStartEpochMs");
        f.setAccessible(true);
        f.set(controller, System.currentTimeMillis() - 120_000L);

        assertThrows(IllegalStateException.class, () -> controller.handleDrawCommand(2));

        GameStateSnapshot snap = last.get();
        assertTrue(snap.isGameOver());
        assertEquals("TIMEOUT", snap.getForceEndReason());
        assertEquals("GAME_FORCE_END", snap.getPhase());
    }
}
