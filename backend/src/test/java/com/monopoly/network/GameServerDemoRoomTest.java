package com.monopoly.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.network.connection.ClientConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerDemoRoomTest {

    @Test
    void joinDemoRoomAssignsHumanIdsInConnectionOrder() {
        GameServer server = new GameServer();
        List<String> firstOut = Collections.synchronizedList(new ArrayList<>());
        List<String> secondOut = Collections.synchronizedList(new ArrayList<>());
        ClientConnection first = recordingClient(firstOut);
        ClientConnection second = recordingClient(secondOut);
        server.onClientConnected(first);
        server.onClientConnected(second);

        server.onMessage(first, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        server.onMessage(second, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");

        assertEquals("human-1", latestAssignedDemoState(firstOut).get("assignedPlayerId").getAsString());
        assertEquals("human-2", latestAssignedDemoState(secondOut).get("assignedPlayerId").getAsString());
        JsonObject room = latestDemoState(secondOut);
        assertEquals("demo-pvp", room.get("sessionId").getAsString());
        assertEquals(2, room.get("joinedPlayers").getAsInt());
        assertTrue(room.get("canStart").getAsBoolean());
    }

    @Test
    void startDemoRoomStartsPvpWithJoinedHumans() {
        GameServer server = new GameServer();
        List<String> firstOut = Collections.synchronizedList(new ArrayList<>());
        List<String> secondOut = Collections.synchronizedList(new ArrayList<>());
        ClientConnection first = recordingClient(firstOut);
        ClientConnection second = recordingClient(secondOut);
        server.onClientConnected(first);
        server.onClientConnected(second);
        server.onMessage(first, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        server.onMessage(second, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        firstOut.clear();
        secondOut.clear();

        server.onMessage(second, "{\"type\":\"START_DEMO_ROOM\",\"payload\":{\"randomizeFirstPlayer\":false}}");

        assertTrue(firstOut.stream().anyMatch(msg -> msg.contains("\"type\":\"STATE_UPDATE\"")));
        assertTrue(firstOut.stream().anyMatch(msg -> msg.contains("\"playerId\":\"human-1\"")));
        assertTrue(secondOut.stream().anyMatch(msg -> msg.contains("\"playerId\":\"human-2\"")));
        JsonObject room = latestDemoState(firstOut);
        assertTrue(room.get("started").getAsBoolean());
        assertEquals(2, room.get("joinedPlayers").getAsInt());
    }

    @Test
    void startRequiresAtLeastTwoJoinedPlayers() {
        GameServer server = new GameServer();
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        ClientConnection client = recordingClient(out);
        server.onClientConnected(client);
        server.onMessage(client, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        out.clear();

        server.onMessage(client, "{\"type\":\"START_DEMO_ROOM\",\"payload\":{}}");

        assertTrue(out.stream().anyMatch(msg -> msg.contains("\"type\":\"DEMO_ROOM_ERROR\"")
                && msg.contains("At least 2 players")));
    }

    @Test
    void demoRoomCapsAtFivePlayers() {
        GameServer server = new GameServer();
        List<String> overflowOut = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 5; i += 1) {
            ClientConnection client = recordingClient(Collections.synchronizedList(new ArrayList<>()));
            server.onClientConnected(client);
            server.onMessage(client, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        }
        ClientConnection overflow = recordingClient(overflowOut);
        server.onClientConnected(overflow);

        server.onMessage(overflow, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");

        assertTrue(overflowOut.stream().anyMatch(msg -> msg.contains("\"type\":\"DEMO_ROOM_ERROR\"")
                && msg.contains("Demo room is full")));
    }

    @Test
    void leavingBeforeStartReseatsRemainingPlayersForTheDemoGame() {
        GameServer server = new GameServer();
        List<String> firstOut = Collections.synchronizedList(new ArrayList<>());
        List<String> secondOut = Collections.synchronizedList(new ArrayList<>());
        List<String> thirdOut = Collections.synchronizedList(new ArrayList<>());
        ClientConnection first = recordingClient(firstOut);
        ClientConnection second = recordingClient(secondOut);
        ClientConnection third = recordingClient(thirdOut);
        server.onClientConnected(first);
        server.onClientConnected(second);
        server.onClientConnected(third);
        server.onMessage(first, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        server.onMessage(second, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        server.onMessage(third, "{\"type\":\"JOIN_DEMO_ROOM\",\"payload\":{}}");
        secondOut.clear();
        thirdOut.clear();

        server.onMessage(first, "{\"type\":\"LEAVE_DEMO_ROOM\",\"payload\":{}}");

        assertEquals("human-1", latestAssignedDemoState(secondOut).get("assignedPlayerId").getAsString());
        assertEquals("human-2", latestAssignedDemoState(thirdOut).get("assignedPlayerId").getAsString());
        server.onMessage(second, "{\"type\":\"START_DEMO_ROOM\",\"payload\":{\"randomizeFirstPlayer\":false}}");

        assertTrue(secondOut.stream().anyMatch(msg -> msg.contains("\"type\":\"STATE_UPDATE\"")));
        assertTrue(secondOut.stream().anyMatch(msg -> msg.contains("\"playerId\":\"human-1\"")));
        assertTrue(thirdOut.stream().anyMatch(msg -> msg.contains("\"playerId\":\"human-2\"")));
    }

    private static JsonObject latestDemoState(List<String> messages) {
        List<String> copy;
        synchronized (messages) {
            copy = new ArrayList<>(messages);
        }
        for (int i = copy.size() - 1; i >= 0; i -= 1) {
            String message = copy.get(i);
            if (message.contains("\"type\":\"DEMO_ROOM_STATE\"")) {
                return JsonParser.parseString(message).getAsJsonObject().getAsJsonObject("payload");
            }
        }
        throw new AssertionError("DEMO_ROOM_STATE not found in " + copy);
    }

    private static JsonObject latestAssignedDemoState(List<String> messages) {
        List<String> copy;
        synchronized (messages) {
            copy = new ArrayList<>(messages);
        }
        for (int i = copy.size() - 1; i >= 0; i -= 1) {
            String message = copy.get(i);
            if (message.contains("\"type\":\"DEMO_ROOM_STATE\"")
                    && message.contains("\"assignedPlayerId\"")) {
                return JsonParser.parseString(message).getAsJsonObject().getAsJsonObject("payload");
            }
        }
        throw new AssertionError("assigned DEMO_ROOM_STATE not found in " + copy);
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
