package com.monopoly.controller;

import com.monopoly.dto.StartSessionRequest;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameControllerPlayerNamingTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void hvmAiDisplayNamesIncludePlayerIndex() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("ai-names");
        req.setPlayerCount(4);
        req.setGameMode("HVM");
        req.setAiDifficulty("NORMAL");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        List<String> names = controller.getSessionPlayersView().stream()
                .map(p -> p.getDisplayName())
                .toList();
        assertEquals(List.of("Human", "AI-Normal-1", "AI-Normal-2", "AI-Normal-3"), names);
    }
}
