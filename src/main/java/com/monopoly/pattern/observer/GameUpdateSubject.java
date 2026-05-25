package com.monopoly.pattern.observer;

import com.monopoly.dto.GameStateSnapshot;

/**
 * [Observer] Subject: register observers and notify on snapshot changes.
 */
public interface GameUpdateSubject {

    void registerObserver(GameUpdateObserver observer);

    void unregisterObserver(GameUpdateObserver observer);

    /** Called after model or controller state changes so observers can push JSON to clients. */
    void notifyStateChanged(GameStateSnapshot snapshot);
}
