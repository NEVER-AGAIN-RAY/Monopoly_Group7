package com.monopoly;

import com.monopoly.controller.GameController;
import com.monopoly.network.GameServer;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

/**
 * Composition root: wires DefaultGameUpdateSubject, GameController (Facade), and GameServer (Observer).
 * Production WebSocket startup uses WsServerMain.
 */
public final class ServerBootstrap {

    private ServerBootstrap() {
    }

    public static void main(String[] args) {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        GameServer server = new GameServer();
        server.attachTo(subject);
        server.wireController(controller);

        controller.startNewSession("demo-session");
    }
}
