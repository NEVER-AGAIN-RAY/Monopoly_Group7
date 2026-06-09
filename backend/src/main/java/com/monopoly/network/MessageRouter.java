package com.monopoly.network;

import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.controller.ProtocolErrors;
import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.protocol.MessageDispatcher;

import java.io.IOException;
import java.util.Optional;

final class MessageRouter {

    private final SessionHub hub;
    private final MessageDispatcher dispatcher;
    private final DemoRoomService demoRoom;
    private final SaveLoadVoteCoordinator saveLoad;

    MessageRouter(
            SessionHub hub,
            MessageDispatcher dispatcher,
            DemoRoomService demoRoom,
            SaveLoadVoteCoordinator saveLoad) {
        this.hub = hub;
        this.dispatcher = dispatcher;
        this.demoRoom = demoRoom;
        this.saveLoad = saveLoad;
    }

    private Optional<String> registeredPlayerId(ClientConnection from) {
        return hub.sessionRegistry().getPlayerId(from);
    }

    private boolean isPlayerAuthorized(ClientConnection from, String claimedPlayerId) {
        if (claimedPlayerId == null || claimedPlayerId.isBlank()) {
            return false;
        }
        return registeredPlayerId(from)
                .filter(pid -> pid.equals(claimedPlayerId.trim()))
                .isPresent();
    }

    private boolean requireAuthorizedPlayer(
            ClientConnection from,
            String playerId,
            String requestId) {
        if (isPlayerAuthorized(from, playerId)) {
            return true;
        }
        hub.sendError(from, "UNAUTHORIZED", "Cannot act as another player", requestId);
        return false;
    }

    private boolean requireCurrentHumanAuthorized(
            ClientConnection from,
            GameController controller,
            String requestId) {
        Player current = controller.getCurrentPlayer();
        if (!(current instanceof HumanPlayer)) {
            hub.sendError(from, "UNAUTHORIZED", "Only the current human player can perform this action", requestId);
            return false;
        }
        return requireAuthorizedPlayer(from, current.getPlayerId(), requestId);
    }

    private boolean requireSessionHumanAuthorized(
            ClientConnection from,
            GameController controller,
            String requestId) {
        Optional<String> playerId = registeredPlayerId(from);
        if (playerId.isEmpty()) {
            hub.sendError(from, "UNAUTHORIZED", "A bound player identity is required", requestId);
            return false;
        }
        if (controller.getSessionPlayersView().isEmpty()) {
            return true;
        }
        boolean inSession = controller.getSessionPlayersView().stream()
                .filter(HumanPlayer.class::isInstance)
                .anyMatch(p -> p.getPlayerId().equals(playerId.get()));
        if (!inSession) {
            hub.sendError(from, "UNAUTHORIZED", "Cannot act in another session", requestId);
            return false;
        }
        return true;
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
        if ("JOIN_DEMO_ROOM".equals(type)) {
            demoRoom.handleJoinDemoRoom(from, payload);
            return;
        }
        if ("START_DEMO_ROOM".equals(type)) {
            demoRoom.handleStartDemoRoom(from, payload);
            return;
        }
        if ("LEAVE_DEMO_ROOM".equals(type)) {
            demoRoom.handleLeaveDemoRoom(from);
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
                    "Please START_SESSION first, or provide sessionId in payload.", dispatcher.extractRequestId(root, payload));
            return;
        }
        if ("PAUSE".equals(type) || "PAUSE_REQUEST".equals(type)) {
            String requestId = dispatcher.extractRequestId(root, payload);
            String playerId = dispatcher.getString(payload, "playerId", null);
            if (playerId != null && !playerId.isBlank()
                    && !requireAuthorizedPlayer(from, playerId, requestId)) {
                return;
            }
            if ((playerId == null || playerId.isBlank())
                    && !requireSessionHumanAuthorized(from, controller, requestId)) {
                return;
            }
            controller.requestPause();
            return;
        }
        if ("PAUSE_ACK".equals(type)) {
            String ackPlayerId = dispatcher.getString(payload, "playerId", null);
            if (!requireAuthorizedPlayer(from, ackPlayerId, dispatcher.extractRequestId(root, payload))) {
                return;
            }
            controller.acknowledgePause(ackPlayerId);
            return;
        }
        if ("RESUME".equals(type)) {
            String requestId = dispatcher.extractRequestId(root, payload);
            String playerId = dispatcher.getString(payload, "playerId", null);
            if (playerId != null && !playerId.isBlank()
                    && !requireAuthorizedPlayer(from, playerId, requestId)) {
                return;
            }
            if ((playerId == null || playerId.isBlank())
                    && !requireSessionHumanAuthorized(from, controller, requestId)) {
                return;
            }
            controller.resume();
            return;
        }
        if ("REASSIGN_WILD".equals(type)) {
            if (!requireCurrentHumanAuthorized(from, controller, dispatcher.extractRequestId(root, payload))) {
                return;
            }
            String wildId = dispatcher.getString(payload, "wildPropertyCardId", null);
            String newKey = dispatcher.getString(payload, "newColorKey", null);
            controller.handleReassignWildCommand(wildId, newKey);
            return;
        }
        if ("DRAW".equals(type)) {
            if (!requireCurrentHumanAuthorized(from, controller, dispatcher.extractRequestId(root, payload))) {
                return;
            }
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
            if (!requireCurrentHumanAuthorized(from, controller, dispatcher.extractRequestId(root, payload))) {
                return;
            }
            controller.handleEndTurnCommand();
            return;
        }
        if ("QUIT".equals(type)) {
            String quitPlayerId = dispatcher.getString(payload, "playerId", null);
            if (!requireAuthorizedPlayer(from, quitPlayerId, dispatcher.extractRequestId(root, payload))) {
                return;
            }
            controller.handleQuitCommand(quitPlayerId);
            return;
        }
        if ("RESPONSE_PASS".equals(type)) {
            handleResponsePass(from, root, payload, controller);
            return;
        }
        if ("SAVE_GAME".equals(type)) {
            if (!requireSessionHumanAuthorized(from, controller, dispatcher.extractRequestId(root, payload))) {
                return;
            }
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
            if (!requireSessionHumanAuthorized(from, controller, dispatcher.extractRequestId(root, payload))) {
                return;
            }
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
                        dispatcher.operationResult(false, "playerId must not be empty")));
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
            demoRoom.sendDemoRoomStateTo(from);
        } catch (IOException ignored) {
        }
        GameController controller = sessionId == null || sessionId.isBlank() ? null : hub.controllerForSession(sessionId);
        if (controller != null && !controller.getSessionPlayersView().isEmpty()) {
            controller.pushCurrentState("JOIN", "Player reconnected.");
        }
    }

    private void handlePlay(ClientConnection from, JsonObject root, JsonObject payload, GameController controller) {
        String requestId = dispatcher.extractRequestId(root, payload);
        try {
            PlayActionRequest playReq = dispatcher.parsePlayActionRequest(payload);
            if (playReq.getActingPlayerId() != null && !playReq.getActingPlayerId().isBlank()) {
                if (!requireAuthorizedPlayer(from, playReq.getActingPlayerId(), requestId)) {
                    return;
                }
            } else if (!requireCurrentHumanAuthorized(from, controller, requestId)) {
                return;
            }
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
            if (!requireAuthorizedPlayer(from, playerId, requestId)) {
                return;
            }
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
            if (!requireAuthorizedPlayer(from, playerId, requestId)) {
                return;
            }
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
            if (!requireAuthorizedPlayer(from, passReq.getActingPlayerId(), requestId)) {
                return;
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
