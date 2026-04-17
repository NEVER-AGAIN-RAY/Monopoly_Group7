package com.monopoly.pattern.observer;

import com.monopoly.dto.GameStateSnapshot;

/**
 * 【Observer 观察者模式】主题端：注册/移除观察者并在状态变更时广播。
 */
public interface GameUpdateSubject {

    void registerObserver(GameUpdateObserver observer);

    void unregisterObserver(GameUpdateObserver observer);

    /** 由模型或控制器在数据变更后调用，驱动网络层向客户端推送 JSON */
    void notifyStateChanged(GameStateSnapshot snapshot);
}
