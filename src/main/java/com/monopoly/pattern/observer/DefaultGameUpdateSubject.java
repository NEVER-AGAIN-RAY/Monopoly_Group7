package com.monopoly.pattern.observer;

import com.monopoly.model.dto.GameStateSnapshot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认主题实现：线程安全的观察者列表（适合 WebSocket 多连接场景）。
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
