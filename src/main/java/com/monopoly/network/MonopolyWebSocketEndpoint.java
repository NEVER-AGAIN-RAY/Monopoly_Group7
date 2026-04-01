package com.monopoly.network;

import com.monopoly.controller.GameController;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jakarta WebSocket 端点：把原生 Session 适配为 {@link ClientConnection} 并转交 {@link GameServer}。
 */
@ServerEndpoint("/ws")
public class MonopolyWebSocketEndpoint {

    private static final DefaultGameUpdateSubject SUBJECT = new DefaultGameUpdateSubject();
    private static final GameServer GAME_SERVER = new GameServer();
    private static final Map<Session, ClientConnection> ADAPTERS = new ConcurrentHashMap<>();

    static {
        GameController controller = new GameController(SUBJECT);
        GAME_SERVER.wireController(controller);
        GAME_SERVER.attachTo(SUBJECT);
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

    private static final class SessionClientConnection implements ClientConnection {
        private final Session session;

        private SessionClientConnection(Session session) {
            this.session = session;
        }

        @Override
        public boolean isOpen() {
            return session != null && session.isOpen();
        }

        @Override
        public void sendText(String text) throws IOException {
            if (!isOpen()) {
                throw new IOException("Session is closed");
            }
            session.getBasicRemote().sendText(text);
        }
    }
}
