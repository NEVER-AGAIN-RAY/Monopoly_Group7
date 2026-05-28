package com.monopoly.simulation;

import com.monopoly.dto.GameStateSnapshot;

/**
 * Optional sink for distillation records.
 */
public interface DecisionTraceSink extends AutoCloseable {

    DecisionTraceSink NONE = new DecisionTraceSink() {
        @Override
        public void record(SimulationDecisionRequest request, SimulationDecisionResult result) {
        }

        @Override
        public void close() {
        }
    };

    void record(SimulationDecisionRequest request, SimulationDecisionResult result);

    default void recordGameResult(String sessionId, GameStateSnapshot finalSnapshot) {
    }

    @Override
    default void close() throws Exception {
    }
}
