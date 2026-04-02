package com.monopoly.persistence;

import com.monopoly.controller.GameController;
import com.monopoly.model.player.Player;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验收：开局 → 导出 memento JSON → 重置引擎并恢复 → 公共牌堆张数与各玩家持有张数一致。
 */
class GameSessionMementoTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void roundTrip_preservesDeckAndHandCounts() {
        GameUpdateSubject subject = new com.monopoly.pattern.observer.DefaultGameUpdateSubject();
        GameController c1 = new GameController(subject);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("snap-1");
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("NORMAL");
        req.setRandomizeFirstPlayer(false);
        c1.startNewSession(req);

        int draw1 = GameEngineSingleton.getInstance().remainingCount();
        int disc1 = GameEngineSingleton.getInstance().discardCount();
        int[] hands1 = handCounts(c1);

        GameSessionMemento memento = GameSessionMemento.capture(c1);
        String json = memento.toJson();

        GameSessionMemento.resetSingletonEngineForTests();
        GameController c2 = GameSessionMemento.restoreFromJson(subject, json);

        assertEquals(draw1, GameEngineSingleton.getInstance().remainingCount(), "抽牌堆张数应一致");
        assertEquals(disc1, GameEngineSingleton.getInstance().discardCount(), "弃牌堆张数应一致");
        int[] hands2 = handCounts(c2);
        assertEquals(hands1.length, hands2.length);
        for (int i = 0; i < hands1.length; i++) {
            assertEquals(hands1[i], hands2[i], "玩家 " + i + " 手牌数应一致");
        }

        int totalOwned = 0;
        for (Player p : c2.getSessionPlayersView()) {
            totalOwned += p.countOwnedCardsTotal();
        }
        assertEquals(
                draw1 + disc1 + totalOwned,
                com.monopoly.model.core.GameConstants.STANDARD_DECK_SIZE,
                "恢复后全场牌数守恒");
    }

    private static int[] handCounts(GameController c) {
        return c.getSessionPlayersView().stream().mapToInt(Player::getHandCardCount).toArray();
    }
}
