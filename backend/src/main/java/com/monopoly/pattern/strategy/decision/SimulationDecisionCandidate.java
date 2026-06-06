package com.monopoly.pattern.strategy.decision;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * One legal option for a model-labeled decision.
 */
public final class SimulationDecisionCandidate {

    private final String id;
    private final String summary;
    private final JsonObject payload;

    public SimulationDecisionCandidate(String id, String summary, JsonObject payload) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("candidate id must not be blank");
        }
        this.id = id.trim();
        this.summary = summary == null ? "" : summary;
        this.payload = copy(payload);
    }

    public String getId() {
        return id;
    }

    public String getSummary() {
        return summary;
    }

    public JsonObject getPayload() {
        return copy(payload);
    }

    private static JsonObject copy(JsonObject source) {
        return source == null ? new JsonObject() : source.deepCopy();
    }

    @Override
    public String toString() {
        return "SimulationDecisionCandidate{"
                + "id='" + id + '\''
                + ", summary='" + summary + '\''
                + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SimulationDecisionCandidate that)) {
            return false;
        }
        return id.equals(that.id)
                && summary.equals(that.summary)
                && payload.equals(that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, summary, payload);
    }
}
