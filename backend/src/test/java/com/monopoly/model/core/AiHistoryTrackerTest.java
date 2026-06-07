package com.monopoly.model.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests: lock the current public-facing behavior of
 * {@link AiHistoryTracker} (prompt JSON shape + memory counters) so that the
 * God-class refactor can be proven behavior-preserving.
 */
class AiHistoryTrackerTest {

    private static Player alice(int brownCount) {
        Player p = new HumanPlayer("alice", "Alice");
        for (int i = 0; i < brownCount; i++) {
            p.addToPropertyZone(new PropertyCard("alice-brown-" + i, "Brown", "BROWN"));
        }
        return p;
    }

    private static Player bob(int brownCount) {
        Player p = new HumanPlayer("bob", "Bob");
        for (int i = 0; i < brownCount; i++) {
            p.addToPropertyZone(new PropertyCard("bob-brown-" + i, "Brown", "BROWN"));
        }
        return p;
    }

    private static Player carolWithBank(int tenM) {
        Player p = new HumanPlayer("carol", "Carol");
        for (int i = 0; i < tenM; i++) {
            p.addToBank(new MoneyCard("carol-10-" + i, "10M", 10));
        }
        return p;
    }

    /** Drives a fixed scenario: baseline snapshot, then Alice steals a property from Bob. */
    private static AiHistoryTracker scriptedTracker() {
        AiHistoryTracker tracker = new AiHistoryTracker();

        Player a = alice(2);              // 2 brown -> 1 complete set
        Player b1 = bob(1);               // 1 brown
        Player c = carolWithBank(2);      // 20M bank, 0 sets -> cash heavy

        // Baseline (previousState empty path): no played card.
        tracker.recordSnapshot(1L, 1, "PLAY", "alice", "start", null, null, null, List.of(a, b1, c));

        // Alice plays Sly Deal (STEAL_PROPERTY); Bob loses his brown property.
        Player b2 = bob(0);
        ActionCard steal = new ActionCard("steal1", "Sly Deal", "STEAL_PROPERTY");
        tracker.recordSnapshot(2L, 1, "ACTION", "alice", "Alice steals Bob's brown",
                a, steal, "ACTION", List.of(a, b2, c));

        return tracker;
    }

    private static JsonObject findRow(JsonArray rows, String key, String value) {
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            if (row.has(key) && value.equals(row.get(key).getAsString())) {
                return row;
            }
        }
        return null;
    }

    @Test
    void fullPrompt_hasStableSchemaAndPlayerPressure() {
        AiHistoryTracker tracker = scriptedTracker();
        Player perspective = alice(2);
        Player b = bob(0);
        Player c = carolWithBank(2);

        JsonObject root = tracker.toPromptJson(perspective, List.of(perspective, b, c));

        assertEquals("ai-history-v1", root.get("schema").getAsString());
        assertTrue(root.has("recentEvents"));
        assertTrue(root.has("contestedColors"));
        assertTrue(root.has("playerPressure"));
        assertTrue(root.has("strategicWarnings"));
        assertTrue(root.has("publicCardMemory"));

        JsonArray pressure = root.getAsJsonArray("playerPressure");
        assertEquals(3, pressure.size());

        JsonObject aliceRow = findRow(pressure, "playerId", "alice");
        assertNotNull(aliceRow);
        assertTrue(aliceRow.get("isSelf").getAsBoolean());
        assertEquals(1, aliceRow.get("completeSets").getAsInt());
        assertEquals(2, aliceRow.get("setsNeededToWin").getAsInt());
        assertFalse(aliceRow.get("cashHeavyWithoutSets").getAsBoolean());

        JsonObject carolRow = findRow(pressure, "playerId", "carol");
        assertNotNull(carolRow);
        assertEquals(0, carolRow.get("completeSets").getAsInt());
        assertEquals(20, carolRow.get("bankM").getAsInt());
        assertTrue(carolRow.get("cashHeavyWithoutSets").getAsBoolean());
    }

    @Test
    void fullPrompt_recordsRecentEvents() {
        AiHistoryTracker tracker = scriptedTracker();
        JsonObject root = tracker.toPromptJson(alice(2), List.of(alice(2), bob(0), carolWithBank(2)));
        JsonArray events = root.getAsJsonArray("recentEvents");
        assertTrue(events.size() >= 1, "the steal should be recorded as an event");
        JsonObject last = events.get(events.size() - 1).getAsJsonObject();
        assertEquals("PROPERTY_SWING", last.get("type").getAsString());
        assertEquals("alice", last.get("actorPlayerId").getAsString());
    }

    @Test
    void compactPrompt_hasCompactSchema() {
        AiHistoryTracker tracker = scriptedTracker();
        JsonObject root = tracker.toCompactPromptJson(alice(2), List.of(alice(2), bob(0), carolWithBank(2)));
        assertEquals("ai-history-v1-compact", root.get("schema").getAsString());
        assertTrue(root.has("recentEvents"));
        assertTrue(root.has("strategicWarnings"));
        assertTrue(root.has("publicCardMemory"));
    }

    @Test
    void memoryCounters_trackAttackAndTempo() {
        AiHistoryTracker tracker = scriptedTracker();
        assertEquals(1, tracker.attacksTakenFrom("bob", "alice"),
                "Bob should record one attack taken from Alice");
        assertTrue(tracker.recentBoardTempoScore("alice") > 0,
                "Alice gained board tempo from the steal");
    }

    @Test
    void publicCardMemory_listsAllImportantActions() {
        AiHistoryTracker tracker = scriptedTracker();
        JsonObject root = tracker.toPromptJson(alice(2), List.of(alice(2), bob(0), carolWithBank(2)));
        JsonObject important = root.getAsJsonObject("publicCardMemory").getAsJsonObject("importantActions");

        for (String effect : List.of("RENT_WAIVER", "DEAL_BREAKER", "STEAL_PROPERTY", "FORCED_DEAL",
                "DOUBLE_RENT", "PASS_GO", "DEBT_COLLECTOR", "BIRTHDAY", "RENT", "RENT_DUAL")) {
            assertTrue(important.has(effect), "missing importantAction key: " + effect);
        }
        JsonObject steal = important.getAsJsonObject("STEAL_PROPERTY");
        assertEquals(3, steal.get("totalInDeck").getAsInt());
        assertTrue(steal.get("seen").getAsInt() >= 1);
        assertTrue(steal.get("played").getAsInt() >= 1);
    }

    /**
     * #4 guard: the tracker's per-effect deck totals must equal the real deck composition.
     * If the deck changes in MonopolyDealCardFactory, this fails and points back here.
     */
    @Test
    void importantActionTotals_matchRealDeckComposition() {
        Map<String, Integer> deckCounts = new TreeMap<>();
        for (Card c : new MonopolyDealCardFactory().createStandardDeck108()) {
            if (c instanceof ActionCard ac && ac.getEffectCode() != null) {
                deckCounts.merge(ac.getEffectCode().toUpperCase(), 1, Integer::sum);
            }
        }

        AiHistoryTracker tracker = new AiHistoryTracker();
        JsonObject important = tracker.toPromptJson(alice(0), List.of(alice(0)))
                .getAsJsonObject("publicCardMemory").getAsJsonObject("importantActions");

        for (String effect : important.keySet()) {
            int declared = important.getAsJsonObject(effect).get("totalInDeck").getAsInt();
            int actual = deckCounts.getOrDefault(effect, 0);
            assertEquals(actual, declared,
                    "tracker totalInDeck for " + effect + " is out of sync with the real deck");
        }
    }
}
