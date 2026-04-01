package com.monopoly.network;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 维护连接与玩家身份的双向索引，供路由与鉴权使用。
 */
public class SessionRegistry {

    private final ConcurrentMap<ClientConnection, String> connectionToPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<ClientConnection>> playerToConnections = new ConcurrentHashMap<>();

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
}
