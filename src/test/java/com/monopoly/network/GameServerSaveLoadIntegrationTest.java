package com.monopoly.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.model.player.Player;
import com.monopoly.model.core.GameConstants;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAVE_GAME → LOAD_GAME 后全场牌数仍为 {@link GameConstants#STANDARD_DECK_SIZE}。
 */
class GameServerSaveLoadIntegrationTest {

    private static final Gson GSON = new Gson();

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void saveThenLoad_inMemoryJson_preservesDeckTotal() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);

        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        server.onClientConnected(client);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("int-save");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);

        out.clear();
        server.onMessage(client, "{\"type\":\"SAVE_GAME\",\"payload\":{}}");

        String saveReply = out.stream()
                .filter(s -> s.contains("\"SAVE_GAME_RESULT\""))
                .reduce((a, b) -> b)
                .orElseThrow();
        JsonObject root = JsonParser.parseString(saveReply).getAsJsonObject();
        assertTrue(root.getAsJsonObject("payload").get("ok").getAsBoolean());
        String mementoJson = root.getAsJsonObject("payload").get("mementoJson").getAsString();

        GameSessionMemento.resetSingletonEngineForTests();
        GameController loaded = new GameController(subject);
        server.wireController(loaded);

        JsonObject loadPayload = new JsonObject();
        loadPayload.addProperty("mementoJson", mementoJson);
        JsonObject loadRoot = new JsonObject();
        loadRoot.addProperty("type", "LOAD_GAME");
        loadRoot.add("payload", loadPayload);
        out.clear();
        server.onMessage(client, GSON.toJson(loadRoot));

        String loadReply = out.stream()
                .filter(s -> s.contains("\"LOAD_GAME_RESULT\""))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertTrue(JsonParser.parseString(loadReply).getAsJsonObject().getAsJsonObject("payload").get("ok").getAsBoolean());

        int total = loaded.getEngine().countAllCardsInPlay(loaded.getSessionPlayersView());
        assertEquals(GameConstants.STANDARD_DECK_SIZE, total);
    }

    @Test
    void saveToPath_thenLoad_preservesDeckTotal() throws Exception {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);

        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        server.onClientConnected(client);

        controller.startNewSession("path-save");

        Path tmp = Files.createTempFile("monopoly-save-", ".json");
        tmp.toFile().deleteOnExit();

        JsonObject savePayload = new JsonObject();
        savePayload.addProperty("path", tmp.toString());
        JsonObject saveRoot = new JsonObject();
        saveRoot.addProperty("type", "SAVE_GAME");
        saveRoot.add("payload", savePayload);
        out.clear();
        server.onMessage(client, GSON.toJson(saveRoot));

        String saveReply = out.stream()
                .filter(s -> s.contains("\"SAVE_GAME_RESULT\""))
                .reduce((a, b) -> b)
                .orElseThrow();
        JsonObject payload = JsonParser.parseString(saveReply).getAsJsonObject().getAsJsonObject("payload");
        assertTrue(payload.get("ok").getAsBoolean());
        assertEquals(tmp.toString(), payload.get("writtenPath").getAsString());

        String fileJson = Files.readString(tmp, StandardCharsets.UTF_8);

        GameSessionMemento.resetSingletonEngineForTests();
        GameController loaded = new GameController(subject);
        server.wireController(loaded);

        JsonObject loadPayload = new JsonObject();
        loadPayload.addProperty("mementoJson", fileJson);
        JsonObject loadRoot = new JsonObject();
        loadRoot.addProperty("type", "LOAD_GAME");
        loadRoot.add("payload", loadPayload);
        out.clear();
        server.onMessage(client, GSON.toJson(loadRoot));

        int total = loaded.getEngine().countAllCardsInPlay(loaded.getSessionPlayersView());
        assertEquals(GameConstants.STANDARD_DECK_SIZE, total);
    }

    @Test
    void authDifferentPlayers_registryDistinguishesConnections() {
        GameServer server = new GameServer();
        ClientConnection c1 = recordingClient(new ArrayList<>());
        ClientConnection c2 = recordingClient(new ArrayList<>());
        server.onClientConnected(c1);
        server.onClientConnected(c2);

        JsonObject p1 = new JsonObject();
        p1.addProperty("playerId", "p1");
        JsonObject m1 = new JsonObject();
        m1.addProperty("type", "AUTH");
        m1.add("payload", p1);
        server.onMessage(c1, GSON.toJson(m1));

        JsonObject p2 = new JsonObject();
        p2.addProperty("playerId", "p2");
        JsonObject m2 = new JsonObject();
        m2.addProperty("type", "AUTH");
        m2.add("payload", p2);
        server.onMessage(c2, GSON.toJson(m2));

        assertEquals("p1", server.getPlayerIdOf(c1).orElseThrow());
        assertEquals("p2", server.getPlayerIdOf(c2).orElseThrow());
        assertTrue(server.connectionsOf("p1").contains(c1));
        assertTrue(server.connectionsOf("p2").contains(c2));
    }

    @Test
    void myHand_shouldBePrivatePerAuthenticatedPlayer() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);

        List<String> out1 = new ArrayList<>();
        List<String> out2 = new ArrayList<>();
        ClientConnection c1 = recordingClient(out1);
        ClientConnection c2 = recordingClient(out2);
        server.onClientConnected(c1);
        server.onClientConnected(c2);

        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("private-hand");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);

        JsonObject auth1Payload = new JsonObject();
        auth1Payload.addProperty("playerId", "pvp-1");
        JsonObject auth1 = new JsonObject();
        auth1.addProperty("type", "AUTH");
        auth1.add("payload", auth1Payload);
        server.onMessage(c1, GSON.toJson(auth1));

        JsonObject auth2Payload = new JsonObject();
        auth2Payload.addProperty("playerId", "pvp-2");
        JsonObject auth2 = new JsonObject();
        auth2.addProperty("type", "AUTH");
        auth2.add("payload", auth2Payload);
        server.onMessage(c2, GSON.toJson(auth2));

        Player p1 = controller.getSessionPlayersView().stream()
                .filter(p -> "pvp-1".equals(p.getPlayerId()))
                .findFirst()
                .orElseThrow();
        String p1FirstHandId = p1.getHandCardsView().get(0).getId();

        out1.clear();
        out2.clear();
        server.onMessage(c1, "{\"type\":\"DRAW\",\"payload\":{\"count\":2}}");

        String c1MyHand = out1.stream()
                .filter(s -> s.contains("\"type\":\"MY_HAND\"") && s.contains("\"playerId\":\"pvp-1\""))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertTrue(handMessageContainsCard(c1MyHand, p1FirstHandId));
        assertFalse(out2.stream()
                .filter(s -> s.contains("\"type\":\"MY_HAND\""))
                .anyMatch(s -> handMessageContainsCard(s, p1FirstHandId)));
        assertFalse(out2.stream().anyMatch(s -> s.contains("\"id\":\"" + p1FirstHandId + "\"")));
    }

    @Test
    void independentSessions_doNotBroadcastStateOrPrivateHandsAcrossSessions() {
        GameServer server = new GameServer();
        List<String> outA = new ArrayList<>();
        List<String> outB = new ArrayList<>();
        ClientConnection clientA = recordingClient(outA);
        ClientConnection clientB = recordingClient(outB);
        server.onClientConnected(clientA);
        server.onClientConnected(clientB);

        server.onMessage(clientA,
                "{\"type\":\"AUTH\",\"payload\":{\"sessionId\":\"batch-a\",\"playerId\":\"human-1\"}}");
        server.onMessage(clientB,
                "{\"type\":\"AUTH\",\"payload\":{\"sessionId\":\"batch-b\",\"playerId\":\"human-1\"}}");

        outA.clear();
        outB.clear();
        server.onMessage(clientA,
                "{\"type\":\"START_SESSION\",\"payload\":{\"sessionId\":\"batch-a\","
                        + "\"playerCount\":2,\"gameMode\":\"HVM\",\"aiDifficulty\":\"EASY\","
                        + "\"randomizeFirstPlayer\":false}}");

        assertTrue(outA.stream().anyMatch(s ->
                s.contains("\"type\":\"STATE_UPDATE\"") && s.contains("\"sessionId\":\"batch-a\"")));
        assertTrue(outA.stream().anyMatch(s ->
                s.contains("\"type\":\"MY_HAND\"") && s.contains("\"playerId\":\"human-1\"")));
        assertFalse(outB.stream().anyMatch(s -> s.contains("\"sessionId\":\"batch-a\"")));
        assertFalse(outB.stream().anyMatch(s -> s.contains("\"type\":\"MY_HAND\"")));

        outA.clear();
        outB.clear();
        server.onMessage(clientB,
                "{\"type\":\"START_SESSION\",\"payload\":{\"sessionId\":\"batch-b\","
                        + "\"playerCount\":2,\"gameMode\":\"HVM\",\"aiDifficulty\":\"EASY\","
                        + "\"randomizeFirstPlayer\":false}}");

        assertTrue(outB.stream().anyMatch(s ->
                s.contains("\"type\":\"STATE_UPDATE\"") && s.contains("\"sessionId\":\"batch-b\"")));
        assertTrue(outB.stream().anyMatch(s ->
                s.contains("\"type\":\"MY_HAND\"") && s.contains("\"playerId\":\"human-1\"")));
        assertFalse(outA.stream().anyMatch(s -> s.contains("\"sessionId\":\"batch-b\"")));
        assertFalse(outA.stream().anyMatch(s -> s.contains("\"type\":\"MY_HAND\"")));

        outA.clear();
        outB.clear();
        server.onMessage(clientA, "{\"type\":\"DRAW\",\"payload\":{\"sessionId\":\"batch-a\",\"count\":2}}");

        JsonObject drawA = outA.stream()
                .filter(s -> s.contains("\"type\":\"STATE_UPDATE\"") && s.contains("\"phase\":\"DRAW\""))
                .map(GameServerSaveLoadIntegrationTest::payloadOf)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals("batch-a", drawA.get("sessionId").getAsString());
        assertEquals(GameConstants.STANDARD_DECK_SIZE - 12, drawA.get("drawPileCount").getAsInt());
        assertFalse(outB.stream().anyMatch(s -> s.contains("\"phase\":\"DRAW\"")));
    }

    @Test
    void loadGame_requiresAllVotesInThreePlayerSession() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);

        StartSessionRequest liveReq = new StartSessionRequest();
        liveReq.setSessionId("live-3p");
        liveReq.setPlayerCount(3);
        liveReq.setGameMode("PVP");
        liveReq.setRandomizeFirstPlayer(false);
        controller.startNewSession(liveReq);

        DefaultGameUpdateSubject snapshotSubject = new DefaultGameUpdateSubject();
        GameController snapshotController = new GameController(snapshotSubject);
        StartSessionRequest loadReq = new StartSessionRequest();
        loadReq.setSessionId("target-3p");
        loadReq.setPlayerCount(3);
        loadReq.setGameMode("PVP");
        loadReq.setRandomizeFirstPlayer(false);
        snapshotController.startNewSession(loadReq);
        String targetMementoJson = snapshotController.exportSessionJson();

        List<String> out1 = new ArrayList<>();
        List<String> out2 = new ArrayList<>();
        List<String> out3 = new ArrayList<>();
        ClientConnection c1 = recordingClient(out1);
        ClientConnection c2 = recordingClient(out2);
        ClientConnection c3 = recordingClient(out3);
        server.onClientConnected(c1);
        server.onClientConnected(c2);
        server.onClientConnected(c3);

        server.onMessage(c1, "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"pvp-1\"}}");
        server.onMessage(c2, "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"pvp-2\"}}");
        server.onMessage(c3, "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"pvp-3\"}}");

        JsonObject loadPayload = new JsonObject();
        loadPayload.addProperty("mementoJson", targetMementoJson);
        JsonObject loadRoot = new JsonObject();
        loadRoot.addProperty("type", "LOAD_GAME");
        loadRoot.add("payload", loadPayload);
        server.onMessage(c1, GSON.toJson(loadRoot));

        server.onMessage(c1, "{\"type\":\"LOAD_VOTE\",\"payload\":{\"playerId\":\"pvp-1\"}}");
        server.onMessage(c2, "{\"type\":\"LOAD_VOTE\",\"payload\":{\"playerId\":\"pvp-2\"}}");

        String currentAfterTwoVotes = GameSessionMemento.fromJson(controller.exportSessionJson()).getSessionId();
        assertEquals("live-3p", currentAfterTwoVotes);

        server.onMessage(c3, "{\"type\":\"LOAD_VOTE\",\"payload\":{\"playerId\":\"pvp-3\"}}");
        String currentAfterThreeVotes = GameSessionMemento.fromJson(controller.exportSessionJson()).getSessionId();
        assertEquals("target-3p", currentAfterThreeVotes);
    }

    @Test
    void loadVotes_areScopedBySessionWhenMultipleRoomsAreActive() {
        GameServer server = new GameServer();
        List<String> roomA1 = new ArrayList<>();
        List<String> roomA2 = new ArrayList<>();
        List<String> roomB1 = new ArrayList<>();
        List<String> roomB2 = new ArrayList<>();
        ClientConnection a1 = recordingClient(roomA1);
        ClientConnection a2 = recordingClient(roomA2);
        ClientConnection b1 = recordingClient(roomB1);
        ClientConnection b2 = recordingClient(roomB2);
        server.onClientConnected(a1);
        server.onClientConnected(a2);
        server.onClientConnected(b1);
        server.onClientConnected(b2);

        auth(server, a1, "room-a", "pvp-1");
        auth(server, a2, "room-a", "pvp-2");
        auth(server, b1, "room-b", "pvp-1");
        auth(server, b2, "room-b", "pvp-2");
        server.onMessage(a1, startPvp("room-a"));
        server.onMessage(b1, startPvp("room-b"));

        String roomATarget = mementoFor("room-a-loaded");
        String roomBTarget = mementoFor("room-b-loaded");
        requestLoad(server, a1, "room-a", "load-a", roomATarget);
        requestLoad(server, b1, "room-b", "load-b", roomBTarget);

        roomA1.clear();
        roomA2.clear();
        roomB1.clear();
        roomB2.clear();
        server.onMessage(a1, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"sessionId\":\"room-a\","
                + "\"requestId\":\"load-a\",\"playerId\":\"pvp-1\"}}");
        server.onMessage(a2, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"sessionId\":\"room-a\","
                + "\"requestId\":\"load-a\",\"playerId\":\"pvp-2\"}}");

        assertTrue(roomA1.stream().anyMatch(s ->
                s.contains("\"type\":\"LOAD_GAME_RESULT\"") && s.contains("\"ok\":true")));
        assertTrue(roomA1.stream().anyMatch(s -> s.contains("\"sessionId\":\"room-a-loaded\"")));
        assertFalse(roomB1.stream().anyMatch(s -> s.contains("\"type\":\"LOAD_GAME_RESULT\"")));
        assertFalse(roomB2.stream().anyMatch(s -> s.contains("\"type\":\"LOAD_GAME_RESULT\"")));

        server.onMessage(b1, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"sessionId\":\"room-b\","
                + "\"requestId\":\"load-b\",\"playerId\":\"pvp-1\"}}");
        assertFalse(roomB1.stream().anyMatch(s ->
                s.contains("\"type\":\"LOAD_GAME_RESULT\"") && s.contains("\"ok\":true")));

        server.onMessage(b2, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"sessionId\":\"room-b\","
                + "\"requestId\":\"load-b\",\"playerId\":\"pvp-2\"}}");
        assertTrue(roomB1.stream().anyMatch(s ->
                s.contains("\"type\":\"LOAD_GAME_RESULT\"") && s.contains("\"ok\":true")));
        assertTrue(roomB1.stream().anyMatch(s -> s.contains("\"sessionId\":\"room-b-loaded\"")));
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

    private static boolean handMessageContainsCard(String message, String cardId) {
        JsonObject root = JsonParser.parseString(message).getAsJsonObject();
        JsonObject payload = root.getAsJsonObject("payload");
        JsonArray cards = payload.getAsJsonArray("cards");
        for (int i = 0; i < cards.size(); i++) {
            JsonObject card = cards.get(i).getAsJsonObject();
            if (cardId.equals(card.get("id").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject payloadOf(String message) {
        return JsonParser.parseString(message).getAsJsonObject().getAsJsonObject("payload");
    }

    private static void auth(GameServer server, ClientConnection client, String sessionId, String playerId) {
        server.onMessage(client, "{\"type\":\"AUTH\",\"payload\":{\"sessionId\":\"" + sessionId
                + "\",\"playerId\":\"" + playerId + "\"}}");
    }

    private static String startPvp(String sessionId) {
        return "{\"type\":\"START_SESSION\",\"payload\":{\"sessionId\":\"" + sessionId
                + "\",\"playerCount\":2,\"gameMode\":\"PVP\",\"randomizeFirstPlayer\":false}}";
    }

    private static String mementoFor(String sessionId) {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId(sessionId);
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        return controller.exportSessionJson();
    }

    private static void requestLoad(
            GameServer server,
            ClientConnection client,
            String sessionId,
            String requestId,
            String mementoJson
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("requestId", requestId);
        payload.addProperty("mementoJson", mementoJson);
        JsonObject root = new JsonObject();
        root.addProperty("type", "LOAD_GAME");
        root.add("payload", payload);
        server.onMessage(client, GSON.toJson(root));
    }
}
