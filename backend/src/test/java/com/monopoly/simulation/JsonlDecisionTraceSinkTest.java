package com.monopoly.simulation;

import com.google.gson.JsonObject;
import com.monopoly.dto.GameStateSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlDecisionTraceSinkTest {

    @Test
    void outcomeUsesLastActionSummaryWhenMultiplePlayersHaveThreeSets() {
        GameStateSnapshot snapshot = new GameStateSnapshot();
        snapshot.setGameOver(true);
        snapshot.setLastActionSummary("AI-Hard-2 wins (3 complete property sets).");
        snapshot.addPlayerSummary("p1", "AI-Lookahead-1", 1, 0, 12, 0, 3);
        snapshot.addPlayerSummary("p2", "AI-Hard-2", 1, 0, 11, 0, 3);

        JsonObject lookahead = JsonlDecisionTraceSink.outcomeJsonForTool(snapshot, "p1");
        JsonObject hard = JsonlDecisionTraceSink.outcomeJsonForTool(snapshot, "p2");

        assertEquals("p2", lookahead.get("winnerPlayerId").getAsString());
        assertEquals("p2", hard.get("winnerPlayerId").getAsString());
        assertTrue(hard.get("naturalWin").getAsBoolean());
    }
}
