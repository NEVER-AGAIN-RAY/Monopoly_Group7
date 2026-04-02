package com.monopoly.performance;

import com.monopoly.model.card.Card;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.RentCalculator;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地性能抽检：避免回归时出现明显性能退化。
 */
class PerformanceSmokeTest {

    private static final int ITERATIONS = 1000;
    private static final long AVG_THRESHOLD_MS = 5;
    private static final long P99_THRESHOLD_MS = 50;

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void drawAndDiscard_shouldMeetLocalThreshold() {
        GameEngineSingleton engine = GameEngineSingleton.getInstance();
        engine.attachDrawPile(new MonopolyDealCardFactory().createStandardDeck108());

        long[] elapsedNs = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            Card c = engine.drawOne();
            assertNotNull(c, "drawOne 不应返回 null（循环中会回收至弃牌堆）");
            engine.discard(c);
            elapsedNs[i] = System.nanoTime() - start;
        }

        assertLatencyWithinThreshold(elapsedNs, AVG_THRESHOLD_MS, P99_THRESHOLD_MS, "draw+discard");
    }

    @Test
    void computeRent_shouldMeetLocalThreshold() {
        HumanPlayer landlord = new HumanPlayer("h1", "perf");
        landlord.addToPropertyZone(new PropertyCard("b1", "brown-1", "BROWN"));
        landlord.addToPropertyZone(new PropertyCard("b2", "brown-2", "BROWN"));

        long[] elapsedNs = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            int due = RentCalculator.computeRentForColor(landlord, "BROWN");
            elapsedNs[i] = System.nanoTime() - start;
            assertTrue(due > 0, "完整地契颜色应产生正租金");
        }

        assertLatencyWithinThreshold(elapsedNs, AVG_THRESHOLD_MS, P99_THRESHOLD_MS, "computeRentForColor");
    }

    private static void assertLatencyWithinThreshold(
            long[] elapsedNs, long avgThresholdMs, long p99ThresholdMs, String scenario) {
        long total = 0L;
        long max = 0L;
        for (long t : elapsedNs) {
            total += t;
            if (t > max) {
                max = t;
            }
        }
        long avgMs = nanosToMillis(total / elapsedNs.length);

        long[] copy = elapsedNs.clone();
        java.util.Arrays.sort(copy);
        int p99Index = Math.min(copy.length - 1, (int) Math.ceil(copy.length * 0.99) - 1);
        long p99Ms = nanosToMillis(copy[p99Index]);

        assertTrue(avgMs < avgThresholdMs,
                scenario + " avg latency too high: " + avgMs + "ms (threshold " + avgThresholdMs + "ms)");
        assertTrue(p99Ms < p99ThresholdMs,
                scenario + " p99 latency too high: " + p99Ms + "ms (threshold " + p99ThresholdMs + "ms, max " + nanosToMillis(max) + "ms)");
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
