package com.monopoly.network;

import com.monopoly.network.connection.ClientConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerMultiSessionIsolationTest {

    @Test
    void stateUpdatesStayWithinStartedSession() {
        GameServer server = new GameServer();
        List<String> roomA = Collections.synchronizedList(new ArrayList<>());
        List<String> roomB = Collections.synchronizedList(new ArrayList<>());
        ClientConnection clientA = recordingClient(roomA);
        ClientConnection clientB = recordingClient(roomB);
        server.onClientConnected(clientA);
        server.onClientConnected(clientB);

        server.onMessage(clientA, startSessionJson("room-a"));

        assertTrue(contains(roomA, "\"sessionId\":\"room-a\""));
        assertFalse(contains(roomB, "\"sessionId\":\"room-a\""));

        roomA.clear();
        roomB.clear();
        server.onMessage(clientB, startSessionJson("room-b"));

        assertTrue(contains(roomB, "\"sessionId\":\"room-b\""));
        assertFalse(contains(roomA, "\"sessionId\":\"room-b\""));

        roomA.clear();
        roomB.clear();
        server.onMessage(clientA, "{\"type\":\"DRAW\",\"payload\":{\"sessionId\":\"room-a\",\"count\":2}}");

        assertTrue(contains(roomA, "\"phase\":\"DRAW\""));
        assertTrue(contains(roomA, "\"sessionId\":\"room-a\""));
        assertFalse(contains(roomB, "\"sessionId\":\"room-a\""));
        assertFalse(contains(roomB, "\"phase\":\"DRAW\""));
    }

    private static String startSessionJson(String sessionId) {
        return "{"
                + "\"type\":\"START_SESSION\","
                + "\"payload\":{"
                + "\"sessionId\":\"" + sessionId + "\","
                + "\"playerCount\":2,"
                + "\"gameMode\":\"PVP\","
                + "\"randomizeFirstPlayer\":false"
                + "}"
                + "}";
    }

    private static boolean contains(List<String> messages, String needle) {
        synchronized (messages) {
            return messages.stream().anyMatch(s -> s.contains(needle));
        }
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
