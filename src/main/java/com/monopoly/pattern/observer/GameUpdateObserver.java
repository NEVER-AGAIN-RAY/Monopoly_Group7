package com.monopoly.pattern.observer;

import com.monopoly.model.dto.GameStateSnapshot;

/**
 * 【Observer 观察者模式】观察者端：接收模型变更通知。
 */
@FunctionalInterface
public interface GameUpdateObserver {

    void onGameStateChanged(GameStateSnapshot snapshot);
}
