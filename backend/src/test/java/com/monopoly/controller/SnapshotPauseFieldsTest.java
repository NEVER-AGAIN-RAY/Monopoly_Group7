package com.monopoly.controller;

import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.pattern.observer.GameUpdateObserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotPauseFieldsTest {

    @Test
    void snapshot_containsPauseFields_afterPause() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameStateSnapshot[] captured = {null};
        subject.registerObserver(new GameUpdateObserver() {
            @Override
            public void onGameStateChanged(GameStateSnapshot snap) {
                captured[0] = snap;
            }
        });

        GameController c = new GameController(subject);
        c.startNewSession("snap-pause-test");
        c.pause();

        GameStateSnapshot snap = captured[0];
        assertTrue(snap != null && snap.isPaused(), "snapshot.paused should be true after pause");
        assertEquals(1, snap.getPauseHumanCount(), "one human in HVM session");
    }
}
