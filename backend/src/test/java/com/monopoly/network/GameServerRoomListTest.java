package com.monopoly.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.persistence.GameSessionMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerRoomListTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void roomListReportsStartedSessionsAndConnectionCounts() {
        GameServer server = new GameServer();
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        ClientConnection host = recordingClient(out);
        server.onClientConnected(host);

        server.onMessage(host, "{\"type\":\"AUTH\",\"payload\":{\"sessionId\":\"lobby-a\",\"playerId\":\"pvp-1\"}}");
        out.clear();
        server.onMessage(host, "{\"type\":\"START_SESSION\",\"payload\":{"
                + "\"sessionId\":\"lobby-a\","
                + "\"gameMode\":\"CUSTOM\","
                + "\"customLineup\":\"human,hard,student\","
                + "\"randomizeFirstPlayer\":false"
                + "}}");
        out.clear();

        server.onMessage(host, "{\"type\":\"ROOM_LIST\",\"payload\":{}}");

        JsonObject room = latestRoom(out, "lobby-a");
        assertEquals(3, room.get("seatCount").getAsInt());
        assertEquals(1, room.get("humanSeats").getAsInt());
        assertEquals(1, room.get("connectedPlayers").getAsInt());
        assertTrue(room.get("started").getAsBoolean());
    }

    @Test
    void lobbyRejectsDuplicateNicknameAndSeatsJoinedNicknames() {
        GameServer server = new GameServer();
        List<String> hostOut = Collections.synchronizedList(new ArrayList<>());
        List<String> guestOut = Collections.synchronizedList(new ArrayList<>());
        List<String> duplicateOut = Collections.synchronizedList(new ArrayList<>());
        ClientConnection host = recordingClient(hostOut);
        ClientConnection guest = recordingClient(guestOut);
        ClientConnection duplicate = recordingClient(duplicateOut);
        server.onClientConnected(host);
        server.onClientConnected(guest);
        server.onClientConnected(duplicate);

        server.onMessage(host, "{\"type\":\"CREATE_ROOM\",\"payload\":{\"sessionId\":\"lobby-b\",\"nickname\":\"房主\"}}");
        server.onMessage(guest, "{\"type\":\"JOIN_ROOM\",\"payload\":{\"sessionId\":\"lobby-b\",\"nickname\":\"客人\"}}");
        server.onMessage(duplicate, "{\"type\":\"JOIN_ROOM\",\"payload\":{\"sessionId\":\"lobby-b\",\"nickname\":\"客人\"}}");

        assertTrue(duplicateOut.stream().anyMatch(s -> s.contains("Nickname is already taken")));

        server.onMessage(host, "{\"type\":\"ROOM_SET_SEAT\",\"payload\":{"
                + "\"sessionId\":\"lobby-b\","
                + "\"seatIndex\":1,"
                + "\"role\":\"human\","
                + "\"nickname\":\"客人\""
                + "}}");
        server.onMessage(host, "{\"type\":\"ROOM_SET_SEAT\",\"payload\":{"
                + "\"sessionId\":\"lobby-b\","
                + "\"seatIndex\":2,"
                + "\"role\":\"student\""
                + "}}");

        JsonObject state = latestRoomState(hostOut);
        JsonArray seats = state.getAsJsonArray("seats");
        assertEquals("房主", seats.get(0).getAsJsonObject().get("nickname").getAsString());
        assertEquals("客人", seats.get(1).getAsJsonObject().get("nickname").getAsString());
        assertEquals("Student Bot 3", seats.get(2).getAsJsonObject().get("nickname").getAsString());
    }

    private static JsonObject latestRoom(List<String> messages, String sessionId) {
        List<String> copy;
        synchronized (messages) {
            copy = new ArrayList<>(messages);
        }
        for (int i = copy.size() - 1; i >= 0; i -= 1) {
            String message = copy.get(i);
            if (!message.contains("\"type\":\"ROOM_LIST_RESULT\"")) {
                continue;
            }
            JsonObject root = JsonParser.parseString(message).getAsJsonObject();
            JsonArray rooms = root.getAsJsonObject("payload").getAsJsonArray("rooms");
            for (int j = 0; j < rooms.size(); j += 1) {
                JsonObject room = rooms.get(j).getAsJsonObject();
                if (sessionId.equals(room.get("sessionId").getAsString())) {
                    return room;
                }
            }
        }
        throw new AssertionError("room not found: " + sessionId);
    }

    private static JsonObject latestRoomState(List<String> messages) {
        List<String> copy;
        synchronized (messages) {
            copy = new ArrayList<>(messages);
        }
        for (int i = copy.size() - 1; i >= 0; i -= 1) {
            String message = copy.get(i);
            if (message.contains("\"type\":\"ROOM_STATE\"")) {
                return JsonParser.parseString(message).getAsJsonObject().getAsJsonObject("payload");
            }
        }
        throw new AssertionError("ROOM_STATE not found");
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
