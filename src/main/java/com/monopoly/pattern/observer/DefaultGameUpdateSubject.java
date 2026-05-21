package com.monopoly.pattern.observer;

import com.monopoly.dto.GameStateSnapshot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe DefaultGameUpdateSubject for many clients.
 */
public class DefaultGameUpdateSubject implements GameUpdateSubject {

    private final List<GameUpdateObserver> observers = new CopyOnWriteArrayList<>();

    @Override
    public void registerObserver(GameUpdateObserver observer) {
        observers.add(observer);
    }

    @Override
    public void unregisterObserver(GameUpdateObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyStateChanged(GameStateSnapshot snapshot) {
        for (GameUpdateObserver observer : observers) {
            observer.onGameStateChanged(snapshot);
        }
    }
}
