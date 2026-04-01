package com.monopoly.controller;

import com.monopoly.model.GameConstants;
import com.monopoly.model.persistence.GameSessionMemento;
import com.monopoly.model.dto.StartSessionRequest;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullRoundsAutosaveTest {

    private String prevHome;
    private String prevAutosave;

    @TempDir
    Path tempHome;

    @BeforeEach
    void setProps() {
        prevHome = System.getProperty("user.home");
        prevAutosave = System.getProperty(GameConstants.AUTOSAVE_PROPERTY);
        System.setProperty("user.home", tempHome.toString());
        System.setProperty(GameConstants.AUTOSAVE_PROPERTY, "true");
    }

    @AfterEach
    void restoreProps() {
        if (prevHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", prevHome);
        }
        if (prevAutosave == null) {
            System.clearProperty(GameConstants.AUTOSAVE_PROPERTY);
        } else {
            System.setProperty(GameConstants.AUTOSAVE_PROPERTY, prevAutosave);
        }
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void afterThreeFullRounds_autosaveFileWritten() throws Exception {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController c = new GameController(subject);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("autosave-round");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        c.startNewSession(req);

        for (int round = 0; round < 3; round++) {
            c.handleDrawCommand(2);
            c.handleEndTurnCommand();
            c.handleDrawCommand(2);
            c.handleEndTurnCommand();
        }

        assertEquals(3, c.getFullRoundsCompleted());

        Path autosave = Path.of(tempHome.toString(), ".monopoly-deal", "autosave.json");
        assertTrue(Files.isRegularFile(autosave));
        String json = Files.readString(autosave, StandardCharsets.UTF_8);
        assertTrue(json.contains("autosave-round"), "memento 应含 sessionId");
    }
}
