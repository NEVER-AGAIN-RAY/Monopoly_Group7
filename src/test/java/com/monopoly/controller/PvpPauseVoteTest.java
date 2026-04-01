package com.monopoly.controller;

import com.monopoly.model.persistence.GameSessionMemento;
import com.monopoly.model.dto.StartSessionRequest;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PVP：PAUSE_REQUEST 后需全员 PAUSE_ACK（本实现为每人一次 acknowledgePause）才 paused。
 */
class PvpPauseVoteTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void threePlayerPvp_requiresThreeAcksBeforePaused() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController c = new GameController(subject);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("pvp-pause");
        req.setPlayerCount(3);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        c.startNewSession(req);

        assertTrue(c.isPvpMode());
        assertFalse(c.isPaused());
        assertFalse(c.isPausePending());

        c.requestPause();
        assertTrue(c.isPausePending());
        assertFalse(c.isPaused());
        assertEquals(0, c.getPauseAcksView().size());

        c.acknowledgePause("pvp-1");
        assertTrue(c.isPausePending());
        assertFalse(c.isPaused());
        assertEquals(1, c.getPauseAcksView().size());

        c.acknowledgePause("pvp-2");
        assertTrue(c.isPausePending());
        assertFalse(c.isPaused());
        assertEquals(2, c.getPauseAcksView().size());

        c.acknowledgePause("pvp-3");
        assertFalse(c.isPausePending());
        assertTrue(c.isPaused());
        assertEquals(0, c.getPauseAcksView().size());
    }
}
