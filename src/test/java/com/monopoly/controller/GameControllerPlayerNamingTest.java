package com.monopoly.controller;

import com.monopoly.dto.StartSessionRequest;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void randomFirstPlayerDoesNotShufflePublicPlayerOrder() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("random-first-order");
        req.setPlayerCount(4);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(true);

        controller.startNewSession(req);

        List<String> ids = controller.getSessionPlayersView().stream()
                .map(p -> p.getPlayerId())
                .toList();
        assertEquals(List.of("pvp-1", "pvp-2", "pvp-3", "pvp-4"), ids);
        assertTrue(ids.contains(controller.getCurrentPlayer().getPlayerId()));
    }

    @Test
    void randomFirstPlayerCanSelectDifferentStartingSeatsAcrossSessions() {
        Set<String> starters = IntStream.range(0, 40)
                .mapToObj(i -> {
                    GameSessionMemento.resetSingletonEngineForTests();
                    GameController controller = new GameController(new DefaultGameUpdateSubject());
                    StartSessionRequest req = new StartSessionRequest();
                    req.setSessionId("random-first-" + i);
                    req.setPlayerCount(4);
                    req.setGameMode("PVP");
                    req.setRandomizeFirstPlayer(true);
                    controller.startNewSession(req);
                    return controller.getCurrentPlayer().getPlayerId();
                })
                .collect(Collectors.toSet());

        assertTrue(starters.size() > 1, "随机先手多次开局应能出现不同先手");
    }
}
