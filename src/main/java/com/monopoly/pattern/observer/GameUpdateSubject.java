package com.monopoly.pattern.observer;

import com.monopoly.dto.GameStateSnapshot;

/**
 * [Observer] Subject: register observers and notify on snapshot changes.
 */
public interface GameUpdateSubject {

    void registerObserver(GameUpdateObserver observer);

    void unregisterObserver(GameUpdateObserver observer);

    /** 由模型或控制器在数据变更后调用，驱动网络层向客户端推送 JSON */
    void notifyStateChanged(GameStateSnapshot snapshot);
}
