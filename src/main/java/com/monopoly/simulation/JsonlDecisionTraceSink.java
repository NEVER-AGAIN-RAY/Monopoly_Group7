package com.monopoly.simulation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes one supervised-label record per completed decision.
 */
public final class JsonlDecisionTraceSink implements DecisionTraceSink {

    private static final Gson GSON = new Gson();

    private final BufferedWriter writer;

    public JsonlDecisionTraceSink(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("trace path must not be null");
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
    }

    @Override
    public synchronized void record(
            SimulationDecisionRequest request,
            SimulationDecisionResult result) {
        try {
            JsonObject row = new JsonObject();
            row.addProperty("schema", "monopoly-deal-decision-v1");
            row.addProperty("recordedAtEpochMs", System.currentTimeMillis());
            row.add("request", requestJson(request));
            row.add("result", resultJson(result));
            writer.write(GSON.toJson(row));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("failed to write decision trace", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    private static JsonObject requestJson(SimulationDecisionRequest request) {
        JsonObject o = new JsonObject();
        o.addProperty("decisionId", request.getDecisionId());
        o.addProperty("sessionId", request.getSessionId());
        o.addProperty("actorPlayerId", request.getActorPlayerId());
        o.addProperty("decisionKind", request.getDecisionKind());
        o.addProperty("stateSequence", request.getStateSequence());
        o.addProperty("createdAtEpochMs", request.getCreatedAtEpochMs());
        o.add("context", request.getContextJson());
        JsonArray candidates = new JsonArray();
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", candidate.getId());
            row.addProperty("summary", candidate.getSummary());
            row.add("payload", candidate.getPayload());
            candidates.add(row);
        }
        o.add("candidates", candidates);
        return o;
    }

    private static JsonObject resultJson(SimulationDecisionResult result) {
        JsonObject o = new JsonObject();
        o.addProperty("decisionId", result.getDecisionId());
        o.addProperty("choiceId", result.getChoiceId());
        o.addProperty("rawResponse", result.getRawResponse());
        o.add("metadata", result.getMetadata());
        return o;
    }
}
