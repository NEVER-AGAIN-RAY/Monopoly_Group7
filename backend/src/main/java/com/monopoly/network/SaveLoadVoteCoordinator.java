package com.monopoly.network;

import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.protocol.MessageDispatcher;
import com.monopoly.persistence.SaveEncryption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class SaveLoadVoteCoordinator {

    private static final Path SAVE_DIR = Path.of(System.getProperty("user.home"), ".monopoly-deal", "saves");
    private static final long LOAD_VOTE_TIMEOUT_MS = 60_000L;

    private final SessionHub hub;
    private final MessageDispatcher dispatcher;
    private final AtomicLong requestCounter;
    private final ConcurrentHashMap<String, PendingLoadVote> pendingLoadVotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSaveVote> pendingSaveVotes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vote-timeout");
        t.setDaemon(true);
        return t;
    });

    SaveLoadVoteCoordinator(SessionHub hub, MessageDispatcher dispatcher, AtomicLong requestCounter) {
        this.hub = hub;
        this.dispatcher = dispatcher;
        this.requestCounter = requestCounter;
    }

    void handleSaveGame(ClientConnection from, JsonObject payload, GameController controller) {
        try {
            Set<String> voters = resolveEligibleLoadVoters(controller);
            if (voters.isEmpty()) {
                commitSaveGame(payload, from, controller);
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId == null || requestId.isBlank()) {
                requestId = "save-" + requestCounter.getAndIncrement();
            }
            long providedDeadline = parseLong(payload, "deadlineEpochMs", 0L);
            long deadlineEpochMs = providedDeadline > 0L ? providedDeadline : System.currentTimeMillis() + 30_000L;
            PendingSaveVote vote = new PendingSaveVote(
                    requestId, deadlineEpochMs, voters, payload, from, controller);
            String sessionId = SessionHub.normalizeSessionId(controller.getCurrentSessionIdPublic());
            pendingSaveVotes.put(sessionId, vote);
            long delayMs = Math.max(1000L, deadlineEpochMs - System.currentTimeMillis() + 1000L);
            scheduler.schedule(() -> {
                PendingSaveVote v = pendingSaveVotes.get(sessionId);
                if (v != null && v.requestId.equals(vote.requestId)
                        && System.currentTimeMillis() > v.deadlineEpochMs) {
                    cancelSaveVote(v, "Save vote timed out");
                }
            }, delayMs, TimeUnit.MILLISECONDS);

            JsonObject req = new JsonObject();
            req.addProperty("requestId", vote.requestId);
            req.addProperty("deadlineEpochMs", vote.deadlineEpochMs);
            hub.broadcastToSession(controller.getCurrentSessionIdPublic(),
                    dispatcher.toJsonEnvelope("SAVE_GAME_REQUEST", req));
        } catch (Exception e) {
            try {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            } catch (IOException ignored) {
            }
        }
    }

    void handleSaveGameAck(ClientConnection from, JsonObject payload) {
        try {
            PendingSaveVote vote = pendingSaveVoteFor(from, payload);
            if (vote == null) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, "No pending save request")));
                return;
            }
            if (!vote.requestId.equals(dispatcher.getString(payload, "requestId", null))) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, "requestId mismatch")));
                return;
            }
            if (System.currentTimeMillis() > vote.deadlineEpochMs) {
                cancelSaveVote(vote, "Save vote timed out");
                return;
            }
            String playerId = hub.sessionRegistry().getPlayerId(from).orElse(null);
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, "Invalid voting player")));
                return;
            }
            if (!vote.acks.add(playerId)) {
                return;
            }
            if (vote.acks.size() < vote.eligibleVoters.size()) {
                return;
            }
            if (!pendingSaveVotes.remove(SessionHub.normalizeSessionId(vote.controller.getCurrentSessionIdPublic()), vote)) {
                return;
            }
            commitSaveGame(vote.originalPayload, vote.requester, vote.controller);
        } catch (Exception e) {
            try {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            } catch (IOException ignored) {
            }
        }
    }

    void handleSaveGameReject(ClientConnection from, JsonObject payload) {
        try {
            PendingSaveVote vote = pendingSaveVoteFor(from, payload);
            if (vote == null) {
                return;
            }
            if (!vote.requestId.equals(dispatcher.getString(payload, "requestId", null))) {
                return;
            }
            String playerId = hub.sessionRegistry().getPlayerId(from).orElse(null);
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                return;
            }
            cancelSaveVote(vote, "A player rejected the save");
        } catch (Exception ignored) {
        }
    }

    void commitSaveGame(JsonObject payload, ClientConnection from, GameController controller) {
        try {
            String mementoJson = controller.exportSessionJson();
            String path = dispatcher.getString(payload, "path", null);
            if (path != null && !path.isBlank()) {
                Path requested = Path.of(path.trim()).normalize();
                Path filenamePath = requested.getFileName();
                String filename = filenamePath == null ? "" : filenamePath.toString();
                if (filename.isBlank() || filename.contains("..") || filename.startsWith(".")) {
                    throw new SecurityException("Invalid save filename: " + filename);
                }
                Path p = requested.isAbsolute()
                        ? requested
                        : SAVE_DIR.resolve(requested).normalize();
                if (!requested.isAbsolute() && !p.startsWith(SAVE_DIR)) {
                    throw new SecurityException("Path traversal blocked");
                }
                Path parent = p.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String out = SaveEncryption.encodeForStorage(mementoJson);
                Files.writeString(p, out, StandardCharsets.UTF_8);
                hub.broadcastToSession(controller.getCurrentSessionIdPublic(), dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT", dispatcher.saveGameResultOkWithPath(p.toString())));
            } else {
                hub.broadcastToSession(controller.getCurrentSessionIdPublic(), dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT", dispatcher.saveGameResultOkWithJson(mementoJson)));
            }
        } catch (Exception e) {
            try {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            } catch (IOException ignored) {
            }
        }
    }

    void cancelSaveVote(PendingSaveVote vote, String reason) {
        if (!pendingSaveVotes.remove(SessionHub.normalizeSessionId(vote.controller.getCurrentSessionIdPublic()), vote)) {
            return;
        }
        hub.broadcastToSession(vote.controller.getCurrentSessionIdPublic(),
                dispatcher.toJsonEnvelope("SAVE_GAME_RESULT", dispatcher.operationResult(false, reason)));
    }

    PendingSaveVote pendingSaveVoteFor(ClientConnection from, JsonObject payload) {
        String sessionId = hub.resolveSessionId(from, payload);
        if (sessionId == null) {
            return null;
        }
        return pendingSaveVotes.get(SessionHub.normalizeSessionId(sessionId));
    }

    void handleLoadGame(ClientConnection from, JsonObject payload, GameController controller) {
        try {
            String raw = dispatcher.getString(payload, "mementoJson", null);
            if (raw == null || raw.isBlank()) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "mementoJson must not be empty")));
                return;
            }
            Set<String> eligibleVoters = resolveEligibleLoadVoters(controller);
            if (eligibleVoters.isEmpty()) {
                controller.importSessionJson(raw);
                hub.registerControllerSession(controller);
                from.sendText(dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(true, null)));
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId == null || requestId.isBlank()) {
                requestId = "load-" + requestCounter.getAndIncrement();
            }
            PendingLoadVote vote = new PendingLoadVote(requestId, raw, eligibleVoters, controller);
            String sessionId = SessionHub.normalizeSessionId(controller.getCurrentSessionIdPublic());
            pendingLoadVotes.put(sessionId, vote);
            long deadlineMs = System.currentTimeMillis() + LOAD_VOTE_TIMEOUT_MS;
            scheduler.schedule(() -> {
                PendingLoadVote v = pendingLoadVotes.get(sessionId);
                if (v != null && v.requestId.equals(vote.requestId)) {
                    cancelLoadVote(v, "Load vote timed out");
                }
            }, LOAD_VOTE_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS);

            JsonObject request = new JsonObject();
            request.addProperty("requestId", vote.requestId);
            hub.broadcastToSession(controller.getCurrentSessionIdPublic(),
                    dispatcher.toJsonEnvelope("LOAD_GAME_REQUEST", request));
        } catch (Exception e) {
            try {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            } catch (IOException ignored) {
            }
        }
    }

    void handleLoadGameAck(ClientConnection from, JsonObject payload) {
        try {
            PendingLoadVote vote = pendingLoadVoteFor(from, payload);
            if (vote == null) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "No pending load request")));
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId != null && !requestId.isBlank() && !vote.requestId.equals(requestId)) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "requestId mismatch")));
                return;
            }
            String playerId = hub.sessionRegistry().getPlayerId(from).orElse(null);
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "Invalid voting player")));
                return;
            }
            if (!vote.acks.add(playerId)) {
                return;
            }
            if (vote.acks.size() < vote.eligibleVoters.size()) {
                return;
            }
            commitLoadGame(vote);
        } catch (Exception e) {
            try {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            } catch (IOException ignored) {
            }
        }
    }

    void handleLoadGameReject(ClientConnection from, JsonObject payload) {
        try {
            PendingLoadVote vote = pendingLoadVoteFor(from, payload);
            if (vote == null) {
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId != null && !requestId.isBlank() && !vote.requestId.equals(requestId)) {
                return;
            }
            String playerId = hub.sessionRegistry().getPlayerId(from).orElse(null);
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                return;
            }
            cancelLoadVote(vote, "A player rejected the load");
        } catch (Exception ignored) {
        }
    }

    void commitLoadGame(PendingLoadVote vote) {
        String previousSessionId = SessionHub.normalizeSessionId(vote.controller.getCurrentSessionIdPublic());
        if (!pendingLoadVotes.remove(previousSessionId, vote)) {
            return;
        }
        try {
            vote.controller.importSessionJson(vote.mementoJson);
            String loadedSessionId = SessionHub.normalizeSessionId(vote.controller.getCurrentSessionIdPublic());
            hub.rebindSessionAfterLoad(previousSessionId, loadedSessionId, vote.controller);
            hub.broadcastToSession(loadedSessionId,
                    dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(true, null)));
            if (!previousSessionId.equals(loadedSessionId)) {
                vote.controller.pushCurrentState("INIT", "Session loaded from save.");
            }
        } catch (Exception ex) {
            hub.broadcastToSession(previousSessionId, dispatcher.toJsonEnvelope(
                    "LOAD_GAME_RESULT",
                    dispatcher.operationResult(false, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())));
        }
    }

    void cancelLoadVote(PendingLoadVote vote, String reason) {
        if (!pendingLoadVotes.remove(SessionHub.normalizeSessionId(vote.controller.getCurrentSessionIdPublic()), vote)) {
            return;
        }
        hub.broadcastToSession(vote.controller.getCurrentSessionIdPublic(),
                dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(false, reason)));
    }

    PendingLoadVote pendingLoadVoteFor(ClientConnection from, JsonObject payload) {
        String sessionId = hub.resolveSessionId(from, payload);
        if (sessionId == null) {
            return null;
        }
        return pendingLoadVotes.get(SessionHub.normalizeSessionId(sessionId));
    }

    Set<String> resolveEligibleLoadVoters(GameController controller) {
        if (controller == null) {
            return Set.of();
        }
        Set<String> eligible = ConcurrentHashMap.newKeySet();
        String sessionId = controller.getCurrentSessionIdPublic();
        for (Player player : controller.getSessionPlayersView()) {
            if (!(player instanceof HumanPlayer)) {
                continue;
            }
            if (!hub.connectionsOfPlayerInSession(player.getPlayerId(), sessionId).isEmpty()) {
                eligible.add(player.getPlayerId());
            }
        }
        return eligible;
    }

    long parseLong(JsonObject obj, String key, long defaultValue) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    static final class PendingSaveVote {
        final String requestId;
        final long deadlineEpochMs;
        final Set<String> eligibleVoters;
        final Set<String> acks = ConcurrentHashMap.newKeySet();
        final JsonObject originalPayload;
        final ClientConnection requester;
        final GameController controller;

        PendingSaveVote(
                String requestId,
                long deadlineEpochMs,
                Set<String> eligibleVoters,
                JsonObject originalPayload,
                ClientConnection requester,
                GameController controller
        ) {
            this.requestId = requestId;
            this.deadlineEpochMs = deadlineEpochMs;
            this.eligibleVoters = ConcurrentHashMap.newKeySet();
            this.eligibleVoters.addAll(eligibleVoters);
            this.originalPayload = originalPayload;
            this.requester = requester;
            this.controller = controller;
        }
    }

    static final class PendingLoadVote {
        final String requestId;
        final String mementoJson;
        final Set<String> eligibleVoters;
        final Set<String> acks = ConcurrentHashMap.newKeySet();
        final GameController controller;

        PendingLoadVote(
                String requestId,
                String mementoJson,
                Set<String> eligibleVoters,
                GameController controller) {
            this.requestId = requestId;
            this.mementoJson = mementoJson;
            this.eligibleVoters = ConcurrentHashMap.newKeySet();
            this.eligibleVoters.addAll(eligibleVoters);
            this.controller = controller;
        }
    }
}
