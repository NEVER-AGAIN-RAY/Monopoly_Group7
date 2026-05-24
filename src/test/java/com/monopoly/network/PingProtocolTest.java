package com.monopoly.network;

import com.monopoly.network.connection.ClientConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PingProtocolTest {

    @Test
    void pingDoesNotRequireStartedSession() {
        List<String> out = new ArrayList<>();
        GameServer server = new GameServer();
        server.onClientConnected(recordingClient(out));

        server.onMessage(recordingClient(out), "{\"type\":\"PING\",\"payload\":{}}");

        assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"PONG\"")));
        assertTrue(out.stream().noneMatch(s -> s.contains("SESSION_NOT_FOUND")));
    }

    private static ClientConnection recordingClient(List<String> sink) {
        return new ClientConnection() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void sendText(String text) {
                sink.add(text);
            }
        };
    }
}
