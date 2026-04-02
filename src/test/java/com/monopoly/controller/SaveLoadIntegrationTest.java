package com.monopoly.controller;

import com.monopoly.model.core.GameConstants;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 控制器级存读档集成：覆盖 START_SESSION -> DRAW/PLAY -> export/import 的业务链路。
 */
class SaveLoadIntegrationTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void roundTrip_afterDrawAndPlay_preservesDeckTotalAndCurrentPlayer() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController source = new GameController(subject);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("save-load-int");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        source.startNewSession(req);

        source.handleDrawCommand(2);
        PlayActionRequest play = new PlayActionRequest();
        play.setActionType("DEPOSIT");
        play.setHandIndex(0);
        source.handlePlayActionRequest(play);
        source.handleEndTurnCommand();

        String expectedCurrentPlayerId = source.getCurrentPlayer().getPlayerId();
        String sessionJson = source.exportSessionJson();

        GameSessionMemento.resetSingletonEngineForTests();
        GameController restored = new GameController(subject);
        restored.importSessionJson(sessionJson);

        int totalCards = GameEngineSingleton.getInstance().countAllCardsInPlay(restored.getSessionPlayersView());
        assertEquals(GameConstants.STANDARD_DECK_SIZE, totalCards, "恢复后全场牌数应为 108");
        assertEquals(expectedCurrentPlayerId, restored.getCurrentPlayer().getPlayerId(), "恢复后当前玩家应一致");
    }
}
