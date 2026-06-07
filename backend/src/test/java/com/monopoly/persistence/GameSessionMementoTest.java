package com.monopoly.persistence;

import com.monopoly.controller.GameController;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：开局 → 导出 memento JSON → 重置引擎并恢复 → 公共牌堆张数与各玩家持有张数一致。
 */
class GameSessionMementoTest {


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

        int draw1 = c1.getEngine().remainingCount();
        int disc1 = c1.getEngine().discardCount();
        int[] hands1 = handCounts(c1);

        GameSessionMemento memento = GameSessionMemento.capture(c1);
        String json = memento.toJson();

        GameController c2 = GameSessionMemento.restoreFromJson(subject, json);

        assertEquals(draw1, c2.getEngine().remainingCount(), "抽牌堆张数应一致");
        assertEquals(disc1, c2.getEngine().discardCount(), "弃牌堆张数应一致");
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

    @Test
    void roundTrip_preservesCustomGameMode() {
        GameUpdateSubject subject = new com.monopoly.pattern.observer.DefaultGameUpdateSubject();
        GameController c1 = new GameController(subject);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("snap-custom");
        req.setPlayerCount(4);
        req.setGameMode("CUSTOM");
        req.setCustomLineup("human,lookahead,hard,llm");
        req.setRandomizeFirstPlayer(false);
        c1.startNewSession(req);

        String json = GameSessionMemento.capture(c1).toJson();
        GameController c2 = GameSessionMemento.restoreFromJson(subject, json);

        assertEquals("CUSTOM", GameSessionMemento.capture(c2).getGameMode());
        Player restoredLookahead = c2.getSessionPlayersView().get(1);
        assertTrue(restoredLookahead instanceof AIPlayer);
        assertTrue(((AIPlayer) restoredLookahead).getPlayStrategy() instanceof SearchLookaheadAiPlayStrategy);
    }

    private static int[] handCounts(GameController c) {
        return c.getSessionPlayersView().stream().mapToInt(Player::getHandCardCount).toArray();
    }
}
