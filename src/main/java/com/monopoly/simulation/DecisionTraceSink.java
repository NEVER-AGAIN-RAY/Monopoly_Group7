package com.monopoly.simulation;

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

    @Override
    default void close() throws Exception {
    }
}
