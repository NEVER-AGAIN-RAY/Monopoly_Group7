package com.monopoly.network.endpoint;

import org.glassfish.tyrus.server.Server;

/**
 * Tyrus WebSocket server (ws://localhost:8025/ws).
 */
public final class WsServerMain {

    private WsServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("monopoly.ws.port", 8025);
        if (System.getProperty("monopoly.ai.decisionDelayMs") == null) {
            System.setProperty("monopoly.ai.decisionDelayMs", "650");
        }
        Server server = new Server("0.0.0.0", port, "/", null, MonopolyWebSocketEndpoint.class);
        try {
            server.start();
            System.out.println("Monopoly WebSocket running at ws://localhost:" + port + "/ws");
            System.out.println("Press Ctrl+C to stop.");
            Thread.currentThread().join();
        } finally {
            server.stop();
        }
    }
}
