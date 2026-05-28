package com.monopoly.simulation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PropertyColorProgress;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
        JsonObject row = rowJson(request, result);
        writeRow(row);
    }

    public synchronized void writeRow(JsonObject row) {
        try {
            writeRowChecked(row);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write decision trace", e);
        }
    }

    private void writeRowChecked(JsonObject row) throws IOException {
        writer.write(GSON.toJson(row == null ? new JsonObject() : row));
        writer.newLine();
        writer.flush();
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

    public static JsonObject rowJson(
            SimulationDecisionRequest request,
            SimulationDecisionResult result) {
        JsonObject row = new JsonObject();
        row.addProperty("schema", "monopoly-deal-decision-v1");
        row.addProperty("recordedAtEpochMs", System.currentTimeMillis());
        row.add("request", requestJson(request));
        row.add("result", resultJson(result));
        return row;
    }

    protected static void addOutcome(
            JsonObject row,
            GameStateSnapshot finalSnapshot,
            String actorPlayerId) {
        if (row == null) {
            return;
        }
        row.add("outcome", outcomeJson(finalSnapshot, actorPlayerId));
    }

    public static void copyOutcome(JsonObject sourceRow, JsonObject targetRow) {
        if (sourceRow == null || targetRow == null
                || !sourceRow.has("outcome") || sourceRow.get("outcome").isJsonNull()) {
            return;
        }
        targetRow.add("outcome", sourceRow.get("outcome").deepCopy());
    }

    protected static JsonObject outcomeJson(
            GameStateSnapshot finalSnapshot,
            String actorPlayerId) {
        JsonObject outcome = new JsonObject();
        outcome.addProperty("schema", "monopoly-deal-outcome-v1");
        outcome.addProperty("actorPlayerId", actorPlayerId);
        if (finalSnapshot == null) {
            outcome.addProperty("gameOver", false);
            outcome.addProperty("naturalWin", false);
            outcome.addProperty("forceEndReason", "NO_FINAL_SNAPSHOT");
            outcome.addProperty("reward", 0.0d);
            return outcome;
        }
        String winnerPlayerId = winnerPlayerId(finalSnapshot);
        PlayerOutcome actor = actorOutcome(finalSnapshot, actorPlayerId, winnerPlayerId);
        outcome.addProperty("gameOver", finalSnapshot.isGameOver());
        outcome.addProperty("phase", finalSnapshot.getPhase());
        outcome.addProperty("forceEndReason", finalSnapshot.getForceEndReason());
        outcome.addProperty("winnerPlayerId", winnerPlayerId);
        outcome.addProperty("naturalWin", actor != null && actor.naturalWinner());
        outcome.addProperty("boardRank", actor == null ? 0 : actor.boardRank());
        outcome.addProperty("boardScore", actor == null ? 0 : actor.boardScore());
        outcome.addProperty("completeSets", actor == null ? 0 : actor.completeSets());
        outcome.addProperty("reward", actor == null ? 0.0d : actor.reward());
        return outcome;
    }

    public static JsonObject outcomeJsonForTool(
            GameStateSnapshot finalSnapshot,
            String actorPlayerId) {
        return outcomeJson(finalSnapshot, actorPlayerId);
    }

    private static String winnerPlayerId(GameStateSnapshot snapshot) {
        if (snapshot == null || snapshot.getForceEndReason() != null) {
            return null;
        }
        String winner = null;
        int winners = 0;
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            if (player.getCompletePropertySets() >= 3) {
                winner = player.getPlayerId();
                winners++;
            }
        }
        if (winners == 1) {
            return winner;
        }
        return winnerFromLastAction(snapshot);
    }

    private static String winnerFromLastAction(GameStateSnapshot snapshot) {
        String summary = snapshot == null || snapshot.getLastActionSummary() == null
                ? ""
                : snapshot.getLastActionSummary().trim().toLowerCase(Locale.ROOT);
        if (summary.isBlank()) {
            return null;
        }
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            String displayName = player.getDisplayName() == null
                    ? ""
                    : player.getDisplayName().trim().toLowerCase(Locale.ROOT);
            String playerId = player.getPlayerId() == null
                    ? ""
                    : player.getPlayerId().trim().toLowerCase(Locale.ROOT);
            if (!displayName.isBlank() && summary.startsWith(displayName + " wins")) {
                return player.getPlayerId();
            }
            if (!playerId.isBlank() && summary.startsWith(playerId + " wins")) {
                return player.getPlayerId();
            }
        }
        return null;
    }

    private static PlayerOutcome actorOutcome(
            GameStateSnapshot snapshot,
            String actorPlayerId,
            String winnerPlayerId) {
        if (snapshot == null || actorPlayerId == null || actorPlayerId.isBlank()) {
            return null;
        }
        List<PlayerDraft> drafts = new ArrayList<>();
        for (GameStateSnapshot.PlayerPublicSummary player : snapshot.getPlayers()) {
            boolean naturalWinner = player.getPlayerId().equals(winnerPlayerId)
                    && snapshot.getForceEndReason() == null;
            drafts.add(new PlayerDraft(
                    player.getPlayerId(),
                    boardScore(player),
                    player.getCompletePropertySets(),
                    naturalWinner));
        }
        drafts.sort(Comparator
                .comparingInt(PlayerDraft::boardScore).reversed()
                .thenComparing(PlayerDraft::playerId));
        int rank = 1;
        int seen = 0;
        int previousScore = Integer.MIN_VALUE;
        int playerCount = Math.max(1, drafts.size());
        for (PlayerDraft draft : drafts) {
            seen++;
            if (draft.boardScore() != previousScore) {
                rank = seen;
                previousScore = draft.boardScore();
            }
            if (draft.playerId().equals(actorPlayerId)) {
                double rankBonus = playerCount == 1
                        ? 0.0d
                        : (playerCount - rank) / (double) (playerCount - 1);
                double setProgress = Math.min(3, draft.completeSets()) / 3.0d;
                double reward = draft.naturalWinner()
                        ? 1.0d
                        : Math.max(-1.0d, Math.min(0.85d, -0.35d + rankBonus * 0.55d + setProgress * 0.35d));
                return new PlayerOutcome(
                        draft.playerId(),
                        draft.boardScore(),
                        rank,
                        draft.completeSets(),
                        draft.naturalWinner(),
                        reward);
            }
        }
        return null;
    }

    private static int boardScore(GameStateSnapshot.PlayerPublicSummary player) {
        int propertyProgress = 0;
        for (PropertyColorProgress progress : player.getPropertyColorProgress()) {
            propertyProgress += Math.min(progress.getEffectiveCount(), progress.getNeed());
        }
        return player.getCompletePropertySets() * 1000
                + propertyProgress * 80
                + player.getPropertyCount() * 20
                + player.getBankTotalValueM() * 10
                + player.getHandCount();
    }

    private record PlayerDraft(
            String playerId,
            int boardScore,
            int completeSets,
            boolean naturalWinner) {
    }

    private record PlayerOutcome(
            String playerId,
            int boardScore,
            int boardRank,
            int completeSets,
            boolean naturalWinner,
            double reward) {
    }
}
