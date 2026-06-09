package com.monopoly.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.protocol.MessageDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class DemoRoomService {

    static final String DEMO_SESSION_ID = "demo-pvp";
    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 5;

    private final SessionHub hub;
    private final MessageDispatcher dispatcher;
    private final Consumer<String> privateHandPusher;
    private final Map<ClientConnection, DemoSeat> seatsByConnection = new LinkedHashMap<>();
    private final Object lock = new Object();

    private boolean started;

    DemoRoomService(SessionHub hub, MessageDispatcher dispatcher, Consumer<String> privateHandPusher) {
        this.hub = hub;
        this.dispatcher = dispatcher;
        this.privateHandPusher = privateHandPusher;
    }

    void onClientDisconnected(ClientConnection client) {
        boolean changed;
        boolean notifyJoinedPlayers;
        synchronized (lock) {
            changed = seatsByConnection.remove(client) != null;
            notifyJoinedPlayers = changed && !started;
            if (notifyJoinedPlayers) {
                hub.sessionRegistry().unregister(client);
                reseatWaitingPlayers();
            }
        }
        if (notifyJoinedPlayers) {
            broadcastDemoRoomState();
            sendJoinedDemoRoomStatesWithAssignments();
        } else if (changed) {
            sendDemoRoomState();
        }
    }

    void handleJoinDemoRoom(ClientConnection from, JsonObject payload) {
        DemoSeat seat;
        synchronized (lock) {
            if (started) {
                sendDemoError(from, "Demo game has already started. Restart the server to run another demo.");
                return;
            }
            seat = seatsByConnection.get(from);
            if (seat == null) {
                if (seatsByConnection.size() >= MAX_PLAYERS) {
                    sendDemoError(from, "Demo room is full");
                    return;
                }
                seat = new DemoSeat(nextPlayerId(), displayName(payload));
                seatsByConnection.put(from, seat);
            } else {
                String displayName = displayName(payload);
                if (!displayName.isBlank()) {
                    seat.displayName = displayName;
                }
            }
            hub.sessionRegistry().register(from, seat.playerId);
            hub.sessionRegistry().bindSession(from, DEMO_SESSION_ID);
        }
        sendDemoRoomStateTo(from, seat.playerId);
        broadcastDemoRoomState();
    }

    void handleStartDemoRoom(ClientConnection from, JsonObject payload) {
        StartSessionRequest req = new StartSessionRequest();
        synchronized (lock) {
            DemoSeat starter = seatsByConnection.get(from);
            if (starter == null) {
                sendDemoError(from, "Join the demo room before starting");
                return;
            }
            if (started) {
                sendDemoRoomStateTo(from);
                return;
            }
            if (seatsByConnection.size() < MIN_PLAYERS) {
                sendDemoError(from, "At least 2 players are required to start the demo");
                return;
            }
            started = true;
            req.setSessionId(DEMO_SESSION_ID);
            req.setGameMode("DEMO_PVP");
            req.setPlayerCount(seatsByConnection.size());
            req.setRandomizeFirstPlayer(payload.has("randomizeFirstPlayer")
                    && payload.get("randomizeFirstPlayer").getAsBoolean());
        }
        SessionHub.SessionRuntime runtime = hub.getOrCreateSession(DEMO_SESSION_ID);
        runtime.controller().startNewSession(req);
        sendDemoRoomState();
        privateHandPusher.accept(DEMO_SESSION_ID);
    }

    void handleLeaveDemoRoom(ClientConnection from) {
        boolean changed;
        boolean notifyJoinedPlayers;
        synchronized (lock) {
            changed = seatsByConnection.remove(from) != null;
            notifyJoinedPlayers = changed && !started;
            if (notifyJoinedPlayers) {
                hub.sessionRegistry().unregister(from);
                reseatWaitingPlayers();
            }
        }
        if (notifyJoinedPlayers) {
            broadcastDemoRoomState();
            sendJoinedDemoRoomStatesWithAssignments();
        } else if (changed) {
            sendDemoRoomState();
        }
    }

    void sendDemoRoomStateTo(ClientConnection client) {
        sendDemoRoomStateTo(client, null);
    }

    private void sendDemoRoomStateTo(ClientConnection client, String assignedPlayerId) {
        if (client == null || !client.isOpen()) {
            return;
        }
        try {
            JsonObject payload = demoRoomPayload();
            if (assignedPlayerId != null && !assignedPlayerId.isBlank()) {
                payload.addProperty("assignedPlayerId", assignedPlayerId);
            }
            client.sendText(dispatcher.toJsonEnvelope("DEMO_ROOM_STATE", payload));
        } catch (IOException e) {
            hub.removeClient(client);
            hub.unregister(client);
        }
    }

    void sendDemoRoomState() {
        hub.broadcastToSession(DEMO_SESSION_ID, dispatcher.toJsonEnvelope("DEMO_ROOM_STATE", demoRoomPayload()));
    }

    void broadcastDemoRoomState() {
        hub.broadcast(dispatcher.toJsonEnvelope("DEMO_ROOM_STATE", demoRoomPayload()));
    }

    private void sendJoinedDemoRoomStatesWithAssignments() {
        for (DemoAssignment assignment : currentAssignments()) {
            sendDemoRoomStateTo(assignment.client(), assignment.playerId());
        }
    }

    JsonObject demoRoomPayload() {
        synchronized (lock) {
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionId", DEMO_SESSION_ID);
            payload.addProperty("started", started);
            payload.addProperty("minPlayers", MIN_PLAYERS);
            payload.addProperty("maxPlayers", MAX_PLAYERS);
            payload.addProperty("joinedPlayers", seatsByConnection.size());
            payload.addProperty("canStart", !started && seatsByConnection.size() >= MIN_PLAYERS);

            JsonArray players = new JsonArray();
            for (DemoSeat seat : orderedSeats()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("playerId", seat.playerId);
                obj.addProperty("displayName", seat.displayName);
                players.add(obj);
            }
            payload.add("players", players);
            return payload;
        }
    }

    private void sendDemoError(ClientConnection client, String error) {
        try {
            client.sendText(dispatcher.toJsonEnvelope("DEMO_ROOM_ERROR", dispatcher.operationResult(false, error)));
        } catch (IOException ignored) {
        }
    }

    private List<DemoSeat> orderedSeats() {
        return new ArrayList<>(seatsByConnection.values());
    }

    private List<DemoAssignment> currentAssignments() {
        synchronized (lock) {
            List<DemoAssignment> assignments = new ArrayList<>();
            for (Map.Entry<ClientConnection, DemoSeat> entry : seatsByConnection.entrySet()) {
                assignments.add(new DemoAssignment(entry.getKey(), entry.getValue().playerId));
            }
            return assignments;
        }
    }

    private void reseatWaitingPlayers() {
        int index = 1;
        for (Map.Entry<ClientConnection, DemoSeat> entry : seatsByConnection.entrySet()) {
            hub.sessionRegistry().unregister(entry.getKey());
            DemoSeat seat = entry.getValue();
            String previousPlayerId = seat.playerId;
            String newPlayerId = "human-" + index;
            seat.playerId = newPlayerId;
            if (seat.displayName == null || seat.displayName.isBlank() || seat.displayName.equals(previousPlayerId)) {
                seat.displayName = newPlayerId;
            }
            index += 1;
        }
        for (Map.Entry<ClientConnection, DemoSeat> entry : seatsByConnection.entrySet()) {
            hub.sessionRegistry().register(entry.getKey(), entry.getValue().playerId);
            hub.sessionRegistry().bindSession(entry.getKey(), DEMO_SESSION_ID);
        }
    }

    private String nextPlayerId() {
        for (int i = 1; i <= MAX_PLAYERS; i += 1) {
            String candidate = "human-" + i;
            boolean taken = seatsByConnection.values().stream()
                    .anyMatch(seat -> candidate.equals(seat.playerId));
            if (!taken) {
                return candidate;
            }
        }
        throw new IllegalStateException("Demo room is full");
    }

    private String displayName(JsonObject payload) {
        String value = dispatcher.getString(payload, "displayName", null);
        return value == null ? "" : value.trim();
    }

    private static final class DemoSeat {
        String playerId;
        String displayName;

        DemoSeat(String playerId, String displayName) {
            this.playerId = playerId;
            this.displayName = displayName == null || displayName.isBlank() ? playerId : displayName;
        }
    }

    private record DemoAssignment(ClientConnection client, String playerId) {
    }
}
