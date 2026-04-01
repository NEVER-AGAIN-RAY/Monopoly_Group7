package com.monopoly.controller;

import com.monopoly.model.Card;
import com.monopoly.model.MoneyCard;
import com.monopoly.model.dto.GameStateSnapshot;
import com.monopoly.model.dto.PlayActionRequest;
import com.monopoly.model.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-7：STATE_UPDATE JSON 中 {@link GameStateSnapshot#getLastActionSummary()} 在摸牌/出牌/结束回合后非空。
 */
class LastActionSummaryTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void afterDraw_play_endTurn_snapshotsContainNonBlankSummary() {
        AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        GameUpdateSubject subject = new GameUpdateSubject() {
            @Override
            public void registerObserver(GameUpdateObserver observer) {
            }

            @Override
            public void unregisterObserver(GameUpdateObserver observer) {
            }

            @Override
            public void notifyStateChanged(GameStateSnapshot snapshot) {
                last.set(snapshot);
            }
        };

        GameController c = new GameController(subject);
        c.startNewSession("summary-test");
        assertFalse(blank(last.get().getLastActionSummary()));

        c.handleDrawCommand(2);
        assertTrue(last.get().getLastActionSummary().toLowerCase().contains("drew"));

        for (int i = 0; i < c.getCurrentPlayer().getHandCardsView().size(); i++) {
            Card card = c.getCurrentPlayer().getHandCardsView().get(i);
            if (card instanceof MoneyCard) {
                PlayActionRequest req = new PlayActionRequest();
                req.setHandIndex(i);
                req.setActionType("DEPOSIT");
                c.handlePlayActionRequest(req);
                assertTrue(last.get().getLastActionSummary().contains("DEPOSIT"));
                break;
            }
        }

        c.handleEndTurnCommand();
        assertTrue(last.get().getLastActionSummary().toLowerCase().contains("ended turn"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
