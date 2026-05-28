package com.monopoly.network;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private final ConcurrentHashMap<String, LobbyRoom> lobbyRooms = new ConcurrentHashMap<>();

    private GameController gameController;

    /**
     * Legacy test hook: binds a default controller for messages without sessionId.
     */
    public void wireController(GameController controller) {
        this.gameController = controller;
        if (controller != null) {
            String sessionId = normalizeSessionId(controller.getCurrentSessionIdPublic());
            if (!"unknown".equals(sessionId)) {
                sessions.put(sessionId, new SessionRuntime(sessionId, controller, null));
            }
        }
    }

    /** Called when a WebSocket session opens */
    public void onClientConnected(ClientConnection client) {
        clients.add(client);
    }

    public void onClientDisconnected(ClientConnection client) {
        clients.remove(client);
        for (LobbyRoom room : lobbyRooms.values()) {
            removeMemberFromRoom(room, playerKey(client));
        }
        sessionRegistry.unregister(client);
        broadcastRoomList();
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
        if ("ROOM_LIST".equals(type) || "LIST_ROOMS".equals(type)) {
            sendRoomList(from);
            return;
        }
        if ("CREATE_ROOM".equals(type)) {
            handleCreateRoom(from, payload);
            return;
        }
        if ("JOIN_ROOM".equals(type)) {
            handleJoinRoom(from, payload);
            return;
        }
        if ("ROOM_SET_SEAT".equals(type)) {
            handleSetRoomSeat(from, payload);
            return;
        }
        if ("LEAVE_ROOM".equals(type)) {
            handleLeaveRoom(from, payload);
            return;
        }
        if ("START_ROOM".equals(type)) {
            handleStartRoom(from, payload);
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
                sendRoomList(from);
            } catch (IOException ignored) {
            }
            GameController controller = sessionId == null || sessionId.isBlank() ? null : controllerForSession(sessionId);
            if (controller != null && !controller.getSessionPlayersView().isEmpty()) {
                controller.pushCurrentState("JOIN", "玩家已连接房间。");
            }
            return;
        }
        if ("START_SESSION".equals(type)) {
            StartSessionRequest startReq = dispatcher.parseStartSessionRequest(payload);
            String sessionId = normalizeSessionId(startReq.getSessionId());
            sessionRegistry.bindSession(from, sessionId);
            SessionRuntime runtime = getOrCreateSession(sessionId);
            runtime.controller.startNewSession(startReq);
            broadcastRoomList();
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

    private void handleCreateRoom(ClientConnection from, JsonObject payload) {
        String sessionId = normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        String nickname = normalizeNickname(dispatcher.getString(payload, "nickname", null));
        if (nickname.isBlank()) {
            sendRoomError(from, "昵称不能为空");
            return;
        }
        LobbyRoom room = new LobbyRoom(sessionId);
        synchronized (room) {
            room.hostPlayerKey = playerKey(from);
            room.addMember(playerKey(from), nickname);
            room.seats.get(0).humanPlayerKey = playerKey(from);
            room.seats.get(0).role = "human";
            room.seats.get(1).role = "strong";
        }
        LobbyRoom existing = lobbyRooms.putIfAbsent(sessionId, room);
        if (existing != null) {
            sendRoomError(from, "房间号已存在");
            return;
        }
        sessionRegistry.register(from, playerKey(from));
        sessionRegistry.bindSession(from, sessionId);
        sendRoomStateToSession(sessionId);
        broadcastRoomList();
    }

    private void handleJoinRoom(ClientConnection from, JsonObject payload) {
        String sessionId = normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        String nickname = normalizeNickname(dispatcher.getString(payload, "nickname", null));
        if (nickname.isBlank()) {
            sendRoomError(from, "昵称不能为空");
            return;
        }
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            sendRoomError(from, "房间不存在");
            return;
        }
        synchronized (room) {
            if (room.started) {
                sendRoomError(from, "房间已开局");
                return;
            }
            if (room.nicknameExists(nickname, playerKey(from))) {
                sendRoomError(from, "昵称已被使用");
                return;
            }
            room.addMember(playerKey(from), nickname);
        }
        sessionRegistry.register(from, playerKey(from));
        sessionRegistry.bindSession(from, sessionId);
        sendRoomStateToSession(sessionId);
        broadcastRoomList();
    }

    private void handleSetRoomSeat(ClientConnection from, JsonObject payload) {
        String sessionId = normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            sendRoomError(from, "房间不存在");
            return;
        }
        synchronized (room) {
            if (!isRoomHost(room, from)) {
                sendRoomError(from, "只有房主可以调整席位");
                return;
            }
            if (room.started) {
                sendRoomError(from, "房间已开局，不能调整席位");
                return;
            }
            int seatIndex = dispatcher.getInt(payload, "seatIndex", -1);
            if (seatIndex < 0 || seatIndex >= room.seats.size()) {
                sendRoomError(from, "席位编号无效");
                return;
            }
            LobbySeat seat = room.seats.get(seatIndex);
            String role = normalizeLobbyRole(dispatcher.getString(payload, "role", "empty"));
            String nickname = normalizeNickname(dispatcher.getString(payload, "nickname", null));
            if ("human".equals(role)) {
                if (nickname.isBlank()) {
                    sendRoomError(from, "真人席位必须选择已加入玩家昵称");
                    return;
                }
                String memberKey = room.memberKeyByNickname(nickname);
                if (memberKey == null) {
                    sendRoomError(from, "该昵称还没有加入房间");
                    return;
                }
                for (int i = 0; i < room.seats.size(); i++) {
                    LobbySeat other = room.seats.get(i);
                    if (i != seatIndex && memberKey.equals(other.humanPlayerKey)) {
                        other.role = "empty";
                        other.humanPlayerKey = null;
                        other.botName = "";
                        other.inGamePlayerId = "";
                    }
                }
                seat.role = "human";
                seat.humanPlayerKey = memberKey;
                seat.botName = "";
                seat.inGamePlayerId = "";
            } else {
                seat.role = role;
                seat.humanPlayerKey = null;
                seat.botName = defaultBotName(role, seatIndex + 1);
                seat.inGamePlayerId = "";
            }
        }
        sendRoomStateToSession(sessionId);
        broadcastRoomList();
    }

    private void handleLeaveRoom(ClientConnection from, JsonObject payload) {
        String sessionId = normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            return;
        }
        removeMemberFromRoom(room, playerKey(from));
        broadcastRoomList();
    }

    private void handleStartRoom(ClientConnection from, JsonObject payload) {
        String sessionId = normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            sendRoomError(from, "房间不存在");
            return;
        }
        StartSessionRequest req = new StartSessionRequest();
        synchronized (room) {
            if (!isRoomHost(room, from)) {
                sendRoomError(from, "只有房主可以开始游戏");
                return;
            }
            List<String> roles = room.activeSeatRoles();
            if (roles.size() < 2) {
                sendRoomError(from, "至少需要 2 个有效席位");
                return;
            }
            if (roles.stream().noneMatch("human"::equals)) {
                sendRoomError(from, "至少需要 1 个真人玩家");
                return;
            }
            room.started = true;
            req.setSessionId(sessionId);
            req.setGameMode("CUSTOM");
            req.setPlayerRoles(roles);
            req.setPlayerCount(roles.size());
            req.setDisplayNames(room.activeSeatNames());
            req.setRandomizeFirstPlayer(payload.has("randomizeFirstPlayer")
                    && payload.get("randomizeFirstPlayer").getAsBoolean());
        }
        SessionRuntime runtime = getOrCreateSession(sessionId);
        bindHumanSeatConnections(room);
        runtime.controller.startNewSession(req);
        sendRoomStateToSession(sessionId);
        pushPrivateHands(sessionId);
        broadcastRoomList();
    }

    private void sendRoomList(ClientConnection client) {
        if (client == null || !client.isOpen()) {
            return;
        }
        try {
            client.sendText(dispatcher.toJsonEnvelope("ROOM_LIST_RESULT", roomListPayload()));
        } catch (IOException e) {
            clients.remove(client);
            sessionRegistry.unregister(client);
        }
    }

    private void broadcastRoomList() {
        if (clients.isEmpty()) {
            return;
        }
        broadcast(dispatcher.toJsonEnvelope("ROOM_LIST_RESULT", roomListPayload()));
    }

    private JsonObject roomListPayload() {
        JsonObject payload = new JsonObject();
        JsonArray rooms = new JsonArray();
        List<LobbyRoom> lobbies = new ArrayList<>(lobbyRooms.values());
        lobbies.sort(Comparator.comparing(room -> room.sessionId));
        for (LobbyRoom lobby : lobbies) {
            rooms.add(lobbyListObject(lobby));
        }
        List<SessionRuntime> ordered = new ArrayList<>(sessions.values());
        ordered.sort(Comparator.comparing(SessionRuntime::sessionId));
        for (SessionRuntime runtime : ordered) {
            GameController controller = runtime.controller();
            if (controller == null) {
                continue;
            }
            String sessionId = normalizeSessionId(runtime.sessionId());
            if (lobbyRooms.containsKey(sessionId)) {
                continue;
            }
            int seatCount = controller.getSessionPlayersView().size();
            long humanSeats = controller.getSessionPlayersView().stream()
                    .filter(HumanPlayer.class::isInstance)
                    .count();
            JsonObject room = new JsonObject();
            room.addProperty("sessionId", sessionId);
            room.addProperty("seatCount", seatCount);
            room.addProperty("humanSeats", humanSeats);
            room.addProperty("connectedPlayers", sessionRegistry.playerIdsInSession(sessionId).size());
            room.addProperty("connections", sessionRegistry.connectionsInSession(sessionId).size());
            room.addProperty("started", seatCount > 0);
            room.addProperty("currentPlayerId", controller.getCurrentPlayer() == null
                    ? "" : controller.getCurrentPlayer().getPlayerId());
            rooms.add(room);
        }
        payload.add("rooms", rooms);
        payload.addProperty("serverTimeEpochMs", System.currentTimeMillis());
        return payload;
    }

    private JsonObject lobbyListObject(LobbyRoom lobby) {
        synchronized (lobby) {
            JsonObject room = new JsonObject();
            room.addProperty("sessionId", lobby.sessionId);
            room.addProperty("seatCount", lobby.activeSeatCount());
            room.addProperty("humanSeats", lobby.humanSeatCount());
            room.addProperty("connectedPlayers", lobby.members.size());
            room.addProperty("connections", sessionRegistry.connectionsInSession(lobby.sessionId).size());
            room.addProperty("started", lobby.started);
            room.addProperty("hostNickname", lobby.nicknameOf(lobby.hostPlayerKey));
            room.addProperty("currentPlayerId", "");
            return room;
        }
    }

    private void sendRoomStateToSession(String sessionId) {
        LobbyRoom room = lobbyRooms.get(normalizeSessionId(sessionId));
        if (room == null) {
            return;
        }
        broadcastToSession(room.sessionId, dispatcher.toJsonEnvelope("ROOM_STATE", roomStatePayload(room)));
    }

    private JsonObject roomStatePayload(LobbyRoom room) {
        synchronized (room) {
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionId", room.sessionId);
            payload.addProperty("started", room.started);
            payload.addProperty("hostKey", room.hostPlayerKey);
            JsonArray members = new JsonArray();
            for (LobbyMember member : room.members.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("playerKey", member.playerKey);
                obj.addProperty("nickname", member.nickname);
                obj.addProperty("host", member.playerKey.equals(room.hostPlayerKey));
                members.add(obj);
            }
            payload.add("members", members);
            JsonArray seats = new JsonArray();
            for (int i = 0; i < room.seats.size(); i++) {
                LobbySeat seat = room.seats.get(i);
                JsonObject obj = new JsonObject();
                obj.addProperty("index", i);
                obj.addProperty("role", seat.role);
                obj.addProperty("nickname", seat.displayName(room));
                obj.addProperty("playerKey", seat.humanPlayerKey == null ? "" : seat.humanPlayerKey);
                obj.addProperty("playerId", seat.inGamePlayerId);
                seats.add(obj);
            }
            payload.add("seats", seats);
            return payload;
        }
    }

    private void sendRoomError(ClientConnection client, String error) {
        try {
            client.sendText(dispatcher.toJsonEnvelope("ROOM_ERROR", dispatcher.operationResult(false, error)));
        } catch (IOException ignored) {
        }
    }

    private void bindHumanSeatConnections(LobbyRoom room) {
        synchronized (room) {
            int playerNumber = 1;
            for (LobbySeat seat : room.seats) {
                if ("empty".equals(seat.role)) {
                    continue;
                }
                String inGamePlayerId = "human".equals(seat.role)
                        ? "pvp-" + playerNumber
                        : "ai-" + playerNumber;
                if ("human".equals(seat.role) && seat.humanPlayerKey != null) {
                    seat.inGamePlayerId = inGamePlayerId;
                    for (ClientConnection conn : sessionRegistry.connectionsOf(seat.humanPlayerKey)) {
                        sessionRegistry.register(conn, inGamePlayerId);
                        sessionRegistry.bindSession(conn, room.sessionId);
                    }
                } else {
                    seat.inGamePlayerId = inGamePlayerId;
                }
                playerNumber++;
            }
        }
    }

    private void removeMemberFromRoom(LobbyRoom room, String playerKey) {
        boolean changed = false;
        synchronized (room) {
            if (room.members.remove(playerKey) != null) {
                changed = true;
            }
            for (LobbySeat seat : room.seats) {
                if (playerKey.equals(seat.humanPlayerKey)) {
                    seat.role = "empty";
                    seat.humanPlayerKey = null;
                    seat.botName = "";
                    seat.inGamePlayerId = "";
                    changed = true;
                }
            }
            if (playerKey.equals(room.hostPlayerKey)) {
                room.hostPlayerKey = room.members.keySet().stream().findFirst().orElse("");
            }
            if (room.members.isEmpty() && !room.started) {
                lobbyRooms.remove(room.sessionId, room);
            }
        }
        if (changed) {
            sendRoomStateToSession(room.sessionId);
        }
    }

    private static String normalizeNickname(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeLobbyRole(String value) {
        String role = value == null ? "empty" : value.trim().toLowerCase();
        return switch (role) {
            case "human", "hard", "strong", "llm", "student", "empty" -> role;
            case "lookahead" -> "strong";
            default -> "empty";
        };
    }

    private static String defaultBotName(String role, int seatNumber) {
        return switch (role) {
            case "hard" -> "Hard Bot " + seatNumber;
            case "strong" -> "Strong Bot " + seatNumber;
            case "llm" -> "LLM Bot " + seatNumber;
            case "student" -> "Student Bot " + seatNumber;
            default -> "";
        };
    }

    private String playerKey(ClientConnection conn) {
        return "conn-" + System.identityHashCode(conn);
    }

    private boolean isRoomHost(LobbyRoom room, ClientConnection conn) {
        return playerKey(conn).equals(room.hostPlayerKey);
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

    private static final class LobbyRoom {
        private final String sessionId;
        private final List<LobbySeat> seats = new ArrayList<>();
        private final java.util.LinkedHashMap<String, LobbyMember> members = new java.util.LinkedHashMap<>();
        private String hostPlayerKey = "";
        private boolean started;

        private LobbyRoom(String sessionId) {
            this.sessionId = sessionId;
            for (int i = 0; i < 5; i++) {
                seats.add(new LobbySeat());
            }
        }

        private void addMember(String playerKey, String nickname) {
            members.put(playerKey, new LobbyMember(playerKey, nickname));
        }

        private boolean nicknameExists(String nickname, String exceptPlayerKey) {
            for (LobbyMember member : members.values()) {
                if (!member.playerKey.equals(exceptPlayerKey)
                        && member.nickname.equalsIgnoreCase(nickname)) {
                    return true;
                }
            }
            return false;
        }

        private String memberKeyByNickname(String nickname) {
            for (LobbyMember member : members.values()) {
                if (member.nickname.equals(nickname)) {
                    return member.playerKey;
                }
            }
            return null;
        }

        private String nicknameOf(String playerKey) {
            LobbyMember member = members.get(playerKey);
            return member == null ? "" : member.nickname;
        }

        private int activeSeatCount() {
            return (int) seats.stream().filter(seat -> !"empty".equals(seat.role)).count();
        }

        private int humanSeatCount() {
            return (int) seats.stream().filter(seat -> "human".equals(seat.role)).count();
        }

        private List<String> activeSeatRoles() {
            List<String> roles = new ArrayList<>();
            for (LobbySeat seat : seats) {
                String mapped = seat.backendRole();
                if (!mapped.isBlank()) {
                    roles.add(mapped);
                }
            }
            return roles;
        }

        private List<String> activeSeatNames() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < seats.size(); i++) {
                LobbySeat seat = seats.get(i);
                if (!"empty".equals(seat.role)) {
                    names.add(seat.displayName(this));
                }
            }
            return names;
        }
    }

    private record LobbyMember(String playerKey, String nickname) {
    }

    private static final class LobbySeat {
        private String role = "empty";
        private String humanPlayerKey;
        private String botName = "";
        private String inGamePlayerId = "";

        private String displayName(LobbyRoom room) {
            if ("human".equals(role)) {
                return room.nicknameOf(humanPlayerKey);
            }
            return botName == null ? "" : botName;
        }

        private String backendRole() {
            return switch (role) {
                case "human" -> humanPlayerKey == null ? "" : "human";
                case "hard" -> "hard";
                case "strong" -> "lookahead";
                case "llm" -> "llm";
                case "student" -> "student";
                default -> "";
            };
        }
    }
}
