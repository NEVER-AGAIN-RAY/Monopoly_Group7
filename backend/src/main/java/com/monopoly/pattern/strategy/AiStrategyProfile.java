package com.monopoly.pattern.strategy;

/**
 * Difficulty profile for AiHeuristics.
 */
public enum AiStrategyProfile {
    /** Softer profile: shuffles candidates and makes less stable wild-color choices. */
    EASY,
    /** Balanced profile: builds sets first, then targets opponents with stronger banks. */
    NORMAL,
    /** Aggressive profile: focuses high-threat opponents with sets, cash, and properties. */
    HARD
}
