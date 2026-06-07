package com.monopoly.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerProtocolGuardTest {

    @Test
    void ping_shouldReturnPongEnvelope() {
        List<String> out = new ArrayList<>();
        GameServer server = new GameServer();
        ClientConnection client = recordingClient(out);

        server.onMessage(client, "{\"type\":\"PING\",\"payload\":{}}");

        JsonObject root = onlyMessage(out);
        assertEquals("PONG", root.get("type").getAsString());
        assertTrue(root.get("payload").isJsonObject());
    }

    @Test
    void authWithoutPlayerId_shouldReturnFailedAuthResult() {
        List<String> out = new ArrayList<>();
        GameServer server = new GameServer();
        ClientConnection client = recordingClient(out);

        server.onMessage(client, "{\"type\":\"AUTH\",\"payload\":{}}");

        JsonObject root = onlyMessage(out);
        assertEquals("AUTH_RESULT", root.get("type").getAsString());
        JsonObject payload = root.getAsJsonObject("payload");
        assertEquals(false, payload.get("ok").getAsBoolean());
        assertTrue(payload.get("error").getAsString().contains("playerId"));
    }

    @Test
    void playBeforeSession_shouldReturnSessionNotFoundErrorWithRequestId() {
        List<String> out = new ArrayList<>();
        GameServer server = new GameServer();
        ClientConnection client = recordingClient(out);

        server.onMessage(client,
                "{\"type\":\"PLAY\",\"requestId\":\"r-no-session\",\"payload\":{\"actionType\":\"DEPOSIT\",\"handIndex\":0}}");

        JsonObject error = onlyMessage(out);
        assertError(error, "SESSION_NOT_FOUND", "r-no-session");
    }

    @Test
    void responsePassBeforeSession_shouldReturnSessionNotFoundErrorWithPayloadRequestId() {
        List<String> out = new ArrayList<>();
        GameServer server = new GameServer();
        ClientConnection client = recordingClient(out);

        server.onMessage(client,
                "{\"type\":\"RESPONSE_PASS\",\"payload\":{\"requestId\":\"r-pass\",\"actingPlayerId\":\"p2\"}}");

        JsonObject error = onlyMessage(out);
        assertError(error, "SESSION_NOT_FOUND", "r-pass");
    }

    @Test
    void actionOptionsMissingCard_shouldReturnActionOptionsBadError() {
        List<String> out = new ArrayList<>();
        GameServer server = newStartedServer(out, "guard-action-options");
        ClientConnection client = recordingClient(out);
        out.clear();

        server.onMessage(client,
                "{\"type\":\"ACTION_OPTIONS\",\"requestId\":\"r-options\",\"payload\":{\"playerId\":\"human-1\"}}");

        JsonObject error = findMessage(out, "ERROR");
        assertError(error, "ACTION_OPTIONS_BAD", "r-options");
    }

    @Test
    void playOptionsMissingCard_shouldReturnPlayOptionsBadError() {
        List<String> out = new ArrayList<>();
        GameServer server = newStartedServer(out, "guard-play-options");
        ClientConnection client = recordingClient(out);
        out.clear();

        server.onMessage(client,
                "{\"type\":\"PLAY_OPTIONS\",\"requestId\":\"r-play-options\",\"payload\":{\"playerId\":\"human-1\"}}");

        JsonObject error = findMessage(out, "ERROR");
        assertError(error, "PLAY_OPTIONS_BAD", "r-play-options");
    }

    private static GameServer newStartedServer(List<String> out, String sessionId) {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        controller.startNewSession(sessionId);
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);
        server.onClientConnected(recordingClient(out));
        return server;
    }

    private static void assertError(JsonObject root, String code, String requestId) {
        assertEquals("ERROR", root.get("type").getAsString());
        JsonObject payload = root.getAsJsonObject("payload");
        assertEquals(code, payload.get("code").getAsString());
        assertEquals(requestId, payload.get("requestId").getAsString());
    }

    private static JsonObject onlyMessage(List<String> out) {
        assertEquals(1, out.size(), out::toString);
        return JsonParser.parseString(out.get(0)).getAsJsonObject();
    }

    private static JsonObject findMessage(List<String> out, String type) {
        return out.stream()
                .map(raw -> JsonParser.parseString(raw).getAsJsonObject())
                .filter(root -> type.equals(root.get("type").getAsString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + type + " in " + out));
    }

    private static ClientConnection recordingClient(List<String> sink) {
        return new ClientConnection() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void sendText(String text) throws IOException {
                sink.add(text);
            }
        };
    }
}
