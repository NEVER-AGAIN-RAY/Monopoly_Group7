package com.monopoly.controller;

import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HvmQuitEndsGameTest {

    @Test
    void hvm_humanQuits_gameEnds() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController c = new GameController(subject);
        c.startNewSession("hvm-quit-test");

        String humanId = c.getSessionPlayersView().stream()
                .filter(p -> p instanceof com.monopoly.model.player.HumanPlayer)
                .map(p -> p.getPlayerId())
                .findFirst()
                .orElse("");
        assertFalse(humanId.isBlank());

        c.handleQuitCommand(humanId);
        assertTrue(c.isSessionForceEnded(), "HVM: human quit should force-end the game");
    }

    @Test
    void pvp_singleHumanQuit_doesNotEndGame() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController c = new GameController(subject);
        com.monopoly.dto.StartSessionRequest req = new com.monopoly.dto.StartSessionRequest();
        req.setSessionId("pvp-quit-test");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        c.startNewSession(req);

        var humans = c.getSessionPlayersView().stream()
                .filter(p -> p instanceof com.monopoly.model.player.HumanPlayer)
                .toList();
        if (humans.size() < 2) return;

        c.handleQuitCommand(humans.get(0).getPlayerId());
        assertFalse(c.isSessionForceEnded(), "PVP: one human quitting should not end the game");
    }
}
