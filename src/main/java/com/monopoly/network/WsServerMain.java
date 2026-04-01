package com.monopoly.network;

import org.glassfish.tyrus.server.Server;

/**
 * 嵌入式 WebSocket 启动入口。
 */
public final class WsServerMain {

    private WsServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8025;
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
