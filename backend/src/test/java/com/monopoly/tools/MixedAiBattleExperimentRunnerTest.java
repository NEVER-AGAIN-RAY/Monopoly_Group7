package com.monopoly.tools;

import com.google.gson.JsonObject;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MixedAiBattleExperimentRunnerTest {

    @Test
    void parsesExplicitDeckSeedList() {
        assertEquals(
                List.of(202605267047L, 202605268048L, 202605269015L),
                MixedAiBattleExperimentRunner.explicitDeckSeedsForTest(
                        "202605267047, 202605268048\n202605269015"));
    }

    @Test
    void rejectsInvalidExplicitDeckSeedListToken() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MixedAiBattleExperimentRunner.explicitDeckSeedsForTest("202605267047 nope"));
    }

    @Test
    void recordsInitialPlayerFromSeedAndLineup() {
        JsonObject initial = MixedAiBattleExperimentRunner.initialPlayerForTest(
                true,
                -3335678456327973910L,
                List.of("lookahead", "hard"));

        assertEquals(0, initial.get("index").getAsInt());
        assertEquals("lookahead", initial.get("role").getAsString());
        assertEquals("lookahead", initial.get("team").getAsString());

        JsonObject fixed = MixedAiBattleExperimentRunner.initialPlayerForTest(
                false,
                -3335678456327973910L,
                List.of("hard", "lookahead"));

        assertEquals(0, fixed.get("index").getAsInt());
        assertEquals("hard", fixed.get("role").getAsString());
        assertEquals("hard", fixed.get("team").getAsString());
    }

    @Test
    void lookaheadEffectiveConfigSnapshotIncludesPromotedDefaults() {
        JsonObject config = SearchLookaheadAiPlayStrategy.effectiveConfigSnapshot();

        assertEquals(900d, config.get("monopoly.search.buildingActionBonus").getAsDouble());
        assertEquals(320d, config.get("monopoly.search.buildingRentBonusValue").getAsDouble());
        assertEquals(260d, config.get("monopoly.search.opponentBuildingThreatValue").getAsDouble());
        assertEquals(true, config.get("monopoly.search.boardAwarePayment").getAsBoolean());
        assertEquals(true, config.get("monopoly.search.boardAwareOverflowDiscard").getAsBoolean());
        assertEquals(-1d, config.get("monopoly.search.hardRentFallbackMargin").getAsDouble());
        assertEquals("", config.get("monopoly.search.rolloutOverrideAllowedTransitions").getAsString());
    }
}
