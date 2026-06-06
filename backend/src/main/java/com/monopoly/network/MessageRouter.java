package com.monopoly.network;

import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.controller.ProtocolErrors;
import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.protocol.MessageDispatcher;

import java.io.IOException;

final class MessageRouter {

    private final SessionHub hub;
    private final MessageDispatcher dispatcher;
    private final LobbyService lobby;
    private final SaveLoadVoteCoordinator saveLoad;

    MessageRouter(
            SessionHub hub,
            MessageDispatcher dispatcher,
            LobbyService lobby,
            SaveLoadVoteCoordinator saveLoad) {
        this.hub = hub;
        this.dispatcher = dispatcher;
        this.lobby = lobby;
        this.saveLoad = saveLoad;
    }

    void route(ClientConnection from, String json) {
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
            lobby.sendRoomList(from);
            return;
        }
        if ("CREATE_ROOM".equals(type)) {
            lobby.handleCreateRoom(from, payload);
            return;
        }
        if ("JOIN_ROOM".equals(type)) {
            lobby.handleJoinRoom(from, payload);
            return;
        }
        if ("ROOM_SET_SEAT".equals(type)) {
            lobby.handleSetRoomSeat(from, payload);
            return;
        }
        if ("LEAVE_ROOM".equals(type)) {
            lobby.handleLeaveRoom(from, payload);
            return;
        }
        if ("START_ROOM".equals(type)) {
            lobby.handleStartRoom(from, payload);
            return;
        }
        if ("AUTH".equals(type) || "JOIN_SESSION".equals(type)) {
            handleAuth(from, payload);
            return;
        }
        if ("START_SESSION".equals(type)) {
            StartSessionRequest startReq = dispatcher.parseStartSessionRequest(payload);
            String sessionId = SessionHub.normalizeSessionId(startReq.getSessionId());
            hub.sessionRegistry().bindSession(from, sessionId);
            SessionHub.SessionRuntime runtime = hub.getOrCreateSession(sessionId);
            runtime.controller().startNewSession(startReq);
            return;
        }
        GameController controller = hub.resolveController(from, payload);
        if (controller == null) {
            hub.sendError(from, "SESSION_NOT_FOUND",
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
            handlePlay(from, root, payload, controller);
            return;
        }
        if ("ACTION_OPTIONS".equals(type)) {
            handleActionOptions(from, root, payload, controller);
            return;
        }
        if ("PLAY_OPTIONS".equals(type)) {
            handlePlayOptions(from, root, payload, controller);
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
            handleResponsePass(from, root, payload, controller);
            return;
        }
        if ("SAVE_GAME".equals(type)) {
            saveLoad.handleSaveGame(from, payload, controller);
            return;
        }
        if ("SAVE_GAME_ACK".equals(type)) {
            saveLoad.handleSaveGameAck(from, payload);
            return;
        }
        if ("SAVE_GAME_REJECT".equals(type)) {
            saveLoad.handleSaveGameReject(from, payload);
            return;
        }
        if ("LOAD_GAME".equals(type)) {
            saveLoad.handleLoadGame(from, payload, controller);
            return;
        }
        if ("LOAD_GAME_ACK".equals(type)) {
            saveLoad.handleLoadGameAck(from, payload);
            return;
        }
        if ("LOAD_GAME_REJECT".equals(type)) {
            saveLoad.handleLoadGameReject(from, payload);
            return;
        }
        if ("LOAD_VOTE".equals(type)) {
            saveLoad.handleLoadGameAck(from, payload);
        }
    }

    private void handleAuth(ClientConnection from, JsonObject payload) {
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
        if (sessionToken != null && sessionToken.isBlank()) {
            sessionToken = null;
        }
        hub.sessionRegistry().register(from, playerId);
        String sessionId = dispatcher.getString(payload, "sessionId", null);
        if (sessionId != null && !sessionId.isBlank()) {
            hub.sessionRegistry().bindSession(from, sessionId);
        }
        try {
            from.sendText(dispatcher.toJsonEnvelope("AUTH_RESULT", dispatcher.operationResult(true, null)));
            lobby.sendRoomList(from);
        } catch (IOException ignored) {
        }
        GameController controller = sessionId == null || sessionId.isBlank() ? null : hub.controllerForSession(sessionId);
        if (controller != null && !controller.getSessionPlayersView().isEmpty()) {
            controller.pushCurrentState("JOIN", "玩家已连接房间。");
        }
    }

    private void handlePlay(ClientConnection from, JsonObject root, JsonObject payload, GameController controller) {
        String requestId = dispatcher.extractRequestId(root, payload);
        try {
            PlayActionRequest playReq = dispatcher.parsePlayActionRequest(payload);
            controller.handlePlayActionRequest(playReq);
        } catch (ProtocolErrors.ProtocolValidationException e) {
            hub.sendError(from, e.getCode(), e.getMessage(), requestId);
        } catch (IllegalArgumentException e) {
            hub.sendError(from, "PLAY_BAD_REQUEST", e.getMessage(), requestId);
        } catch (IllegalStateException e) {
            hub.sendError(from, "PLAY_STATE_VIOLATION", e.getMessage(), requestId);
        }
    }

    private void handleActionOptions(ClientConnection from, JsonObject root, JsonObject payload, GameController controller) {
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
            hub.sendError(from, "ACTION_OPTIONS_BAD", e.getMessage(), requestId);
        } catch (IllegalStateException e) {
            hub.sendError(from, "ACTION_OPTIONS_STATE", e.getMessage(), requestId);
        } catch (RuntimeException e) {
            hub.sendError(from, "ACTION_OPTIONS_FAIL", e.getMessage(), requestId);
        }
    }

    private void handlePlayOptions(ClientConnection from, JsonObject root, JsonObject payload, GameController controller) {
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
            hub.sendError(from, "PLAY_OPTIONS_BAD", e.getMessage(), requestId);
        } catch (IllegalStateException e) {
            hub.sendError(from, "PLAY_OPTIONS_STATE", e.getMessage(), requestId);
        } catch (RuntimeException e) {
            hub.sendError(from, "PLAY_OPTIONS_FAIL", e.getMessage(), requestId);
        }
    }

    private void handleResponsePass(ClientConnection from, JsonObject root, JsonObject payload, GameController controller) {
        String requestId = dispatcher.extractRequestId(root, payload);
        try {
            PlayActionRequest passReq = dispatcher.parsePlayActionRequest(payload);
            if (passReq.getActingPlayerId() == null || passReq.getActingPlayerId().isBlank()) {
                passReq.setActingPlayerId(dispatcher.getString(payload, "actingPlayerId", null));
            }
            passReq.setActionType("RESPONSE_PASS");
            controller.handlePlayActionRequest(passReq);
        } catch (ProtocolErrors.ProtocolValidationException e) {
            hub.sendError(from, e.getCode(), e.getMessage(), requestId);
        } catch (RuntimeException e) {
            hub.sendError(from, "RESPONSE_PASS_BAD", e.getMessage(), requestId);
        }
    }
}
