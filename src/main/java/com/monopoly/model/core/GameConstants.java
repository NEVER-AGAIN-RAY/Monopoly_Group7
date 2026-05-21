package com.monopoly.model.core;

/**
 * Global constants (108-card standard deck, session limits).
 */
public final class GameConstants {

    /** 标准牌堆张数（含房产、万能、钱币、行动卡）。 */
    public static final int STANDARD_DECK_SIZE = 108;

    /** 单局默认最长时长（毫秒），对应需求「单局不超过 1 小时」。 */
    public static final long DEFAULT_SESSION_LIMIT_MS = 3_600_000L;

    /**
     * Override session length: -Dmonopoly.sessionLimitMs
     */
    public static final String SESSION_LIMIT_MS_PROPERTY = "monopoly.sessionLimitMs";

    /**
     * When true, writes exportSessionJson() to ~/.monopoly-deal/autosave.json
     * every three full rounds (-Dmonopoly.autosave).
     */
    public static final String AUTOSAVE_PROPERTY = "monopoly.autosave";

    /**
     * -Dmonopoly.saveKey enables AES for saves.
     */
    public static final String SAVE_KEY_PROPERTY = "monopoly.saveKey";

    private GameConstants() {
    }
}
