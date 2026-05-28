package com.monopoly.model.core;

/**
 * Global constants (108-card standard deck, session limits).
 */
public final class GameConstants {

    /** Playable deck size after removing the two rule cards from the 108-card retail list. */
    public static final int STANDARD_DECK_SIZE = 106;

    /** Default one-hour session limit in milliseconds. */
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
