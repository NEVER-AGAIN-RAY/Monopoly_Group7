package com.monopoly.network.connection;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maps WebSocket connections to playerId/sessionId.
 */
public class SessionRegistry {

    private final ConcurrentMap<ClientConnection, String> connectionToPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<ClientConnection>> playerToConnections = new ConcurrentHashMap<>();
    private final ConcurrentMap<ClientConnection, String> connectionToSession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<ClientConnection>> sessionToConnections = new ConcurrentHashMap<>();

    public void register(ClientConnection conn, String playerId) {
        if (conn == null || playerId == null || playerId.isBlank()) {
            return;
        }
        String normalized = playerId.trim();
        String previous = connectionToPlayer.put(conn, normalized);
        if (previous != null && !previous.equals(normalized)) {
            removeFromPlayerBucket(previous, conn);
        }
        playerToConnections.computeIfAbsent(normalized, k -> ConcurrentHashMap.newKeySet()).add(conn);
    }

    public void unregister(ClientConnection conn) {
        if (conn == null) {
            return;
        }
        String playerId = connectionToPlayer.remove(conn);
        if (playerId != null) {
            removeFromPlayerBucket(playerId, conn);
        }
        String sessionId = connectionToSession.remove(conn);
        if (sessionId != null) {
            removeFromSessionBucket(sessionId, conn);
        }
    }

    public Optional<String> getPlayerId(ClientConnection conn) {
        if (conn == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(connectionToPlayer.get(conn));
    }

    public Set<ClientConnection> connectionsOf(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Set.of();
        }
        Set<ClientConnection> bucket = playerToConnections.get(playerId.trim());
        if (bucket == null || bucket.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(bucket));
    }

    public void bindSession(ClientConnection conn, String sessionId) {
        if (conn == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String normalized = sessionId.trim();
        String previous = connectionToSession.put(conn, normalized);
        if (previous != null && !previous.equals(normalized)) {
            removeFromSessionBucket(previous, conn);
        }
        sessionToConnections.computeIfAbsent(normalized, k -> ConcurrentHashMap.newKeySet()).add(conn);
    }

    public Optional<String> getSessionId(ClientConnection conn) {
        if (conn == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(connectionToSession.get(conn));
    }

    public Set<ClientConnection> connectionsInSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Set.of();
        }
        Set<ClientConnection> bucket = sessionToConnections.get(sessionId.trim());
        if (bucket == null || bucket.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(bucket));
    }

    public void rebindSession(String oldSessionId, String newSessionId) {
        if (oldSessionId == null || oldSessionId.isBlank()
                || newSessionId == null || newSessionId.isBlank()) {
            return;
        }
        String oldKey = oldSessionId.trim();
        String newKey = newSessionId.trim();
        if (oldKey.equals(newKey)) {
            return;
        }
        Set<ClientConnection> existing = sessionToConnections.get(oldKey);
        if (existing == null || existing.isEmpty()) {
            return;
        }
        for (ClientConnection conn : new HashSet<>(existing)) {
            bindSession(conn, newKey);
        }
    }

    private void removeFromPlayerBucket(String playerId, ClientConnection conn) {
        Set<ClientConnection> bucket = playerToConnections.get(playerId);
        if (bucket == null) {
            return;
        }
        bucket.remove(conn);
        if (bucket.isEmpty()) {
            playerToConnections.remove(playerId, bucket);
        }
    }

    private void removeFromSessionBucket(String sessionId, ClientConnection conn) {
        Set<ClientConnection> bucket = sessionToConnections.get(sessionId);
        if (bucket == null) {
            return;
        }
        bucket.remove(conn);
        if (bucket.isEmpty()) {
            sessionToConnections.remove(sessionId, bucket);
        }
    }
}
