package com.monopoly.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.protocol.MessageDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class LobbyService {

    private final SessionHub hub;
    private final MessageDispatcher dispatcher;
    private final Consumer<String> privateHandPusher;
    private final ConcurrentHashMap<String, LobbyRoom> lobbyRooms = new ConcurrentHashMap<>();

    LobbyService(SessionHub hub, MessageDispatcher dispatcher, Consumer<String> privateHandPusher) {
        this.hub = hub;
        this.dispatcher = dispatcher;
        this.privateHandPusher = privateHandPusher;
    }

    void onClientDisconnected(ClientConnection client) {
        for (LobbyRoom room : lobbyRooms.values()) {
            removeMemberFromRoom(room, playerKey(client));
        }
        broadcastRoomList();
    }

    void handleCreateRoom(ClientConnection from, JsonObject payload) {
        String sessionId = SessionHub.normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        String nickname = normalizeNickname(dispatcher.getString(payload, "nickname", null));
        if (nickname.isBlank()) {
            sendRoomError(from, "Nickname cannot be empty");
            return;
        }
        LobbyRoom room = new LobbyRoom(sessionId);
        synchronized (room) {
            room.hostPlayerKey = playerKey(from);
            room.addMember(playerKey(from), nickname);
            room.seats.get(0).humanPlayerKey = playerKey(from);
            room.seats.get(0).role = "human";
            room.seats.get(1).role = "strong";
        }
        LobbyRoom existing = lobbyRooms.putIfAbsent(sessionId, room);
        if (existing != null) {
            sendRoomError(from, "Room ID already exists");
            return;
        }
        hub.sessionRegistry().register(from, playerKey(from));
        hub.sessionRegistry().bindSession(from, sessionId);
        sendRoomStateToSession(sessionId);
        broadcastRoomList();
    }

    void handleJoinRoom(ClientConnection from, JsonObject payload) {
        String sessionId = SessionHub.normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        String nickname = normalizeNickname(dispatcher.getString(payload, "nickname", null));
        if (nickname.isBlank()) {
            sendRoomError(from, "Nickname cannot be empty");
            return;
        }
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            sendRoomError(from, "Room does not exist");
            return;
        }
        synchronized (room) {
            if (room.started) {
                sendRoomError(from, "The room has already started");
                return;
            }
            if (room.nicknameExists(nickname, playerKey(from))) {
                sendRoomError(from, "Nickname is already taken");
                return;
            }
            room.addMember(playerKey(from), nickname);
        }
        hub.sessionRegistry().register(from, playerKey(from));
        hub.sessionRegistry().bindSession(from, sessionId);
        sendRoomStateToSession(sessionId);
        broadcastRoomList();
    }

    void handleSetRoomSeat(ClientConnection from, JsonObject payload) {
        String sessionId = SessionHub.normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            sendRoomError(from, "Room does not exist");
            return;
        }
        synchronized (room) {
            if (!isRoomHost(room, from)) {
                sendRoomError(from, "Only the host can adjust seats");
                return;
            }
            if (room.started) {
                sendRoomError(from, "The room has already started; seats cannot be adjusted");
                return;
            }
            int seatIndex = dispatcher.getInt(payload, "seatIndex", -1);
            if (seatIndex < 0 || seatIndex >= room.seats.size()) {
                sendRoomError(from, "Invalid seat index");
                return;
            }
            LobbySeat seat = room.seats.get(seatIndex);
            String role = normalizeLobbyRole(dispatcher.getString(payload, "role", "empty"));
            String nickname = normalizeNickname(dispatcher.getString(payload, "nickname", null));
            if ("human".equals(role)) {
                if (nickname.isBlank()) {
                    sendRoomError(from, "A human seat must select the nickname of a player who has joined");
                    return;
                }
                String memberKey = room.memberKeyByNickname(nickname);
                if (memberKey == null) {
                    sendRoomError(from, "That nickname has not joined the room");
                    return;
                }
                for (int i = 0; i < room.seats.size(); i++) {
                    LobbySeat other = room.seats.get(i);
                    if (i != seatIndex && memberKey.equals(other.humanPlayerKey)) {
                        other.role = "empty";
                        other.humanPlayerKey = null;
                        other.botName = "";
                        other.inGamePlayerId = "";
                    }
                }
                seat.role = "human";
                seat.humanPlayerKey = memberKey;
                seat.botName = "";
                seat.inGamePlayerId = "";
            } else {
                seat.role = role;
                seat.humanPlayerKey = null;
                seat.botName = defaultBotName(role, seatIndex + 1);
                seat.inGamePlayerId = "";
            }
        }
        sendRoomStateToSession(sessionId);
        broadcastRoomList();
    }

    void handleLeaveRoom(ClientConnection from, JsonObject payload) {
        String sessionId = SessionHub.normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            return;
        }
        removeMemberFromRoom(room, playerKey(from));
        broadcastRoomList();
    }

    void handleStartRoom(ClientConnection from, JsonObject payload) {
        String sessionId = SessionHub.normalizeSessionId(dispatcher.getString(payload, "sessionId", null));
        LobbyRoom room = lobbyRooms.get(sessionId);
        if (room == null) {
            sendRoomError(from, "Room does not exist");
            return;
        }
        StartSessionRequest req = new StartSessionRequest();
        synchronized (room) {
            if (!isRoomHost(room, from)) {
                sendRoomError(from, "Only the host can start the game");
                return;
            }
            List<String> roles = room.activeSeatRoles();
            if (roles.size() < 2) {
                sendRoomError(from, "At least 2 valid seats are required");
                return;
            }
            if (roles.stream().noneMatch("human"::equals)) {
                sendRoomError(from, "At least 1 human player is required");
                return;
            }
            room.started = true;
            req.setSessionId(sessionId);
            req.setGameMode("CUSTOM");
            req.setPlayerRoles(roles);
            req.setPlayerCount(roles.size());
            req.setDisplayNames(room.activeSeatNames());
            req.setRandomizeFirstPlayer(payload.has("randomizeFirstPlayer")
                    && payload.get("randomizeFirstPlayer").getAsBoolean());
        }
        SessionHub.SessionRuntime runtime = hub.getOrCreateSession(sessionId);
        bindHumanSeatConnections(room);
        runtime.controller().startNewSession(req);
        sendRoomStateToSession(sessionId);
        privateHandPusher.accept(sessionId);
        broadcastRoomList();
    }

    void sendRoomList(ClientConnection client) {
        if (client == null || !client.isOpen()) {
            return;
        }
        try {
            client.sendText(dispatcher.toJsonEnvelope("ROOM_LIST_RESULT", roomListPayload()));
        } catch (IOException e) {
            hub.removeClient(client);
            hub.unregister(client);
        }
    }

    void broadcastRoomList() {
        if (hub.clients().isEmpty()) {
            return;
        }
        hub.broadcast(dispatcher.toJsonEnvelope("ROOM_LIST_RESULT", roomListPayload()));
    }

    JsonObject roomListPayload() {
        JsonObject payload = new JsonObject();
        JsonArray rooms = new JsonArray();
        List<LobbyRoom> lobbies = new ArrayList<>(lobbyRooms.values());
        lobbies.sort(Comparator.comparing(room -> room.sessionId));
        for (LobbyRoom lobby : lobbies) {
            rooms.add(lobbyListObject(lobby));
        }
        for (SessionHub.SessionRuntime runtime : hub.sessionRuntimesSnapshot()) {
            GameController controller = runtime.controller();
            if (controller == null) {
                continue;
            }
            String sessionId = SessionHub.normalizeSessionId(runtime.sessionId());
            if (lobbyRooms.containsKey(sessionId)) {
                continue;
            }
            int seatCount = controller.getSessionPlayersView().size();
            long humanSeats = controller.getSessionPlayersView().stream()
                    .filter(HumanPlayer.class::isInstance)
                    .count();
            JsonObject room = new JsonObject();
            room.addProperty("sessionId", sessionId);
            room.addProperty("seatCount", seatCount);
            room.addProperty("humanSeats", humanSeats);
            room.addProperty("connectedPlayers", hub.sessionRegistry().playerIdsInSession(sessionId).size());
            room.addProperty("connections", hub.sessionRegistry().connectionsInSession(sessionId).size());
            room.addProperty("started", seatCount > 0);
            room.addProperty("currentPlayerId", controller.getCurrentPlayer() == null
                    ? "" : controller.getCurrentPlayer().getPlayerId());
            rooms.add(room);
        }
        payload.add("rooms", rooms);
        payload.addProperty("serverTimeEpochMs", System.currentTimeMillis());
        return payload;
    }

    JsonObject lobbyListObject(LobbyRoom lobby) {
        synchronized (lobby) {
            JsonObject room = new JsonObject();
            room.addProperty("sessionId", lobby.sessionId);
            room.addProperty("seatCount", lobby.activeSeatCount());
            room.addProperty("humanSeats", lobby.humanSeatCount());
            room.addProperty("connectedPlayers", lobby.members.size());
            room.addProperty("connections", hub.sessionRegistry().connectionsInSession(lobby.sessionId).size());
            room.addProperty("started", lobby.started);
            room.addProperty("hostNickname", lobby.nicknameOf(lobby.hostPlayerKey));
            room.addProperty("currentPlayerId", "");
            return room;
        }
    }

    void sendRoomStateToSession(String sessionId) {
        LobbyRoom room = lobbyRooms.get(SessionHub.normalizeSessionId(sessionId));
        if (room == null) {
            return;
        }
        hub.broadcastToSession(room.sessionId, dispatcher.toJsonEnvelope("ROOM_STATE", roomStatePayload(room)));
    }

    JsonObject roomStatePayload(LobbyRoom room) {
        synchronized (room) {
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionId", room.sessionId);
            payload.addProperty("started", room.started);
            payload.addProperty("hostKey", room.hostPlayerKey);
            JsonArray members = new JsonArray();
            for (LobbyMember member : room.members.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("playerKey", member.playerKey);
                obj.addProperty("nickname", member.nickname);
                obj.addProperty("host", member.playerKey.equals(room.hostPlayerKey));
                members.add(obj);
            }
            payload.add("members", members);
            JsonArray seats = new JsonArray();
            for (int i = 0; i < room.seats.size(); i++) {
                LobbySeat seat = room.seats.get(i);
                JsonObject obj = new JsonObject();
                obj.addProperty("index", i);
                obj.addProperty("role", seat.role);
                obj.addProperty("nickname", seat.displayName(room));
                obj.addProperty("playerKey", seat.humanPlayerKey == null ? "" : seat.humanPlayerKey);
                obj.addProperty("playerId", seat.inGamePlayerId);
                seats.add(obj);
            }
            payload.add("seats", seats);
            return payload;
        }
    }

    void sendRoomError(ClientConnection client, String error) {
        try {
            client.sendText(dispatcher.toJsonEnvelope("ROOM_ERROR", dispatcher.operationResult(false, error)));
        } catch (IOException ignored) {
        }
    }

    void bindHumanSeatConnections(LobbyRoom room) {
        synchronized (room) {
            int playerNumber = 1;
            for (LobbySeat seat : room.seats) {
                if ("empty".equals(seat.role)) {
                    continue;
                }
                String inGamePlayerId = "human".equals(seat.role)
                        ? "pvp-" + playerNumber
                        : "ai-" + playerNumber;
                if ("human".equals(seat.role) && seat.humanPlayerKey != null) {
                    seat.inGamePlayerId = inGamePlayerId;
                    for (ClientConnection conn : hub.sessionRegistry().connectionsOf(seat.humanPlayerKey)) {
                        hub.sessionRegistry().register(conn, inGamePlayerId);
                        hub.sessionRegistry().bindSession(conn, room.sessionId);
                    }
                } else {
                    seat.inGamePlayerId = inGamePlayerId;
                }
                playerNumber++;
            }
        }
    }

    void removeMemberFromRoom(LobbyRoom room, String playerKey) {
        boolean changed = false;
        synchronized (room) {
            if (room.members.remove(playerKey) != null) {
                changed = true;
            }
            for (LobbySeat seat : room.seats) {
                if (playerKey.equals(seat.humanPlayerKey)) {
                    seat.role = "empty";
                    seat.humanPlayerKey = null;
                    seat.botName = "";
                    seat.inGamePlayerId = "";
                    changed = true;
                }
            }
            if (playerKey.equals(room.hostPlayerKey)) {
                room.hostPlayerKey = room.members.keySet().stream().findFirst().orElse("");
            }
            if (room.members.isEmpty() && !room.started) {
                lobbyRooms.remove(room.sessionId, room);
            }
        }
        if (changed) {
            sendRoomStateToSession(room.sessionId);
        }
    }

    static String normalizeNickname(String value) {
        return value == null ? "" : value.trim();
    }

    static String normalizeLobbyRole(String value) {
        String role = value == null ? "empty" : value.trim().toLowerCase();
        return switch (role) {
            case "human", "hard", "strong", "llm", "student", "empty" -> role;
            case "lookahead" -> "strong";
            default -> "empty";
        };
    }

    static String defaultBotName(String role, int seatNumber) {
        return switch (role) {
            case "hard" -> "Hard Bot " + seatNumber;
            case "strong" -> "Strong Bot " + seatNumber;
            case "llm" -> "LLM Bot " + seatNumber;
            case "student" -> "Student Bot " + seatNumber;
            default -> "";
        };
    }

    String playerKey(ClientConnection conn) {
        return "conn-" + System.identityHashCode(conn);
    }

    boolean isRoomHost(LobbyRoom room, ClientConnection conn) {
        return playerKey(conn).equals(room.hostPlayerKey);
    }

    static final class LobbyRoom {
        final String sessionId;
        final List<LobbySeat> seats = new ArrayList<>();
        final java.util.LinkedHashMap<String, LobbyMember> members = new java.util.LinkedHashMap<>();
        String hostPlayerKey = "";
        boolean started;

        LobbyRoom(String sessionId) {
            this.sessionId = sessionId;
            for (int i = 0; i < 5; i++) {
                seats.add(new LobbySeat());
            }
        }

        void addMember(String playerKey, String nickname) {
            members.put(playerKey, new LobbyMember(playerKey, nickname));
        }

        boolean nicknameExists(String nickname, String exceptPlayerKey) {
            for (LobbyMember member : members.values()) {
                if (!member.playerKey.equals(exceptPlayerKey)
                        && member.nickname.equalsIgnoreCase(nickname)) {
                    return true;
                }
            }
            return false;
        }

        String memberKeyByNickname(String nickname) {
            for (LobbyMember member : members.values()) {
                if (member.nickname.equals(nickname)) {
                    return member.playerKey;
                }
            }
            return null;
        }

        String nicknameOf(String playerKey) {
            LobbyMember member = members.get(playerKey);
            return member == null ? "" : member.nickname;
        }

        int activeSeatCount() {
            return (int) seats.stream().filter(seat -> !"empty".equals(seat.role)).count();
        }

        int humanSeatCount() {
            return (int) seats.stream().filter(seat -> "human".equals(seat.role)).count();
        }

        List<String> activeSeatRoles() {
            List<String> roles = new ArrayList<>();
            for (LobbySeat seat : seats) {
                String mapped = seat.backendRole();
                if (!mapped.isBlank()) {
                    roles.add(mapped);
                }
            }
            return roles;
        }

        List<String> activeSeatNames() {
            List<String> names = new ArrayList<>();
            for (LobbySeat seat : seats) {
                if (!"empty".equals(seat.role)) {
                    names.add(seat.displayName(this));
                }
            }
            return names;
        }
    }

    record LobbyMember(String playerKey, String nickname) {
    }

    static final class LobbySeat {
        String role = "empty";
        String humanPlayerKey;
        String botName = "";
        String inGamePlayerId = "";

        String displayName(LobbyRoom room) {
            if ("human".equals(role)) {
                return room.nicknameOf(humanPlayerKey);
            }
            return botName == null ? "" : botName;
        }

        String backendRole() {
            return switch (role) {
                case "human" -> humanPlayerKey == null ? "" : "human";
                case "hard" -> "hard";
                case "strong" -> "lookahead";
                case "llm" -> "llm";
                case "student" -> "student";
                default -> "";
            };
        }
    }
}
