package com.monopoly.network;

import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayProtocolValidationTest {

    @Test
    void playMissingActionType_shouldReturnErrorEnvelope() {
        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        GameServer server = newServer(out, client);
        server.onMessage(client, "{\"type\":\"PLAY\",\"requestId\":\"r-1\",\"payload\":{\"cardId\":\"x\"}}");
        assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"ERROR\"")
                && s.contains("\"code\":\"PLAY_ACTION_TYPE_REQUIRED\"")
                && s.contains("\"requestId\":\"r-1\"")));
    }

    @Test
    void playMissingCardSelector_shouldReturnErrorEnvelope() {
        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        GameServer server = newServer(out, client);
        server.onMessage(client, "{\"type\":\"PLAY\",\"payload\":{\"actionType\":\"ACTION\"}}");
        assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"ERROR\"")
                && s.contains("\"code\":\"PLAY_CARD_SELECTOR_REQUIRED\"")));
    }

    private static GameServer newServer(List<String> out, ClientConnection client) {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        controller.startNewSession("play-validate");
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);
        server.onClientConnected(client);
        server.onMessage(client,
                "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"human-1\",\"sessionId\":\"play-validate\"}}");
        out.clear();
        return server;
    }

    private static ClientConnection recordingClient(List<String> sink) {
        return new ClientConnection() {
            private final String id = UUID.randomUUID().toString();

            @Override
            public String connectionId() {
                return id;
            }

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
