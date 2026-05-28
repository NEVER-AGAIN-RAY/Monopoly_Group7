package com.monopoly.simulation;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Zero-cost deterministic teacher for smoke tests and simulator plumbing.
 */
public final class FirstChoiceDecisionTeacher implements SimulationDecisionTeacher {

    @Override
    public List<SimulationDecisionResult> decideBatch(List<SimulationDecisionRequest> requests) {
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionRequest request : requests) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("source", "first_choice");
            out.add(new SimulationDecisionResult(
                    request.getDecisionId(),
                    request.getCandidates().get(0).getId(),
                    null,
                    metadata));
        }
        return out;
    }
}
