package com.monopoly.network;

import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayProtocolValidationTest {

    @Test
    void playMissingActionType_shouldReturnErrorEnvelope() {
        List<String> out = new ArrayList<>();
        GameServer server = newServer(out);
        server.onMessage(recordingClient(out), "{\"type\":\"PLAY\",\"requestId\":\"r-1\",\"payload\":{\"cardId\":\"x\"}}");
        assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"ERROR\"")
                && s.contains("\"code\":\"PLAY_ACTION_TYPE_REQUIRED\"")
                && s.contains("\"requestId\":\"r-1\"")));
    }

    @Test
    void playMissingCardSelector_shouldReturnErrorEnvelope() {
        List<String> out = new ArrayList<>();
        GameServer server = newServer(out);
        server.onMessage(recordingClient(out), "{\"type\":\"PLAY\",\"payload\":{\"actionType\":\"ACTION\"}}");
        assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"ERROR\"")
                && s.contains("\"code\":\"PLAY_CARD_SELECTOR_REQUIRED\"")));
    }

    private static GameServer newServer(List<String> out) {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        controller.startNewSession("play-validate");
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);
        server.onClientConnected(recordingClient(out));
        return server;
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
