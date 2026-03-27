package com.monopoly.pattern.singleton;

import com.monopoly.model.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 【Singleton 单例模式】
 * 全局唯一游戏引擎入口：集中持有牌堆引用与核心会话句柄，避免多处 new 导致状态分裂。
 * 骨架阶段仅保留结构与线程安全单例获取方式。
 */
public final class GameEngineSingleton {

    private static volatile GameEngineSingleton instance;

    private final List<Card> drawPile = new ArrayList<>();
    private final List<Card> discardPile = new ArrayList<>();

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

    public List<Card> getDiscardPileView() {
        return Collections.unmodifiableList(discardPile);
    }

    /** 初始化牌堆等逻辑由工厂与控制器协作完成，此处仅占位 */
    public void attachDrawPile(List<Card> pile) {
        drawPile.clear();
        discardPile.clear();
        if (pile != null) {
            drawPile.addAll(pile);
        }
    }

    /**
     * 从抽牌堆摸一张并移除。
     * 若抽牌堆已空，则按规则将弃牌堆全部洗牌后转为新抽牌堆，再继续摸牌。
     * 若抽牌堆与弃牌堆皆空，则返回 {@code null}。
     */
    public Card drawOne() {
        replenishDrawPileFromDiscardIfEmpty();
        if (drawPile.isEmpty()) {
            return null;
        }
        return drawPile.remove(0);
    }

    /**
     * requirements：摸牌时若公共抽牌堆无牌，将弃牌堆所有牌随机洗牌后作为新抽牌堆。
     * 仅在抽牌堆为空时调用；弃牌堆也为空时不做任何事。
     */
    public void replenishDrawPileFromDiscardIfEmpty() {
        if (!drawPile.isEmpty()) {
            return;
        }
        if (discardPile.isEmpty()) {
            return;
        }
        drawPile.addAll(discardPile);
        discardPile.clear();
        Collections.shuffle(drawPile, ThreadLocalRandom.current());
    }

    /**
     * 最小闭环：将一张牌放入弃牌堆。
     */
    public void discard(Card card) {
        if (card != null) {
            discardPile.add(card);
        }
    }

    /**
     * @return 当前抽牌堆剩余数量
     */
    public int remainingCount() {
        return drawPile.size();
    }

    public int discardCount() {
        return discardPile.size();
    }

    public void discardMany(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        for (Card card : cards) {
            discard(card);
        }
    }
}
