package com.monopoly.network.endpoint;

import com.monopoly.controller.GameController;
import com.monopoly.network.GameServer;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ServerEndpoint("/ws")
public class MonopolyWebSocketEndpoint {

    private static final DefaultGameUpdateSubject SUBJECT = new DefaultGameUpdateSubject();
    private static final GameServer GAME_SERVER = new GameServer();
    private static final Map<Session, ClientConnection> ADAPTERS = new ConcurrentHashMap<>();
    private static final ByteBuffer HEARTBEAT_PAYLOAD = ByteBuffer.wrap(new byte[] {1});
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-heartbeat");
        t.setDaemon(true);
        return t;
    });

    static {
        GameController controller = new GameController(SUBJECT);
        GAME_SERVER.wireController(controller);
        GAME_SERVER.attachTo(SUBJECT);
        HEARTBEAT.scheduleAtFixedRate(MonopolyWebSocketEndpoint::sendHeartbeats,
                25, 25, TimeUnit.SECONDS);
    }

    private static void sendHeartbeats() {
        for (Map.Entry<Session, ClientConnection> entry : ADAPTERS.entrySet()) {
            Session s = entry.getKey();
            if (s.isOpen()) {
                try {
                    s.getAsyncRemote().sendPing(HEARTBEAT_PAYLOAD.asReadOnlyBuffer());
                } catch (Exception ignored) {
                }
            }
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        ClientConnection adapter = new SessionClientConnection(session);
        ADAPTERS.put(session, adapter);
        GAME_SERVER.onClientConnected(adapter);
    }

    @OnMessage
    public void onMessage(Session session, String text) {
        ClientConnection adapter = ADAPTERS.get(session);
        if (adapter == null) {
            adapter = new SessionClientConnection(session);
            ADAPTERS.put(session, adapter);
            GAME_SERVER.onClientConnected(adapter);
        }
        GAME_SERVER.onMessage(adapter, text);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        ClientConnection adapter = ADAPTERS.remove(session);
        if (adapter != null) {
            GAME_SERVER.onClientDisconnected(adapter);
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        ClientConnection adapter = ADAPTERS.remove(session);
        if (adapter != null) {
            GAME_SERVER.onClientDisconnected(adapter);
        }
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class SessionClientConnection implements ClientConnection {
        private final Session session;
        private final String connectionId;
        private final Object sendLock = new Object();

        private SessionClientConnection(Session session) {
            this.session = session;
            this.connectionId = UUID.randomUUID().toString();
        }

        @Override
        public String connectionId() {
            return connectionId;
        }

        @Override
        public boolean isOpen() {
            return session != null && session.isOpen();
        }

        @Override
        public void sendText(String text) throws IOException {
            synchronized (sendLock) {
                if (!isOpen()) {
                    throw new IOException("Session is closed");
                }
                session.getBasicRemote().sendText(text);
            }
        }
    }
}
