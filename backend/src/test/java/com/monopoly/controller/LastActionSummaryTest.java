package com.monopoly.controller;

import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-7：STATE_UPDATE JSON 中 {@link GameStateSnapshot#getLastActionSummary()} 在摸牌/出牌/结束回合后非空。
 */
class LastActionSummaryTest {


    @Test
    void afterDraw_play_endTurn_snapshotsContainNonBlankSummary() {
        AtomicReference<GameStateSnapshot> last = new AtomicReference<>();
        List<String> summaries = new ArrayList<>();
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
                summaries.add(snapshot.getLastActionSummary());
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
        assertTrue(summaries.stream()
                .filter(s -> s != null)
                .anyMatch(s -> s.toLowerCase().contains("ended turn")));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
