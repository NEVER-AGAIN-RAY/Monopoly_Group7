package com.monopoly.controller;

import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.1.6a：暂停后摸牌等指令失败，恢复后继续。
 */
class PauseResumeTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void pauseBlocksDraw_resumeAllowsDraw() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController c = new GameController(subject);
        c.startNewSession("pause-test");

        c.pause();
        assertTrue(c.isPaused());
        assertThrows(IllegalStateException.class, () -> c.handleDrawCommand(2));

        c.resume();
        assertDoesNotThrow(() -> c.handleDrawCommand(2));
    }

    @Test
    void startSessionIgnoredWhilePaused() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController c = new GameController(subject);
        c.startNewSession("s1");
        int n0 = c.getSessionPlayersView().size();

        c.pause();
        com.monopoly.dto.StartSessionRequest req = new com.monopoly.dto.StartSessionRequest();
        req.setSessionId("s2");
        req.setPlayerCount(3);
        req.setGameMode("PVP");
        c.startNewSession(req);

        assertTrue(c.isPaused());
        assertEquals(n0, c.getSessionPlayersView().size(), "暂停时不应完成新开局");
    }
}
