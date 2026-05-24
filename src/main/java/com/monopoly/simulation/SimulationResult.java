package com.monopoly.simulation;

/**
 * Summary of one simulated game.
 */
public record SimulationResult(
        String sessionId,
        int snapshots,
        boolean gameOver,
        String phase,
        String forceEndReason) {
}
