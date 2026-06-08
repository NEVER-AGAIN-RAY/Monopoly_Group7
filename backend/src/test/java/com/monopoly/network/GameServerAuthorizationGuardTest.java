package com.monopoly.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerAuthorizationGuardTest {

    @Test
    void drawWithoutBoundPlayer_shouldReturnUnauthorizedAndNotAdvanceState() {
        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        GameServer server = startedHvmServer(client, out, "auth-draw");

        out.clear();
        server.onMessage(client, "{\"type\":\"DRAW\",\"requestId\":\"r-draw\",\"payload\":{\"count\":2}}");

        JsonObject error = findMessage(out, "ERROR");
        assertError(error, "UNAUTHORIZED", "r-draw");
        assertFalse(out.stream().anyMatch(s -> s.contains("\"phase\":\"DRAW\"")));
    }

    @Test
    void endTurnAsDifferentBoundPlayer_shouldReturnUnauthorized() {
        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        GameServer server = startedHvmServer(client, out, "auth-end-turn");
        server.onMessage(client,
                "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"human-2\",\"sessionId\":\"auth-end-turn\"}}");

        out.clear();
        server.onMessage(client, "{\"type\":\"END_TURN\",\"requestId\":\"r-end\",\"payload\":{}}");

        JsonObject error = findMessage(out, "ERROR");
        assertError(error, "UNAUTHORIZED", "r-end");
    }

    @Test
    void saveVoteAck_shouldIgnoreSpoofedPayloadPlayerId() {
        List<String> ownerOut = new ArrayList<>();
        List<String> attackerOut = new ArrayList<>();
        ClientConnection owner = recordingClient(ownerOut);
        ClientConnection attacker = recordingClient(attackerOut);
        GameServer server = startedHvmServer(owner, ownerOut, "auth-save");
        server.onClientConnected(attacker);
        server.onMessage(owner,
                "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"human-1\",\"sessionId\":\"auth-save\"}}");
        ownerOut.clear();

        server.onMessage(owner, "{\"type\":\"SAVE_GAME\",\"payload\":{\"requestId\":\"save-guard\"}}");
        server.onMessage(attacker,
                "{\"type\":\"SAVE_GAME_ACK\",\"payload\":{\"sessionId\":\"auth-save\",\"requestId\":\"save-guard\",\"playerId\":\"human-1\"}}");

        JsonObject error = findMessage(attackerOut, "SAVE_GAME_RESULT");
        JsonObject payload = error.getAsJsonObject("payload");
        assertEquals(false, payload.get("ok").getAsBoolean());
        assertTrue(payload.get("error").getAsString().contains("Invalid voting player"));
        assertFalse(ownerOut.stream().anyMatch(s -> s.contains("\"mementoJson\"")));
    }

    @Test
    void disconnectAfterRoomStarted_shouldKeepLobbyMembershipForReconnect() {
        List<String> hostOut = new ArrayList<>();
        List<String> observerOut = new ArrayList<>();
        ClientConnection host = recordingClient(hostOut);
        ClientConnection observer = recordingClient(observerOut);
        GameServer server = new GameServer();
        server.onClientConnected(host);
        server.onClientConnected(observer);

        server.onMessage(host,
                "{\"type\":\"CREATE_ROOM\",\"payload\":{\"sessionId\":\"started-room\",\"nickname\":\"Host\"}}");
        server.onMessage(host,
                "{\"type\":\"ROOM_SET_SEAT\",\"payload\":{\"sessionId\":\"started-room\","
                        + "\"seatIndex\":1,\"role\":\"strong\"}}");
        server.onMessage(host,
                "{\"type\":\"START_ROOM\",\"payload\":{\"sessionId\":\"started-room\",\"randomizeFirstPlayer\":false}}");
        hostOut.clear();
        observerOut.clear();

        server.onClientDisconnected(host);
        server.onMessage(observer, "{\"type\":\"ROOM_LIST\",\"payload\":{}}");

        JsonObject list = findMessage(observerOut, "ROOM_LIST_RESULT");
        JsonObject room = findRoom(list.getAsJsonObject("payload").getAsJsonArray("rooms"), "started-room");
        assertEquals(1, room.get("connectedPlayers").getAsInt());
        assertEquals(0, room.get("connections").getAsInt());
        assertTrue(room.get("started").getAsBoolean());
    }

    private static GameServer startedHvmServer(ClientConnection client, List<String> out, String sessionId) {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        controller.startNewSession(sessionId);
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);
        server.onClientConnected(client);
        out.clear();
        return server;
    }

    private static JsonObject findRoom(JsonArray rooms, String sessionId) {
        for (var el : rooms) {
            if (el.isJsonObject()) {
                JsonObject room = el.getAsJsonObject();
                if (sessionId.equals(room.get("sessionId").getAsString())) {
                    return room;
                }
            }
        }
        throw new AssertionError("Missing room " + sessionId + " in " + rooms);
    }

    private static void assertError(JsonObject root, String code, String requestId) {
        assertEquals("ERROR", root.get("type").getAsString());
        JsonObject payload = root.getAsJsonObject("payload");
        assertEquals(code, payload.get("code").getAsString());
        assertEquals(requestId, payload.get("requestId").getAsString());
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
