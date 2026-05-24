package com.monopoly.simulation;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * Label returned for a SimulationDecisionRequest.
 */
public final class SimulationDecisionResult {

    private final String decisionId;
    private final String choiceId;
    private final String rawResponse;
    private final JsonObject metadata;

    public SimulationDecisionResult(String decisionId, String choiceId) {
        this(decisionId, choiceId, null, null);
    }

    public SimulationDecisionResult(
            String decisionId,
            String choiceId,
            String rawResponse,
            JsonObject metadata) {
        if (decisionId == null || decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
        if (choiceId == null || choiceId.isBlank()) {
            throw new IllegalArgumentException("choiceId must not be blank");
        }
        this.decisionId = decisionId.trim();
        this.choiceId = choiceId.trim();
        this.rawResponse = rawResponse;
        this.metadata = copy(metadata);
    }

    public String getDecisionId() {
        return decisionId;
    }

    public String getChoiceId() {
        return choiceId;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public JsonObject getMetadata() {
        return copy(metadata);
    }

    private static JsonObject copy(JsonObject source) {
        return source == null ? new JsonObject() : source.deepCopy();
    }

    @Override
    public String toString() {
        return "SimulationDecisionResult{"
                + "decisionId='" + decisionId + '\''
                + ", choiceId='" + choiceId + '\''
                + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SimulationDecisionResult that)) {
            return false;
        }
        return decisionId.equals(that.decisionId)
                && choiceId.equals(that.choiceId)
                && Objects.equals(rawResponse, that.rawResponse)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decisionId, choiceId, rawResponse, metadata);
    }
}
