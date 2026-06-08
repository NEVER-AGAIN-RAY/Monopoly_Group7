package com.monopoly.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.protocol.MessageDispatcher;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCodeContractTest {

    @Test
    void errorEnvelope_shouldContainCodeMessageRequestId() {
        MessageDispatcher dispatcher = new MessageDispatcher();
        String json = dispatcher.toErrorEnvelope("X_CODE", "bad request", "req-9");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("ERROR", root.get("type").getAsString());
        JsonObject payload = root.getAsJsonObject("payload");
        assertEquals("X_CODE", payload.get("code").getAsString());
        assertEquals("bad request", payload.get("message").getAsString());
        assertEquals("req-9", payload.get("requestId").getAsString());
    }

    @Test
    void playStateViolation_shouldMapToUnifiedErrorCode() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        controller.startNewSession("state-violation");
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);
        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        server.onClientConnected(client);
        server.onMessage(client,
                "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"human-1\",\"sessionId\":\"state-violation\"}}");
        out.clear();

        // 初始阶段是 DRAW，此时出牌会触发状态错误。
        server.onMessage(client, "{\"type\":\"PLAY\",\"requestId\":\"r-state\",\"payload\":{\"actionType\":\"DEPOSIT\",\"handIndex\":0}}");
        assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"ERROR\"")
                && s.contains("\"code\":\"PLAY_STATE_VIOLATION\"")
                && s.contains("\"requestId\":\"r-state\"")));
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
