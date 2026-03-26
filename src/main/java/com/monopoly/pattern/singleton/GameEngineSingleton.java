package com.monopoly.pattern.singleton;

import com.monopoly.model.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【Singleton 单例模式】
 * 全局唯一游戏引擎入口：集中持有牌堆引用与核心会话句柄，避免多处 new 导致状态分裂。
 * 骨架阶段仅保留结构与线程安全单例获取方式。
 */
public final class GameEngineSingleton {

    private static volatile GameEngineSingleton instance;

    private final List<Card> drawPile = new ArrayList<>();

    private GameEngineSingleton() {
    }

    /**
     * 双重检查锁定（骨架）：后续若需可替换为枚举单例或 IoC 注入。
     */
    public static GameEngineSingleton getInstance() {
        if (instance == null) {
            synchronized (GameEngineSingleton.class) {
                if (instance == null) {
                    instance = new GameEngineSingleton();
                }
            }
        }
        return instance;
    }

    /** 仅供测试或重置会话时清理单例（骨架占位，慎用） */
    static void resetForTests() {
        synchronized (GameEngineSingleton.class) {
            instance = null;
        }
    }

    public List<Card> getDrawPileView() {
        return Collections.unmodifiableList(drawPile);
    }

    /** 初始化牌堆等逻辑由工厂与控制器协作完成，此处仅占位 */
    public void attachDrawPile(List<Card> pile) {
        drawPile.clear();
        drawPile.addAll(pile);
    }
}
