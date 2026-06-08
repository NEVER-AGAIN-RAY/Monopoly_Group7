package com.monopoly.network;

import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.connection.SessionRegistry;
import com.monopoly.network.protocol.MessageDispatcher;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class SessionHub {

    private final MessageDispatcher dispatcher;
    private final GameUpdateObserver observer;
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private final ConcurrentHashMap<String, SessionRuntime> sessions = new ConcurrentHashMap<>();

    private GameController gameController;

    SessionHub(MessageDispatcher dispatcher, GameUpdateObserver observer) {
        this.dispatcher = dispatcher;
        this.observer = observer;
    }

    MessageDispatcher dispatcher() {
        return dispatcher;
    }

    SessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    Set<ClientConnection> clients() {
        return clients;
    }

    GameController gameController() {
        return gameController;
    }

    void setGameController(GameController gameController) {
        this.gameController = gameController;
    }

    void addClient(ClientConnection client) {
        clients.add(client);
    }

    void removeClient(ClientConnection client) {
        clients.remove(client);
    }

    void unregister(ClientConnection client) {
        sessionRegistry.unregister(client);
    }

    List<SessionRuntime> sessionRuntimesSnapshot() {
        List<SessionRuntime> out = new ArrayList<>(sessions.values());
        out.sort(Comparator.comparing(SessionRuntime::sessionId));
        return out;
    }

    SessionRuntime getOrCreateSession(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        return sessions.computeIfAbsent(normalized, sid -> {
            DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
            GameController controller = new GameController(subject);
            subject.registerObserver(observer);
            return new SessionRuntime(sid, controller, subject);
        });
    }

    void registerControllerSession(GameController controller) {
        if (controller == null) {
            return;
        }
        String sessionId = normalizeSessionId(controller.getCurrentSessionIdPublic());
        sessions.put(sessionId, new SessionRuntime(sessionId, controller, null));
    }

    GameController resolveController(ClientConnection from, JsonObject payload) {
        String sessionId = dispatcher.getString(payload, "sessionId", null);
        boolean explicitSession = sessionId != null && !sessionId.isBlank();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionRegistry.getSessionId(from).orElse(null);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sessionRegistry.bindSession(from, sessionId);
            SessionRuntime runtime = sessions.get(normalizeSessionId(sessionId));
            if (runtime != null) {
                return runtime.controller();
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

    String resolveSessionId(ClientConnection from, JsonObject payload) {
        String sessionId = dispatcher.getString(payload, "sessionId", null);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionRegistry.getSessionId(from).orElse(null);
        }
        if ((sessionId == null || sessionId.isBlank()) && gameController != null) {
            sessionId = gameController.getCurrentSessionIdPublic();
        }
        return sessionId;
    }

    GameController controllerForSession(String sessionId) {
        SessionRuntime runtime = sessions.get(normalizeSessionId(sessionId));
        if (runtime != null) {
            return runtime.controller();
        }
        if (gameController != null && normalizeSessionId(gameController.getCurrentSessionIdPublic())
                .equals(normalizeSessionId(sessionId))) {
            return gameController;
        }
        return null;
    }

    Set<ClientConnection> connectionsOfPlayerInSession(String playerId, String sessionId) {
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

    void rebindSessionAfterLoad(String previousSessionId, String loadedSessionId, GameController controller) {
        sessionRegistry.rebindSession(previousSessionId, loadedSessionId);
        sessions.remove(previousSessionId);
        registerControllerSession(controller);
    }

    static String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "session-default" : sessionId.trim();
    }

    void broadcastToSession(String sessionId, String message) {
        sendToSessionTargets(sessionId, message);
    }

    void sendToSessionTargets(String sessionId, String message) {
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

    boolean shouldFallbackBroadcast(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        if (gameController == null) {
            return false;
        }
        if (!normalizeSessionId(gameController.getCurrentSessionIdPublic()).equals(normalized)) {
            return false;
        }
        if (sessions.isEmpty()) {
            return true;
        }
        if (sessions.size() == 1) {
            SessionRuntime only = sessions.values().iterator().next();
            return only.controller() == gameController;
        }
        return false;
    }

    void broadcast(String message) {
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

    void sendError(ClientConnection client, String code, String message, String requestId) {
        try {
            client.sendText(dispatcher.toErrorEnvelope(code, message, requestId));
        } catch (IOException ignored) {
        }
    }

    static record SessionRuntime(
            String sessionId,
            GameController controller,
            GameUpdateSubject subject) {
    }
}
