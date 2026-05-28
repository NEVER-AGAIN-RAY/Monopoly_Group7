package com.monopoly.simulation;

import com.google.gson.JsonObject;
import com.monopoly.pattern.strategy.LocalRankerAiPlayStrategy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses the exported local ranker to label brokered decision envelopes.
 */
public final class LocalRankerDecisionTeacher implements SimulationDecisionTeacher {

    private final LocalRankerAiPlayStrategy.RankerModel model;
    private final String modelPath;

    public LocalRankerDecisionTeacher(Path modelPath) {
        if (modelPath == null) {
            throw new IllegalArgumentException("modelPath must not be null");
        }
        this.model = LocalRankerAiPlayStrategy.RankerModel.load(modelPath);
        this.modelPath = modelPath.toString();
    }

    @Override
    public List<SimulationDecisionResult> decideBatch(List<SimulationDecisionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionRequest request : requests) {
            Choice choice = choose(request);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("source", "local_ranker");
            metadata.addProperty("score", choice.score());
            metadata.addProperty("modelPath", modelPath);
            out.add(new SimulationDecisionResult(
                    request.getDecisionId(),
                    choice.candidateId(),
                    null,
                    metadata));
        }
        return out;
    }

    private Choice choose(SimulationDecisionRequest request) {
        Choice best = null;
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            double[] features = LocalRankerAiPlayStrategy.FeatureExtractor.featuresFor(
                    request.getDecisionKind(),
                    request.getStateSequence(),
                    request.getContextJson(),
                    candidate.getPayload(),
                    candidate.getId(),
                    candidate.getSummary());
            Choice choice = new Choice(candidate.getId(), model.score(features));
            if (best == null || choice.score() > best.score()) {
                best = choice;
            }
        }
        if (best != null) {
            return best;
        }
        SimulationDecisionCandidate fallback = request.getCandidates().get(0);
        return new Choice(fallback.getId(), 0d);
    }

    private record Choice(String candidateId, double score) {
    }
}
