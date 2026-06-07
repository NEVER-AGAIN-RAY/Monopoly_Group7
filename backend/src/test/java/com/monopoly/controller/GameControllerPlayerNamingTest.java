package com.monopoly.controller;

import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.pattern.strategy.DeepSeekAiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.LocalRankerAiPlayStrategy;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameControllerPlayerNamingTest {


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

    @Test
    void customLineupCreatesHumanAndMixedAiSeats() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("custom-mixed");
        req.setPlayerCount(4);
        req.setGameMode("CUSTOM");
        req.setCustomLineup("human,human,llm,lookahead");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals(List.of("pvp-1", "pvp-2", "ai-3", "ai-4"),
                controller.getSessionPlayersView().stream().map(p -> p.getPlayerId()).toList());
        assertEquals(List.of("Player-1", "Player-2", "DeepSeek-AI-3", "AI-Lookahead-4"),
                controller.getSessionPlayersView().stream().map(p -> p.getDisplayName()).toList());
        assertTrue(controller.getSessionPlayersView().get(0) instanceof HumanPlayer);
        assertTrue(controller.getSessionPlayersView().get(1) instanceof HumanPlayer);
        assertTrue(controller.getSessionPlayersView().get(2) instanceof AIPlayer);
        assertTrue(((AIPlayer) controller.getSessionPlayersView().get(2)).getPlayStrategy()
                instanceof DeepSeekAiPlayStrategy);
        assertTrue(((AIPlayer) controller.getSessionPlayersView().get(3)).getPlayStrategy()
                instanceof SearchLookaheadAiPlayStrategy);
    }

    @Test
    void hvmStrongDifficultyUsesLookaheadStrategy() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("hvm-strong");
        req.setPlayerCount(3);
        req.setGameMode("HVM");
        req.setAiDifficulty("STRONG");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals(List.of("Human", "AI-Strong-1", "AI-Strong-2"),
                controller.getSessionPlayersView().stream().map(p -> p.getDisplayName()).toList());
        assertTrue(((AIPlayer) controller.getSessionPlayersView().get(1)).getPlayStrategy()
                instanceof SearchLookaheadAiPlayStrategy);
        assertTrue(((AIPlayer) controller.getSessionPlayersView().get(2)).getPlayStrategy()
                instanceof SearchLookaheadAiPlayStrategy);
    }

    @Test
    void hvmLookaheadAliasUsesStrongStrategy() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("hvm-lookahead-alias");
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("lookahead");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals("AI-Strong-1", controller.getSessionPlayersView().get(1).getDisplayName());
        assertTrue(((AIPlayer) controller.getSessionPlayersView().get(1)).getPlayStrategy()
                instanceof SearchLookaheadAiPlayStrategy);
    }

    @Test
    void customStrongAliasUsesLookaheadStrategy() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("custom-strong-alias");
        req.setPlayerCount(2);
        req.setGameMode("CUSTOM");
        req.setCustomLineup("human,local_strong");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals(List.of("Player-1", "AI-Lookahead-2"),
                controller.getSessionPlayersView().stream().map(p -> p.getDisplayName()).toList());
        assertTrue(((AIPlayer) controller.getSessionPlayersView().get(1)).getPlayStrategy()
                instanceof SearchLookaheadAiPlayStrategy);
    }

    @Test
    void customStudentAliasUsesLocalRankerWhenDefaultModelExists() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("custom-student-alias");
        req.setPlayerCount(2);
        req.setGameMode("CUSTOM");
        req.setCustomLineup("human,llm_student");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals(List.of("Player-1", "AI-Student-2"),
                controller.getSessionPlayersView().stream().map(p -> p.getDisplayName()).toList());
        assertTrue(((AIPlayer) controller.getSessionPlayersView().get(1)).getPlayStrategy()
                instanceof LocalRankerAiPlayStrategy);
    }

    @Test
    void customDisplayNamesOverrideGeneratedSeatNames() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("custom-display-names");
        req.setPlayerCount(3);
        req.setGameMode("CUSTOM");
        req.setPlayerRoles(List.of("human", "hard", "student"));
        req.setDisplayNames(List.of("阿泽", "Hard Bot", "Student Bot"));
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals(List.of("阿泽", "Hard Bot", "Student Bot"),
                controller.getSessionPlayersView().stream().map(p -> p.getDisplayName()).toList());
    }

    @Test
    void customPlayerRolesOverridePlayerCount() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("custom-roles");
        req.setPlayerCount(2);
        req.setGameMode("CUSTOM");
        req.setPlayerRoles(List.of("hard", "hard", "llm", "llm"));
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals(List.of("AI-Hard-1", "AI-Hard-2", "DeepSeek-AI-3", "DeepSeek-AI-4"),
                controller.getSessionPlayersView().stream().map(p -> p.getDisplayName()).toList());
        assertEquals(4, controller.getSessionPlayersView().size());
    }

    @Test
    void customModeIsPreservedWhenCapturedForSave() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("custom-save-mode");
        req.setPlayerCount(4);
        req.setGameMode("CUSTOM");
        req.setCustomLineup("human,human,llm,llm");
        req.setRandomizeFirstPlayer(false);

        controller.startNewSession(req);

        assertEquals("CUSTOM", GameSessionMemento.capture(controller).getGameMode());
    }
}
