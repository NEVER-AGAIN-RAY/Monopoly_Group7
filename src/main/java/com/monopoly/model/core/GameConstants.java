package com.monopoly.model.core;

/**
 * 全局游戏常量（与 Monopoly Deal 标准牌组一致）。
 */
public final class GameConstants {

    /** 标准牌堆张数（含房产、万能、钱币、行动卡）。 */
    public static final int STANDARD_DECK_SIZE = 108;

    /** 单局默认最长时长（毫秒），对应需求「单局不超过 1 小时」。 */
    public static final long DEFAULT_SESSION_LIMIT_MS = 3_600_000L;

    /**
     * JVM 覆盖单局时长（毫秒），例如测试：{@code -Dmonopoly.sessionLimitMs=60000}。
     */
    public static final String SESSION_LIMIT_MS_PROPERTY = "monopoly.sessionLimitMs";

    /**
     * 为 true 时每满 3 个「整轮」将 {@code exportSessionJson()} 写入用户目录下
     * {@code ~/.monopoly-deal/autosave.json}；默认 false 仅打日志（阶段 3 T3-5）。
     */
    public static final String AUTOSAVE_PROPERTY = "monopoly.autosave";

    /**
     * 存档加密口令（演示用）；未设置时 autosave / SAVE 写盘为明文 JSON（阶段 3 T3-6）。
     */
    public static final String SAVE_KEY_PROPERTY = "monopoly.saveKey";

    private GameConstants() {
    }
}
