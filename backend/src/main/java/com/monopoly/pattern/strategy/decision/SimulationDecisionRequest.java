package com.monopoly.pattern.strategy.decision;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/**
 * Model-labeling request emitted by a real game session.
 */
public final class SimulationDecisionRequest {

    private final String decisionId;
    private final String sessionId;
    private final String actorPlayerId;
    private final String decisionKind;
    private final long stateSequence;
    private final JsonObject contextJson;
    private final List<SimulationDecisionCandidate> candidates;
    private final long createdAtEpochMs;

    public SimulationDecisionRequest(
            String decisionId,
            String sessionId,
            String actorPlayerId,
            String decisionKind,
            long stateSequence,
            JsonObject contextJson,
            List<SimulationDecisionCandidate> candidates) {
        if (decisionId == null || decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
        if (decisionKind == null || decisionKind.isBlank()) {
            throw new IllegalArgumentException("decisionKind must not be blank");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("decision candidates must not be empty");
        }
        this.decisionId = decisionId.trim();
        this.sessionId = sessionId == null ? "" : sessionId.trim();
        this.actorPlayerId = actorPlayerId == null ? "" : actorPlayerId.trim();
        this.decisionKind = decisionKind.trim();
        this.stateSequence = stateSequence;
        this.contextJson = copy(contextJson);
        this.candidates = List.copyOf(candidates);
        this.createdAtEpochMs = System.currentTimeMillis();
    }

    public String getDecisionId() {
        return decisionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getActorPlayerId() {
        return actorPlayerId;
    }

    public String getDecisionKind() {
        return decisionKind;
    }

    public long getStateSequence() {
        return stateSequence;
    }

    public JsonObject getContextJson() {
        return copy(contextJson);
    }

    public List<SimulationDecisionCandidate> getCandidates() {
        return candidates;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public boolean hasCandidate(String candidateId) {
        if (candidateId == null) {
            return false;
        }
        for (SimulationDecisionCandidate candidate : candidates) {
            if (candidate.getId().equals(candidateId)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject copy(JsonObject source) {
        return source == null ? new JsonObject() : source.deepCopy();
    }

    @Override
    public String toString() {
        return "SimulationDecisionRequest{"
                + "decisionId='" + decisionId + '\''
                + ", sessionId='" + sessionId + '\''
                + ", actorPlayerId='" + actorPlayerId + '\''
                + ", decisionKind='" + decisionKind + '\''
                + ", candidates=" + candidates.size()
                + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SimulationDecisionRequest that)) {
            return false;
        }
        return stateSequence == that.stateSequence
                && decisionId.equals(that.decisionId)
                && sessionId.equals(that.sessionId)
                && actorPlayerId.equals(that.actorPlayerId)
                && decisionKind.equals(that.decisionKind)
                && contextJson.equals(that.contextJson)
                && candidates.equals(that.candidates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                decisionId,
                sessionId,
                actorPlayerId,
                decisionKind,
                stateSequence,
                contextJson,
                candidates);
    }
}
