package com.monopoly.simulation;

import com.monopoly.persistence.GameSessionMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationWorkerTest {

    private final String prevDeepSeekEnabled = System.getProperty("monopoly.deepseek.enabled");
    private final String prevAiDelay = System.getProperty("monopoly.ai.decisionDelayMs");

    @AfterEach
    void tearDown() {
        restore("monopoly.deepseek.enabled", prevDeepSeekEnabled);
        restore("monopoly.ai.decisionDelayMs", prevAiDelay);
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void workerRunsRealAiVsAiSessionThroughBroker() throws Exception {
        System.setProperty("monopoly.deepseek.enabled", "false");
        System.setProperty("monopoly.ai.decisionDelayMs", "0");
        try (DecisionBroker broker = new DecisionBroker(
                new FirstChoiceDecisionTeacher(),
                4,
                Duration.ofMillis(5))) {
            SimulationWorker worker = new SimulationWorker(
                    "sim-worker-test",
                    2,
                    broker,
                    10,
                    Duration.ofSeconds(2));

            SimulationResult result = worker.call();

            assertTrue(result.snapshots() > 1);
            assertTrue(broker.getDecisionCount() > 0);
            assertTrue(broker.getTeacherCallCount() > 0);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
