package com.monopoly.simulation;

import java.util.List;

/**
 * Batch labeler for simulation decisions.
 */
@FunctionalInterface
public interface SimulationDecisionTeacher {

    List<SimulationDecisionResult> decideBatch(List<SimulationDecisionRequest> requests) throws Exception;
}
