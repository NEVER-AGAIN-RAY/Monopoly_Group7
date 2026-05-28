package com.monopoly.simulation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.monopoly.dto.GameStateSnapshot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Buffers decision rows and flushes them after the game outcome is known.
 */
public final class OutcomeDecisionTraceSink implements DecisionTraceSink {

    private static final Gson GSON = new Gson();

    private final BufferedWriter writer;
    private final Map<String, List<PendingDecision>> pendingBySession = new HashMap<>();

    public OutcomeDecisionTraceSink(Path path) throws IOException {
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
        String sessionId = request.getSessionId();
        pendingBySession
                .computeIfAbsent(sessionId, ignored -> new ArrayList<>())
                .add(new PendingDecision(request, result));
    }

    @Override
    public synchronized void recordGameResult(String sessionId, GameStateSnapshot finalSnapshot) {
        List<PendingDecision> pending = pendingBySession.remove(sessionId == null ? "" : sessionId.trim());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        try {
            for (PendingDecision decision : pending) {
                JsonObject row = JsonlDecisionTraceSink.rowJson(decision.request(), decision.result());
                JsonlDecisionTraceSink.addOutcome(
                        row,
                        finalSnapshot,
                        decision.request().getActorPlayerId());
                writer.write(GSON.toJson(row));
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("failed to write outcome decision trace", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        for (Map.Entry<String, List<PendingDecision>> entry : new ArrayList<>(pendingBySession.entrySet())) {
            recordGameResult(entry.getKey(), null);
        }
        writer.close();
    }

    private record PendingDecision(
            SimulationDecisionRequest request,
            SimulationDecisionResult result) {
    }
}
