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
 * WebSocket 服务端骨架：维护已连接客户端抽象，在状态更新时向所有连接广播 JSON。
 * 具体容器（Tyrus / Jetty / Netty）在 @ServerEndpoint 中把原生 Session 适配为 {@link com.monopoly.network.connection.ClientConnection}。
 */
public class GameServer implements GameUpdateObserver {

    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private final AtomicLong requestCounter = new AtomicLong(1);
    private volatile PendingLoadVote pendingLoadVote;
    private volatile PendingSaveVote pendingSaveVote;

    private GameController gameController;

    public void wireController(GameController controller) {
        this.gameController = controller;
    }

    /** 新客户端连接时注册（由 WebSocket 端点回调调用） */
    public void onClientConnected(ClientConnection client) {
        clients.add(client);
    }

    public void onClientDisconnected(ClientConnection client) {
        clients.remove(client);
        sessionRegistry.unregister(client);
    }

    /** 收到客户端 JSON 文本：转交控制器（骨架） */
    public void onMessage(ClientConnection from, String json) {
        String type = dispatcher.extractMessageType(json);
        JsonObject root = dispatcher.parseObject(json);
        JsonObject payload = dispatcher.extractPayload(root);
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
            // sessionToken 预留给后续鉴权链路，当前阶段仅完成连接与玩家绑定。
            if (sessionToken != null && sessionToken.isBlank()) {
                sessionToken = null;
            }
            sessionRegistry.register(from, playerId);
            try {
                from.sendText(dispatcher.toJsonEnvelope("AUTH_RESULT", dispatcher.operationResult(true, null)));
            } catch (IOException ignored) {
            }
            return;
        }
        if (gameController == null) {
            return;
        }
        if ("START_SESSION".equals(type)) {
            StartSessionRequest startReq = dispatcher.parseStartSessionRequest(payload);
            gameController.startNewSession(startReq);
            return;
        }
        if ("PAUSE".equals(type) || "PAUSE_REQUEST".equals(type)) {
            gameController.requestPause();
            return;
        }
        if ("PAUSE_ACK".equals(type)) {
            String ackPlayerId = dispatcher.getString(payload, "playerId", null);
            gameController.acknowledgePause(ackPlayerId);
            return;
        }
        if ("RESUME".equals(type)) {
            gameController.resume();
            return;
        }
        if ("REASSIGN_WILD".equals(type)) {
            String wildId = dispatcher.getString(payload, "wildPropertyCardId", null);
            String newKey = dispatcher.getString(payload, "newColorKey", null);
            gameController.handleReassignWildCommand(wildId, newKey);
            return;
        }
        if ("DRAW".equals(type)) {
            int count = dispatcher.getInt(payload, "count", 2);
            gameController.handleDrawCommand(count);
            return;
        }
        if ("PLAY".equals(type)) {
            String requestId = dispatcher.extractRequestId(root, payload);
            try {
                PlayActionRequest playReq = dispatcher.parsePlayActionRequest(payload);
                gameController.handlePlayActionRequest(playReq);
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
                ActionOptionsResult r = gameController.queryActionOptionsForHandCard(playerId, cardId);
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
                ActionOptionsResult r = gameController.queryPlayOptions(playerId, cardId, actionType);
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
            gameController.handleEndTurnCommand();
            return;
        }
        if ("QUIT".equals(type)) {
            String quitPlayerId = dispatcher.getString(payload, "playerId", null);
            gameController.handleQuitCommand(quitPlayerId);
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
                gameController.handlePlayActionRequest(passReq);
            } catch (ProtocolErrors.ProtocolValidationException e) {
                sendError(from, e.getCode(), e.getMessage(), requestId);
            } catch (RuntimeException e) {
                sendError(from, "RESPONSE_PASS_BAD", e.getMessage(), requestId);
            }
            return;
        }
        if ("SAVE_GAME".equals(type)) {
            handleSaveGame(from, payload);
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
            handleLoadGame(from, payload);
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
            // 兼容旧客户端：映射到新协议 ACK
            handleLoadGameAck(from, payload);
            return;
        }
        if ("PING".equals(type)) {
            // 保留联通性探测占位
        }
    }

    private void handleSaveGame(ClientConnection from, JsonObject payload) {
        try {
            Set<String> voters = resolveEligibleLoadVoters();
            if (voters.isEmpty()) {
                commitSaveGame(payload, from);
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId == null || requestId.isBlank()) {
                requestId = "save-" + requestCounter.getAndIncrement();
            }
            long providedDeadline = parseLong(payload, "deadlineEpochMs", 0L);
            long deadlineEpochMs = providedDeadline > 0L ? providedDeadline : System.currentTimeMillis() + 30_000L;
            PendingSaveVote vote = new PendingSaveVote(requestId, deadlineEpochMs, voters, payload, from);
            pendingSaveVote = vote;

            JsonObject req = new JsonObject();
            req.addProperty("requestId", vote.requestId);
            req.addProperty("deadlineEpochMs", vote.deadlineEpochMs);
            broadcast(dispatcher.toJsonEnvelope("SAVE_GAME_REQUEST", req));
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
            PendingSaveVote vote = pendingSaveVote;
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
            pendingSaveVote = null;
            commitSaveGame(vote.originalPayload, vote.requester);
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
            PendingSaveVote vote = pendingSaveVote;
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

    private void commitSaveGame(JsonObject payload, ClientConnection from) {
        try {
            String mementoJson = gameController.exportSessionJson();
            String path = dispatcher.getString(payload, "path", null);
            if (path != null && !path.isBlank()) {
                Path p = Paths.get(path.trim());
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                String out = SaveEncryption.encodeForStorage(mementoJson);
                Files.writeString(p, out, StandardCharsets.UTF_8);
                broadcast(dispatcher.toJsonEnvelope(
                        "SAVE_GAME_RESULT", dispatcher.saveGameResultOkWithPath(p.toString())));
            } else {
                broadcast(dispatcher.toJsonEnvelope(
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
        if (pendingSaveVote != vote) {
            return;
        }
        pendingSaveVote = null;
        broadcast(dispatcher.toJsonEnvelope("SAVE_GAME_RESULT", dispatcher.operationResult(false, reason)));
    }

    private void handleLoadGame(ClientConnection from, JsonObject payload) {
        try {
            String raw = dispatcher.getString(payload, "mementoJson", null);
            if (raw == null || raw.isBlank()) {
                from.sendText(dispatcher.toJsonEnvelope(
                        "LOAD_GAME_RESULT",
                        dispatcher.operationResult(false, "mementoJson 不能为空")));
                return;
            }
            Set<String> eligibleVoters = resolveEligibleLoadVoters();
            if (eligibleVoters.isEmpty()) {
                // 兼容无人身份绑定场景（例如旧测试/脚本）
                gameController.importSessionJson(raw);
                from.sendText(dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(true, null)));
                return;
            }
            String requestId = dispatcher.getString(payload, "requestId", null);
            if (requestId == null || requestId.isBlank()) {
                requestId = "load-" + requestCounter.getAndIncrement();
            }
            PendingLoadVote vote = new PendingLoadVote(requestId, raw, eligibleVoters);
            pendingLoadVote = vote;

            JsonObject request = new JsonObject();
            request.addProperty("requestId", vote.requestId);
            broadcast(dispatcher.toJsonEnvelope("LOAD_GAME_REQUEST", request));
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
            PendingLoadVote vote = pendingLoadVote;
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
            PendingLoadVote vote = pendingLoadVote;
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
        if (pendingLoadVote != vote) {
            return;
        }
        try {
            gameController.importSessionJson(vote.mementoJson);
            pendingLoadVote = null;
            broadcast(dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(true, null)));
        } catch (Exception ex) {
            pendingLoadVote = null;
            broadcast(dispatcher.toJsonEnvelope(
                    "LOAD_GAME_RESULT",
                    dispatcher.operationResult(false, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())));
        }
    }

    private void cancelLoadVote(PendingLoadVote vote, String reason) {
        if (pendingLoadVote != vote) {
            return;
        }
        pendingLoadVote = null;
        broadcast(dispatcher.toJsonEnvelope("LOAD_GAME_RESULT", dispatcher.operationResult(false, reason)));
    }

    @Override
    public void onGameStateChanged(GameStateSnapshot snapshot) {
        String payload = dispatcher.toJsonBroadcast(snapshot);
        for (ClientConnection client : clients) {
            if (client.isOpen()) {
                try {
                    client.sendText(payload);
                } catch (IOException e) {
                    clients.remove(client);
                }
            }
        }
        pushPrivateHands();
    }

    /** 将本服务器注册为观察者，复用 GameUpdateSubject */
    public void attachTo(GameUpdateSubject subject) {
        subject.registerObserver(this);
    }

    public Optional<String> getPlayerIdOf(ClientConnection client) {
        return sessionRegistry.getPlayerId(client);
    }

    public Set<ClientConnection> connectionsOf(String playerId) {
        return sessionRegistry.connectionsOf(playerId);
    }

    private void pushPrivateHands() {
        if (gameController == null) {
            return;
        }
        for (Player player : gameController.getSessionPlayersView()) {
            if (!(player instanceof HumanPlayer)) {
                continue;
            }
            Set<ClientConnection> targets = sessionRegistry.connectionsOf(player.getPlayerId());
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

    private Set<String> resolveEligibleLoadVoters() {
        if (gameController == null) {
            return Set.of();
        }
        Set<String> eligible = ConcurrentHashMap.newKeySet();
        for (Player player : gameController.getSessionPlayersView()) {
            if (!(player instanceof HumanPlayer)) {
                continue;
            }
            if (!sessionRegistry.connectionsOf(player.getPlayerId()).isEmpty()) {
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

        private PendingSaveVote(
                String requestId,
                long deadlineEpochMs,
                Set<String> eligibleVoters,
                JsonObject originalPayload,
                ClientConnection requester
        ) {
            this.requestId = requestId;
            this.deadlineEpochMs = deadlineEpochMs;
            this.eligibleVoters = ConcurrentHashMap.newKeySet();
            this.eligibleVoters.addAll(eligibleVoters);
            this.originalPayload = originalPayload;
            this.requester = requester;
        }
    }

    private static final class PendingLoadVote {
        private final String requestId;
        private final String mementoJson;
        private final Set<String> eligibleVoters;
        private final Set<String> acks = ConcurrentHashMap.newKeySet();

        private PendingLoadVote(String requestId, String mementoJson, Set<String> eligibleVoters) {
            this.requestId = requestId;
            this.mementoJson = mementoJson;
            this.eligibleVoters = ConcurrentHashMap.newKeySet();
            this.eligibleVoters.addAll(eligibleVoters);
        }
    }
}
