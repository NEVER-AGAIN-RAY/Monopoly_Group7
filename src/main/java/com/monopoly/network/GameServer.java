package com.monopoly.network;

import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.controller.ProtocolErrors;
import com.monopoly.model.card.Card;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.persistence.SaveEncryption;
import com.monopoly.presentation.HandCardJson;
import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.connection.SessionRegistry;
import com.monopoly.network.protocol.MessageDispatcher;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket server: routes JSON to GameController and broadcasts state snapshots to all clients.
 */
public class GameServer implements GameUpdateObserver {

    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private final ConcurrentHashMap<String, SessionRuntime> sessions = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong(1);
    private final ConcurrentHashMap<String, PendingLoadVote> pendingLoadVotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSaveVote> pendingSaveVotes = new ConcurrentHashMap<>();

    private GameController gameController;

    /**
     * Legacy test hook: binds a default controller for messages without sessionId.
     */
    public void wireController(GameController controller) {
        this.gameController = controller;
        if (controller != null) {
            sessions.put(controller.getCurrentSessionIdPublic(),
                    new SessionRuntime(controller.getCurrentSessionIdPublic(), controller, null));
        }
    }

    /** Called when a WebSocket session opens */
    public void onClientConnected(ClientConnection client) {
        clients.add(client);
    }

    public void onClientDisconnected(ClientConnection client) {
        clients.remove(client);
        sessionRegistry.unregister(client);
    }

    /** Inbound JSON dispatcher */
    public void onMessage(ClientConnection from, String json) {
        String type = dispatcher.extractMessageType(json);
        JsonObject root = dispatcher.parseObject(json);
        JsonObject payload = dispatcher.extractPayload(root);
        if ("PING".equals(type)) {
            try {
                from.sendText(dispatcher.toJsonEnvelope("PONG", new JsonObject()));
            } catch (IOException ignored) {
            }
            return;
        }
        if ("AUTH".equals(type) || "JOIN_SESSION".equals(type)) {
            String playerId = dispatcher.getString(payload, "playerId", null);
            String sessionToken = dispatcher.getString(payload, "sessionToken", null);
            if (playerId == null || playerId.isBlank()) {
                try {
                    from.sendText(dispatcher.toJsonEnvelope(
                            "AUTH_RESULT",
                            dispatcher.operationResult(false, "playerId 不能为空")));
                } catch (IOException ignored) {
                }
                return;
            }
            // sessionToken reserved for future auth; we only bind playerId today.
            if (sessionToken != null && sessionToken.isBlank()) {
                sessionToken = null;
            }
            sessionRegistry.register(from, playerId);
            String sessionId = dispatcher.getString(payload, "sessionId", null);
            if (sessionId != null && !sessionId.isBlank()) {
                sessionRegistry.bindSession(from, sessionId);
            }
            try {
                from.sendText(dispatcher.toJsonEnvelope("AUTH_RESULT", dispatcher.operationResult(true, null)));
            } catch (IOException ignored) {
            }
            return;
        }
        if ("START_SESSION".equals(type)) {
            StartSessionRequest startReq = dispatcher.parseStartSessionRequest(payload);
            String sessionId = normalizeSessionId(startReq.getSessionId());
            sessionRegistry.bindSession(from, sessionId);
            SessionRuntime runtime = getOrCreateSession(sessionId);
            runtime.controller.startNewSession(startReq);
            return;
        }
        GameController controller = resolveController(from, payload);
        if (controller == null) {
            sendError(from, "SESSION_NOT_FOUND",
                    "请先 START_SESSION，或在 payload 中提供 sessionId。", dispatcher.extractRequestId(root, payload));
            return;
        }
        if ("PAUSE".equals(type) || "PAUSE_REQUEST".equals(type)) {
            controller.requestPause();
            return;
        }
        if ("PAUSE_ACK".equals(type)) {
            String ackPlayerId = dispatcher.getString(payload, "playerId", null);
            controller.acknowledgePause(ackPlayerId);
            return;
        }
        if ("RESUME".equals(type)) {
            controller.resume();
            return;
        }
        if ("REASSIGN_WILD".equals(type)) {
            String wildId = dispatcher.getString(payload, "wildPropertyCardId", null);
            String newKey = dispatcher.getString(payload, "newColorKey", null);
            controller.handleReassignWildCommand(wildId, newKey);
            return;
        }
        if ("DRAW".equals(type)) {
            int count = dispatcher.getInt(payload, "count", 2);
            controller.handleDrawCommand(count);
            return;
        }
        if ("PLAY".equals(type)) {
            String requestId = dispatcher.extractRequestId(root, payload);
            try {
                PlayActionRequest playReq = dispatcher.parsePlayActionRequest(payload);
                controller.handlePlayActionRequest(playReq);
            } catch (ProtocolErrors.ProtocolValidationException e) {
                sendError(from, e.getCode(), e.getMessage(), requestId);
            } catch (IllegalArgumentException e) {
                sendError(from, "PLAY_BAD_REQUEST", e.getMessage(), requestId);
            } catch (IllegalStateException e) {
                sendError(from, "PLAY_STATE_VIOLATION", e.getMessage(), requestId);
            }
            return;
        }
        if ("ACTION_OPTIONS".equals(type)) {
            String requestId = dispatcher.extractRequestId(root, payload);
            try {
                String playerId = dispatcher.getString(payload, "playerId", null);
                String cardId = dispatcher.getString(payload, "cardId", null);
                ActionOptionsResult r = controller.queryActionOptionsForHandCard(playerId, cardId);
                try {
                    from.sendText(dispatcher.toJsonEnvelopeModel("ACTION_OPTIONS_RESULT", r));
                } catch (IOException ignored) {
                }
            } catch (IllegalArgumentException e) {
                sendError(from, "ACTION_OPTIONS_BAD", e.getMessage(), requestId);
            } catch (IllegalStateException e) {
                sendError(from, "ACTION_OPTIONS_STATE", e.getMessage(), requestId);
            } catch (RuntimeException e) {
                sendError(from, "ACTION_OPTIONS_FAIL", e.getMessage(), requestId);
            }
            return;
        }
        if ("PLAY_OPTIONS".equals(type)) {
            String requestId = dispatcher.extractRequestId(root, payload);
            try {
                String playerId = dispatcher.getString(payload, "playerId", null);
                String cardId = dispatcher.getString(payload, "cardId", null);
                String actionType = dispatcher.getString(payload, "actionType", null);
                ActionOptionsResult r = controller.queryPlayOptions(playerId, cardId, actionType);
                try {
                    from.sendText(dispatcher.toJsonEnvelopeModel("PLAY_OPTIONS_RESULT", r));
                } catch (IOException ignored) {
                }
            } catch (IllegalArgumentException e) {
                sendError(from, "PLAY_OPTIONS_BAD", e.getMessage(), requestId);
            } catch (IllegalStateException e) {
                sendError(from, "PLAY_OPTIONS_STATE", e.getMessage(), requestId);
            } catch (RuntimeException e) {
                sendError(from, "PLAY_OPTIONS_FAIL", e.getMessage(), requestId);
            }
            return;
        }
        if ("END_TURN".equals(type)) {
            controller.handleEndTurnCommand();
            return;
        }
        if ("QUIT".equals(type)) {
            String quitPlayerId = dispatcher.getString(payload, "playerId", null);
            controller.handleQuitCommand(quitPlayerId);
            return;
        }
        if ("RESPONSE_PASS".equals(type)) {
            String requestId = dispatcher.extractRequestId(root, payload);
            try {
                PlayActionRequest passReq = dispatcher.parsePlayActionRequest(payload);
                if (passReq.getActingPlayerId() == null || passReq.getActingPlayerId().isBlank()) {
                    passReq.setActingPlayerId(dispatcher.getString(payload, "actingPlayerId", null));
                }
                passReq.setActionType("RESPONSE_PASS");
                controller.handlePlayActionRequest(passReq);
            } catch (ProtocolErrors.ProtocolValidationException e) {
                sendError(from, e.getCode(), e.getMessage(), requestId);
            } catch (RuntimeException e) {
                sendError(from, "RESPONSE_PASS_BAD", e.getMessage(), requestId);
            }
            return;
        }
        if ("SAVE_GAME".equals(type)) {
            handleSaveGame(from, payload, controller);
            return;
        }
        if ("SAVE_GAME_ACK".equals(type)) {
            handleSaveGameAck(from, payload);
            return;
        }
        if ("SAVE_GAME_REJECT".equals(type)) {
            handleSaveGameReject(from, payload);
            return;
        }
        if ("LOAD_GAME".equals(type)) {
            handleLoadGame(from, payload, controller);
            return;
        }
        if ("LOAD_GAME_ACK".equals(type)) {
            handleLoadGameAck(from, payload);
            return;
        }
        if ("LOAD_GAME_REJECT".equals(type)) {
            handleLoadGameReject(from, payload);
            return;
        }
        if ("LOAD_VOTE".equals(type)) {
            // legacy LOAD_VOTE -> LOAD_GAME_ACK
            handleLoadGameAck(from, payload);
            return;
        }
    }

    private void handleSaveGame(ClientConnection from, JsonObject payload, GameController controller) {
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
            String sessionId = normalizeSessionId(controller.getCurrentSessionIdPublic());
            pendingSaveVotes.put(sessionId, vote);

            JsonObject req = new JsonObject();
            req.addProperty("requestId", vote.requestId);
            req.addProperty("deadlineEpochMs", vote.deadlineEpochMs);
            broadcastToSession(controller.getCurrentSessionIdPublic(),
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

    private void handleSaveGameAck(ClientConnection from, JsonObject payload) {
        try {
            PendingSaveVote vote = pendingSaveVoteFor(from, payload);
            if (vote == null) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, "当前没有待确认的保存请求")));
                return;
            }
            if (!vote.requestId.equals(dispatcher.getString(payload, "requestId", null))) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, "requestId 不匹配")));
                return;
            }
            if (System.currentTimeMillis() > vote.deadlineEpochMs) {
                cancelSaveVote(vote, "投票超时");
                return;
            }
            String playerId = sessionRegistry.getPlayerId(from)
                    .orElse(dispatcher.getString(payload, "playerId", null));
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT",
                        dispatcher.operationResult(false, "无效投票玩家")));
                return;
            }
            vote.acks.add(playerId);
            if (vote.acks.size() < vote.eligibleVoters.size()) {
                return;
            }
            pendingSaveVotes.remove(normalizeSessionId(vote.controller.getCurrentSessionIdPublic()), vote);
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

    private void handleSaveGameReject(ClientConnection from, JsonObject payload) {
        try {
            PendingSaveVote vote = pendingSaveVoteFor(from, payload);
            if (vote == null) {
                return;
            }
            if (!vote.requestId.equals(dispatcher.getString(payload, "requestId", null))) {
                return;
            }
            String playerId = sessionRegistry.getPlayerId(from)
                    .orElse(dispatcher.getString(payload, "playerId", null));
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                return;
            }
            cancelSaveVote(vote, "有玩家拒绝保存");
        } catch (Exception ignored) {
        }
    }

    private static final Path SAVE_DIR = Path.of(System.getProperty("user.home"), ".monopoly-deal", "saves");

    private void commitSaveGame(JsonObject payload, ClientConnection from, GameController controller) {
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
                broadcastToSession(controller.getCurrentSessionIdPublic(), dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT", dispatcher.saveGameResultOkWithPath(p.toString())));
            } else {
                broadcastToSession(controller.getCurrentSessionIdPublic(), dispatcher.toJsonEnvelope(
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

    private void cancelSaveVote(PendingSaveVote vote, String reason) {
        if (!pendingSaveVotes.remove(normalizeSessionId(vote.controller.getCurrentSessionIdPublic()), vote)) {
            return;
        }
        broadcastToSession(vote.controller.getCurrentSessionIdPublic(),
                dispatcher.toJsonEnvelope("SAVE_GAME_RESULT", dispatcher.operationResult(false, reason)));
    }

    private void handleLoadGame(ClientConnection from, JsonObject payload, GameController controller) {
        try {
            String raw = dispatcher.getString(payload, "mementoJson", null);
            if (raw == null || raw.isBlank()) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "mementoJson 不能为空")));
                return;
            }
            Set<String> eligibleVoters = resolveEligibleLoadVoters(controller);
            if (eligibleVoters.isEmpty()) {
                // no AUTH: load immediately (tests/scripts)
                controller.importSessionJson(raw);
                registerControllerSession(controller);
                from.sendText(dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(true, null)));
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId == null || requestId.isBlank()) {
                requestId = "load-" + requestCounter.getAndIncrement();
            }
            PendingLoadVote vote = new PendingLoadVote(requestId, raw, eligibleVoters, controller);
            String sessionId = normalizeSessionId(controller.getCurrentSessionIdPublic());
            pendingLoadVotes.put(sessionId, vote);

            JsonObject request = new JsonObject();
            request.addProperty("requestId", vote.requestId);
            broadcastToSession(controller.getCurrentSessionIdPublic(),
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

    private void handleLoadGameAck(ClientConnection from, JsonObject payload) {
        try {
            PendingLoadVote vote = pendingLoadVoteFor(from, payload);
            if (vote == null) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "当前没有待确认的加载请求")));
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId != null && !requestId.isBlank() && !vote.requestId.equals(requestId)) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "requestId 不匹配")));
                return;
            }
            String playerId = sessionRegistry.getPlayerId(from)
                    .orElse(dispatcher.getString(payload, "playerId", null));
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "无效投票玩家")));
                return;
            }
            vote.acks.add(playerId);
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

    private void handleLoadGameReject(ClientConnection from, JsonObject payload) {
        try {
            PendingLoadVote vote = pendingLoadVoteFor(from, payload);
            if (vote == null) {
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId != null && !requestId.isBlank() && !vote.requestId.equals(requestId)) {
                return;
            }
            String playerId = sessionRegistry.getPlayerId(from)
                    .orElse(dispatcher.getString(payload, "playerId", null));
            if (playerId == null || playerId.isBlank() || !vote.eligibleVoters.contains(playerId)) {
                return;
            }
            cancelLoadVote(vote, "有玩家拒绝加载");
        } catch (Exception ignored) {
        }
    }

    private void commitLoadGame(PendingLoadVote vote) {
        String previousSessionId = normalizeSessionId(vote.controller.getCurrentSessionIdPublic());
        if (!pendingLoadVotes.remove(previousSessionId, vote)) {
            return;
        }
        try {
            vote.controller.importSessionJson(vote.mementoJson);
            String loadedSessionId = normalizeSessionId(vote.controller.getCurrentSessionIdPublic());
            sessionRegistry.rebindSession(previousSessionId, loadedSessionId);
            sessions.remove(previousSessionId);
            registerControllerSession(vote.controller);
            broadcastToSession(loadedSessionId,
                    dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(true, null)));
            if (!previousSessionId.equals(loadedSessionId)) {
                vote.controller.pushCurrentState("INIT", "Session loaded from save.");
            }
        } catch (Exception ex) {
            broadcastToSession(previousSessionId, dispatcher.toJsonEnvelope(
                    "LOAD_GAME_RESULT",
                    dispatcher.operationResult(false, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())));
        }
    }

    private void cancelLoadVote(PendingLoadVote vote, String reason) {
        if (!pendingLoadVotes.remove(normalizeSessionId(vote.controller.getCurrentSessionIdPublic()), vote)) {
            return;
        }
        broadcastToSession(vote.controller.getCurrentSessionIdPublic(),
                dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(false, reason)));
    }

    private PendingSaveVote pendingSaveVoteFor(ClientConnection from, JsonObject payload) {
        String sessionId = resolveSessionId(from, payload);
        if (sessionId == null) {
            return null;
        }
        return pendingSaveVotes.get(normalizeSessionId(sessionId));
    }

    private PendingLoadVote pendingLoadVoteFor(ClientConnection from, JsonObject payload) {
        String sessionId = resolveSessionId(from, payload);
        if (sessionId == null) {
            return null;
        }
        return pendingLoadVotes.get(normalizeSessionId(sessionId));
    }

    @Override
    public void onGameStateChanged(GameStateSnapshot snapshot) {
        String payload = dispatcher.toJsonBroadcast(snapshot);
        sendToSessionTargets(snapshot.getSessionId(), payload);
        pushPrivateHands(snapshot.getSessionId());
    }

    /** Registers as GameUpdateObserver */
    public void attachTo(GameUpdateSubject subject) {
        subject.registerObserver(this);
    }

    public Optional<String> getPlayerIdOf(ClientConnection client) {
        return sessionRegistry.getPlayerId(client);
    }

    public Set<ClientConnection> connectionsOf(String playerId) {
        return sessionRegistry.connectionsOf(playerId);
    }

    private void pushPrivateHands(String sessionId) {
        GameController controller = controllerForSession(sessionId);
        if (controller == null) {
            return;
        }
        for (Player player : controller.getSessionPlayersView()) {
            if (!(player instanceof HumanPlayer)) {
                continue;
            }
            Set<ClientConnection> targets = connectionsOfPlayerInSession(player.getPlayerId(), sessionId);
            if (targets.isEmpty()) {
                continue;
            }
            String msg = buildMyHandMessage(player);
            for (ClientConnection conn : targets) {
                if (!conn.isOpen()) {
                    continue;
                }
                try {
                    conn.sendText(msg);
                } catch (IOException e) {
                    sessionRegistry.unregister(conn);
                    clients.remove(conn);
                }
            }
        }
    }

    private String buildMyHandMessage(Player player) {
        JsonObject payload = new JsonObject();
        payload.addProperty("playerId", player.getPlayerId());
        com.google.gson.JsonArray cards = new com.google.gson.JsonArray();
        for (Card card : player.getHandCardsView()) {
            cards.add(HandCardJson.toHandCardObject(card));
        }
        payload.add("cards", cards);
        return dispatcher.toJsonEnvelope("MY_HAND", payload);
    }

    private Set<String> resolveEligibleLoadVoters(GameController controller) {
        if (controller == null) {
            return Set.of();
        }
        Set<String> eligible = ConcurrentHashMap.newKeySet();
        String sessionId = controller.getCurrentSessionIdPublic();
        for (Player player : controller.getSessionPlayersView()) {
            if (!(player instanceof HumanPlayer)) {
                continue;
            }
            if (!connectionsOfPlayerInSession(player.getPlayerId(), sessionId).isEmpty()) {
                eligible.add(player.getPlayerId());
            }
        }
        return eligible;
    }

    private long parseLong(JsonObject obj, String key, long defaultValue) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private SessionRuntime getOrCreateSession(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        return sessions.computeIfAbsent(normalized, sid -> {
            DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
            GameController controller = new GameController(subject);
            subject.registerObserver(this);
            return new SessionRuntime(sid, controller, subject);
        });
    }

    private void registerControllerSession(GameController controller) {
        if (controller == null) {
            return;
        }
        String sessionId = normalizeSessionId(controller.getCurrentSessionIdPublic());
        sessions.put(sessionId, new SessionRuntime(sessionId, controller, null));
    }

    private GameController resolveController(ClientConnection from, JsonObject payload) {
        String sessionId = dispatcher.getString(payload, "sessionId", null);
        boolean explicitSession = sessionId != null && !sessionId.isBlank();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionRegistry.getSessionId(from).orElse(null);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sessionRegistry.bindSession(from, sessionId);
            SessionRuntime runtime = sessions.get(normalizeSessionId(sessionId));
            if (runtime != null) {
                return runtime.controller;
            }
            if (gameController != null && normalizeSessionId(gameController.getCurrentSessionIdPublic())
                    .equals(normalizeSessionId(sessionId))) {
                return gameController;
            }
            if (explicitSession) {
                return null;
            }
        }
        return gameController;
    }

    private String resolveSessionId(ClientConnection from, JsonObject payload) {
        String sessionId = dispatcher.getString(payload, "sessionId", null);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionRegistry.getSessionId(from).orElse(null);
        }
        if ((sessionId == null || sessionId.isBlank()) && gameController != null) {
            sessionId = gameController.getCurrentSessionIdPublic();
        }
        return sessionId;
    }

    private GameController controllerForSession(String sessionId) {
        SessionRuntime runtime = sessions.get(normalizeSessionId(sessionId));
        if (runtime != null) {
            return runtime.controller;
        }
        if (gameController != null && normalizeSessionId(gameController.getCurrentSessionIdPublic())
                .equals(normalizeSessionId(sessionId))) {
            return gameController;
        }
        return null;
    }

    private Set<ClientConnection> connectionsOfPlayerInSession(String playerId, String sessionId) {
        Set<ClientConnection> byPlayer = sessionRegistry.connectionsOf(playerId);
        if (byPlayer.isEmpty()) {
            return Set.of();
        }
        Set<ClientConnection> bySession = sessionRegistry.connectionsInSession(sessionId);
        if (bySession.isEmpty()) {
            return shouldFallbackBroadcast(sessionId) ? byPlayer : Set.of();
        }
        Set<ClientConnection> out = ConcurrentHashMap.newKeySet();
        for (ClientConnection conn : byPlayer) {
            if (bySession.contains(conn)) {
                out.add(conn);
            }
        }
        return out;
    }

    private static String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "session-default" : sessionId.trim();
    }

    private void broadcastToSession(String sessionId, String message) {
        sendToSessionTargets(sessionId, message);
    }

    private void sendToSessionTargets(String sessionId, String message) {
        Set<ClientConnection> targets = sessionRegistry.connectionsInSession(sessionId);
        if (targets.isEmpty() && shouldFallbackBroadcast(sessionId)) {
            targets = clients;
        }
        for (ClientConnection client : targets) {
            if (!client.isOpen()) {
                continue;
            }
            try {
                client.sendText(message);
            } catch (IOException e) {
                clients.remove(client);
                sessionRegistry.unregister(client);
            }
        }
    }

    private boolean shouldFallbackBroadcast(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        if (gameController == null
                || !normalizeSessionId(gameController.getCurrentSessionIdPublic()).equals(normalized)) {
            return false;
        }
        for (SessionRuntime runtime : sessions.values()) {
            if (runtime.controller != gameController) {
                return false;
            }
        }
        return true;
    }

    private void broadcast(String message) {
        for (ClientConnection client : clients) {
            if (!client.isOpen()) {
                continue;
            }
            try {
                client.sendText(message);
            } catch (IOException e) {
                clients.remove(client);
                sessionRegistry.unregister(client);
            }
        }
    }

    private void sendError(ClientConnection client, String code, String message, String requestId) {
        try {
            client.sendText(dispatcher.toErrorEnvelope(code, message, requestId));
        } catch (IOException ignored) {
        }
    }

    private static final class PendingSaveVote {
        private final String requestId;
        private final long deadlineEpochMs;
        private final Set<String> eligibleVoters;
        private final Set<String> acks = ConcurrentHashMap.newKeySet();
        private final JsonObject originalPayload;
        private final ClientConnection requester;
        private final GameController controller;

        private PendingSaveVote(
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

    private static final class PendingLoadVote {
        private final String requestId;
        private final String mementoJson;
        private final Set<String> eligibleVoters;
        private final Set<String> acks = ConcurrentHashMap.newKeySet();
        private final GameController controller;

        private PendingLoadVote(
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

    private record SessionRuntime(
            String sessionId,
            GameController controller,
            GameUpdateSubject subject) {
    }
}
