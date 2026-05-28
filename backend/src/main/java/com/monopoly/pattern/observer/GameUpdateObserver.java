package com.monopoly.pattern.observer;

import com.monopoly.dto.GameStateSnapshot;

/**
 * [Observer] Observer: receives GameStateSnapshot updates.
 */
@FunctionalInterface
public interface GameUpdateObserver {

    void onGameStateChanged(GameStateSnapshot snapshot);
}
